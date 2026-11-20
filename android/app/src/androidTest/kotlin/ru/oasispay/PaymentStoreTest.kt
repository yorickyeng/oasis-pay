package ru.oasispay

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.oasispay.data.local.PaymentStore
import ru.oasispay.data.protocol.*
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PaymentStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name: String
    private lateinit var legacy: String
    private lateinit var store: PaymentStore

    @Before
    fun create() {
        name = "test-${newId()}.db"
        legacy = "test-${newId()}"
        store = PaymentStore(context, name, legacy)
    }

    @After
    fun cleanup() {
        store.close()
        context.deleteDatabase(name)
        context.deleteSharedPreferences(legacy)
    }

    @Test
    fun migratesInstalledPreferencesOnceWithoutLosingJournalMetadata() {
        val transaction = newId()
        assertTrue(context.getSharedPreferences(legacy, Context.MODE_PRIVATE).edit()
            .putString("role", "buyer").putString("buyer_token", "sealed-token")
            .putString("journal_$transaction", "UNKNOWN:OPERATOR_CLOSED")
            .putString("terminal_ack_handed", "yes").putString("terminal_fulfilled", "yes").commit())
        assertEquals("buyer", store.read { store.get("role") })
        assertEquals("", store.get("terminal_ack_handed"))
        assertEquals("yes", store.get("terminal_fulfilled"))
        assertTrue(store.hasOperation("buyer", transaction))
        store.save("role" to "terminal")
        store.close()
        assertTrue(context.getSharedPreferences(legacy, Context.MODE_PRIVATE).edit().putString("role", "buyer").commit())
        store = PaymentStore(context, name, legacy)
        assertEquals("terminal", store.get("role"))
        assertEquals("sealed-token", store.get("buyer_token"))
    }

    @Test
    fun databaseVersionOneMigratesWithoutResettingCurrentState() {
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { database ->
            database.execSQL("CREATE TABLE state (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            database.execSQL("""CREATE TABLE operations (
                role TEXT, transaction_id TEXT, request_hash TEXT, request BLOB, result BLOB,
                acknowledgement BLOB, status TEXT, fulfilled INTEGER, PRIMARY KEY(role,transaction_id))""")
            database.execSQL("CREATE TABLE legacy_journal (transaction_id TEXT PRIMARY KEY, value TEXT)")
            database.execSQL("INSERT INTO state VALUES ('role','terminal'),('terminal_ack_handed','yes')")
            database.version = 1
        }
        assertEquals("terminal", store.get("role"))
        assertEquals("", store.get("terminal_ack_handed"))
        store.close()
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { database ->
            assertEquals(2, database.version)
            database.rawQuery("SELECT handoff FROM operations", null).use { assertEquals(0, it.count) }
        }
        store = PaymentStore(context, name, legacy)
    }

    @Test
    fun failingTransitionRollsBackMemoryDiskAndRevision() {
        store.save("role" to "buyer")
        val revision = store.changes.value
        assertThrows(IllegalStateException::class.java) {
            store.write {
                store.save("role" to "terminal", "buyer_attempted" to "yes")
                error("Simulated interruption before commit")
            }
        }
        assertEquals(revision, store.changes.value)
        assertEquals("buyer", store.get("role"))
        assertEquals("", store.get("buyer_attempted"))
        store.close()
        store = PaymentStore(context, name, legacy)
        assertEquals("buyer", store.get("role"))
        assertEquals("", store.get("buyer_attempted"))
    }

    @Test
    fun sqliteWriteFailureDoesNotPublishOrPartiallyCommitState() {
        store.save("role" to "buyer")
        val revision = store.changes.value
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { database ->
            database.execSQL("""CREATE TRIGGER reject_write BEFORE INSERT ON state
                WHEN NEW.key='buyer_attempted' BEGIN SELECT RAISE(ABORT,'simulated write failure'); END""")
        }
        assertThrows(android.database.sqlite.SQLiteException::class.java) {
            store.save("role" to "terminal", "buyer_attempted" to "yes")
        }
        assertEquals(revision, store.changes.value)
        assertEquals("buyer", store.get("role"))
        assertEquals("", store.get("buyer_attempted"))
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { database ->
            database.execSQL("DROP TRIGGER reject_write")
        }
        store.save("buyer_attempted" to "yes")
        assertEquals("yes", store.get("buyer_attempted"))
    }

    @Test
    fun parallelReadModifyWriteTransitionsDoNotLoseUpdates() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val jobs = List(40) {
                executor.submit {
                    store.write {
                        val next = (store.get("counter").toLongOrNull() ?: 0) + 1
                        store.save("counter" to next.toString())
                    }
                }
            }
            jobs.forEach { it.get(15, TimeUnit.SECONDS) }
            assertEquals("40", store.get("counter"))
            store.close()
            store = PaymentStore(context, name, legacy)
            assertEquals("40", store.get("counter"))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun journalRetainsCompletedRequestsAndRejectsReplacementResults() {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        val transaction = newId()
        val request = Cose.sign(message("payment_request", "transaction_id" to transaction), keys.private, keys.public)
        val result = Cose.sign(message("payment_result", "status" to "DECLINED"), keys.private, keys.public)
        store.save("buyer_request" to request.raw.b64(), "buyer_result" to result.raw.b64())
        assertTrue(store.hasOperation("buyer", transaction))
        val other = Cose.sign(message("payment_result", "status" to "PAID"), keys.private, keys.public)
        assertThrows(IllegalStateException::class.java) { store.save("buyer_result" to other.raw.b64()) }
        assertEquals(result.raw.b64(), store.get("buyer_result"))
        store.save("buyer_request" to "", "buyer_result" to "")
        store.close()
        store = PaymentStore(context, name, legacy)
        assertTrue(store.hasOperation("buyer", transaction))
        assertEquals("", store.get("buyer_request"))
    }

    @Test
    fun journalInsertAndActiveStateRollbackTogether() {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        val transaction = newId()
        val request = Cose.sign(message("payment_request", "transaction_id" to transaction), keys.private, keys.public)
        assertThrows(IllegalStateException::class.java) {
            store.write {
                store.save("buyer_request" to request.raw.b64())
                error("Simulated crash before journal commit")
            }
        }
        assertEquals("", store.get("buyer_request"))
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { database ->
            database.rawQuery("SELECT count(*) FROM operations", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
        }
    }
}
