package ru.oasispay.data.protocol

internal fun verifyResult(request: Envelope, result: Envelope, bankKey: String) {
    result.verify(bankKey)
    val payment = request.value
    val receipt = result.value
    require(receipt.getString("type") == "payment_result" && receipt.getString("request_hash") == request.hash)
    for (field in listOf("terminal_id", "transaction_id", "nonce", "currency")) {
        require(receipt.getString(field) == payment.getString(field)) { "Ответ относится к другой операции" }
    }
    require(receipt.getLong("amount") == payment.getLong("amount")) { "Сумма ответа отличается" }
    require(receipt.getLong("processed_at") <= now() + 60 && now() <= receipt.getLong("expires_at")) {
        "Срок действия ответа истёк или часы устройства сбились"
    }
    require(receipt.getString("status") in listOf("PAID", "DECLINED"))
}
