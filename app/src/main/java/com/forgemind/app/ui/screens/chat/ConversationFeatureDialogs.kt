package com.forgemind.app.ui.screens.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.forgemind.app.ui.MainViewModel

@Composable
fun ConversationFeatureDialogs(vm: MainViewModel) {
    val schedules by vm.showSchedules.collectAsState()
    if (schedules) SchedulesDialog(vm)
    val permissions by vm.showToolPermissions.collectAsState()
    val history by vm.showCompactionHistory.collectAsState()
    val grants by vm.toolGrants.collectAsState()
    val events by vm.compactionEvents.collectAsState()
    val session by vm.currentSession.collectAsState()
    if (permissions) AlertDialog(onDismissRequest = { vm.showToolPermissions.value = false }, title = { Text("Разрешения этого чата") },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            Text("Разрешения относятся к этому чату, среде и версии инструмента. Опасные операции с обязательным подтверждением не получают исключений.")
            val own = grants.filter { it.sessionId == session?.id }
            if (own.isEmpty()) Text("Запомненных разрешений нет")
            own.forEach { grant -> Row { Text(grant.toolName, Modifier.weight(1f)); TextButton(onClick = { vm.revokeToolGrant(grant.sessionId, grant.toolName) }) { Text("Отозвать") } } }
            TextButton(onClick = { session?.let { vm.clearToolGrants(it.id) } }) { Text("Отозвать все") }
        } }, confirmButton = { TextButton(onClick = { vm.showToolPermissions.value = false }) { Text("Закрыть") } })
    if (history) AlertDialog(onDismissRequest = { vm.showCompactionHistory.value = false }, title = { Text("История сжатия") },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            val own = events.filter { it.sessionId == session?.id }.asReversed()
            if (own.isEmpty()) Text("Новых событий сжатия нет")
            own.forEach { event -> Text("${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(event.createdAt))}: ${if(event.automatic) "автоматически" else "вручную"}, ≈${event.before} → ≈${event.after}${if(event.restored) " · отменено" else ""}") }
            TextButton(enabled = !vm.isAgentRunning.value, onClick = { vm.undoCompaction(); vm.showCompactionHistory.value = false }) { Text("Отменить последнее сжатие") }
        } }, confirmButton = { TextButton(onClick = { vm.showCompactionHistory.value = false }) { Text("Закрыть") } })
    val regeneration by vm.regenerationRequest.collectAsState()
    val fragment by vm.fragmentBranch.collectAsState()
    val undo by vm.branchUndo.collectAsState()
    val running by vm.isAgentRunning.collectAsState()
    regeneration?.let {
        AlertDialog(onDismissRequest = { vm.regenerationRequest.value = null },
            title = { Text("Создать новый вариант ответа?") },
            text = { Text("Прежний ответ и продолжение сохранятся в своей ветке. После завершения можно вернуться к ним.") },
            confirmButton = { TextButton(onClick = vm::confirmRegeneration) { Text("Перегенерировать") } },
            dismissButton = { TextButton(onClick = { vm.regenerationRequest.value = null }) { Text("Отмена") } })
    }
    fragment?.let { request ->
        var text by remember(request) { mutableStateOf(request.text) }
        AlertDialog(onDismissRequest = { vm.fragmentBranch.value = null }, title = { Text("Ветка из фрагмента") },
            text = { Column {
                Text("Выделенный текст станет началом отдельного чата. Исходный разговор сохраняется.")
                OutlinedTextField(text, { text = it }, minLines = 3, maxLines = 10, modifier = Modifier.fillMaxWidth())
            } }, confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { vm.createFragmentBranch(text) }) { Text("Создать") } },
            dismissButton = { TextButton(onClick = { vm.fragmentBranch.value = null }) { Text("Отмена") } })
    }
    if (undo != null && !running && regeneration == null && fragment == null) {
        AlertDialog(onDismissRequest = { vm.branchUndo.value = null }, title = { Text("Новый вариант сохранён") },
            text = { Text("Оставить выбранной новую ветку или вернуться к прежнему ответу? Оба варианта останутся в истории.") },
            confirmButton = { TextButton(onClick = { vm.branchUndo.value = null }) { Text("Оставить новый") } },
            dismissButton = { TextButton(onClick = vm::undoRegeneration) { Text("Вернуться к прежнему") } })
    }
}
