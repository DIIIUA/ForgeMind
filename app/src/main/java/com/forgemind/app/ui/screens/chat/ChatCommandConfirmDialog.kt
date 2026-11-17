package com.forgemind.app.ui.screens.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.forgemind.app.ui.PendingChatCommand

@Composable
fun ChatCommandConfirmDialog(command: PendingChatCommand, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Передать команду в терминал?") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(command.targetLabel)
            SelectionContainer { Text(command.command, fontFamily = FontFamily.Monospace) }
            Text("Команда будет отправлена как ввод с Enter. Результат появится в терминале. Отмена сохранит черновик.")
        } },
        confirmButton = { Button(onClick = onConfirm) { Text("Выполнить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}
