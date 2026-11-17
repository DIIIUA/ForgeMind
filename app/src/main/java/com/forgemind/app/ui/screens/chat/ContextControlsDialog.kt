package com.forgemind.app.ui.screens.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.forgemind.app.data.db.ChatSessionEntity

@Composable
fun ContextControlsDialog(session: ChatSessionEntity, contextWindow: Int, reserve: Int, inheritedLimit: Int,
    checkpoints: Int, busy: Boolean, agentBusy: Boolean, omitted: Int, folded: Int,
    onLimit: (Int) -> Unit, onCompact: () -> Unit, onUndo: () -> Unit, onCancel: () -> Unit, onDismiss: () -> Unit) {
    var limit by remember(session.id, session.historyMessageLimit) { mutableStateOf(session.historyMessageLimit.toString()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Контекст чата") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Оценка последнего запроса: ≈${session.estimatedContextTokens} / $contextWindow токенов. Резерв ответа: $reserve.")
            if (session.estimatedContextTokens.toLong() + reserve >= contextWindow.toLong() * 9 / 10)
                Text("Контекст близок к пределу модели", color = MaterialTheme.colorScheme.error)
            Text("Сводка: ${session.compactedSummary.length} символов. Скрыто лимитом: $omitted. Сокращено результатов: $folded. Оригиналы сохранены.")
            OutlinedTextField(value = limit, onValueChange = { limit = it }, label = { Text("Лимит сообщений") }, singleLine = true)
            Text("−1 — настройка провайдера ($inheritedLimit), 0 — вся история. Текущий ход пользователя и связанные вызовы сохраняются целиком.")
            TextButton(enabled = limit.toIntOrNull() in -1..10000, onClick = { limit.toIntOrNull()?.let(onLimit) }) { Text("Сохранить лимит") }
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = onCancel) { Text("Отменить сжатие") }
            } else {
                Button(enabled = !agentBusy, onClick = onCompact) { Text("Сжать сейчас") }
                OutlinedButton(enabled = !agentBusy && checkpoints > 0, onClick = onUndo) { Text("Отменить последнее сжатие ($checkpoints)") }
            }
            Text("Если автосжатие включено, при следующем запросе оно может сработать снова. Настройки модели и инструкции сжатия находятся в разделе «Агент».")
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
}
