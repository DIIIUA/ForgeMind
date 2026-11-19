package com.forgemind.app.ui.screens.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.forgemind.app.R
import com.forgemind.app.ui.MainViewModel
import com.forgemind.app.ui.theme.ForgeColors
import com.forgemind.app.ui.theme.ForgeType

@Composable
fun ConversationFeatureDialogs(vm: MainViewModel) {
    val schedules by vm.showSchedules.collectAsState()
    if (schedules) SchedulesDialog(vm)
    val permissions by vm.showToolPermissions.collectAsState()
    val history by vm.showCompactionHistory.collectAsState()
    val grants by vm.toolGrants.collectAsState()
    val events by vm.compactionEvents.collectAsState()
    val session by vm.currentSession.collectAsState()

    if (permissions) {
        AlertDialog(
            onDismissRequest = { vm.showToolPermissions.value = false },
            containerColor = ForgeColors.SurfaceCard,
            titleContentColor = ForgeColors.TextPrimary,
            textContentColor = ForgeColors.TextPrimary,
            title = { Text(stringResource(R.string.chat_perms_title), fontSize = ForgeType.Title, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(
                    Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.chat_perms_desc),
                        fontSize = ForgeType.Meta,
                        color = ForgeColors.TextSecondary
                    )
                    val own = grants.filter { it.sessionId == session?.id }
                    if (own.isEmpty()) {
                        Text(stringResource(R.string.chat_perms_empty), fontSize = ForgeType.Body, color = ForgeColors.TextSecondary)
                    } else {
                        own.forEach { grant ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(grant.toolName, Modifier.weight(1f), fontSize = ForgeType.Body)
                                TextButton(onClick = { vm.revokeToolGrant(grant.sessionId, grant.toolName) }) {
                                    Text(stringResource(R.string.chat_perms_revoke), color = ForgeColors.DangerRed)
                                }
                            }
                        }
                        TextButton(onClick = { session?.let { vm.clearToolGrants(it.id) } }) {
                            Text(stringResource(R.string.chat_perms_revoke_all), color = ForgeColors.DangerRed)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.showToolPermissions.value = false }) { Text(stringResource(R.string.action_close)) } }
        )
    }

    if (history) {
        AlertDialog(
            onDismissRequest = { vm.showCompactionHistory.value = false },
            containerColor = ForgeColors.SurfaceCard,
            titleContentColor = ForgeColors.TextPrimary,
            textContentColor = ForgeColors.TextPrimary,
            title = { Text(stringResource(R.string.context_dlg_history_title), fontSize = ForgeType.Title, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(
                    Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val own = events.filter { it.sessionId == session?.id }.asReversed()
                    if (own.isEmpty()) {
                        Text(stringResource(R.string.compaction_hist_empty), fontSize = ForgeType.Body, color = ForgeColors.TextSecondary)
                    } else {
                        val autoFull = stringResource(R.string.context_dlg_mode_auto_full)
                        val manualLabel = stringResource(R.string.context_dlg_mode_manual)
                        val restoredSuffix = stringResource(R.string.context_dlg_restored_suffix)
                        own.forEach { event ->
                            Text(
                                "${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(event.createdAt))}: ${if (event.automatic) autoFull else manualLabel}, ≈${event.before} → ≈${event.after}${if (event.restored) restoredSuffix else ""}",
                                fontSize = ForgeType.Meta
                            )
                        }
                    }
                    TextButton(
                        enabled = !vm.isAgentRunning.value,
                        onClick = { vm.undoCompaction(); vm.showCompactionHistory.value = false }
                    ) { Text(stringResource(R.string.compaction_hist_undo_last)) }
                }
            },
            confirmButton = { TextButton(onClick = { vm.showCompactionHistory.value = false }) { Text(stringResource(R.string.action_close)) } }
        )
    }

    val regeneration by vm.regenerationRequest.collectAsState()
    val fragment by vm.fragmentBranch.collectAsState()
    val undo by vm.branchUndo.collectAsState()
    val running by vm.isAgentRunning.collectAsState()

    regeneration?.let {
        AlertDialog(
            onDismissRequest = { vm.regenerationRequest.value = null },
            containerColor = ForgeColors.SurfaceCard,
            titleContentColor = ForgeColors.TextPrimary,
            textContentColor = ForgeColors.TextPrimary,
            title = { Text(stringResource(R.string.regen_confirm_title), fontSize = ForgeType.Title, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    stringResource(R.string.regen_confirm_body),
                    fontSize = ForgeType.Body,
                    color = ForgeColors.TextSecondary
                )
            },
            confirmButton = { TextButton(onClick = vm::confirmRegeneration) { Text(stringResource(R.string.regen_confirm_btn)) } },
            dismissButton = { TextButton(onClick = { vm.regenerationRequest.value = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    fragment?.let { request ->
        var text by remember(request) { mutableStateOf(request.text) }
        AlertDialog(
            onDismissRequest = { vm.fragmentBranch.value = null },
            containerColor = ForgeColors.SurfaceCard,
            titleContentColor = ForgeColors.TextPrimary,
            textContentColor = ForgeColors.TextPrimary,
            title = { Text(stringResource(R.string.frag_branch_title), fontSize = ForgeType.Title, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.frag_branch_desc),
                        fontSize = ForgeType.Meta,
                        color = ForgeColors.TextSecondary
                    )
                    OutlinedTextField(text, { text = it }, minLines = 3, maxLines = 10, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { vm.createFragmentBranch(text) }) { Text(stringResource(R.string.action_create)) } },
            dismissButton = { TextButton(onClick = { vm.fragmentBranch.value = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    if (undo != null && !running && regeneration == null && fragment == null) {
        AlertDialog(
            onDismissRequest = { vm.branchUndo.value = null },
            containerColor = ForgeColors.SurfaceCard,
            titleContentColor = ForgeColors.TextPrimary,
            textContentColor = ForgeColors.TextPrimary,
            title = { Text(stringResource(R.string.regen_saved_title), fontSize = ForgeType.Title, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    stringResource(R.string.regen_saved_body),
                    fontSize = ForgeType.Body,
                    color = ForgeColors.TextSecondary
                )
            },
            confirmButton = { TextButton(onClick = { vm.branchUndo.value = null }) { Text(stringResource(R.string.regen_keep_new)) } },
            dismissButton = { TextButton(onClick = vm::undoRegeneration) { Text(stringResource(R.string.regen_return_prev)) } }
        )
    }
}
