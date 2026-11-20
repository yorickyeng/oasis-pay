package ru.oasispay.data

import androidx.annotation.WorkerThread
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.oasispay.data.bank.BankClient
import ru.oasispay.data.bank.enroll
import ru.oasispay.data.local.PaymentStore
import ru.oasispay.data.local.SecretStore
import ru.oasispay.data.protocol.*
import ru.oasispay.domain.BuyerPayment
import ru.oasispay.domain.BuyerState
import ru.oasispay.domain.PaymentPreview
import ru.oasispay.domain.PaymentStatus

internal class BuyerRepository(
    private val store: PaymentStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val key = DeviceKey("buyer")

    @WorkerThread
    fun secureIdentity(): SecureIdentity = store.read {
        val certificate = requireNotNull(read("buyer_certificate"))
        val fields = verifyCertificate(certificate, bankKey(), "buyer", now())
        require(fields.bytes("public_key").contentEquals(key.publicBytes()))
        SecureIdentity(certificate.raw, bankKey(), key::sign)
    }

    suspend fun snapshot(): BuyerState = withContext(io) { store.read { snapshotState() } }

    suspend fun configure(url: String, pin: String, code: String) = withContext(io) {
        store.read {
            read("buyer_certificate")?.let { certificate ->
                val fields = verifyCertificate(certificate, bankKey(), "buyer")
                require(fields.bytes("public_key").contentEquals(key.publicBytes()))
            }
        }
        val reply = enroll("buyer", url, pin, code, key)
        store.write {
            val existing = store.get("buyer_bank_key")
            require(existing.isEmpty() || existing == reply.bytes("bank_key").b64()) {
                "На телефоне уже настроен другой банк"
            }
            store.save("buyer_url" to url.trim(), "buyer_pin" to pin,
                "buyer_token" to SecretStore.seal(reply.text("access_token")),
                "buyer_bank_key" to reply.bytes("bank_key").b64(),
                "buyer_certificate" to reply.bytes("certificate").b64(),
                "buyer_configured_at" to System.currentTimeMillis().toString())
        }
    }

    suspend fun refreshAccount() = withContext(io) {
        val nonce = randomBytes(32)
        val query = key.sign(message("account_query", "phone_device_id" to deviceId(),
            "nonce" to nonce, "created_at" to now()))
        val reply = bank().post("account", query.raw)
        val snapshot = Cose.parse(reply.bytes("account"))
        store.write {
            val fields = verifyAccount(snapshot)
            require(fields.bytes("request_nonce").contentEquals(nonce) &&
                kotlin.math.abs(now() - fields.number("observed_at")) <= CLOCK_SKEW) { "Ответ о балансе устарел" }
            store.save("buyer_account" to snapshot.raw.b64())
        }
    }

    private fun verifyAccount(snapshot: Cose): Fields {
        val fields = snapshot.verified(publicKey(bankKey()))
        fields.requireFields(setOf("device_id", "registration_number", "balance", "currency", "request_nonce", "observed_at"))
        require(fields.text("type") == "account_snapshot" && fields.text("device_id") == deviceId() &&
            fields.text("registration_number").matches(Regex("OP-C-[0-9]{6,19}")) &&
            fields.number("balance") >= 0 && fields.text("currency") == "RUB" &&
            fields.bytes("request_nonce").size == 32 && fields.number("observed_at") >= 0)
        return fields
    }

    suspend fun preview(requestHash: String): PaymentPreview = withContext(io) {
        val request = store.read { requestFor(requestHash) }
        val response = bank().post("payments/preview", authorization("payment_preview", request))
        require(response.number("amount") == request.fields.number("amount") &&
            response.text("merchant_id") == request.fields.text("merchant_id"))
        store.read { requestFor(requestHash) }
        PaymentPreview(requestHash, response.number("amount"), response.getValue("settled") as Boolean)
    }

    suspend fun pay(requestHash: String) = settle(requestHash, resolution = false)
    suspend fun resolve(requestHash: String) = settle(requestHash, resolution = true)

    private suspend fun settle(requestHash: String, resolution: Boolean) = withContext(io) {
        val request = store.write {
            val request = requestFor(requestHash)
            if (resolution) {
                check(now() > request.fields.number("expires_at") + CLOCK_SKEW) { "Счёт ещё действует" }
            }
            store.save("buyer_attempted" to "yes")
            request
        }
        val kind = if (resolution) "payment_resolution" else "payment_authorization"
        val route = if (resolution) "payments/resolve" else "payments"
        val response = bank().post(route, authorization(kind, request))
        store.write {
            requestFor(requestHash)
            val result = Cose.parse(response.bytes("result"))
            val fields = verifyResult(request, result, bankKey(), checkExpiry = false)
            require(fields.text("phone_device_id") == deviceId())
            val permit = response["permit"] as? ByteArray
            if (permit != null) verifyPermit(request, result, Cose.parse(permit), bankKey())
            val previous = read("buyer_result")
            require(previous == null || previous.raw.contentEquals(result.raw)) { "Банк изменил сохранённый результат" }
            store.save("buyer_result" to result.raw.b64(), "buyer_permit" to permit?.b64().orEmpty())
        }
    }

    suspend fun confirmDelivery(requestHash: String) = withContext(io) {
        val (request, ack) = store.read { requestFor(requestHash) to requireNotNull(read("buyer_ack")) }
        val signed = key.sign(message("relay_ack", "phone_device_id" to deviceId(), "terminal_ack" to ack.raw))
        val transaction = request.fields.text("transaction_id")
        val reply = bank().post("payments/$transaction/delivery-confirmation", signed.raw)
        require(reply.text("terminal_state") in setOf("RECEIVED", "FULFILLED"))
        store.write {
            requestFor(requestHash)
            // A newer acknowledgement remains pending if it arrived during the HTTP request.
            store.save("buyer_ack_sent" to ack.hash.hex())
        }
    }

    @WorkerThread
    fun receiveBundle(raw: ByteArray): ByteArray? = store.write {
        val bundle = Cbor.decode(raw).fields()
        require(bundle.keys == setOf("request", "certificate"))
        val request = Cose.parse(bundle.bytes("request"))
        val fields = request.fields
        validatePaymentRequest(fields)
        val certificate = verifyCertificate(Cose.parse(bundle.bytes("certificate")), bankKey(), "terminal")
        require(certificateCovers(certificate, fields.number("created_at")) || certificateCovers(certificate, now())) { "Требуется проверка регистрации кассы" }
        val terminalKey = certificate.bytes("public_key")
        request.verified(publicKey(terminalKey))
        require(certificate.text("device_id") == fields.text("terminal_id") &&
            certificate.text("merchant_id") == fields.text("merchant_id"))
        val old = snapshotState().payment
        if (old?.requestHash == request.hash.hex()) {
            return@write deliveryPacket()
        }
        require(old == null || old.complete) { "Сначала завершите текущую оплату" }
        require(!store.hasOperation("buyer", fields.text("transaction_id"))) { "Этот счёт уже обработан" }
        require(fields.number("created_at") <= now() + CLOCK_SKEW) { "Часы кассы требуют проверки" }
        store.save("buyer_request" to request.raw.b64(), "buyer_terminal_key" to terminalKey.b64(),
            "buyer_result" to "", "buyer_permit" to "", "buyer_ack" to "", "buyer_ack_sent" to "", "buyer_attempted" to "")
        null
    }

    @WorkerThread
    fun receiveAcknowledgement(raw: ByteArray, session: Int): ByteArray = store.write {
        val request = requireNotNull(read("buyer_request"))
        val result = requireNotNull(read("buyer_result"))
        val ack = Cose.parse(raw)
        val fields = verifyAcknowledgement(request, result, ack)
        val previous = read("buyer_ack")
        val alreadyFulfilled = previous?.fields?.text("terminal_state") == "FULFILLED"
        if (!alreadyFulfilled || fields.text("terminal_state") == "FULFILLED") {
            store.save("buyer_ack" to raw.b64())
        }
        val proof = key.sign(message("relay_handoff", "session_id" to session,
            "phone_device_id" to deviceId(), "transaction_id" to request.fields.text("transaction_id"),
            "ack_hash" to ack.hash))
        Cbor.encode(mapOf("proof" to proof.raw, "certificate" to store.get("buyer_certificate").unb64()))
    }

    suspend fun resetUnattempted(requestHash: String) = withContext(io) {
        store.write {
            requestFor(requestHash)
            val payment = requireNotNull(snapshotState().payment)
            check(payment.status == null && !payment.paymentAttempted) { "Сначала получите итоговый статус в банке" }
            store.save("buyer_request" to "", "buyer_terminal_key" to "", "buyer_result" to "", "buyer_permit" to "",
                "buyer_ack" to "", "buyer_ack_sent" to "", "buyer_attempted" to "")
        }
    }

    private fun snapshotState(): BuyerState {
        val certificate = read("buyer_certificate")?.let { certificate ->
            verifyCertificate(certificate, bankKey(), "buyer").also {
                require(it.bytes("public_key").contentEquals(key.publicBytes()))
            }
        }
        val payment = read("buyer_request")?.let { request ->
            validatePaymentRequest(request.verified(publicKey(store.get("buyer_terminal_key").unb64())))
            val result = read("buyer_result")
            val fields = result?.let { verifyResult(request, it, bankKey(), checkExpiry = false) }
            if (fields != null) require(fields.text("phone_device_id") == deviceId())
            val ack = read("buyer_ack")
            if (ack != null) verifyAcknowledgement(request, requireNotNull(result), ack)
            val permit = read("buyer_permit")
            if (permit != null) verifyPermit(request, requireNotNull(result), permit, bankKey(), checkExpiry = false)
            val permitExpiry = permit?.fields?.number("expires_at") ?: 0
            BuyerPayment(
                requestHash = request.hash.hex(),
                expiresAt = request.fields.number("expires_at"),
                status = fields?.text("status")?.let(PaymentStatus::valueOf),
                hasAcknowledgement = ack != null,
                acknowledgementSent = ack?.hash?.hex() == store.get("buyer_ack_sent"),
                paymentAttempted = store.get("buyer_attempted") == "yes",
                fulfilled = ack?.fields?.text("terminal_state") == "FULFILLED",
                transactionId = request.fields.text("transaction_id"),
                amount = request.fields.number("amount"),
                resultExpiresAt = fields?.number("expires_at") ?: 0,
                permitExpiresAt = permitExpiry,
                merchantId = request.fields.text("merchant_id"),
                description = request.fields.text("description"),
            )
        }
        val account = read("buyer_account")?.let(::verifyAccount)
        return BuyerState(certificate != null && store.get("buyer_token").isNotEmpty(),
            store.get("buyer_url"), payment, certificate?.number("valid_to") ?: 0,
            store.get("buyer_configured_at").toLongOrNull() ?: 0,
            certificate?.get("registration_number") as? String ?: account?.text("registration_number").orEmpty(),
            account?.number("balance"), account?.number("observed_at") ?: 0)
    }

    private fun verifyAcknowledgement(request: Cose, result: Cose, ack: Cose): Fields =
        verifyAcknowledgement(request, result, ack, publicKey(store.get("buyer_terminal_key").unb64()))

    private fun deliveryPacket(): ByteArray? {
        val result = read("buyer_result") ?: return null
        val permit = read("buyer_permit")
        return Cbor.encode(buildMap {
            put("result", result.raw)
            if (permit != null) put("permit", permit.raw)
        })
    }

    private fun authorization(kind: String, request: Cose): ByteArray = key.sign(
        message(kind, "phone_device_id" to deviceId(), "terminal_request" to request.raw) +
            bindings.associateWith { request.fields.getValue(it) },
    ).raw

    private fun read(name: String) = store.get(name).takeIf(String::isNotEmpty)?.let { Cose.parse(it.unb64()) }
    private fun requestFor(hash: String) = requireNotNull(read("buyer_request")).also {
        check(it.hash.hex() == hash) { "Операция изменилась" }
    }
    private fun deviceId() = key.publicBytes().sha256().hex()
    private fun bankKey() = store.get("buyer_bank_key").unb64()
    private fun bank() = store.write {
        val sealed = store.get("buyer_token")
        val token = SecretStore.open(sealed)
        if (SecretStore.isLegacy(sealed)) store.save("buyer_token" to SecretStore.seal(token))
        BankClient(store.get("buyer_url"), store.get("buyer_pin"), token)
    }
}
