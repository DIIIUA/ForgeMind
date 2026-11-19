package com.forgemind.app.ui.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.forgemind.app.R
import com.forgemind.app.ui.MainViewModel
import com.forgemind.app.ui.theme.ForgeColors
import com.forgemind.app.ui.theme.ForgeType

@Composable
fun SemanticModelDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val ready by vm.semanticModelReady.collectAsState()
    val busy by vm.semanticUiBusy.collectAsState()
    val status by vm.semanticStatus.collectAsState()
    val error by vm.memoryError.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ForgeColors.SurfaceCard,
        titleContentColor = ForgeColors.TextPrimary,
        textContentColor = ForgeColors.TextPrimary,
        title = { Text(stringResource(R.string.semantic_dlg_title), fontSize = ForgeType.Title, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.semantic_dlg_desc),
                    fontSize = ForgeType.Meta,
                    color = ForgeColors.TextSecondary
                )
                Text(
                    stringResource(R.string.semantic_dlg_usage),
                    fontSize = ForgeType.Meta,
                    color = ForgeColors.TextSecondary
                )
                Text(status, fontSize = ForgeType.Body, color = ForgeColors.AccentText)
                error?.let { Text(it, color = ForgeColors.DangerRed, fontSize = ForgeType.Meta) }
                if (busy) {
                    TextButton(onClick = vm::cancelSemanticSearch) { Text(stringResource(R.string.action_stop)) }
                } else {
                    Button(onClick = vm::downloadSemanticModel) {
                        Text(stringResource(if (ready) R.string.semantic_dlg_verify else R.string.semantic_dlg_download))
                    }
                    if (ready) {
                        TextButton(onClick = { confirmDelete = true }) {
                            Text(stringResource(R.string.semantic_dlg_delete_btn), color = ForgeColors.DangerRed)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = ForgeColors.SurfaceCard,
            titleContentColor = ForgeColors.TextPrimary,
            textContentColor = ForgeColors.TextPrimary,
            title = { Text(stringResource(R.string.semantic_dlg_delete_title), fontSize = ForgeType.Title, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    stringResource(R.string.semantic_dlg_delete_body),
                    fontSize = ForgeType.Body,
                    color = ForgeColors.TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.removeSemanticModel(); confirmDelete = false }) {
                    Text(stringResource(R.string.action_delete), color = ForgeColors.DangerRed)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}
