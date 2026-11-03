package ru.oasispay.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.input.PasswordVisualTransformation

@Composable
internal fun BankSetup(
    title: String,
    initialUrl: String,
    busy: Boolean,
    onSubmit: (String, String, String) -> Unit,
) {
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var pin by rememberSaveable { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    Panel {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text("Возьмите данные из консоли банка.")
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("HTTPS-адрес") }, singleLine = true)
        OutlinedTextField(pin, { pin = it }, Modifier.fillMaxWidth(), label = { Text("Отпечаток TLS") }, singleLine = true)
        OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), label = { Text("Код доступа") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true)
        Button(
            onClick = { onSubmit(url.trim(), pin, code.trim()) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && url.isNotBlank() && pin.isNotBlank() && code.isNotBlank(),
        ) { Text("Сохранить") }
    }
}
