package com.forgemind.app.ui.screens.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.text.TextLayoutResult
import com.forgemind.app.data.model.ChatContentSearch
import com.forgemind.app.data.model.ChatSearchHit
import com.forgemind.app.ui.MainViewModel

val LocalChatSearchQuery = staticCompositionLocalOf { "" }
val LocalChatSearchFocus = staticCompositionLocalOf<ChatSearchHit?> { null }

/** Raw source during focused search makes offsets exact even in code, tables and links. */
@Composable
fun SearchFocusedText(text: String, offset: Int, modifier: Modifier = Modifier) {
    val requester = remember { BringIntoViewRequester() }
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    LaunchedEffect(text, offset, layout?.size) {
        val measured = layout
        if (measured != null && text.isNotEmpty()) requester.bringIntoView(measured.getBoundingBox(offset.coerceIn(0, text.lastIndex)))
    }
    Text(highlightChatText(AnnotatedString(text)), modifier = modifier.bringIntoViewRequester(requester),
        style = MaterialTheme.typography.bodyMedium, onTextLayout = { layout = it })
}

@Composable
fun highlightChatText(text: AnnotatedString): AnnotatedString {
    val query = LocalChatSearchQuery.current
    return remember(text, query) {
        AnnotatedString.Builder(text).apply {
            ChatContentSearch.ranges(text.text, query).forEach {
                addStyle(SpanStyle(background = Color(0xFFFFD166), color = Color(0xFF171717)), it.first, it.last + 1)
            }
        }.toAnnotatedString()
    }
}

@Composable
fun ChatSearchControls(query: String, onQuery: (String) -> Unit, hits: List<ChatSearchHit>, target: ChatSearchHit?,
    onTarget: (ChatSearchHit?) -> Unit, onClose: () -> Unit) {
    val index = hits.indexOfFirst { target != null && it.messageId == target.messageId && it.offset == target.offset && it.inThinking == target.inThinking }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        OutlinedTextField(query, onQuery, singleLine = true, label = { Text("Поиск в этом чате") },
            modifier = Modifier.fillMaxWidth(), trailingIcon = { TextButton(onClick = onClose) { Text("Закрыть поиск") } })
        Row {
            Text(if (hits.isEmpty()) "Совпадений нет" else "Совпадение ${index.coerceAtLeast(0) + 1} из ${hits.size}${if(hits.size==1000) " (первые 1000)" else ""}", Modifier.weight(1f))
            TextButton(enabled = hits.isNotEmpty(), onClick = { onTarget(hits[(index - 1).mod(hits.size)]) }) { Text("↑") }
            TextButton(enabled = hits.isNotEmpty(), onClick = { onTarget(hits[(index + 1).mod(hits.size)]) }) { Text("↓") }
        }
        hits.getOrNull(index)?.let { hit ->
            CompositionLocalProvider(LocalChatSearchQuery provides query) {
                Text(highlightChatText(AnnotatedString(hit.excerpt)), maxLines = 3, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun ContextExclusionDialog(vm: MainViewModel) {
    val request by vm.exclusionRequest.collectAsState()
    val running by vm.isAgentRunning.collectAsState()
    request?.let { message ->
        var wholeTurn by remember(message.id) { mutableStateOf(false) }
        val restoring = message.excludedFromContext
        AlertDialog(onDismissRequest = { vm.exclusionRequest.value = null },
            title = { Text(if (restoring) "Вернуть в контекст?" else "Исключить из контекста?") },
            text = { Column {
                Text("История и ветки сохранятся. Модель не получит исключённые сообщения в следующих запросах. Прежняя сводка сжатия будет сброшена, чтобы не переносить их содержание. Уже выполненные действия и память не отменяются.")
                Row { Checkbox(wholeTurn, { wholeTurn = it }); Text("Весь вопрос и ответ с шагами инструментов") }
                if (running) Text("Сначала остановите текущую генерацию.")
            } }, confirmButton = { TextButton(enabled = !running, onClick = {
                vm.setMessageExclusion(message, wholeTurn, !restoring)
            }) { Text(if (restoring) "Вернуть" else "Исключить") } },
            dismissButton = { TextButton(onClick = { vm.exclusionRequest.value = null }) { Text("Отмена") } })
    }
}
