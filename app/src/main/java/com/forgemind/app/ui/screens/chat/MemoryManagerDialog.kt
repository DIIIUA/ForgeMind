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
import com.forgemind.app.ui.theme.ForgeColors
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.forgemind.app.R

@Composable
private fun localizedMemoryScope(scope: MemoryScope): String = when (scope) {
    MemoryScope.CHAT -> stringResource(R.string.memory_scope_chat)
    MemoryScope.ASSISTANT -> stringResource(R.string.memory_scope_assistant)
    MemoryScope.FOLDER -> stringResource(R.string.memory_scope_folder)
    MemoryScope.MODEL -> stringResource(R.string.memory_scope_model)
    MemoryScope.GLOBAL -> stringResource(R.string.memory_scope_global)
}

@Composable
fun MemoryManagerDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val context by vm.memoryContext.collectAsState()
    val folderOverride by vm.memoryFolderOverride.collectAsState()
    val closeDialog = {
        vm.memoryFolderOverride.value = null
        onDismiss()
    }
    DisposableEffect(Unit) {
        onDispose { vm.memoryFolderOverride.value = null }
    }
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

    val labelThisChat = stringResource(R.string.memory_scope_chat)
    val labelDeleted = stringResource(R.string.memory_scope_deleted)
    val labelAllChats = stringResource(R.string.memory_scope_global)
    val labelAssistantFmt = stringResource(R.string.memory_scope_assistant_fmt)
    val labelFolderFmt = stringResource(R.string.memory_scope_folder_fmt)
    val labelModelFmt = stringResource(R.string.memory_scope_model_fmt)
    fun label(type: String, key: String): String = when (type) {
        "CHAT" -> labelThisChat
        "ASSISTANT" -> String.format(labelAssistantFmt, profiles.firstOrNull { it.id == key }?.name ?: labelDeleted)
        "FOLDER" -> String.format(labelFolderFmt, folders.firstOrNull { it.id == key }?.name ?: key)
        "MODEL" -> String.format(labelModelFmt, runCatching { kotlinx.serialization.json.Json.decodeFromString<List<String>>(key).joinToString(" / ") }.getOrDefault(key))
        else -> labelAllChats
    }
    Dialog(onDismissRequest = { if (!busy) closeDialog() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.widthIn(max = 600.dp).fillMaxWidth().fillMaxHeight(0.94f),
            shape = MaterialTheme.shapes.large,
            color = ForgeColors.SurfaceCard,
            contentColor = ForgeColors.TextPrimary
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.chat_menu_memory), style = MaterialTheme.typography.titleLarge)
                        folderOverride?.let { fid ->
                            Text(
                                text = label("FOLDER", fid),
                                style = MaterialTheme.typography.labelMedium,
                                color = ForgeColors.AccentBlue
                            )
                        }
                    }
                    Row {
                        TextButton(onClick = { searchSettings = true }) { Text(stringResource(R.string.memory_search_btn)) }
                        TextButton(onClick = closeDialog, enabled = !busy) { Text(stringResource(R.string.action_close)) }
                    }
                }
                error?.let { Text(it, color = ForgeColors.DangerRed) }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.memory_enable_toggle), modifier = Modifier.weight(1f))
                            Switch(settings.memoryEnabled, { vm.saveAgentSettings(settings.copy(memoryEnabled = it)) })
                        }
                        Text(stringResource(R.string.memory_enable_desc))
                        Text(stringResource(R.string.memory_budget_fmt, settings.memoryBudgetTokens))
                        Slider(settings.memoryBudgetTokens.coerceIn(256, 8192).toFloat(),
                            { vm.saveAgentSettings(settings.copy(memoryBudgetTokens = (it.toInt() / 256 * 256).coerceIn(256, 8192))) },
                            valueRange = 256f..8192f, steps = 30)
                        Text(stringResource(R.string.memory_priority_desc))
                    }
                    if (proposals.isNotEmpty()) item { Text(stringResource(R.string.memory_pending_fmt, proposals.size), style = MaterialTheme.typography.titleMedium) }
                    items(proposals, key = { "proposal-" + it.id }) { proposal ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(label(proposal.scopeType, proposal.scopeKey))
                                val actionLabel = stringResource(
                                    when (proposal.action) {
                                        "create" -> R.string.memory_action_create
                                        "update" -> R.string.memory_action_update
                                        else -> R.string.memory_action_delete
                                    }
                                )
                                Text("$actionLabel: ${proposal.title}")
                                TextButton(onClick = { vm.memoryError.value = null; reviewing = proposal }, enabled = !busy) { Text(stringResource(R.string.memory_review_proposal)) }
                            }
                        }
                    }
                    item {
                        Text(stringResource(R.string.memory_saved_notes), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.memory_saved_notes_desc))
                        OutlinedTextField(query, { vm.memoryQuery.value = it; vm.memoryListLimit.value = 50 },
                            label = { Text(stringResource(R.string.memory_search_label)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Row {
                            TextButton(onClick = { vm.memorySemanticMode.value = false }) { Text(stringResource(if(!semanticMode) R.string.memory_mode_keyword_active else R.string.memory_mode_keyword)) }
                            TextButton(onClick = { vm.memorySemanticMode.value = true }) { Text(stringResource(if(semanticMode) R.string.memory_mode_semantic_active else R.string.memory_mode_semantic)) }
                        }
                        if (semanticMode) {
                            Text(stringResource(R.string.memory_semantic_hint))
                            if (!modelReady) TextButton(onClick = { searchSettings = true }) { Text(stringResource(R.string.semantic_dlg_download)) }
                            else Row {
                                Button(onClick = vm::searchMemoryByMeaning, enabled = query.isNotBlank() && !semanticBusy) { Text(stringResource(R.string.memory_find_btn)) }
                                if (semanticBusy) TextButton(onClick = vm::cancelSemanticSearch) { Text(stringResource(R.string.memory_stop_search)) }
                            }
                            Text(semanticStatus)
                        }
                        Button(onClick = { vm.memoryError.value = null; creating = true }, enabled = !busy) { Text(stringResource(R.string.memory_add_note)) }
                    }
                    if (notes.isEmpty()) item { Text(stringResource(R.string.memory_empty)) }
                    items(notes, key = { it.id }) { note ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(label(note.scopeType, note.scopeKey), style = MaterialTheme.typography.labelMedium)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(note.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                    Switch(note.enabled, { vm.memoryAction({ vm.memoryRepository.toggle(current, note) }) }, enabled = !busy)
                                }
                                if (semanticMode) semanticResults.firstOrNull { it.first.id == note.id }?.let { Text(stringResource(R.string.memory_similarity_fmt, it.second)) }
                                Text(note.content.take(240) + if (note.content.length > 240) "…" else "")
                                TextButton(onClick = { vm.memoryError.value = null; editing = note }, enabled = !busy) { Text(stringResource(R.string.memory_open_edit)) }
                            }
                        }
                    }
                    if (!semanticMode && notes.size == limit) item {
                        TextButton(onClick = { vm.memoryListLimit.value += 50 }) { Text(stringResource(R.string.memory_show_more)) }
                    }
                }
            }
        }
    }
    if (searchSettings) SemanticModelDialog(vm) { searchSettings = false }
    if (creating || editing != null) {
        val original = editing
        var scope by remember(original, current, folderOverride) {
            mutableStateOf(
                original?.scopeType?.let(MemoryScope::valueOf)
                    ?: if (folderOverride != null) MemoryScope.FOLDER else MemoryScope.CHAT
            )
        }
        var title by remember(original) { mutableStateOf(original?.title.orEmpty()) }
        var content by remember(original) { mutableStateOf(original?.content.orEmpty()) }
        var scopeMenu by remember { mutableStateOf(false) }
        var deleting by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!busy) { creating = false; editing = null } },
            containerColor = ForgeColors.SurfaceCard,
            titleContentColor = ForgeColors.TextPrimary,
            textContentColor = ForgeColors.TextPrimary,
            title = { Text(stringResource(if (original == null) R.string.memory_new_note_title else R.string.memory_edit_note_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (original == null) Box {
                        OutlinedButton(onClick = { scopeMenu = true }) { Text(localizedMemoryScope(scope)) }
                        DropdownMenu(scopeMenu, { scopeMenu = false }) {
                            MemoryScope.entries.filter { (it != MemoryScope.FOLDER || current.folderId != null) && (it != MemoryScope.ASSISTANT || current.assistantId != null) }.forEach { value ->
                                DropdownMenuItem(text = { Text(localizedMemoryScope(value)) }, onClick = { scope = value; scopeMenu = false })
                            }
                        }
                    } else Text(label(original.scopeType, original.scopeKey))
                    if (scope == MemoryScope.ASSISTANT) Text(stringResource(R.string.memory_scope_hint_assistant))
                    if (scope == MemoryScope.GLOBAL) Text(stringResource(R.string.memory_scope_hint_global))
                    if (scope == MemoryScope.FOLDER) Text(stringResource(R.string.memory_scope_hint_folder))
                    if (scope == MemoryScope.MODEL) Text(stringResource(R.string.memory_scope_hint_model))
                    OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.memory_title_counter_fmt, title.length)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(content, { content = it }, label = { Text(stringResource(R.string.memory_content_counter_fmt, content.length)) }, minLines = 4, modifier = Modifier.fillMaxWidth())
                    if (original != null) TextButton(onClick = { deleting = true }, enabled = !busy) { Text(stringResource(R.string.memory_delete_note_btn)) }
                }
            },
            confirmButton = { TextButton(enabled = !busy && title.isNotBlank() && title.length <= 120 && content.isNotBlank() && content.length <= 8000,
                onClick = { vm.memoryAction({ vm.memoryRepository.save(current, scope, title, content, original) }, { creating = false; editing = null }) }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { creating = false; editing = null }, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } })
        if (deleting && original != null) AlertDialog(
            onDismissRequest = { deleting = false },
            containerColor = ForgeColors.SurfaceCard,
            titleContentColor = ForgeColors.TextPrimary,
            textContentColor = ForgeColors.TextPrimary,
            title = { Text(stringResource(R.string.memory_delete_confirm_title)) },
            text = { Text(label(original.scopeType, original.scopeKey) + "\n" + original.title) },
            confirmButton = { TextButton(onClick = { vm.memoryAction({ vm.memoryRepository.delete(current, original) }, { deleting = false; editing = null }) }, enabled = !busy) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.action_cancel)) } })
    }
    reviewing?.let { proposal ->
        AlertDialog(
            onDismissRequest = { if (!busy) reviewing = null },
            containerColor = ForgeColors.SurfaceCard,
            titleContentColor = ForgeColors.TextPrimary,
            textContentColor = ForgeColors.TextPrimary,
            title = { Text(stringResource(R.string.memory_proposal_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(label(proposal.scopeType, proposal.scopeKey))
                Text(
                    stringResource(
                        when (proposal.action) {
                            "create" -> R.string.memory_proposal_create
                            "update" -> R.string.memory_proposal_update
                            else -> R.string.memory_proposal_delete
                        }
                    ),
                    style = MaterialTheme.typography.titleMedium
                )
                if (proposal.action != "create") { Text(stringResource(R.string.memory_proposal_before_fmt, proposal.previousTitle)); Text(proposal.previousContent) }
                if (proposal.action != "delete") { Text(stringResource(R.string.memory_proposal_proposed_fmt, proposal.title)); Text(proposal.content) }
                TextButton(onClick = { reviewing = null }, enabled = !busy) { Text(stringResource(R.string.memory_decide_later)) }
            } },
            confirmButton = { TextButton(onClick = { vm.memoryAction({ vm.memoryRepository.approve(current.chatId, proposal.id) }, { reviewing = null }) }, enabled = !busy) { Text(stringResource(R.string.memory_approve)) } },
            dismissButton = { TextButton(onClick = { vm.memoryAction({ vm.memoryRepository.reject(current.chatId, proposal.id) }, { reviewing = null }) }, enabled = !busy) { Text(stringResource(R.string.memory_reject)) } })
    }
}
