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
        Surface(Modifier.widthIn(max = 600.dp).fillMaxWidth().fillMaxHeight(0.94f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp)) {
                Text("Профили ассистентов",style=MaterialTheme.typography.titleLarge)
                TextButton(onClick=onDismiss,enabled=!busy) { Text("Закрыть") }
                error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                OutlinedTextField(query,{query=it},label={Text("Имя или категория")},modifier=Modifier.fillMaxWidth())
                Row { Checkbox(useModel,{useModel=it});Text("Применять модель из профиля",modifier=Modifier.padding(top=12.dp)) }
                Text("Инструкция профиля дополняет основную, общую и чатовую. Память ассистента разделяется только между его чатами.")
                Row {
                    TextButton(onClick={vm.memoryError.value=null;creating=true},enabled=!busy) { Text("Создать профиль") }
                    TextButton(onClick={vm.memoryAction({vm.applyAssistant(null,false,false)},onDismiss)},enabled=!busy && session!=null) { Text("Без профиля") }
                }
                LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    items(profiles.filter { it.name.contains(query,true)||it.category.contains(query,true) },key={it.id}) { profile ->
                        OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                            Text(profile.name + if(session?.assistantId==profile.id) " ✓" else "",style=MaterialTheme.typography.titleMedium)
                            if(profile.category.isNotBlank()) Text(profile.category)
                            if(profile.defaultModelId.isNotBlank()) Text(profile.defaultProviderId+" / "+profile.defaultModelId)
                            Row {
                                TextButton(onClick={vm.memoryAction({vm.applyAssistant(profile.id,useModel,false)},onDismiss)},enabled=!busy && session!=null) { Text("Выбрать") }
                                TextButton(onClick={vm.memoryAction({vm.applyAssistant(profile.id,useModel,true)},onDismiss)},enabled=!busy) { Text("Новый чат") }
                            }
                            Row {
                                TextButton(onClick={vm.memoryError.value=null;editing=profile},enabled=!busy) { Text("Изменить") }
                                TextButton(onClick={deleting=profile},enabled=!busy) { Text("Удалить") }
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
        var providerMenu by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest={if(!busy){creating=false;editing=null}},title={Text(if(original==null) "Новый ассистент" else "Профиль ассистента")},
            text={Column(Modifier.verticalScroll(rememberScrollState())) {
                error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                OutlinedTextField(draft.name,{draft=draft.copy(name=it)},label={Text("Имя")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(draft.category,{draft=draft.copy(category=it)},label={Text("Категория")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(draft.instructions,{draft=draft.copy(instructions=it)},label={Text("Инструкция ассистента")},minLines=3,modifier=Modifier.fillMaxWidth())
                Box {
                    OutlinedButton(onClick={providerMenu=true}) { Text(draft.defaultProviderId.ifBlank { "Модель чата" }) }
                    DropdownMenu(providerMenu,{providerMenu=false}) {
                        DropdownMenuItem(text={Text("Модель чата")},onClick={draft=draft.copy(defaultProviderId="",defaultModelId="");providerMenu=false})
                        providers.values.forEach { provider -> DropdownMenuItem(text={Text(provider.customDisplayName.ifBlank {provider.providerId})},onClick={draft=draft.copy(defaultProviderId=provider.providerId,defaultModelId=provider.modelId);providerMenu=false}) }
                    }
                }
                if(draft.defaultProviderId.isNotBlank()) OutlinedTextField(draft.defaultModelId,{draft=draft.copy(defaultModelId=it)},label={Text("ID модели по умолчанию")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(limit,{limit=it},label={Text("Сообщений истории")},supportingText={Text("−1: настройка провайдера; 0: вся история. Настройка самого чата имеет приоритет.")},modifier=Modifier.fillMaxWidth())
            }},
            confirmButton={TextButton(enabled=!busy && limit.toIntOrNull()?.let { it in -1..10000 } == true && draft.name.isNotBlank(),onClick={vm.memoryAction({vm.assistantRepository.save(draft.copy(historyMessageLimit=limit.toInt()),original?.revision)},{creating=false;editing=null})}) {Text("Сохранить")}},
            dismissButton={TextButton(onClick={creating=false;editing=null},enabled=!busy){Text("Отмена")}})
    }
    deleting?.let { profile -> AlertDialog(onDismissRequest={deleting=null},title={Text("Удалить ассистента?")},
        text={Column {error?.let {Text(it,color=MaterialTheme.colorScheme.error)};Text("${profile.name}: его отдельная память и предложения будут удалены. Чаты, сообщения, модели и чатовые инструкции сохранятся.")}},
        confirmButton={TextButton(onClick={vm.memoryAction({vm.deleteAssistant(profile)},{deleting=null})},enabled=!busy){Text("Удалить")}},
        dismissButton={TextButton(onClick={deleting=null},enabled=!busy){Text("Отмена")}}) }
}
