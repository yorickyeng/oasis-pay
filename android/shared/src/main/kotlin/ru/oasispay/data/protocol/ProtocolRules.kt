package ru.oasispay.data.protocol

internal const val MAX_AMOUNT = 100_000L
internal class BankClockException : IllegalArgumentException("Время телефона и банка расходится. Включите автоматическую дату и время на телефоне и Mac и повторите проверку.")

internal const val CLOCK_SKEW = 30L
internal const val REQUEST_LIFETIME = 120L
internal const val RESULT_LIFETIME = 86_400L
internal const val PERMIT_LIFETIME = 300L
private val IDENTIFIER = Regex("[0-9a-f]{32}")
private val DEVICE_IDENTIFIER = Regex("[0-9a-f]{64}")
private val COMMON = setOf("type", "protocol_version", "environment")

internal fun Fields.requireFields(required: Set<String>, optional: Set<String> = emptySet()) {
    require(keys.containsAll(COMMON + required) && keys.all { it in COMMON || it in required || it in optional }) {
        "Неподдерживаемые поля сообщения"
    }
}

internal fun Fields.requireDevice(name: String): String = text(name).also {
    require(DEVICE_IDENTIFIER.matches(it)) { "Некорректный идентификатор устройства" }
}

internal fun Fields.requireIdentifier(name: String): String = text(name).also {
    require(IDENTIFIER.matches(it)) { "Некорректный идентификатор операции" }
}

internal fun verifyCertificate(
    certificate: Cose,
    bankKey: ByteArray,
    role: String,
    at: Long? = null,
): Fields {
    val fields = certificate.verified(publicKey(bankKey))
    fields.requireFields(setOf("device_id", "role", "public_key", "merchant_id", "valid_from", "valid_to"),
        setOf("device_model", "registration_number"))
    require(fields.text("type") == "device_certificate" && fields.text("role") == role)
    if ("registration_number" in fields) {
        val prefix = if (role == "terminal") "T" else "C"
        require(fields.text("registration_number").matches(Regex("OP-$prefix-[0-9]{6,19}"))) {
            "Некорректный номер регистрации"
        }
    }
    val key = fields.bytes("public_key")
    publicKey(key)
    require(fields.requireDevice("device_id") == key.sha256().hex())
    require(fields.text("merchant_id").matches(Regex("[A-Za-z0-9_-]{1,64}")))
    val from = fields.number("valid_from")
    val to = fields.number("valid_to")
    require(from >= 0 && to > from && to - from <= 30 * RESULT_LIFETIME)
    if (at != null && at < from - CLOCK_SKEW) throw BankClockException()
    if (at != null) require(certificateCovers(fields, at)) { "Срок регистрации устройства истёк" }
    return fields
}

internal fun validatePaymentRequest(fields: Fields) {
    fields.requireFields(setOf("terminal_id", "merchant_id", "transaction_id", "order_id",
        "amount", "currency", "description", "nonce", "created_at", "expires_at",
        "terminal_sequence", "capabilities"))
    require(fields.text("type") == "payment_request")
    fields.requireDevice("terminal_id")
    fields.requireIdentifier("transaction_id")
    fields.requireIdentifier("order_id")
    require(fields.text("merchant_id").matches(Regex("[A-Za-z0-9_-]{1,64}")))
    require(fields.number("amount") in 1..MAX_AMOUNT && fields.text("currency") == "RUB")
    require(fields.text("description").length in 1..80 && fields.bytes("nonce").size == 32)
    require(fields.number("terminal_sequence") > 0 && fields["capabilities"] in listOf(listOf("ble-l2cap/1"), listOf("nfc-isodep/1"), listOf("ble-gatt/1"), listOf("ble-gatt/2")))
    val created = fields.number("created_at")
    val expires = fields.number("expires_at")
    require(created in 0..(Long.MAX_VALUE - REQUEST_LIFETIME) && expires > created &&
        expires - created <= REQUEST_LIFETIME)
}

internal fun verifyPermit(request: Cose, result: Cose, permit: Cose, bankKey: ByteArray,
                          moment: Long = now(), checkExpiry: Boolean = true) {
    val fields = permit.verified(publicKey(bankKey))
    fields.requireFields(setOf("terminal_id", "transaction_id", "phone_device_id",
        "request_hash", "result_hash", "issued_at", "expires_at"))
    require(fields.text("type") == "result_permit")
    require(fields.text("terminal_id") == request.fields.text("terminal_id") &&
        fields.text("transaction_id") == request.fields.text("transaction_id") &&
        fields.text("phone_device_id") == result.fields.text("phone_device_id") &&
        fields.bytes("request_hash").contentEquals(request.hash) &&
        fields.bytes("result_hash").contentEquals(result.hash))
    val issued = fields.number("issued_at")
    val expires = fields.number("expires_at")
    require(issued >= 0 && issued <= moment + CLOCK_SKEW && expires > issued &&
        expires - issued <= PERMIT_LIFETIME && (!checkExpiry || expires >= moment)) { "Разрешение банка просрочено" }
}

internal fun certificateCovers(certificate: Fields, moment: Long) =
    moment >= certificate.number("valid_from") - CLOCK_SKEW && moment <= certificate.number("valid_to")

internal fun verifyAcknowledgement(
    request: Cose,
    result: Cose,
    acknowledgement: Cose,
    terminalKey: java.security.PublicKey,
): Fields {
    val fields = acknowledgement.verified(terminalKey)
    fields.requireFields(setOf("terminal_id", "transaction_id", "result_hash", "terminal_state", "received_at"))
    require(fields.text("type") == "payment_result_ack" &&
        fields.text("terminal_id") == request.fields.text("terminal_id") &&
        fields.text("transaction_id") == request.fields.text("transaction_id") &&
        fields.bytes("result_hash").contentEquals(result.hash) &&
        fields.number("received_at") >= result.fields.number("processed_at") - CLOCK_SKEW &&
        fields.number("received_at") <= now() + CLOCK_SKEW)
    val state = fields.text("terminal_state")
    require(state == "RECEIVED" || (state == "FULFILLED" && result.fields.text("status") == "PAID"))
    return fields
}
