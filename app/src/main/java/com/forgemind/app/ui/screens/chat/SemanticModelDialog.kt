package com.forgemind.app.ui.screens.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.forgemind.app.ui.MainViewModel

@Composable
fun SemanticModelDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val ready by vm.semanticModelReady.collectAsState()
    val busy by vm.semanticUiBusy.collectAsState()
    val status by vm.semanticStatus.collectAsState()
    val error by vm.memoryError.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Локальный поиск по смыслу") }, text = {
        Column {
            Text("Многоязычная модель distiluse-base-multilingual-cased-v2 (Apache 2.0), около 138 МБ. Первичная загрузка с Hugging Face требует интернета. Записи и запросы обрабатываются на устройстве.")
            Text("Используется для памяти и поиска по инструментам. Без модели остаётся поиск по словам.")
            Text(status)
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            if (busy) TextButton(onClick=vm::cancelSemanticSearch) { Text("Остановить") }
            else {
                Button(onClick=vm::downloadSemanticModel) { Text(if(ready) "Проверить модель" else "Скачать модель поиска") }
                if(ready) TextButton(onClick={confirmDelete=true}) { Text("Удалить модель и кэш") }
            }
        }
    }, confirmButton = { TextButton(onClick=onDismiss) { Text("Закрыть") } })
    if(confirmDelete) AlertDialog(onDismissRequest={confirmDelete=false},title={Text("Удалить модель поиска?")},
        text={Text("Записи памяти сохранятся. Для поиска по смыслу потребуется скачать модель снова.")},
        confirmButton={TextButton(onClick={vm.removeSemanticModel();confirmDelete=false}) {Text("Удалить")}},
        dismissButton={TextButton(onClick={confirmDelete=false}) {Text("Отмена")}})
}
