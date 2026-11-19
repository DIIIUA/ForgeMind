package com.forgemind.app.ui.screens.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.forgemind.app.R
import com.forgemind.app.ui.MainViewModel
import com.forgemind.app.ui.theme.ForgeColors
import com.forgemind.app.ui.theme.ForgeType

@Composable
fun ArchiveImportDialog(vm: MainViewModel) {
    val preview by vm.archivePreview.collectAsState()
    val busy by vm.archiveBusy.collectAsState()
    val report by vm.archiveReport.collectAsState()
    val running by vm.isAgentRunning.collectAsState()
    preview?.let { data ->
        AlertDialog(
            onDismissRequest = vm::dismissArchivePreview,
            containerColor = ForgeColors.SurfaceCard,
            titleContentColor = ForgeColors.TextPrimary,
            textContentColor = ForgeColors.TextPrimary,
            title = { Text(stringResource(R.string.archive_import_title), fontSize = ForgeType.Title, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.archive_import_summary_fmt, data.sessions, data.messages, data.attachments, data.preservedSessions),
                        fontSize = ForgeType.Body
                    )
                    Text(
                        stringResource(R.string.archive_import_rules),
                        fontSize = ForgeType.Meta,
                        color = ForgeColors.TextSecondary
                    )
                    data.changes.groupingBy { it.action }.eachCount().forEach { (action, count) ->
                        Text("$action: $count", fontSize = ForgeType.Meta, fontWeight = FontWeight.Medium)
                    }
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(data.changes) { row ->
                            Text("${row.kind} · ${row.action}", style = MaterialTheme.typography.labelLarge)
                            if (row.before.isNotEmpty()) {
                                Text(stringResource(R.string.archive_import_now_fmt, row.before), style = MaterialTheme.typography.bodySmall, color = ForgeColors.TextSecondary)
                            }
                            Text(stringResource(R.string.archive_import_in_archive_fmt, row.after), style = MaterialTheme.typography.bodySmall, color = ForgeColors.TextSecondary)
                            HorizontalDivider(color = ForgeColors.Divider)
                        }
                    }
                    if (running) Text(stringResource(R.string.archive_import_stop_agent), color = ForgeColors.AccentAmber, fontSize = ForgeType.Meta)
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && !running, onClick = vm::applyArchivePreview) { Text(stringResource(R.string.archive_import_apply)) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = vm::dismissArchivePreview) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
    report?.let { text ->
        AlertDialog(
            onDismissRequest = { vm.archiveReport.value = null },
            containerColor = ForgeColors.SurfaceCard,
            titleContentColor = ForgeColors.TextPrimary,
            textContentColor = ForgeColors.TextPrimary,
            title = { Text(stringResource(R.string.archive_import_report_title), fontSize = ForgeType.Title, fontWeight = FontWeight.SemiBold) },
            text = { Text(text, fontSize = ForgeType.Body) },
            confirmButton = { TextButton(onClick = { vm.archiveReport.value = null }) { Text(stringResource(R.string.action_close)) } }
        )
    }
}
