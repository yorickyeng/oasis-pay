package ru.oasispay.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.oasispay.data.NetworkState
import ru.oasispay.domain.BuyerState
import ru.oasispay.domain.rubles
import ru.oasispay.ui.ExchangeEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun ConnectionStrip(network: NetworkState, nfc: Boolean, radio: String = "NFC") {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text("СЕТЬ УСТРОЙСТВА", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(network.label, style = MaterialTheme.typography.bodySmall)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(radio, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (nfc) "Готов" else "Нужен доступ", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A sandbox account visual, never a PAN, NFC credential or a third-party card. */
@Composable
internal fun WalletCard(state: BuyerState, busy: Boolean, onRefresh: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), shadowElevation = 4.dp) {
        Column(Modifier.background(Brush.linearGradient(listOf(Color(0xFF063E35), Color(0xFF147B61))))
            .padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("oasis pay", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
                Spacer(Modifier.weight(1f))
                Text("DEMO · RUB", color = Color(0xFFC6F6DD), style = MaterialTheme.typography.labelMedium)
            }
            Text("Доступный баланс", color = Color(0xFFD0E9DF), style = MaterialTheme.typography.bodyMedium)
            Text(state.balance?.let(::rubles) ?: "— ₽", color = Color.White,
                style = MaterialTheme.typography.displaySmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(state.registrationNumber.ifEmpty { "Номер появится после обновления регистрации" },
                        color = Color(0xFFD0E9DF), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Text("Тестовый счёт", color = Color(0xFFD0E9DF), fontSize = 11.sp)
                }
                TextButton(onRefresh, enabled = !busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White,
                        disabledContentColor = Color.White.copy(alpha = .5f))) { Text("Обновить") }
            }
        }
    }
    if (state.balanceObservedAt > 0) {
        val format = remember { SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()) }
        Caption("Подтверждено банком ${format.format(Date(state.balanceObservedAt * 1000))}")
    } else Caption("Баланс пока не получен. Обновите его при доступном банке.")
}

@Composable
internal fun ContactlessTarget(active: Boolean, label: String, compact: Boolean = false) {
    val ink = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth().padding(vertical = if (compact) 6.dp else 18.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Canvas(Modifier.size(if (compact) 64.dp else 104.dp)) {
            drawCircle(ink.copy(alpha = if (active) .14f else .06f))
            drawCircle(ink.copy(alpha = .15f), radius = size.minDimension * .38f, style = Stroke(2.dp.toPx()))
            val scale = size.width / 104f
            for (i in 0..2) {
                drawArc(ink, -55f, 110f, false, topLeft = Offset((30 + i * 5) * scale, (36 - i * 6) * scale),
                    size = androidx.compose.ui.geometry.Size((20 + i * 10) * scale, (32 + i * 12) * scale),
                    style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
internal fun ProtocolConsole(events: List<ExchangeEvent>, fingerprint: String, lastResultAt: Long, resultKeyId: String, radio: String = "NFC") {
    var expanded by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Журнал обмена", style = MaterialTheme.typography.titleSmall)
                    Text("События текущего запуска", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton({ expanded = !expanded }) { Text(if (expanded) "Свернуть" else "Открыть") }
            }
            if (expanded) {
                Text("КАССА ← $radio → ПОКУПАТЕЛЬ ← HTTPS → БАНК",
                    style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                Caption("Прямого соединения кассы с банком при оплате нет. TLS 1.3 использует покупатель.")
                if (fingerprint.isNotEmpty()) {
                    Text("Доверенный ключ банка · SHA-256\n${fingerprint.chunked(16).joinToString(" ")}",
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text("Ожидаемый kid: ${fingerprint.take(16)}\nkid ответа: ${resultKeyId.ifEmpty { "ожидаем ответ" }}",
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    if (resultKeyId.isNotEmpty()) StatusTag("kid совпал · подпись проверена", positive = true)
                    Caption(if (lastResultAt > 0) "Последний финансовый ответ проверен этим ключом. Сумма и идентификатор связаны подписью ES256."
                        else "Этот ключ используется для проверки сертификатов и будущего ответа банка.")
                }
                HorizontalDivider()
                if (events.isEmpty()) Caption("Ожидаем начало обмена.")
                val format = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                for (event in events.takeLast(16)) {
                    Text("${format.format(Date(event.time * 1000))}  ${event.message}",
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 17.sp,
                        color = if (event.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Caption("Секретные ключи и токены в журнал не выводятся.")
            } else Text(events.lastOrNull()?.message ?: "Готов к работе · защищённый обмен", maxLines = 2,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
