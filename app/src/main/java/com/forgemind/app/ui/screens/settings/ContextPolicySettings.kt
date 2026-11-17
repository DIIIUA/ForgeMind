package com.forgemind.app.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.forgemind.app.data.model.AgentSettings
import com.forgemind.app.data.model.ProviderConfig

@Composable
fun ContextPolicySettings(value: AgentSettings, providers: Map<String, ProviderConfig>, onChange: (AgentSettings) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Text("Сжатие и история")
    Box {
        OutlinedButton(onClick = { menu = true }) { Text("Модель сжатия: " +
            (providers[value.compactProviderId]?.let { it.customDisplayName.ifBlank { it.providerId } } ?: "текущий провайдер")) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Текущий провайдер") }, onClick = { onChange(value.copy(compactProviderId = "", compactModelId = "")); menu = false })
            providers.values.forEach { provider -> DropdownMenuItem(text = { Text(provider.customDisplayName.ifBlank { provider.providerId }) },
                onClick = { onChange(value.copy(compactProviderId = provider.providerId, compactModelId = provider.modelId)); menu = false }) }
        }
    }
    OutlinedTextField(value = value.compactModelId, onValueChange = { onChange(value.copy(compactModelId = it)) },
        label = { Text("ID модели сжатия (пусто — текущая)") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = value.compactCustomPrompt, onValueChange = { onChange(value.copy(compactCustomPrompt = it)) },
        label = { Text("Инструкция для сжатия") }, minLines = 2, modifier = Modifier.fillMaxWidth())
    Text("Оставлять последних сообщений: ${value.compactKeepMessages}")
    Slider(value = value.compactKeepMessages.toFloat(), onValueChange = { onChange(value.copy(compactKeepMessages = it.toInt())) }, valueRange = 4f..100f, steps = 95)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Сокращать старый вывод инструментов", modifier = Modifier.weight(1f))
        Switch(checked = value.collapseOldToolResults, onCheckedChange = { onChange(value.copy(collapseOldToolResults = it)) })
    }
    Text("Полных результатов инструментов: ${value.keepRecentToolResults}")
    Slider(value = value.keepRecentToolResults.toFloat(), onValueChange = { onChange(value.copy(keepRecentToolResults = it.toInt())) }, valueRange = 1f..100f, steps = 98)
    Text("Оригиналы остаются в истории. Ошибка модели не заменяет память усечённым текстом.")
}
