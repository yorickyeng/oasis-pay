package ru.oasispay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.oasispay.R
import ru.oasispay.domain.PaymentRole
import ru.oasispay.ui.buyer.BuyerScreen
import ru.oasispay.ui.components.Panel
import ru.oasispay.ui.terminal.TerminalScreen
import ru.oasispay.ui.theme.OasisPayTheme

@Composable
internal fun PaymentRoute(model: PaymentViewModel, supportsHce: Boolean, nfcAvailable: () -> Boolean) {
    val state by model.state.collectAsStateWithLifecycle()
    OasisPayTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (state.role != null) {
                    TextButton({ model.choose(null) }, enabled = !state.busy && !state.armed) { Text("Сменить роль") }
                }
                val title = when (state.role) {
                    PaymentRole.TERMINAL -> "Касса"
                    PaymentRole.BUYER -> "Покупатель"
                    null -> stringResource(R.string.app_name)
                }
                Text(title, style = MaterialTheme.typography.headlineLarge)
                Text("Учебный стенд · Тестовый баланс", style = MaterialTheme.typography.bodyMedium)

                when (state.role) {
                    PaymentRole.TERMINAL -> TerminalScreen(
                        state.terminal, state.busy, state.armed, state.now,
                        onEnroll = model::enroll,
                        onCreateOrder = { model.createOrder(it, nfcAvailable()) },
                        onArm = { model.arm(nfcAvailable()) },
                        onPause = model::pauseNfc,
                        onCloseOrder = { model.closeOrder() },
                    )
                    PaymentRole.BUYER -> BuyerScreen(
                        state.buyer, supportsHce, state.busy, state.preview, state.now,
                        onConfigure = model::configureBuyer,
                        onPreview = model::preview,
                        onPay = model::pay,
                        onConfirmDelivery = model::confirmDelivery,
                        onReset = { model.resetBuyer(it) },
                    )
                    null -> RoleSelection { model.choose(it) }
                }
                if (state.busy) Text("Запрос к банку…")
                if (state.message.isNotEmpty()) Panel { Text(state.message) }
            }
        }
    }
}

@Composable
private fun RoleSelection(onSelect: (PaymentRole) -> Unit) {
    Text("Установите приложение на два телефона и выберите разные роли.")
    Button({ onSelect(PaymentRole.TERMINAL) }, Modifier.fillMaxWidth()) { Text("Роль кассы") }
    OutlinedButton({ onSelect(PaymentRole.BUYER) }, Modifier.fillMaxWidth()) { Text("Роль покупателя") }
}
