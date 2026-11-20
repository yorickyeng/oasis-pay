package ru.oasispay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.oasispay.domain.PaymentRole
import ru.oasispay.ui.buyer.BuyerScreen
import ru.oasispay.ui.components.Caption
import ru.oasispay.ui.components.Glyph
import ru.oasispay.ui.components.Notice
import ru.oasispay.ui.components.StatusTag
import ru.oasispay.ui.components.Symbol
import ru.oasispay.ui.terminal.TerminalScreen
import ru.oasispay.ui.theme.OasisPayTheme

@Composable
internal fun PaymentRoute(
    model: PaymentViewModel,
    hasNfc: Boolean, enabled: Boolean, hasHce: Boolean,
    useBle: Boolean, hasBle: Boolean, bleEnabled: Boolean, bleAllowed: (PaymentRole?) -> Boolean,
    onMode: (Boolean) -> Unit,
    onNfcSettings: () -> Unit,
    onApprovePayment: (String) -> Unit,
    onBankAccess: (() -> Unit) -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val role = state.role
    val radio = if (useBle) "BLE" else "NFC"
    val nfcSupported = if (useBle) hasBle else hasNfc && (role != PaymentRole.TERMINAL || hasHce)
    val nfcReady = nfcSupported && (if (useBle) bleEnabled && bleAllowed(role) else enabled)
    val nfcActionLabel = when {
        !nfcSupported -> "$radio недоступен для этой роли"
        useBle && !bleAllowed(role) -> "Разрешить Bluetooth"
        else -> "Включить $radio"
    }
    OasisPayTheme(darkTheme = role == PaymentRole.TERMINAL || androidx.compose.foundation.isSystemInDarkTheme()) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = if (role == PaymentRole.TERMINAL) 640.dp else 520.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Symbol(Glyph.BRAND)
                        Column {
                            Text("oasis pay", style = MaterialTheme.typography.titleLarge)
                            if (role == PaymentRole.BUYER) Text("КОШЕЛЁК", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.weight(1f))
                        if (state.role == null) StatusTag("Тестовый режим")
                        else TextButton({ model.choose(null) }, enabled = !state.busy && !state.armed) { Text("Сменить роль") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (ble in listOf(false, true)) {
                            androidx.compose.material3.FilterChip(selected = ble == useBle, onClick = { onMode(ble) },
                                enabled = !state.busy && !state.armed, label = { Text(if (ble) "BLE" else "NFC") })
                        }
                    }
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (!state.initialized) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        return@Column
                    }
                    if (!state.storageHealthy) {
                        Notice(state.recoveryMessage.ifEmpty {
                            "Не удалось проверить локальный журнал или ключ устройства. Разблокируйте телефон и повторите проверку."
                        }, true)
                        ru.oasispay.ui.components.PrimaryAction("Повторить проверку", onClick = model::retryValidation)
                        return@Column
                    }
                    when (state.role) {
                        PaymentRole.TERMINAL -> {
                            StatusTag("КАССА · офлайн-приём")
                            ru.oasispay.ui.components.ConnectionStrip(state.network, nfcReady, radio)
                            if (state.message.isNotEmpty()) Notice(state.message, state.error)
                            TerminalScreen(state.terminal, state.busy, state.armed, state.now,
                                peerConnected = state.terminalPeerConnected,
                                onEnroll = { url, pin, code -> onBankAccess { model.enroll(url, pin, code) } },
                                onCreateOrder = { model.createOrder(it, nfcReady) },
                                onArm = { model.arm(nfcReady) }, onPause = model::pause,
                                onFulfill = { model.fulfill(nfcReady) },
                                nfcReady = nfcReady, nfcSupported = nfcSupported,
                                nfcActionLabel = nfcActionLabel, onCheckBank = { url, pin -> onBankAccess { model.checkBank(url, pin) } },
                                onPermission = onNfcSettings)
                            ru.oasispay.ui.components.ProtocolConsole(state.events, state.terminal.bankKeyFingerprint,
                                state.terminal.lastBankResultAt, state.terminal.bankResultKeyId, radio)
                        }
                        PaymentRole.BUYER -> {
                            if (!state.buyer.configured) Heading("Кошелёк", null)
                            ru.oasispay.ui.components.ConnectionStrip(state.network, nfcReady, radio)
                            if (state.message.isNotEmpty()) Notice(state.message, state.error)
                            BuyerScreen(state.buyer, state.busy, state.preview, state.now,
                                scanning = state.scanning, onCancelScan = model::cancelExchange,
                                onRefreshAccount = { onBankAccess { model.refreshAccount() } },
                                onConfigure = { url, pin, code -> onBankAccess { model.configureBuyer(url, pin, code) } },
                                onPreview = { hash -> onBankAccess { model.preview(hash) } }, onPay = onApprovePayment, onResolve = { hash -> onBankAccess { model.resolve(hash) } },
                                onConfirmDelivery = { hash -> onBankAccess { model.confirmDelivery(hash) } }, onReset = model::resetBuyer,
                                onScan = { model.scan(nfcReady) }, nfcReady = nfcReady,
                                nfcSupported = nfcSupported,
                                nfcActionLabel = nfcActionLabel, onCheckBank = { url, pin -> onBankAccess { model.checkBank(url, pin) } },
                                onPermission = onNfcSettings)
                        }
                        null -> {
                            Heading("Выберите роль", "На одном телефоне откройте кассу, на втором — экран покупателя.")
                            RoleCard("Касса", "Выставить счёт и принять оплату", Glyph.TERMINAL) { model.choose(PaymentRole.TERMINAL) }
                            RoleCard("Покупатель", "Получить счёт и оплатить", Glyph.PHONE) { model.choose(PaymentRole.BUYER) }
                            Caption("Оплата с тестового баланса. Банковская карта не нужна.")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Heading(title: String, description: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        description?.let { Caption(it) }
    }
}

@Composable
private fun RoleCard(title: String, detail: String, glyph: Glyph, onClick: () -> Unit) {
    Card(onClick, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 92.dp).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Symbol(glyph)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Caption(detail)
            }
            Text("›", Modifier.clearAndSetSemantics { }, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}
