package com.forgemind.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.forgemind.app.ui.MainViewModel

/** The run belongs to the application; waiting for approval must be visible in every section. */
@Composable
fun AgentRunPanel(vm: MainViewModel) {
    val running by vm.isAgentRunning.collectAsState()
    val banner by vm.agentStatusBanner.collectAsState()
    val message by vm.runningAgentMessage.collectAsState()
    val approvals by vm.pendingApprovals.collectAsState()
    val fallback by vm.pendingModelFallback.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    if (running || approvals.isNotEmpty() || fallback != null) Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.weight(1f)) {
                    Text(when {
                        approvals.isNotEmpty() -> "Агент ждёт разрешения"
                        fallback != null -> "Агент ждёт выбора модели"
                        message?.thinking?.isNotBlank() == true && message?.content.isNullOrBlank() -> "Модель рассуждает"
                        else -> banner ?: "Агент работает"
                    }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = vm::openRunningAgentChat) { Text("В чат") }
                TextButton(onClick = vm::stopAgent) { Text("Стоп") }
            }
            if (expanded) Text(message?.thinking?.takeLast(1200)?.takeIf { it.isNotBlank() }
                ?: message?.content?.takeLast(1200).orEmpty(), modifier = Modifier.padding(12.dp))
            approvals.firstOrNull()?.let { PendingApprovalBanner(it,
                onAllow = { vm.resolveApproval(it.id,true) }, onDeny = { vm.resolveApproval(it.id,false) },
                onEditArguments = { value -> vm.updateApprovalArguments(it.id, value) }, onRemember = { vm.rememberApproval(it.id) }) }
            fallback?.let { ModelFallbackBanner(it, onSwitch = { vm.resolveModelFallback(it.id,true) },
                onCancel = { vm.resolveModelFallback(it.id,false) }, inOtherChat = true, onOpenChat = vm::openRunningAgentChat) }
        }
    }
}
