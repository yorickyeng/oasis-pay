package ru.oasispay.data

import androidx.annotation.WorkerThread
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.oasispay.data.bank.enroll
import ru.oasispay.data.local.PaymentStore
import ru.oasispay.data.protocol.*
import ru.oasispay.domain.Invoice
import ru.oasispay.domain.PaymentStatus
import ru.oasispay.domain.TerminalState

internal class TerminalRepository(
    private val store: PaymentStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val key = DeviceKey("terminal")

    @WorkerThread
    fun secureIdentity(): SecureIdentity = store.read {
        val certificate = requireNotNull(read("terminal_certificate"))
        val fields = verifyCertificate(certificate, bankKey(), "terminal", now())
        require(fields.bytes("public_key").contentEquals(key.publicBytes()))
        SecureIdentity(certificate.raw, bankKey(), key::sign)
    }

    suspend fun snapshot(): TerminalState = withContext(io) { store.read { snapshotState() } }

    suspend fun enroll(url: String, pin: String, code: String) = withContext(io) {
        store.read {
            if (store.get("terminal_certificate").isNotEmpty()) certificate()
        }
        val reply = enroll("terminal", url, pin, code, key)
        store.write {
            val existing = store.get("terminal_bank_key")
            require(existing.isEmpty() || existing == reply.bytes("bank_key").b64()) {
                "На кассе уже настроен другой банк"
            }
            store.save("terminal_certificate" to reply.bytes("certificate").b64(),
                "terminal_bank_key" to reply.bytes("bank_key").b64())
        }
    }

    suspend fun createOrder(amount: Long, transport: String = "nfc-isodep/1") = withContext(io) {
        store.write {
            val previous = snapshotState()
            check(previous.enrolled && (previous.invoice == null || previous.invoice.complete))
            require(amount in 1..MAX_AMOUNT)
            val certificate = certificate()
            val timestamp = now()
            check(certificate.number("valid_to") >= timestamp + REQUEST_LIFETIME) {
                "Продлите регистрацию кассы в банке"
            }
            val storedSequence = store.get("terminal_sequence")
            val previousSequence = if (storedSequence.isEmpty()) 0L else storedSequence.toLong()
            require(previousSequence >= 0)
            read("terminal_order")?.let { require(previousSequence >= it.fields.number("terminal_sequence")) }
            val sequence = Math.addExact(previousSequence, 1L)
            val request = key.sign(message("payment_request",
                "terminal_id" to certificate.text("device_id"), "merchant_id" to certificate.text("merchant_id"),
                "transaction_id" to newId(), "order_id" to newId(), "amount" to amount, "currency" to "RUB",
                "description" to "Покупка", "nonce" to randomBytes(32), "created_at" to timestamp,
                "expires_at" to timestamp + REQUEST_LIFETIME, "terminal_sequence" to sequence,
                "capabilities" to listOf(transport)))
            store.save("terminal_order" to request.raw.b64(), "terminal_sequence" to sequence.toString(),
                "terminal_result" to "", "terminal_ack" to "", "terminal_ack_handed" to "", "terminal_fulfilled" to "", "terminal_handoff" to "")
        }
    }

    /** Called by the GATT worker. The payload and its hash come from the same committed snapshot. */
    @WorkerThread
    fun exchangeRequest(): Pair<ByteArray, ByteArray> = store.read {
        val request = requireNotNull(read("terminal_order"))
        validateOwnRequest(request)
        request.hash to Cbor.encode(mapOf(
            "request" to request.raw, "certificate" to store.get("terminal_certificate").unb64()))
    }

    @WorkerThread
    fun acceptResult(orderHash: ByteArray, packet: ByteArray, buyerId: String? = null): ByteArray = store.write {
        val request = requireNotNull(read("terminal_order"))
        validateOwnRequest(request)
        check(request.hash.contentEquals(orderHash)) { "Счёт на кассе изменился" }
        // Legacy senders deliver a raw COSE object. New senders add a bank permit for recovery.
        val result = when (val decoded = Cbor.decode(packet)) {
            is List<*> -> Cose.parse(packet)
            is Map<*, *> -> Cose.parse(decoded.fields().also {
                require(it.keys == setOf("result") || it.keys == setOf("result", "permit"))
            }.bytes("result"))
            else -> error("Некорректный ответ банка")
        }
        if (buyerId != null) require(result.fields.text("phone_device_id") == buyerId) { "Ответ предназначен другому покупателю" }
        val original = read("terminal_result")
        if (original != null) {
            check(original.raw.contentEquals(result.raw)) { "Для счёта уже сохранён другой результат" }
            return@write requireNotNull(read("terminal_ack")).raw
        }
        val fields = verifyResult(request, result, bankKey(), checkExpiry = false)
        if (fields.number("expires_at") < now()) {
            val permit = Cose.parse(Cbor.decode(packet).fields().bytes("permit"))
            verifyPermit(request, result, permit, bankKey())
        }
        val ack = acknowledgement(request, result, "RECEIVED")
        store.save("terminal_result" to result.raw.b64(), "terminal_ack" to ack.raw.b64())
        ack.raw
    }

    suspend fun fulfill() = withContext(io) {
        store.write {
            val request = requireNotNull(read("terminal_order"))
            validateOwnRequest(request)
            val result = requireNotNull(read("terminal_result"))
            val fields = verifyResult(request, result, bankKey(), checkExpiry = false)
            check(fields.text("status") == "PAID" && store.get("terminal_fulfilled").isEmpty())
            val ack = acknowledgement(request, result, "FULFILLED")
            store.save("terminal_ack" to ack.raw.b64(), "terminal_fulfilled" to "yes", "terminal_ack_handed" to "", "terminal_handoff" to "")
        }
    }

    @WorkerThread
    fun markHanded(raw: ByteArray, session: Int) = store.write {
        verifyHandoff(raw, session)
        store.save("terminal_ack_handed" to "yes", "terminal_handoff" to raw.b64())
    }

    private fun verifyHandoff(raw: ByteArray, session: Int? = null) {
        val ack = requireNotNull(read("terminal_ack"))
        val result = requireNotNull(read("terminal_result")).fields
        val bundle = Cbor.decode(raw).fields()
        require(bundle.keys == setOf("proof", "certificate"))
        val certificate = verifyCertificate(Cose.parse(bundle.bytes("certificate")), bankKey(), "buyer",
            at = if (session != null) now() else null)
        val buyerId = certificate.text("device_id")
        require(buyerId == result.text("phone_device_id"))
        val proof = Cose.parse(bundle.bytes("proof")).verified(publicKey(certificate.bytes("public_key")))
        proof.requireFields(setOf("session_id", "phone_device_id", "transaction_id", "ack_hash"))
        require(proof.text("type") == "relay_handoff" &&
            proof.number("session_id") in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() &&
            (session == null || proof.number("session_id") == session.toLong()) &&
            proof.text("phone_device_id") == buyerId && proof.text("transaction_id") == result.text("transaction_id") &&
            proof.bytes("ack_hash").contentEquals(ack.hash))
    }

    private fun snapshotState(): TerminalState {
        val certificate = read("terminal_certificate")?.let { certificate() }
        val invoice = read("terminal_order")?.let { request ->
            validateOwnRequest(request)
            val result = read("terminal_result")?.let {
                verifyResult(request, it, bankKey(), checkExpiry = false)
            }
            val status = result?.text("status")?.let(PaymentStatus::valueOf)
            val fulfilled = store.get("terminal_fulfilled") == "yes"
            require(!fulfilled || status == PaymentStatus.PAID)
            val ack = read("terminal_ack")
            if (result != null) {
                val fields = verifyAcknowledgement(request, requireNotNull(read("terminal_result")),
                    requireNotNull(ack), publicKey(key.publicBytes()))
                require(fields.text("terminal_state") == if (fulfilled) "FULFILLED" else "RECEIVED")
                require(fields.bytes("result_hash").contentEquals(requireNotNull(read("terminal_result")).hash))
            } else check(!fulfilled && ack == null && store.get("terminal_ack_handed").isEmpty())
            val handoff = store.get("terminal_handoff")
            if (store.get("terminal_ack_handed") == "yes") {
                require(handoff.isNotEmpty()) { "Требуется повторная передача подтверждения" }
                verifyHandoff(handoff.unb64())
            }
            Invoice(request.fields.number("amount"), request.fields.text("transaction_id"),
                request.fields.number("expires_at"), status, store.get("terminal_ack_handed") == "yes", fulfilled)
        }
        return TerminalState(certificate != null, invoice, certificate?.number("valid_to") ?: 0,
            certificate?.get("registration_number") as? String ?: "",
            if (certificate != null) bankKey().sha256().hex() else "",
            read("terminal_result")?.fields?.number("processed_at") ?: 0,
            read("terminal_result")?.signingKeyId?.hex().orEmpty())
    }

    private fun validateOwnRequest(request: Cose) {
        val fields = request.verified(publicKey(key.publicBytes()))
        validatePaymentRequest(fields)
        val certificate = certificate()
        require(fields.text("terminal_id") == certificate.text("device_id") &&
            fields.text("merchant_id") == certificate.text("merchant_id"))
    }

    private fun certificate(): Fields = verifyCertificate(
        requireNotNull(read("terminal_certificate")), bankKey(), "terminal",
    ).also { require(it.bytes("public_key").contentEquals(key.publicBytes())) }

    private fun bankKey() = store.get("terminal_bank_key").unb64()
    private fun read(name: String) = store.get(name).takeIf(String::isNotEmpty)?.let { Cose.parse(it.unb64()) }
    private fun acknowledgement(request: Cose, result: Cose, state: String) = key.sign(message("payment_result_ack",
        "terminal_id" to request.fields.text("terminal_id"), "transaction_id" to request.fields.text("transaction_id"),
        "result_hash" to result.hash, "terminal_state" to state, "received_at" to now()))
}
