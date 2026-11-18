package com.forgemind.app.ui.screens.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.forgemind.app.ui.MainViewModel
import com.forgemind.app.scheduling.ScheduledPrompt
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

@Composable
fun SchedulesDialog(vm: MainViewModel) {
    val tasks by vm.scheduledPrompts.collectAsState()
    val session by vm.currentSession.collectAsState()
    val error by vm.scheduleError.collectAsState()
    var edit by remember { mutableStateOf<ScheduledPrompt?>(null) }
    var deleting by remember { mutableStateOf<ScheduledPrompt?>(null) }
    AlertDialog(onDismissRequest = { vm.showSchedules.value = false }, title = { Text("Расписания") },
        text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            Text("Задача использует модель и настройки своего чата. Подтверждения инструментов сохраняются. Android может отложить запуск из-за сети или энергосбережения.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(enabled = session != null, onClick = { edit = ScheduledPrompt(UUID.randomUUID().toString(),session!!.id,"","",
                dateTime=LocalDateTime.now().plusMinutes(5).withSecond(0).withNano(0).toString()) }) { Text("Создать для этого чата") }
            tasks.forEach { task -> OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Column(Modifier.padding(10.dp)) {
                Text(task.name, style = MaterialTheme.typography.titleMedium)
                Text("${task.kind}: ${task.dateTime} · ${task.timeZone}")
                Text(task.lastStatus + if(task.lastError.isNotBlank()) ": ${task.lastError}" else "")
                Row {
                    TextButton(onClick = { edit = task }) { Text("Изменить") }
                    TextButton(onClick = { vm.saveSchedule(task.copy(enabled=!task.enabled)) }) { Text(if(task.enabled) "Отключить" else "Включить") }
                    TextButton(onClick = { deleting=task }) { Text("Удалить") }
                }
            } } }
        } }, confirmButton = { TextButton(onClick = { vm.showSchedules.value = false }) { Text("Закрыть") } })
    edit?.let { task ->
        var name by remember(task) { mutableStateOf(task.name) }
        var prompt by remember(task) { mutableStateOf(task.prompt) }
        var daily by remember(task) { mutableStateOf(task.kind=="daily") }
        var time by remember(task) { mutableStateOf(task.dateTime) }
        var zone by remember(task) { mutableStateOf(task.timeZone) }
        AlertDialog(onDismissRequest = { edit=null },title = { Text("Задача по расписанию") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                OutlinedTextField(name,{name=it},label={Text("Название")})
                OutlinedTextField(prompt,{prompt=it},label={Text("Задание модели")},minLines=3)
                Row { Checkbox(daily,{daily=it;time=if(it) "09:00" else LocalDateTime.now().plusMinutes(5).withSecond(0).withNano(0).toString()});Text("Каждый день") }
                OutlinedTextField(time,{time=it},label={Text(if(daily) "Время HH:mm" else "Дата и время YYYY-MM-DDTHH:mm")})
                OutlinedTextField(zone,{zone=it},label={Text("Часовой пояс")},supportingText={Text("Например, ${ZoneId.systemDefault().id}")})
            } },confirmButton={TextButton(enabled=name.isNotBlank()&&prompt.isNotBlank(),onClick={
                vm.saveSchedule(task.copy(name=name,prompt=prompt,kind=if(daily) "daily" else "once",dateTime=time,timeZone=zone,enabled=true)) { edit=null }
            }){Text("Сохранить")}},dismissButton={TextButton(onClick={edit=null}){Text("Отмена")}})
    }
    deleting?.let { task -> AlertDialog(onDismissRequest={deleting=null},title={Text("Удалить расписание?")},
        text={Text("${task.name}: запланированные запуски будут отменены. Чат сохраняется.")},
        confirmButton={TextButton(onClick={vm.deleteSchedule(task.id);deleting=null}){Text("Удалить")}},
        dismissButton={TextButton(onClick={deleting=null}){Text("Отмена")}}) }
}
