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
import com.forgemind.app.data.db.AssistantProfileEntity
import com.forgemind.app.ui.MainViewModel
import java.util.UUID
import com.forgemind.app.data.model.GenerationPreset
import com.forgemind.app.ui.theme.ForgeColors
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.forgemind.app.R
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Composable
fun AssistantProfilesDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val profiles by vm.assistantProfiles.collectAsState()
    val session by vm.currentSession.collectAsState()
    val providers by vm.providerConfigs.collectAsState()
    val busy by vm.memoryBusy.collectAsState()
    val error by vm.memoryError.collectAsState()
    var query by remember { mutableStateOf("") }
    var useModel by remember { mutableStateOf(true) }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AssistantProfileEntity?>(null) }
    var deleting by remember { mutableStateOf<AssistantProfileEntity?>(null) }
    Dialog(onDismissRequest = { if(!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.widthIn(max = 600.dp).fillMaxWidth().fillMaxHeight(0.94f),
            shape = MaterialTheme.shapes.large,
            color = ForgeColors.SurfaceCard,
            contentColor = ForgeColors.TextPrimary
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.chat_menu_assistants), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_close)) }
                }
                error?.let { Text(it, color = ForgeColors.DangerRed) }
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.assistant_search_label)) }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(useModel, { useModel = it })
                    Text(stringResource(R.string.assistant_use_model))
                }
                Text(stringResource(R.string.assistant_desc))
                Row {
                    TextButton(onClick={vm.memoryError.value=null;creating=true},enabled=!busy) { Text(stringResource(R.string.assistant_create_btn)) }
                    TextButton(onClick={vm.memoryAction({vm.applyAssistant(null,false,false)},onDismiss)},enabled=!busy && session!=null) { Text(stringResource(R.string.assistant_none_btn)) }
                }
                LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    items(profiles.filter { it.name.contains(query,true)||it.category.contains(query,true) },key={it.id}) { profile ->
                        OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                            Text(profile.name + if(session?.assistantId==profile.id) " ✓" else "",style=MaterialTheme.typography.titleMedium)
                            if(profile.category.isNotBlank()) Text(profile.category)
                            if(profile.defaultModelId.isNotBlank()) Text(profile.defaultProviderId+" / "+profile.defaultModelId)
                            Row {
                                TextButton(onClick={vm.memoryAction({vm.applyAssistant(profile.id,useModel,false)},onDismiss)},enabled=!busy && session!=null) { Text(stringResource(R.string.assistant_select_btn)) }
                                TextButton(onClick={vm.memoryAction({vm.applyAssistant(profile.id,useModel,true)},onDismiss)},enabled=!busy) { Text(stringResource(R.string.assistant_new_chat_btn)) }
                            }
                            Row {
                                TextButton(onClick={vm.memoryError.value=null;editing=profile},enabled=!busy) { Text(stringResource(R.string.action_edit)) }
                                TextButton(onClick={deleting=profile},enabled=!busy) { Text(stringResource(R.string.action_delete)) }
                            }
                        } }
                    }
                }
            }
        }
    }
    if(creating||editing!=null) {
        val original=editing
        var draft by remember(original) { mutableStateOf(original ?: AssistantProfileEntity(UUID.randomUUID().toString(),"")) }
        var limit by remember(original) { mutableStateOf(draft.historyMessageLimit.toString()) }
        val initialPreset = remember(original) { runCatching { GenerationPreset.decode(draft.generationConfigJson) }.getOrDefault(GenerationPreset()) }
        var temperature by remember(original) { mutableStateOf(initialPreset.temperature?.toString().orEmpty()) }
        var outputTokens by remember(original) { mutableStateOf(initialPreset.maxTokens?.toString().orEmpty()) }
        var reasoning by remember(original) { mutableStateOf(initialPreset.reasoning.orEmpty()) }
        val generation = runCatching {
            GenerationPreset(if (temperature.isBlank()) null else temperature.toFloat(),
                if (outputTokens.isBlank()) null else outputTokens.toInt(), reasoning.ifBlank { null }).also { it.validate() }
        }.getOrNull()
        var providerMenu by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest={if(!busy){creating=false;editing=null}},
            containerColor = ForgeColors.SurfaceCard,
            titleContentColor = ForgeColors.TextPrimary,
            textContentColor = ForgeColors.TextPrimary,
            title={Text(stringResource(if(original==null) R.string.assistant_new_title else R.string.assistant_edit_title))},
            text={Column(Modifier.verticalScroll(rememberScrollState())) {
                error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                OutlinedTextField(draft.name,{draft=draft.copy(name=it)},label={Text(stringResource(R.string.assistant_name_label))},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(draft.category,{draft=draft.copy(category=it)},label={Text(stringResource(R.string.assistant_category_label))},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(draft.instructions,{draft=draft.copy(instructions=it)},label={Text(stringResource(R.string.assistant_instructions_label))},minLines=3,modifier=Modifier.fillMaxWidth())
                Box {
                    val chatModelLabel = stringResource(R.string.assistant_chat_model)
                    OutlinedButton(onClick={providerMenu=true}) { Text(draft.defaultProviderId.ifBlank { chatModelLabel }) }
                    DropdownMenu(providerMenu,{providerMenu=false}) {
                        DropdownMenuItem(text={Text(chatModelLabel)},onClick={draft=draft.copy(defaultProviderId="",defaultModelId="");providerMenu=false})
                        providers.values.forEach { provider -> DropdownMenuItem(text={Text(provider.customDisplayName.ifBlank {provider.providerId})},onClick={draft=draft.copy(defaultProviderId=provider.providerId,defaultModelId=provider.modelId);providerMenu=false}) }
                    }
                }
                if(draft.defaultProviderId.isNotBlank()) OutlinedTextField(draft.defaultModelId,{draft=draft.copy(defaultModelId=it)},label={Text(stringResource(R.string.assistant_default_model_label))},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(limit,{limit=it},label={Text(stringResource(R.string.assistant_history_limit_label))},supportingText={Text(stringResource(R.string.assistant_history_limit_hint))},modifier=Modifier.fillMaxWidth())
                Text(stringResource(R.string.assistant_preset_hint))
                OutlinedTextField(temperature, { temperature = it }, label = { Text(stringResource(R.string.assistant_temperature_label)) })
                OutlinedTextField(outputTokens, { outputTokens = it }, label = { Text(stringResource(R.string.assistant_max_tokens_label)) })
                OutlinedTextField(reasoning, { reasoning = it.trim().lowercase() }, label = { Text(stringResource(R.string.assistant_reasoning_label)) })
                if (generation == null) Text(stringResource(R.string.assistant_invalid_params), color = MaterialTheme.colorScheme.error)
            }},
            confirmButton={TextButton(enabled=!busy && generation != null && limit.toIntOrNull()?.let { it in -1..10000 } == true && draft.name.isNotBlank(),onClick={vm.memoryAction({vm.assistantRepository.save(draft.copy(historyMessageLimit=limit.toInt(),generationConfigJson=Json.encodeToString(generation!!)),original?.revision)},{creating=false;editing=null})}) {Text(stringResource(R.string.action_save))}},
            dismissButton={TextButton(onClick={creating=false;editing=null},enabled=!busy){Text(stringResource(R.string.action_cancel))}})
    }
    deleting?.let { profile -> AlertDialog(
        onDismissRequest={deleting=null},
        containerColor = ForgeColors.SurfaceCard,
        titleContentColor = ForgeColors.TextPrimary,
        textContentColor = ForgeColors.TextPrimary,
        title={Text(stringResource(R.string.assistant_delete_title))},
        text={Column {error?.let {Text(it,color=MaterialTheme.colorScheme.error)};Text(stringResource(R.string.assistant_delete_body_fmt, profile.name))}},
        confirmButton={TextButton(onClick={vm.memoryAction({vm.deleteAssistant(profile)},{deleting=null})},enabled=!busy){Text(stringResource(R.string.action_delete))}},
        dismissButton={TextButton(onClick={deleting=null},enabled=!busy){Text(stringResource(R.string.action_cancel))}}) }
}
