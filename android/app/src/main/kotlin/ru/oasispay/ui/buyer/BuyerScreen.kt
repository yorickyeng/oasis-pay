package ru.oasispay.ui.buyer

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import ru.oasispay.domain.BuyerState
import ru.oasispay.domain.PaymentPreview
import ru.oasispay.domain.PaymentStatus
import ru.oasispay.domain.rubles
import ru.oasispay.ui.components.*

@Composable
internal fun BuyerScreen(
    state: BuyerState, busy: Boolean, preview: PaymentPreview?, now: Long,
    scanning: Boolean, onCancelScan: () -> Unit,
    onRefreshAccount: () -> Unit,
    onConfigure: (String, String, String) -> Unit, onPreview: (String) -> Unit, onPay: (String) -> Unit,
    onResolve: (String) -> Unit,
    onConfirmDelivery: (String) -> Unit, onReset: (String) -> Unit, onScan: () -> Unit,
    nfcReady: Boolean, nfcSupported: Boolean, nfcActionLabel: String,
    onCheckBank: (String, String) -> Unit,
    onPermission: () -> Unit,
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.configurationRevision) { showSettings = false }
    if (!state.configured || state.registrationExpiresAt <= now || showSettings) {
        BankSetup("Подключение к банку", state.bankUrl, busy, onConfigure, ru.oasispay.domain.PaymentRole.BUYER, onCheckBank)
        if (showSettings && state.configured && state.registrationExpiresAt > now) {
            TextButton({ showSettings = false }, enabled = !busy) { Text("Назад") }
        }
        return
    }
    WalletCard(state, busy, onRefreshAccount)
    val payment = state.payment
    var discoveryStarted by rememberSaveable(state.configurationRevision) { mutableStateOf(false) }
    LaunchedEffect(state.configurationRevision, nfcReady, busy, payment) {
        if (!discoveryStarted && nfcReady && !busy && payment == null) {
            discoveryStarted = true
            onScan()
        }
    }
    val scan = if (nfcReady) onScan else onPermission
    val access = nfcActionLabel.takeUnless { nfcReady }
    if (payment == null) {
        ContactlessTarget(scanning, if (scanning) "Ищем кассу рядом" else "Телефон готов к оплате", compact = true)
        Caption("Поднесите телефон к кассе и подтвердите сумму. После оплаты приложите телефон ещё раз для передачи результата.")
        if (scanning) SecondaryAction("Остановить поиск", onClick = onCancelScan)
        else PrimaryAction(access ?: "Найти кассу рядом", !busy && nfcSupported, scan)
        TextButton({ showSettings = true }, enabled = !busy) { Text("Параметры банка") }
        return
    }
    val displayAmount = payment.amount
    Panel {
        val label = when {
            payment.fulfilled -> "Товар выдан"
            payment.status == PaymentStatus.PAID -> "Оплачено"
            payment.status == PaymentStatus.DECLINED -> "Платёж отклонён"
            payment.status == PaymentStatus.CANCELLED -> "Счёт закрыт банком"
            payment.paymentAttempted -> "Проверяем результат"
            else -> "Счёт получен"
        }
        StatusTag(label, positive = payment.status == PaymentStatus.PAID,
            negative = payment.status == PaymentStatus.DECLINED)
        Text(rubles(displayAmount), style = MaterialTheme.typography.displaySmall)
        Caption("Счёт ${payment.transactionId.takeLast(8).uppercase()}")
        Caption("Получатель ${payment.merchantId}")
        Caption(when {
            payment.complete -> "Банк получил подтверждение кассы."
            payment.hasAcknowledgement && !payment.acknowledgementSent -> "Подтверждение кассы сохранено на телефоне."
            payment.acknowledgementSent -> "Касса получила оплату. Доставка подтверждения завершена."
            payment.status != null -> "Для завершения держите телефон рядом с кассой. Повторите доставку, если связь прервалась."
            payment.paymentAttempted -> "Запрос можно повторить с тем же счётом. Банк проверит прежнюю операцию."
            else -> "Сверьте сумму и номер счёта с экраном кассы."
        })
    }
    when {
        payment.complete -> PrimaryAction(access ?: "Найти кассу для новой оплаты", !busy && nfcSupported, scan)
        payment.hasAcknowledgement && !payment.acknowledgementSent ->
            PrimaryAction("Завершить доставку подтверждения", !busy) { onConfirmDelivery(payment.requestHash) }
        payment.status != null && payment.resultExpiresAt < now && payment.permitExpiresAt < now ->
            PrimaryAction("Обновить разрешение банка", !busy) { onPay(payment.requestHash) }
        payment.status != null -> PrimaryAction(access ?: "Повторить доставку результата", !busy && nfcSupported, scan)
        else -> {
            if (now > payment.expiresAt + 30) {
                Caption("Банк проверит, прошла ли оплата, и вернёт подписанный итог.")
                PrimaryAction("Уточнить статус в банке", !busy) { onResolve(payment.requestHash) }
            } else if (payment.paymentAttempted && preview == null) {
                PrimaryAction("Проверить результат в банке", !busy) { onPreview(payment.requestHash) }
            } else {
                PrimaryAction(if (payment.paymentAttempted || preview?.settled == true) "Получить результат" else "Оплатить ${rubles(displayAmount)}",
                    !busy) { onPay(payment.requestHash) }
            }
            if (!payment.paymentAttempted) {
                TextButton({ onReset(payment.requestHash) }, enabled = !busy) { Text("Убрать счёт") }
            }
        }
    }
    TextButton({ showSettings = true }, enabled = !busy) { Text("Параметры банка") }
}
