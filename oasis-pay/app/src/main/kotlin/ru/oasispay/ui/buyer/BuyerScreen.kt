package ru.oasispay.ui.buyer

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import ru.oasispay.domain.BuyerState
import ru.oasispay.domain.PaymentPreview
import ru.oasispay.domain.PaymentStatus
import ru.oasispay.domain.rubles
import ru.oasispay.ui.components.BankSetup
import ru.oasispay.ui.components.ClosePaymentDialog
import ru.oasispay.ui.components.Panel

@Composable
internal fun BuyerScreen(
    state: BuyerState,
    supportsHce: Boolean,
    busy: Boolean,
    preview: PaymentPreview?,
    now: Long,
    onConfigure: (String, String, String) -> Boolean,
    onPreview: (String) -> Unit,
    onPay: (String) -> Unit,
    onConfirmDelivery: (String) -> Unit,
    onReset: (String) -> Unit,
) {

    var showSettings by rememberSaveable { mutableStateOf(false) }
    if (!supportsHce) {
        Panel { Text("Для роли покупателя нужен Android-телефон с NFC HCE.") }
        return
    }
    if (!state.configured || showSettings) {
        BankSetup("Подключение покупателя", state.bankUrl, busy) { url, pin, code ->
            if (onConfigure(url, pin, code)) showSettings = false
        }
        return
    }

    val payment = state.payment
    when {
        payment == null -> Panel { Text("Поднесите разблокированный телефон к кассе. Сумма появится после проверки банком.") }
        payment.acknowledgementSent -> Panel { Text("Банк получил подтверждение кассы. Можно начать следующую оплату.") }
        payment.status != null -> {
            Panel { Text(if (payment.status == PaymentStatus.PAID) "Банк принял платёж" else "Банк отклонил платёж") }
            if (payment.hasAcknowledgement) {
                Button({ onConfirmDelivery(payment.requestHash) }, Modifier.fillMaxWidth(), enabled = !busy) {
                    Text("Завершить обмен с банком")
                }
            } else {
                Text("Поднесите телефон к кассе ещё раз, чтобы передать результат.")
            }
        }
        else -> {
            PaymentApproval(preview, busy, { onPreview(payment.requestHash) }, { onPay(payment.requestHash) })
            if (!payment.paymentAttempted) {
                TextButton({ onReset(payment.requestHash) }, enabled = !busy) { Text("Сбросить запрос") }
            } else if (!busy && now > payment.expiresAt) {
                var showCloseDialog by rememberSaveable { mutableStateOf(false) }
                TextButton({ showCloseDialog = true }) { Text("Закрыть зависшую операцию") }
                if (showCloseDialog) {
                    ClosePaymentDialog("Закрыть операцию на телефоне?", { showCloseDialog = false }) {
                        onReset(payment.requestHash)
                        showCloseDialog = false
                    }
                }
            }
        }
    }
    if (payment == null || payment.acknowledgementSent) {
        TextButton({ showSettings = true }, enabled = !busy) { Text("Настройки подключения") }
    }
}

@Composable
private fun PaymentApproval(preview: PaymentPreview?, busy: Boolean, onPreview: () -> Unit, onPay: () -> Unit) {
    if (preview == null) {
        Button(onPreview, Modifier.fillMaxWidth(), enabled = !busy) { Text("Проверить сумму в банке") }
        return
    }
    Panel {
        Text(rubles(preview.amount), style = MaterialTheme.typography.displaySmall)
        Text(if (preview.settled) "Банк уже обработал эту операцию" else "Сумма проверена банком")
    }
    Button(onPay, Modifier.fillMaxWidth(), enabled = !busy) {
        Text(if (preview.settled) "Получить результат" else "Подтвердить оплату")
    }
}
