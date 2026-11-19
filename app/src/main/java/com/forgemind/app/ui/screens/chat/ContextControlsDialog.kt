package com.forgemind.app.ui.screens.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.forgemind.app.R
import com.forgemind.app.data.db.ChatSessionEntity
import com.forgemind.app.data.prefs.CompactionEvent
import com.forgemind.app.ui.theme.ForgeColors
import com.forgemind.app.ui.theme.ForgeType
import java.text.DateFormat
import java.util.Date

@Composable
fun ContextControlsDialog(
    session: ChatSessionEntity,
    contextWindow: Int,
    reserve: Int,
    inheritedLimit: Int,
    checkpoints: Int,
    busy: Boolean,
    agentBusy: Boolean,
    omitted: Int,
    folded: Int,
    compactionHistory: List<CompactionEvent> = emptyList(),
    onLimit: (Int) -> Unit,
    onCompact: () -> Unit,
    onUndo: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    var limit by remember(session.id, session.historyMessageLimit) { mutableStateOf(session.historyMessageLimit.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ForgeColors.SurfaceCard,
        titleContentColor = ForgeColors.TextPrimary,
        textContentColor = ForgeColors.TextPrimary,
        title = { Text(stringResource(R.string.chat_menu_context), fontSize = ForgeType.Title, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.context_dlg_estimate_fmt, session.estimatedContextTokens, contextWindow, reserve),
                    fontSize = ForgeType.Body
                )
                if (session.estimatedContextTokens.toLong() + reserve >= contextWindow.toLong() * 9 / 10) {
                    Text(stringResource(R.string.context_dlg_near_limit), color = ForgeColors.DangerRed, fontSize = ForgeType.Meta)
                }
                Text(
                    stringResource(R.string.context_dlg_stats_fmt, session.compactedSummary.length, omitted, folded),
                    fontSize = ForgeType.Meta,
                    color = ForgeColors.TextSecondary
                )
                OutlinedTextField(
                    value = limit,
                    onValueChange = { limit = it },
                    label = { Text(stringResource(R.string.context_dlg_limit_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.context_dlg_limit_hint_fmt, inheritedLimit),
                    fontSize = ForgeType.Caption,
                    color = ForgeColors.TextSecondary
                )
                TextButton(enabled = limit.toIntOrNull() in -1..10000, onClick = { limit.toIntOrNull()?.let(onLimit) }) {
                    Text(stringResource(R.string.context_dlg_save_limit))
                }
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.context_dlg_cancel_compact)) }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !agentBusy, onClick = onCompact) { Text(stringResource(R.string.context_dlg_compact_now)) }
                        if (checkpoints > 0) {
                            OutlinedButton(enabled = !agentBusy, onClick = onUndo) {
                                Text(stringResource(R.string.context_dlg_undo_fmt, checkpoints))
                            }
                        }
                    }
                }
                if (compactionHistory.isNotEmpty()) {
                    HorizontalDivider(color = ForgeColors.Divider)
                    Text(stringResource(R.string.context_dlg_history_title), fontSize = ForgeType.Meta, fontWeight = FontWeight.SemiBold, color = ForgeColors.TextPrimary)
                    val autoLabel = stringResource(R.string.context_dlg_mode_auto)
                    val manualLabel = stringResource(R.string.context_dlg_mode_manual)
                    val restoredSuffix = stringResource(R.string.context_dlg_restored_suffix)
                    compactionHistory.asReversed().take(8).forEach { event ->
                        val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(event.createdAt))
                        val mode = if (event.automatic) autoLabel else manualLabel
                        val suffix = if (event.restored) restoredSuffix else ""
                        Text(
                            "$date ($mode): ≈${event.before} → ≈${event.after}$suffix",
                            fontSize = ForgeType.Caption,
                            color = ForgeColors.TextSecondary
                        )
                    }
                }
                Text(
                    stringResource(R.string.context_dlg_settings_hint),
                    fontSize = ForgeType.Caption,
                    color = ForgeColors.TextTertiary
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
    )
}
