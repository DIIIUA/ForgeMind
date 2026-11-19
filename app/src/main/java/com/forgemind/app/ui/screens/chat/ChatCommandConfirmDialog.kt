package com.forgemind.app.ui.screens.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.forgemind.app.R
import com.forgemind.app.ui.PendingChatCommand
import com.forgemind.app.ui.theme.ForgeColors
import com.forgemind.app.ui.theme.ForgeType

@Composable
fun ChatCommandConfirmDialog(command: PendingChatCommand, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ForgeColors.SurfaceCard,
        titleContentColor = ForgeColors.TextPrimary,
        textContentColor = ForgeColors.TextPrimary,
        title = { Text(stringResource(R.string.chat_cmd_confirm_title), fontSize = ForgeType.Title, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(command.targetLabel, fontSize = ForgeType.Meta, color = ForgeColors.AccentText)
                SelectionContainer { Text(command.command, fontFamily = FontFamily.Monospace, fontSize = ForgeType.Body) }
                Text(
                    stringResource(R.string.chat_cmd_confirm_body),
                    fontSize = ForgeType.Meta,
                    color = ForgeColors.TextSecondary
                )
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text(stringResource(R.string.action_run)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}
