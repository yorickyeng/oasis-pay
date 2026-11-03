package ru.oasispay.data

import ru.oasispay.data.bank.BankClient
import ru.oasispay.data.local.PaymentPreferences
import ru.oasispay.data.protocol.Envelope
import ru.oasispay.data.protocol.TerminalKey
import ru.oasispay.data.protocol.hex
import ru.oasispay.data.protocol.json
import ru.oasispay.data.protocol.now
import ru.oasispay.data.protocol.randomHex
import ru.oasispay.data.protocol.sha256
import ru.oasispay.data.protocol.unb64
import ru.oasispay.data.protocol.verifyResult
import ru.oasispay.domain.Invoice
import ru.oasispay.domain.PaymentStatus
import ru.oasispay.domain.TerminalState

internal class TerminalRepository(private val preferences: PaymentPreferences) {
    @Synchronized
    fun snapshot(): TerminalState {
        val invoice = currentOrder().takeIf(String::isNotEmpty)?.let {
            val request = Envelope.decode(it).value
            val status = preferences.get("terminal_result").takeIf(String::isNotEmpty)?.let { result ->
                PaymentStatus.valueOf(Envelope.decode(result).value.getString("status"))
            }
            Invoice(request.getLong("amount"), request.getString("transaction_id"),
                request.getLong("expires_at"), status, preferences.get("ack_handed") == "yes")
        }
        return TerminalState(preferences.get("terminal_id").isNotEmpty(), invoice)
    }

    fun enroll(url: String, pin: String, code: String) {
        val key = TerminalKey.publicKey()
        val response = BankClient(url, pin, code).post("enroll", json("public_key" to key).toString())
        val terminal = response.getString("terminal_id")
        check(terminal == unb64(key).sha256().hex())
        preferences.save("terminal_id" to terminal, "bank_key" to response.getString("bank_key"))
    }

    fun currentOrder() = preferences.get("order")

    @Synchronized
    fun createOrder(amount: Long) {
        val terminal = preferences.get("terminal_id")
        check(terminal.isNotEmpty()) { "Сначала зарегистрируйте кассу" }
        check(currentOrder().isEmpty() || preferences.get("ack_handed") == "yes")
        val timestamp = now()
        val request = TerminalKey.sign(json(
            "type" to "payment_request", "terminal_id" to terminal,
            "transaction_id" to randomHex(16), "nonce" to randomHex(32),
            "amount" to amount, "currency" to "RUB", "created_at" to timestamp,
            "expires_at" to timestamp + 300,
        ))
        saveOrder(request.encode())
    }

    @Synchronized
    fun closeExpiredOrder() {
        check(snapshot().invoice?.expiresAt?.let { now() > it } == true)
        saveOrder("")
    }

    private fun saveOrder(order: String) {
        preferences.save("order" to order, "terminal_result" to "", "terminal_ack" to "", "ack_handed" to "")
    }

    @Synchronized
    fun acceptResult(order: String, raw: String): String {
        checkCurrentOrder(order)
        val original = preferences.get("terminal_result")
        if (original.isNotEmpty()) {
            check(raw == original) { "Для этой операции уже сохранён другой результат" }
            return preferences.get("terminal_ack")
        }
        val receipt = Envelope.decode(raw)
        val request = Envelope.decode(order)
        verifyResult(request, receipt, preferences.get("bank_key"))
        val ack = TerminalKey.sign(json(
            "type" to "delivery_ack", "terminal_id" to request.value.getString("terminal_id"),
            "transaction_id" to request.value.getString("transaction_id"), "result_hash" to receipt.hash,
        )).encode()
        preferences.save("terminal_result" to raw, "terminal_ack" to ack)
        return ack
    }

    @Synchronized
    fun markDelivered(order: String) {
        checkCurrentOrder(order)
        preferences.save("ack_handed" to "yes")
    }

    private fun checkCurrentOrder(order: String) {
        check(order.isNotEmpty() && order == currentOrder()) { "Операция на кассе изменилась" }
    }
}
