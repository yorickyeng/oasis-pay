package ru.oasispay.ui

import ru.oasispay.domain.BuyerState
import ru.oasispay.domain.PaymentPreview
import ru.oasispay.domain.PaymentRole
import ru.oasispay.domain.TerminalState

internal data class ExchangeEvent(val time: Long, val message: String, val error: Boolean = false)

internal data class PaymentUiState(
    val role: PaymentRole?,
    val terminal: TerminalState,
    val buyer: BuyerState,
    val initialized: Boolean = false,
    val storageHealthy: Boolean = true,
    val recoveryMessage: String = "",
    val armed: Boolean = false,
    val busy: Boolean = false,
    val message: String = "",
    val error: Boolean = false,
    val preview: PaymentPreview? = null,
    val now: Long = 0,
    val network: ru.oasispay.data.NetworkState = ru.oasispay.data.NetworkState(),
    val events: List<ExchangeEvent> = emptyList(),
    val terminalPeerConnected: Boolean = false,
    val scanning: Boolean = false,
)
