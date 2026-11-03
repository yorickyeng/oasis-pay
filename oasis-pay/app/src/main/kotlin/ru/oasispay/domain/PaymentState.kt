package ru.oasispay.domain

internal enum class PaymentRole { TERMINAL, BUYER }

internal enum class PaymentStatus { PAID, DECLINED }

internal data class Invoice(
    val amount: Long,
    val transactionId: String,
    val expiresAt: Long,
    val status: PaymentStatus?,
    val acknowledgementHanded: Boolean,
)

internal data class TerminalState(
    val enrolled: Boolean,
    val invoice: Invoice?,
)

internal data class BuyerPayment(
    val requestHash: String,
    val expiresAt: Long,
    val status: PaymentStatus?,
    val hasAcknowledgement: Boolean,
    val acknowledgementSent: Boolean,
    val paymentAttempted: Boolean,
)

internal data class BuyerState(
    val configured: Boolean,
    val bankUrl: String,
    val payment: BuyerPayment?,
)

internal data class PaymentPreview(val requestHash: String, val amount: Long, val settled: Boolean)
