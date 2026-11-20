package ru.oasispay.domain

internal enum class PaymentRole { TERMINAL, BUYER }
internal enum class PaymentStatus { PAID, DECLINED, CANCELLED }

internal data class Invoice(
    val amount: Long,
    val transactionId: String,
    val expiresAt: Long,
    val status: PaymentStatus?,
    val acknowledgementHanded: Boolean,
    val fulfilled: Boolean,
) {
    val complete get() = acknowledgementHanded && status != null
}

internal data class TerminalState(
    val enrolled: Boolean,
    val invoice: Invoice?,
    val registrationExpiresAt: Long = 0,
    val registrationNumber: String = "",
    val bankKeyFingerprint: String = "",
    val lastBankResultAt: Long = 0,
    val bankResultKeyId: String = "",
)

internal data class BuyerPayment(
    val requestHash: String,
    val expiresAt: Long,
    val status: PaymentStatus?,
    val hasAcknowledgement: Boolean,
    val acknowledgementSent: Boolean,
    val paymentAttempted: Boolean,
    val fulfilled: Boolean,
    val transactionId: String,
    val amount: Long,
    val resultExpiresAt: Long = 0,
    val permitExpiresAt: Long = 0,
    val merchantId: String = "",
    val description: String = "",
) {
    val complete get() = acknowledgementSent && status != null
}

internal data class BuyerState(
    val configured: Boolean,
    val bankUrl: String,
    val payment: BuyerPayment?,
    val registrationExpiresAt: Long = 0,
    val configurationRevision: Long = 0,
    val registrationNumber: String = "",
    val balance: Long? = null,
    val balanceObservedAt: Long = 0,
)
internal data class PaymentPreview(val requestHash: String, val amount: Long, val settled: Boolean)
