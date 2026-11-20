package ru.oasispay.ui.components

import androidx.core.net.toUri
import android.content.ClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.fillMaxWidth
import ru.oasispay.domain.BankBootstrap
import ru.oasispay.domain.PaymentRole
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation

@Composable
internal fun BankSetup(title: String, initialUrl: String, busy: Boolean, onSubmit: (String, String, String) -> Unit, role: PaymentRole, onCheckBank: (String, String) -> Unit) {
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var pin by rememberSaveable { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val context = LocalContext.current
    var importError by remember { mutableStateOf("") }
    Panel {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Caption("Скопируйте весь вывод ./oasis show. Приложение выберет код нужной роли.")
        SecondaryAction("Вставить настройки из буфера", enabled = !busy) {
            try {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                val imported = BankBootstrap.parse(text, role)
                url = imported.url; pin = imported.pin; code = imported.code
                importError = ""
            } catch (_: Exception) {
                importError = "Не удалось разобрать блок. Нужны URL, TLS SHA-256 и код выбранной роли из ./oasis show."
            }
        }
        if (importError.isNotEmpty()) Notice(importError, true)
        OutlinedTextField(url, { if (it.length <= 256) url = it }, Modifier.fillMaxWidth(), label = { Text("HTTPS-адрес банка") },
            placeholder = { Text("https://192.168.1.10:8443") }, enabled = !busy, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(pin, { if (it.length <= 192) pin = it }, Modifier.fillMaxWidth(), label = { Text("Отпечаток TLS") },
            enabled = !busy, singleLine = true, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
        OutlinedTextField(code, { if (it.length <= 64) code = it }, Modifier.fillMaxWidth(), label = { Text("Код подключения") },
            enabled = !busy, visualTransformation = PasswordVisualTransformation(), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        if (url.toUri().host in setOf("127.0.0.1", "localhost", "::1")) {
            Caption("Для теста по Wi-Fi укажите IP компьютера из ./oasis network. Этот адрес ведёт на сам телефон.")
        }
        SecondaryAction("Проверить связь с банком", enabled = !busy && url.isNotBlank() && pin.isNotBlank()) {
            onCheckBank(url.trim(), pin)
        }
        PrimaryAction("Подключить", !busy && url.isNotBlank() && pin.isNotBlank() && code.isNotBlank()) {
            onSubmit(url.trim(), pin, code.trim())
        }
    }
}
