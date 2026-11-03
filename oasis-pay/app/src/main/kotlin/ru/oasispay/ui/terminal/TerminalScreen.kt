package ru.oasispay.ui.terminal

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import ru.oasispay.domain.Invoice
import ru.oasispay.domain.PaymentStatus
import ru.oasispay.domain.TerminalState
import ru.oasispay.domain.rubles
import ru.oasispay.ui.components.BankSetup
import ru.oasispay.ui.components.ClosePaymentDialog
import ru.oasispay.ui.components.Panel

@Composable
internal fun TerminalScreen(
    state: TerminalState,
    busy: Boolean,
    armed: Boolean,
    now: Long,
    onEnroll: (String, String, String) -> Unit,
    onCreateOrder: (String) -> Unit,
    onArm: () -> Unit,
    onPause: () -> Unit,
    onCloseOrder: () -> Unit,
) {
    if (!state.enrolled) {
        BankSetup("Регистрация кассы", "", busy, onEnroll)
        return
    }

    var amount by rememberSaveable { mutableStateOf("199,00") }
    var showCloseDialog by rememberSaveable { mutableStateOf(false) }
    val invoice = state.invoice
    if (invoice != null) {
        InvoicePanel(invoice)
        if (!invoice.acknowledgementHanded) {
            Button(onArm, Modifier.fillMaxWidth(), enabled = !armed) { Text("Начать обмен NFC") }
            if (armed) {
                OutlinedButton(onPause, Modifier.fillMaxWidth()) { Text("Приостановить NFC") }
            } else if (now > invoice.expiresAt) {
                TextButton({ showCloseDialog = true }) { Text("Закрыть зависшую операцию") }
            }
        }
    }

    if (invoice == null || invoice.acknowledgementHanded) {
        OutlinedTextField(
            amount, { amount = it }, Modifier.fillMaxWidth(), label = { Text("Сумма, ₽") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        Button({ onCreateOrder(amount) }, Modifier.fillMaxWidth()) { Text("Выставить счёт") }
    }

    if (showCloseDialog) {
        ClosePaymentDialog("Закрыть операцию на кассе?", { showCloseDialog = false }) {
            onCloseOrder()
            showCloseDialog = false
        }
    }
}

@Composable
private fun InvoicePanel(invoice: Invoice) {
    Panel {
        Text(rubles(invoice.amount), style = MaterialTheme.typography.displaySmall)
        Text("Операция ${invoice.transactionId.takeLast(8).uppercase()}")
        Text(when (invoice.status) {
            PaymentStatus.PAID -> "Оплачено · подпись банка проверена"
            PaymentStatus.DECLINED -> "Отклонено банком"
            null -> "Ожидается подписанный результат банка"
        })
        if (invoice.acknowledgementHanded) Text("Подтверждение передано покупателю")
    }
}
