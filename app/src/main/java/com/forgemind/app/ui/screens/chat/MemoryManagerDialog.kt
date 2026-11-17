package com.forgemind.app.ui.screens.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.forgemind.app.data.db.*
import com.forgemind.app.data.memory.*
import com.forgemind.app.ui.MainViewModel

@Composable
fun MemoryManagerDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val context by vm.memoryContext.collectAsState()
    val allNotes by vm.memoryNotes.collectAsState()
    val allProposals by vm.memoryProposals.collectAsState()
    val settings by vm.agentSettings.collectAsState()
    val folders by vm.folders.collectAsState()
    val profiles by vm.assistantProfiles.collectAsState()
    val semanticMode by vm.memorySemanticMode.collectAsState()
    val semanticResults by vm.semanticVisibleMemory.collectAsState()
    val modelReady by vm.semanticModelReady.collectAsState()
    val semanticBusy by vm.semanticUiBusy.collectAsState()
    val semanticStatus by vm.semanticStatus.collectAsState()
    var searchSettings by remember { mutableStateOf(false) }
    val busy by vm.memoryBusy.collectAsState()
    val error by vm.memoryError.collectAsState()
    val query by vm.memoryQuery.collectAsState()
    val limit by vm.memoryListLimit.collectAsState()
    val current = context ?: return
    LaunchedEffect(current, query, semanticMode) {
        if (semanticMode) { vm.resetSemanticQuery() }
    }
    val notes = if (semanticMode) semanticResults.map { it.first } else allNotes.filter(current::allows)
    val proposals = allProposals.filter { it.sourceSessionId == current.chatId }
    var editing by remember(current) { mutableStateOf<MemoryNoteEntity?>(null) }
    var creating by remember(current) { mutableStateOf(false) }
    var reviewing by remember(current) { mutableStateOf<MemoryProposalEntity?>(null) }
    fun label(type: String, key: String): String = when (type) {
        "CHAT" -> "Этот чат"
        "ASSISTANT" -> "Ассистент: " + (profiles.firstOrNull { it.id == key }?.name ?: "удалён")
        "FOLDER" -> "Папка: " + (folders.firstOrNull { it.id == key }?.name ?: key)
        "MODEL" -> "Модель: " + runCatching { kotlinx.serialization.json.Json.decodeFromString<List<String>>(key).joinToString(" / ") }.getOrDefault(key)
        else -> "Все чаты"
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 600.dp).fillMaxWidth().fillMaxHeight(0.94f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Память", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { searchSettings = true }) { Text("Поиск") }
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("Закрыть") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Использовать память", modifier = Modifier.weight(1f))
                            Switch(settings.memoryEnabled, { vm.saveAgentSettings(settings.copy(memoryEnabled = it)) })
                        }
                        Text("Память добавляется к запросам. Предложения агента ждут вашего решения даже в автопилоте.")
                        Text("Бюджет памяти: ≈${settings.memoryBudgetTokens} токенов")
                        Slider(settings.memoryBudgetTokens.coerceIn(256, 8192).toFloat(),
                            { vm.saveAgentSettings(settings.copy(memoryBudgetTokens = (it.toInt() / 256 * 256).coerceIn(256, 8192))) },
                            valueRange = 256f..8192f, steps = 30)
                        Text("Записи включаются целиком: чат → ассистент → папка → модель → все чаты. Не поместившиеся доступны через поиск памяти.")
                    }
                    if (proposals.isNotEmpty()) item { Text("Ожидают подтверждения (${proposals.size})", style = MaterialTheme.typography.titleMedium) }
                    items(proposals, key = { "proposal-" + it.id }) { proposal ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(label(proposal.scopeType, proposal.scopeKey))
                                Text((when (proposal.action) { "create" -> "Добавить"; "update" -> "Изменить"; else -> "Удалить" }) + ": " + proposal.title)
                                TextButton(onClick = { vm.memoryError.value = null; reviewing = proposal }, enabled = !busy) { Text("Посмотреть предложение") }
                            }
                        }
                    }
                    item {
                        Text("Сохранённые записи", style = MaterialTheme.typography.titleMedium)
                        Text("Показаны записи этого чата, ассистента, папки, выбранной модели и всех чатов, включая выключенные.")
                        OutlinedTextField(query, { vm.memoryQuery.value = it; vm.memoryListLimit.value = 50 },
                            label = { Text("Поиск по памяти") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Row {
                            TextButton(onClick = { vm.memorySemanticMode.value = false }) { Text(if(!semanticMode) "По словам ✓" else "По словам") }
                            TextButton(onClick = { vm.memorySemanticMode.value = true }) { Text(if(semanticMode) "По смыслу ✓" else "По смыслу") }
                        }
                        if (semanticMode) {
                            Text("До 100 ближайших включённых записей. Близость — оценка сходства, не вероятность.")
                            if (!modelReady) TextButton(onClick = { searchSettings = true }) { Text("Скачать модель поиска") }
                            else Row {
                                Button(onClick = vm::searchMemoryByMeaning, enabled = query.isNotBlank() && !semanticBusy) { Text("Найти") }
                                if (semanticBusy) TextButton(onClick = vm::cancelSemanticSearch) { Text("Остановить поиск") }
                            }
                            Text(semanticStatus)
                        }
                        Button(onClick = { vm.memoryError.value = null; creating = true }, enabled = !busy) { Text("Добавить запись") }
                    }
                    if (notes.isEmpty()) item { Text("Записей нет") }
                    items(notes, key = { it.id }) { note ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(label(note.scopeType, note.scopeKey), style = MaterialTheme.typography.labelMedium)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(note.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                    Switch(note.enabled, { vm.memoryAction({ vm.memoryRepository.toggle(current, note) }) }, enabled = !busy)
                                }
                                if (semanticMode) semanticResults.firstOrNull { it.first.id == note.id }?.let { Text("Близость: %.3f".format(it.second)) }
                                Text(note.content.take(240) + if (note.content.length > 240) "…" else "")
                                TextButton(onClick = { vm.memoryError.value = null; editing = note }, enabled = !busy) { Text("Открыть / изменить") }
                            }
                        }
                    }
                    if (!semanticMode && notes.size == limit) item {
                        TextButton(onClick = { vm.memoryListLimit.value += 50 }) { Text("Показать ещё") }
                    }
                }
            }
        }
    }
    if (searchSettings) SemanticModelDialog(vm) { searchSettings = false }
    if (creating || editing != null) {
        val original = editing
        var scope by remember(original, current) { mutableStateOf(original?.scopeType?.let(MemoryScope::valueOf) ?: MemoryScope.CHAT) }
        var title by remember(original) { mutableStateOf(original?.title.orEmpty()) }
        var content by remember(original) { mutableStateOf(original?.content.orEmpty()) }
        var scopeMenu by remember { mutableStateOf(false) }
        var deleting by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { if (!busy) { creating = false; editing = null } },
            title = { Text(if (original == null) "Новая запись памяти" else "Изменить память") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (original == null) Box {
                        OutlinedButton(onClick = { scopeMenu = true }) { Text(scope.label) }
                        DropdownMenu(scopeMenu, { scopeMenu = false }) {
                            MemoryScope.entries.filter { (it != MemoryScope.FOLDER || current.folderId != null) && (it != MemoryScope.ASSISTANT || current.assistantId != null) }.forEach { value ->
                                DropdownMenuItem(text = { Text(value.label) }, onClick = { scope = value; scopeMenu = false })
                            }
                        }
                    } else Text(label(original.scopeType, original.scopeKey))
                    if (scope == MemoryScope.ASSISTANT) Text("Эту запись смогут получать чаты только этого ассистента, независимо от модели.")
                    if (scope == MemoryScope.GLOBAL) Text("Эту запись смогут получать все чаты.")
                    if (scope == MemoryScope.FOLDER) Text("Эту запись смогут получать все чаты в папке.")
                    if (scope == MemoryScope.MODEL) Text("Эту запись смогут получать чаты с этой моделью у этого провайдера.")
                    OutlinedTextField(title, { title = it }, label = { Text("Название (${title.length}/120)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(content, { content = it }, label = { Text("Текст (${content.length}/8000)") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                    if (original != null) TextButton(onClick = { deleting = true }, enabled = !busy) { Text("Удалить запись") }
                }
            },
            confirmButton = { TextButton(enabled = !busy && title.isNotBlank() && title.length <= 120 && content.isNotBlank() && content.length <= 8000,
                onClick = { vm.memoryAction({ vm.memoryRepository.save(current, scope, title, content, original) }, { creating = false; editing = null }) }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { creating = false; editing = null }, enabled = !busy) { Text("Отмена") } })
        if (deleting && original != null) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Удалить память?") },
            text = { Text(label(original.scopeType, original.scopeKey) + "\n" + original.title) },
            confirmButton = { TextButton(onClick = { vm.memoryAction({ vm.memoryRepository.delete(current, original) }, { deleting = false; editing = null }) }, enabled = !busy) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Отмена") } })
    }
    reviewing?.let { proposal ->
        AlertDialog(onDismissRequest = { if (!busy) reviewing = null }, title = { Text("Предложение памяти") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(label(proposal.scopeType, proposal.scopeKey))
                Text(when (proposal.action) { "create" -> "Добавление"; "update" -> "Изменение"; else -> "Удаление" }, style = MaterialTheme.typography.titleMedium)
                if (proposal.action != "create") { Text("Было: " + proposal.previousTitle); Text(proposal.previousContent) }
                if (proposal.action != "delete") { Text("Предложено: " + proposal.title); Text(proposal.content) }
                TextButton(onClick = { reviewing = null }, enabled = !busy) { Text("Решить позже") }
            } },
            confirmButton = { TextButton(onClick = { vm.memoryAction({ vm.memoryRepository.approve(current.chatId, proposal.id) }, { reviewing = null }) }, enabled = !busy) { Text("Подтвердить") } },
            dismissButton = { TextButton(onClick = { vm.memoryAction({ vm.memoryRepository.reject(current.chatId, proposal.id) }, { reviewing = null }) }, enabled = !busy) { Text("Отклонить") } })
    }
}
