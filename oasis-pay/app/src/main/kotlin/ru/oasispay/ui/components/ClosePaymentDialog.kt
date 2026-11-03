package ru.oasispay.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
internal fun ClosePaymentDialog(title: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text("Сначала проверьте операцию в реестре банка. Закрытие записи не отменяет списание.") },
        confirmButton = { TextButton(onConfirm) { Text("Закрыть") } },
        dismissButton = { TextButton(onDismiss) { Text("Назад") } },
    )
}
