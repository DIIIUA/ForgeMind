package com.forgemind.app.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.forgemind.app.R
import com.forgemind.app.data.model.AgentSettings
import com.forgemind.app.data.model.ProviderConfig

@Composable
fun ContextPolicySettings(value: AgentSettings, providers: Map<String, ProviderConfig>, onChange: (AgentSettings) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Text(stringResource(R.string.context_policy_title))
    Box {
        val currentProvLabel = providers[value.compactProviderId]?.let { it.customDisplayName.ifBlank { it.providerId } }
            ?: stringResource(R.string.context_policy_current_provider_lower)
        OutlinedButton(onClick = { menu = true }) { Text(stringResource(R.string.context_policy_model_fmt, currentProvLabel)) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.context_policy_current_provider)) }, onClick = { onChange(value.copy(compactProviderId = "", compactModelId = "")); menu = false })
            providers.values.forEach { provider -> DropdownMenuItem(text = { Text(provider.customDisplayName.ifBlank { provider.providerId }) },
                onClick = { onChange(value.copy(compactProviderId = provider.providerId, compactModelId = provider.modelId)); menu = false }) }
        }
    }
    OutlinedTextField(value = value.compactModelId, onValueChange = { onChange(value.copy(compactModelId = it)) },
        label = { Text(stringResource(R.string.context_policy_model_id_label)) }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = value.compactCustomPrompt, onValueChange = { onChange(value.copy(compactCustomPrompt = it)) },
        label = { Text(stringResource(R.string.context_policy_prompt_label)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
    Text(stringResource(R.string.context_policy_keep_messages_fmt, value.compactKeepMessages))
    Slider(value = value.compactKeepMessages.toFloat(), onValueChange = { onChange(value.copy(compactKeepMessages = it.toInt())) }, valueRange = 4f..100f, steps = 95)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.context_policy_collapse_tools), modifier = Modifier.weight(1f))
        Switch(checked = value.collapseOldToolResults, onCheckedChange = { onChange(value.copy(collapseOldToolResults = it)) })
    }
    Text(stringResource(R.string.context_policy_keep_tools_fmt, value.keepRecentToolResults))
    Slider(value = value.keepRecentToolResults.toFloat(), onValueChange = { onChange(value.copy(keepRecentToolResults = it.toInt())) }, valueRange = 1f..100f, steps = 98)
    Text(stringResource(R.string.context_policy_note))
}
