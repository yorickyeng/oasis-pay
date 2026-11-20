package ru.oasispay.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.annotation.WorkerThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import ru.oasispay.data.protocol.Cose
import ru.oasispay.data.protocol.hex
import ru.oasispay.data.protocol.sha256
import ru.oasispay.data.protocol.text
import ru.oasispay.data.protocol.unb64

/**
 * All access is serialized. A revision becomes visible only after SQLite has committed.
 * Signed requests, results and acknowledgements are retained in the operation journal.
 */
internal class PaymentStore(
    context: Context,
    databaseName: String = "oasis-pay.db",
    legacyName: String = "oasis-pay-relay",
) {
    private val lock = Any()
    private val helper = Database(context.applicationContext, databaseName, legacyName)
    private val active = ThreadLocal<State>()
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()

    private class State(val values: MutableMap<String, String>, val writable: Boolean) {
        val changed = linkedMapOf<String, String>()
    }

    @WorkerThread
    fun <T> read(block: () -> T): T = synchronized(lock) {
        if (active.get() != null) return@synchronized block()
        active.set(State(load(helper.writableDatabase), writable = false))
        try {
            block()
        } finally {
            active.remove()
        }
    }

    @WorkerThread
    fun <T> write(block: () -> T): T = synchronized(lock) {
        active.get()?.let {
            check(it.writable) { "Запись внутри снимка хранилища запрещена" }
            return@synchronized block()
        }
        val database = helper.writableDatabase
        database.beginTransaction()
        val result: T
        var changed = false
        try {
            val state = State(load(database), writable = true)
            active.set(state)
            result = block()
            for ((key, value) in state.changed) {
                val row = ContentValues().apply {
                    put("key", key)
                    put("value", value)
                }
                database.replaceOrThrow("state", null, row)
            }
            if (state.changed.isNotEmpty()) {
                journal(database, state.values, "terminal")
                journal(database, state.values, "buyer")
            }
            changed = state.changed.isNotEmpty()
            database.setTransactionSuccessful()
        } finally {
            active.remove()
            database.endTransaction()
        }
        if (changed) revision.update { it + 1 }
        result
    }

    @WorkerThread
    fun get(key: String): String {
        val state = active.get()
        return if (state == null) read { get(key) } else state.values[key].orEmpty()
    }

    @WorkerThread
    fun save(vararg values: Pair<String, String>) {
        val state = active.get()
        if (state == null) {
            write { save(*values) }
            return
        }
        check(state.writable)
        for ((key, value) in values) {
            require(key.matches(Regex("[a-z_]{1,64}")) && value.length <= 32_768)
            if (state.values[key].orEmpty() != value) {
                state.values[key] = value
                state.changed[key] = value
            }
        }
    }

    @WorkerThread
    fun hasOperation(role: String, transactionId: String): Boolean {
        check(role == "terminal" || role == "buyer")
        return synchronized(lock) {
            helper.readableDatabase.rawQuery(
                """SELECT 1 FROM operations WHERE role=? AND transaction_id=? AND status<>'WAITING'
                   UNION ALL SELECT 1 FROM legacy_journal WHERE transaction_id=? LIMIT 1""",
                arrayOf(role, transactionId, transactionId),
            ).use { it.moveToFirst() }
        }
    }

    private fun load(database: SQLiteDatabase): MutableMap<String, String> =
        database.rawQuery("SELECT key,value FROM state", null).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1))
            }.toMutableMap()
        }

    private fun journal(database: SQLiteDatabase, state: Map<String, String>, role: String) {
        val requestKey = if (role == "terminal") "terminal_order" else "buyer_request"
        val request = state[requestKey]?.takeIf(String::isNotEmpty)?.unb64() ?: return
        val fields = Cose.parse(request).fields
        val transaction = fields.text("transaction_id")
        require(transaction.matches(Regex("[0-9a-f]{32}")))
        val result = state["${role}_result"]?.takeIf(String::isNotEmpty)?.unb64()
        val acknowledgement = state["${role}_ack"]?.takeIf(String::isNotEmpty)?.unb64()
        val handoff = if (role == "terminal") state["terminal_handoff"]?.takeIf(String::isNotEmpty)?.unb64() else null
        val status = result?.let { Cose.parse(it).fields.text("status") } ?: "WAITING"
        val fulfilled = if (role == "terminal") state["terminal_fulfilled"] == "yes"
            else acknowledgement?.let { Cose.parse(it).fields.text("terminal_state") == "FULFILLED" } == true
        val requestHash = request.sha256().hex()
        database.rawQuery(
            "SELECT request_hash,result,fulfilled FROM operations WHERE role=? AND transaction_id=?",
            arrayOf(role, transaction),
        ).use { existing ->
            if (existing.moveToFirst()) {
                check(existing.getString(0) == requestHash) { "Конфликт идентификатора в журнале" }
                check(existing.getInt(2) == 0 || fulfilled) { "Состояние выдачи уже зафиксировано" }
                if (!existing.isNull(1)) {
                    check(result != null && existing.getBlob(1).contentEquals(result)) {
                        "Финансовый результат операции уже зафиксирован"
                    }
                }
            }
        }
        val row = ContentValues().apply {
            put("role", role)
            put("transaction_id", transaction)
            put("request_hash", requestHash)
            put("request", request)
            put("result", result)
            put("acknowledgement", acknowledgement)
            put("handoff", handoff)
            put("status", status)
            put("fulfilled", if (fulfilled) 1 else 0)
        }
        database.replaceOrThrow("operations", null, row)
    }

    @WorkerThread
    fun close() = synchronized(lock) { helper.close() }

    private class Database(
        private val context: Context,
        name: String,
        private val legacyName: String,
    ) : SQLiteOpenHelper(context, name, null, 2) {
        override fun onConfigure(database: SQLiteDatabase) {
            database.setForeignKeyConstraintsEnabled(true)
            // Keep the power-loss durability of each committed payment transition.
            database.execSQL("PRAGMA synchronous=FULL")
        }

        override fun onCreate(database: SQLiteDatabase) {
            database.execSQL("CREATE TABLE state (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID")
            database.execSQL("""CREATE TABLE operations (
                role TEXT NOT NULL CHECK(role IN ('terminal','buyer')),
                transaction_id TEXT NOT NULL,
                request_hash TEXT NOT NULL,
                request BLOB NOT NULL,
                result BLOB,
                acknowledgement BLOB,
                handoff BLOB,
                status TEXT NOT NULL CHECK(status IN ('WAITING','PAID','DECLINED','CANCELLED')),
                fulfilled INTEGER NOT NULL CHECK(fulfilled IN (0,1)),
                PRIMARY KEY(role,transaction_id)
            ) WITHOUT ROWID""")
            database.execSQL("CREATE TABLE legacy_journal (transaction_id TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID")
            val legacy = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE).all
            for ((key, value) in legacy) {
                check(value is String) { "Неизвестный формат предыдущего хранилища" }
                val journal = key.startsWith("journal_")
                val row = ContentValues().apply {
                    put(if (journal) "transaction_id" else "key", if (journal) key.removePrefix("journal_") else key)
                    put("value", value)
                }
                database.insertOrThrow(if (journal) "legacy_journal" else "state", null, row)
            }
            // The previous app persisted only a delivery flag. Keep the signed result and
            // fulfillment state, then request a fresh signed handoff from the buyer.
            if (legacy["terminal_ack_handed"] == "yes" && (legacy["terminal_handoff"] as? String).isNullOrEmpty()) {
                database.execSQL("UPDATE state SET value='' WHERE key='terminal_ack_handed'")
            }
        }

        override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion == 1 && newVersion == 2) {
                database.execSQL("ALTER TABLE operations ADD COLUMN handoff BLOB")
                database.execSQL("UPDATE state SET value='' WHERE key='terminal_ack_handed'")
            } else {
                error("Требуется миграция базы ${oldVersion} → ${newVersion}")
            }
        }
    }
}
