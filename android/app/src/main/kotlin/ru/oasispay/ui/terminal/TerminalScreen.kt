package ru.oasispay.ui.terminal

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ru.oasispay.domain.PaymentStatus
import ru.oasispay.domain.TerminalState
import ru.oasispay.domain.rubles
import ru.oasispay.ui.components.*

@Composable
internal fun TerminalScreen(
    state: TerminalState, busy: Boolean, armed: Boolean, now: Long,
    peerConnected: Boolean = false,
    onEnroll: (String, String, String) -> Unit, onCreateOrder: (String) -> Unit,
    onArm: () -> Unit, onPause: () -> Unit, onFulfill: () -> Unit,
    nfcReady: Boolean, nfcSupported: Boolean, nfcActionLabel: String,
    onCheckBank: (String, String) -> Unit,
    onPermission: () -> Unit,
) {
    var showRegistration by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.registrationExpiresAt, state.registrationNumber) { showRegistration = false }
    if (!state.enrolled || showRegistration ||
        (state.registrationExpiresAt <= now + 120 && (state.invoice == null || state.invoice.complete))) {
        BankSetup("Регистрация терминала", "", busy, onEnroll, ru.oasispay.domain.PaymentRole.TERMINAL, onCheckBank)
        if (showRegistration) TextButton({ showRegistration = false }, enabled = !busy) { Text("Назад") }
        return
    }
    var digits by rememberSaveable { mutableStateOf("") }
    var showFulfill by rememberSaveable { mutableStateOf(false) }
    val invoice = state.invoice
    val newOrder = invoice == null || invoice.complete
    val entered = digits.toLongOrNull() ?: 0L
    val paid = invoice?.status == PaymentStatus.PAID

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("ПЛАТЁЖНЫЙ ТЕРМИНАЛ", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(state.registrationNumber.ifEmpty { "Зарегистрирован · обновите номер" },
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleSmall)
        }
        TextButton({ showRegistration = true }, enabled = !busy && !armed && newOrder) { Text("Регистрация") }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusTag(if (peerConnected) "Телефон подключён" else "Ожидаем телефон", positive = peerConnected)
    }
    Caption(if (state.lastBankResultAt > 0) "Банк · подписанный ответ доставлен покупателем"
        else "Банк · ответ ожидается через покупателя")

    if (invoice != null) {
        Panel {
            StatusTag(when (invoice.status) {
                PaymentStatus.PAID -> "Оплата принята"
                PaymentStatus.DECLINED -> "Банк отклонил платёж"
                PaymentStatus.CANCELLED -> "Счёт отменён банком"
                null -> if (now > invoice.expiresAt) "Требуется сверка" else "Ожидаем покупателя"
            }, positive = paid, negative = invoice.status == PaymentStatus.DECLINED)
            Text(rubles(invoice.amount), style = MaterialTheme.typography.displayLarge)
            Caption("Счёт ${invoice.transactionId.takeLast(8).uppercase()}")
            if (!newOrder && invoice.status == null) {
                ContactlessTarget(armed, if (armed) "Поднесите телефон покупателя" else "Обмен приостановлен")
                Caption("Приложите телефон покупателя к кассе. Покупатель проверяет сумму и подтверждает оплату.")
                val remaining = (invoice.expiresAt - now).coerceAtLeast(0)
                Caption(if (remaining > 0) "Ожидаем оплату · %d:%02d".format(remaining / 60, remaining % 60)
                    else "Срок счёта истёк. Покупатель может уточнить результат в банке и вернуть подписанный ответ.")
            }
            if (invoice.status != null) {
                Caption(if (invoice.acknowledgementHanded) "Подпись банка проверена · подтверждение кассы передано покупателю"
                    else "Подпись банка проверена · ожидаем завершения доставки подтверждения")
                if (paid) Text("✓", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
                if (invoice.fulfilled) Caption("Выдача товара отмечена отдельно.")
                else if (paid && invoice.acknowledgementHanded) TextButton({ showFulfill = true }, enabled = !busy && !armed) {
                    Text("Отметить фактическую выдачу")
                }
            }
        }
    }

    if (!nfcReady) {
        PrimaryAction(nfcActionLabel, enabled = !busy && nfcSupported, onClick = onPermission)
    }
    if (newOrder) {
        Panel {
            Text(if (invoice == null) "Сумма к оплате" else "Следующий покупатель", style = MaterialTheme.typography.titleMedium)
            Text(rubles(entered), style = MaterialTheme.typography.displaySmall)
            for (row in listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("Очистить", "0", "Удалить"))) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (symbol in row) FilledTonalButton(onClick = {
                        digits = when (symbol) {
                            "Очистить" -> ""
                            "Удалить" -> digits.dropLast(1)
                            else -> (digits + symbol).trimStart('0').take(6)
                        }
                    }, enabled = !busy && !armed, modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Text(symbol, style = if (symbol.length == 1) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Caption("Ввод в копейках · до 1 000 ₽ на одну тестовую оплату")
            PrimaryAction("Принять оплату", enabled = nfcReady && !busy && !armed && entered in 1..100_000) {
                onCreateOrder("%d,%02d".format(entered / 100, entered % 100))
                digits = ""
            }
        }
    } else if (nfcReady) {
        if (armed) SecondaryAction("Приостановить обмен", !busy, onPause)
        else PrimaryAction("Продолжить приём", !busy, onArm)
    }

    if (showFulfill) AlertDialog(onDismissRequest = { showFulfill = false },
        title = { Text("Товар выдан?") },
        text = { Text("Это отдельная отметка о фактической выдаче. Она не меняет результат оплаты.") },
        confirmButton = { TextButton({ showFulfill = false; onFulfill() }) { Text("Товар выдан") } },
        dismissButton = { TextButton({ showFulfill = false }) { Text("Назад") } })
}
