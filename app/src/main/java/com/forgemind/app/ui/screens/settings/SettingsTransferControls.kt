package com.forgemind.app.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.forgemind.app.R
import com.forgemind.app.ui.MainViewModel

@Composable
fun SettingsTransferControls(vm: MainViewModel) {
    val busy by vm.settingsTransferBusy.collectAsState()
    val running by vm.isAgentRunning.collectAsState()
    var restoreDialog by remember { mutableStateOf<Boolean?>(null) }
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<CharArray?>(null) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val secret = pending
        pending = null
        if (uri != null && secret != null) vm.transferSettings(uri, secret, restore = false)
        else secret?.fill('\u0000')
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val secret = pending
        pending = null
        if (uri != null && secret != null) vm.transferSettings(uri, secret, restore = true)
        else secret?.fill('\u0000')
    }
    DisposableEffect(Unit) { onDispose { pending?.fill('\u0000') } }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.settings_transfer_title))
        OutlinedButton(onClick = { restoreDialog = false }, enabled = !busy && !running && pending == null) {
            Text(stringResource(R.string.settings_transfer_export))
        }
        OutlinedButton(onClick = { restoreDialog = true }, enabled = !busy && !running && pending == null) {
            Text(stringResource(R.string.settings_transfer_import))
        }
    }
    restoreDialog?.let { restore ->
        fun dismiss() { password = ""; repeated = ""; restoreDialog = null }
        AlertDialog(
            onDismissRequest = { dismiss() },
            title = { Text(stringResource(if (restore) R.string.settings_transfer_import else R.string.settings_transfer_export)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (restore) R.string.settings_transfer_import_info else R.string.settings_transfer_export_info))
                    OutlinedTextField(value = password, onValueChange = { password = it },
                        label = { Text(stringResource(R.string.settings_transfer_password)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    if (!restore) {
                        OutlinedTextField(value = repeated, onValueChange = { repeated = it },
                            label = { Text(stringResource(R.string.settings_transfer_repeat)) }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = password.length >= 12 && (restore || password == repeated), onClick = {
                    pending = password.toCharArray()
                    dismiss()
                    if (restore) open.launch(arrayOf("*/*")) else create.launch("forgemind-settings.fms")
                }) { Text(stringResource(R.string.settings_transfer_choose)) }
            },
            dismissButton = { TextButton(onClick = { dismiss() }) { Text(stringResource(android.R.string.cancel)) } }
        )
    }
}
