package ru.oasispay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.oasispay.data.BuyerRepository
import ru.oasispay.data.TerminalRepository
import ru.oasispay.data.local.PaymentStore
import ru.oasispay.data.local.SecretStore
import ru.oasispay.data.protocol.*
import ru.oasispay.domain.PaymentStatus
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

@RunWith(AndroidJUnit4::class)
class PaymentRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name: String
    private lateinit var legacy: String
    private lateinit var store: PaymentStore
    private lateinit var bank: KeyPair
    private lateinit var terminalKey: DeviceKey
    private lateinit var buyerKey: DeviceKey

    @Before
    fun create() {
        name = "recovery-${newId()}.db"
        legacy = "recovery-${newId()}"
        store = PaymentStore(context, name, legacy)
        bank = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        terminalKey = DeviceKey("terminal").apply { createIfNeeded() }
        buyerKey = DeviceKey("buyer").apply { createIfNeeded() }
        store.save(
            "terminal_bank_key" to bank.public.encoded.b64(),
            "terminal_certificate" to certificate("terminal", terminalKey).raw.b64(),
            "buyer_bank_key" to bank.public.encoded.b64(),
            "buyer_certificate" to certificate("buyer", buyerKey).raw.b64(),
            "buyer_token" to SecretStore.seal("a".repeat(64)),
        )
    }

    @After
    fun cleanup() {
        store.close()
        context.deleteDatabase(name)
        context.deleteSharedPreferences(legacy)
    }

    private fun certificate(role: String, key: DeviceKey): Cose = Cose.sign(message("device_certificate",
        "role" to role, "device_id" to key.publicBytes().sha256().hex(), "public_key" to key.publicBytes(),
        "merchant_id" to "merchant", "valid_from" to now() - 4 * RESULT_LIFETIME,
        "valid_to" to now() + RESULT_LIFETIME), bank.private, bank.public)

    private fun newRequest(old: Boolean = false): Cose {
        val created = now() - if (old) 2 * RESULT_LIFETIME else 0
        return terminalKey.sign(message("payment_request",
            "terminal_id" to terminalKey.publicBytes().sha256().hex(), "merchant_id" to "merchant",
            "transaction_id" to newId(), "order_id" to newId(), "amount" to 19900L,
            "currency" to "RUB", "description" to "Покупка", "nonce" to randomBytes(32),
            "terminal_sequence" to 1L, "created_at" to created, "expires_at" to created + 120,
            "capabilities" to listOf("ble-gatt/1")))
    }

    private fun result(request: Cose, status: String = "PAID"): Cose {
        val processed = request.fields.number("created_at") + 1
        return Cose.sign(message("payment_result",
            "status" to status, "phone_device_id" to buyerKey.publicBytes().sha256().hex(),
            "server_operation_id" to newId(), "result_nonce" to randomBytes(32),
            "request_hash" to request.hash, "processed_at" to processed, "expires_at" to processed + RESULT_LIFETIME) +
            bindings.associateWith { request.fields.getValue(it) }, bank.private, bank.public)
    }

    private fun permit(request: Cose, result: Cose, resultHash: ByteArray = result.hash): Cose =
        Cose.sign(message("result_permit",
            "terminal_id" to request.fields.text("terminal_id"),
            "transaction_id" to request.fields.text("transaction_id"),
            "phone_device_id" to buyerKey.publicBytes().sha256().hex(),
            "request_hash" to request.hash, "result_hash" to resultHash,
            "issued_at" to now(), "expires_at" to now() + PERMIT_LIFETIME), bank.private, bank.public)

    private fun saveInvoice(request: Cose) {
        store.save("terminal_order" to request.raw.b64(), "terminal_sequence" to "1")
    }

    @Test
    fun expiredBankReceiptRequiresAnExactFreshPermit(): Unit = runBlocking {
        val request = newRequest(old = true)
        val result = result(request)
        saveInvoice(request)
        val repository = TerminalRepository(store)
        assertThrows(Exception::class.java) { repository.acceptResult(request.hash, result.raw) }
        assertNull(repository.snapshot().invoice!!.status)
        val wrong = Cbor.encode(mapOf("result" to result.raw, "permit" to permit(request, result, ByteArray(32)).raw))
        assertThrows(Exception::class.java) { repository.acceptResult(request.hash, wrong) }
        assertEquals("", store.get("terminal_result"))
        val packet = Cbor.encode(mapOf("result" to result.raw, "permit" to permit(request, result).raw))
        val ack = repository.acceptResult(request.hash, packet)
        assertEquals(PaymentStatus.PAID, repository.snapshot().invoice!!.status)
        assertArrayEquals(ack, repository.acceptResult(request.hash, packet))
        store.close()
        store = PaymentStore(context, name, legacy)
        assertEquals(PaymentStatus.PAID, TerminalRepository(store).snapshot().invoice!!.status)
    }

    @Test
    fun fulfillmentAndSignedHandoffSurviveRestartAndCannotBeRepeated(): Unit = runBlocking {
        val request = newRequest()
        val result = result(request)
        saveInvoice(request)
        val repository = TerminalRepository(store)
        repository.acceptResult(request.hash, result.raw)
        repository.fulfill()
        val ack = Cose.parse(store.get("terminal_ack").unb64())
        val proof = buyerKey.sign(message("relay_handoff",
            "session_id" to -42L, "phone_device_id" to buyerKey.publicBytes().sha256().hex(),
            "transaction_id" to request.fields.text("transaction_id"), "ack_hash" to ack.hash))
        val handoff = Cbor.encode(mapOf("proof" to proof.raw, "certificate" to certificate("buyer", buyerKey).raw))
        assertThrows(Exception::class.java) { repository.markHanded(handoff, 43) }
        repository.markHanded(handoff, -42)
        assertTrue(repository.snapshot().invoice!!.complete)
        store.close()
        store = PaymentStore(context, name, legacy)
        val restored = TerminalRepository(store)
        assertTrue(restored.snapshot().invoice!!.fulfilled)
        assertTrue(restored.snapshot().invoice!!.complete)
        try {
            restored.fulfill()
            fail("Fulfillment was applied twice")
        } catch (_: IllegalStateException) {
        }
        restored.createOrder(100)
        assertEquals(100L, restored.snapshot().invoice!!.amount)
        assertEquals(2L, Cose.parse(store.get("terminal_order").unb64()).fields.number("terminal_sequence"))
    }

    @Test
    fun cancelledInvoiceCanCloseButCannotAuthorizeGoods(): Unit = runBlocking {
        val request = newRequest()
        saveInvoice(request)
        val result = result(request, "CANCELLED")
        val repository = TerminalRepository(store)
        repository.acceptResult(request.hash, result.raw)
        assertEquals(PaymentStatus.CANCELLED, repository.snapshot().invoice!!.status)
        try {
            repository.fulfill()
            fail("Cancelled invoice authorized goods")
        } catch (_: IllegalStateException) {
        }
        assertEquals("", store.get("terminal_fulfilled"))
    }

    @Test
    fun buyerKeepsUnknownPaymentAndRejectsReplayOfCompletedInvoice(): Unit = runBlocking {
        val repository = BuyerRepository(store)
        val request = newRequest()
        val bundle = Cbor.encode(mapOf("request" to request.raw, "certificate" to certificate("terminal", terminalKey).raw))
        assertNull(repository.receiveBundle(bundle))
        val hash = request.hash.hex()
        store.save("buyer_attempted" to "yes")
        try {
            repository.resetUnattempted(hash)
            fail("Unknown payment was discarded")
        } catch (_: IllegalStateException) {
        }
        assertEquals(hash, repository.snapshot().payment!!.requestHash)
        val result = result(request, "DECLINED")
        store.save("buyer_result" to result.raw.b64())
        val ack = terminalKey.sign(message("payment_result_ack",
            "terminal_id" to request.fields.text("terminal_id"), "transaction_id" to request.fields.text("transaction_id"),
            "result_hash" to result.hash, "terminal_state" to "RECEIVED", "received_at" to now()))
        val handoff = Cbor.decode(repository.receiveAcknowledgement(ack.raw, 123)).fields()
        val proof = Cose.parse(handoff.bytes("proof")).verified(publicKey(buyerKey.publicBytes()))
        assertEquals(123L, proof.number("session_id"))
        store.save("buyer_ack_sent" to ack.hash.hex())
        assertTrue(repository.snapshot().payment!!.complete)
        val next = newRequest()
        repository.receiveBundle(Cbor.encode(mapOf("request" to next.raw,
            "certificate" to certificate("terminal", terminalKey).raw)))
        try {
            repository.resetUnattempted(next.hash.hex())
        } catch (error: Exception) {
            throw AssertionError("Unattempted request should remain removable", error)
        }
        assertThrows(Exception::class.java) { repository.receiveBundle(bundle) }
    }

    @Test
    fun corruptedSequenceCannotSilentlyRestartFromZero(): Unit = runBlocking {
        store.save("terminal_sequence" to "damaged")
        val repository = TerminalRepository(store)
        try {
            repository.createOrder(100)
            fail("Corrupt sequence was reset")
        } catch (_: NumberFormatException) {
        }
        assertEquals("", store.get("terminal_order"))
    }

    @Test
    fun legacyTokenCanBeOpenedAndMigratedWithoutChangingTheCredential() {
        val alias = "oasis-pay-relay-token-v1"
        val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val oldKey = (keys.getKey(alias, null) as? javax.crypto.SecretKey) ?: javax.crypto.KeyGenerator
            .getInstance("AES", "AndroidKeyStore").apply {
                init(android.security.keystore.KeyGenParameterSpec.Builder(alias,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                        android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, oldKey)
        cipher.updateAAD("offline-relay/1:buyer-access-token".toByteArray())
        val legacy = (cipher.iv + cipher.doFinal("c".repeat(64).toByteArray())).b64()
        assertTrue(SecretStore.isLegacy(legacy))
        assertEquals("c".repeat(64), SecretStore.open(legacy))
        val migrated = SecretStore.seal(SecretStore.open(legacy))
        assertFalse(SecretStore.isLegacy(migrated))
        assertEquals("c".repeat(64), SecretStore.open(migrated))
    }

    @Test
    fun tokenCiphertextTamperingFailsAuthentication() {
        val sealed = SecretStore.seal("b".repeat(64))
        assertEquals("b".repeat(64), SecretStore.open(sealed))
        val encoded = sealed.removePrefix("v2.").unb64()
        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { SecretStore.open("v2." + encoded.b64()) }
    }
}
