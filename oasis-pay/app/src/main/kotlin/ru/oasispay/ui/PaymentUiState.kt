package ru.oasispay.ui

import ru.oasispay.domain.BuyerState
import ru.oasispay.domain.PaymentPreview
import ru.oasispay.domain.PaymentRole
import ru.oasispay.domain.TerminalState

internal data class PaymentUiState(
    val role: PaymentRole?,
    val terminal: TerminalState,
    val buyer: BuyerState,
    val armed: Boolean = false,
    val busy: Boolean = false,
    val message: String = "",
    val preview: PaymentPreview? = null,
    val now: Long = 0,
)
