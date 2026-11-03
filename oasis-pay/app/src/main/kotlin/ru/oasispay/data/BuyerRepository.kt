package ru.oasispay.data

import ru.oasispay.data.bank.BankClient
import ru.oasispay.data.local.PaymentPreferences
import ru.oasispay.data.protocol.Envelope
import ru.oasispay.data.protocol.now
import ru.oasispay.domain.BuyerPayment
import ru.oasispay.domain.BuyerState
import ru.oasispay.domain.PaymentPreview
import ru.oasispay.domain.PaymentStatus

internal class BuyerRepository(private val preferences: PaymentPreferences) {
    fun isConfigured() = preferences.get("buyer_code").isNotEmpty()

    fun snapshot(): BuyerState {
        val payment = preferences.get("buyer_request").takeIf(String::isNotEmpty)?.let { request ->
            val status = preferences.get("buyer_result").takeIf(String::isNotEmpty)?.let { result ->
                PaymentStatus.valueOf(Envelope.decode(result).value.getString("status"))
            }
            val envelope = Envelope.decode(request)
            BuyerPayment(
                envelope.hash, envelope.value.getLong("expires_at"), status,
                preferences.get("buyer_ack").isNotEmpty(), preferences.get("ack_sent") == "yes",
                preferences.get("pay_attempted") == "yes",
            )
        }
        return BuyerState(isConfigured(), preferences.get("url"), payment)
    }

    fun configure(url: String, pin: String, code: String) {
        BankClient(url, pin, code)
        preferences.save("url" to url, "pin" to pin, "buyer_code" to code)
    }

    fun preview(requestHash: String): PaymentPreview {
        val request = requestFor(requestHash)
        val response = bank().post("preview", request)
        return PaymentPreview(requestHash, response.getLong("amount"), !response.isNull("settled"))
    }

    fun pay(requestHash: String) {
        val request = requestFor(requestHash)
        preferences.save("pay_attempted" to "yes")
        val result = Envelope.decode(bank().post("pay", request).toString())
        val value = result.value
        check(value.getString("type") == "payment_result" &&
            value.getString("request_hash") == Envelope.decode(request).hash)
        PaymentStatus.valueOf(value.getString("status"))
        preferences.save("buyer_result" to result.encode())
    }

    fun confirmDelivery(requestHash: String) {
        requestFor(requestHash)
        val ack = preferences.get("buyer_ack")
        check(ack.isNotEmpty() && bank().post("ack", ack).getBoolean("received"))
        preferences.save("ack_sent" to "yes", "pay_attempted" to "")
    }

    fun reset(requestHash: String) {
        requestFor(requestHash)
        val payment = requireNotNull(snapshot().payment)
        check(payment.status == null && (!payment.paymentAttempted || now() > payment.expiresAt))
        saveRequest("")
    }

    fun receiveRequest(incoming: String): String {
        val envelope = Envelope.decode(incoming)
        require(envelope.value.getString("type") == "payment_request")
        require(envelope.value.getLong("expires_at") > 0)
        val previous = preferences.get("buyer_request")
        if (incoming != previous) {
            require(previous.isEmpty() || preferences.get("ack_sent") == "yes")
            saveRequest(incoming)
        }
        return preferences.get("buyer_result")
    }

    fun receiveAcknowledgement(request: String, result: String, incoming: String) {
        checkCurrentRequest(request)
        val ack = Envelope.decode(incoming).value
        val original = Envelope.decode(request).value
        require(ack.getString("type") == "delivery_ack")
        for (key in listOf("terminal_id", "transaction_id")) {
            require(ack.getString(key) == original.getString(key))
        }
        require(ack.getString("result_hash") == Envelope.decode(result).hash)
        if (preferences.get("ack_sent") != "yes") preferences.save("buyer_ack" to incoming)
    }

    private fun saveRequest(request: String) {
        preferences.save("buyer_request" to request, "buyer_result" to "", "buyer_ack" to "",
            "ack_sent" to "", "pay_attempted" to "")
    }

    private fun requestFor(requestHash: String): String {
        val request = preferences.get("buyer_request")
        check(request.isNotEmpty() && Envelope.decode(request).hash == requestHash) {
            "Операция на телефоне изменилась"
        }
        return request
    }

    private fun checkCurrentRequest(request: String) {
        check(request.isNotEmpty() && request == preferences.get("buyer_request")) {
            "Операция на телефоне изменилась"
        }
    }

    private fun bank() = BankClient(preferences.get("url"), preferences.get("pin"), preferences.get("buyer_code"))
}
