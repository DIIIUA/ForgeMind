package com.forgemind.app.ui.screens.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.forgemind.app.ui.MainViewModel

@Composable
fun ArchiveImportDialog(vm: MainViewModel) {
    val preview by vm.archivePreview.collectAsState()
    val busy by vm.archiveBusy.collectAsState()
    val report by vm.archiveReport.collectAsState()
    val running by vm.isAgentRunning.collectAsState()
    preview?.let { data ->
        AlertDialog(onDismissRequest=vm::dismissArchivePreview,title={Text("Сравнение перед импортом")},
            text={Column {
                Text("Чатов: ${data.sessions}, сообщений: ${data.messages}, вложений: ${data.attachments}. Остальные ${data.preservedSessions} чатов сохранятся.")
                Text("Совпадения ID заменяют чаты, сообщения и папки. Текущие профили и заметки сохраняются. Новые расписания импортируются отключёнными.")
                data.changes.groupingBy { it.action }.eachCount().forEach { (action,count) -> Text("$action: $count") }
                LazyColumn(Modifier.heightIn(max=300.dp)) { items(data.changes) { row ->
                    Text("${row.kind} · ${row.action}",style=MaterialTheme.typography.labelLarge)
                    if(row.before.isNotEmpty()) Text("Сейчас: ${row.before}",style=MaterialTheme.typography.bodySmall)
                    Text("В архиве: ${row.after}",style=MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                } }
                if(running) Text("Остановите агента перед применением.")
                if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }},confirmButton={TextButton(enabled=!busy && !running,onClick=vm::applyArchivePreview){Text("Применить импорт")}},
            dismissButton={TextButton(enabled=!busy,onClick=vm::dismissArchivePreview){Text("Отмена")}})
    }
    report?.let { text -> AlertDialog(onDismissRequest={vm.archiveReport.value=null},title={Text("Отчёт импорта")},
        text={Text(text)},confirmButton={TextButton(onClick={vm.archiveReport.value=null}){Text("Закрыть")}}) }
}
