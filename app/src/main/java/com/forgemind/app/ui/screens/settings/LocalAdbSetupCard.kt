package com.forgemind.app.ui.screens.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.forgemind.app.R
import com.forgemind.app.setup.*
import kotlinx.coroutines.launch

@Composable
fun LocalAdbSetupCard() {
    if (Build.VERSION.SDK_INT < 30) return
    val context = LocalContext.current
    val client = remember { LocalAdb.get(context) }
    val state by client.state.collectAsState()
    val scope = rememberCoroutineScope()
    var manual by remember { mutableStateOf(false) }
    var pairPort by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf(SetupGrant.OVERLAY, SetupGrant.ACCESSIBILITY, SetupGrant.SECURE_SETTINGS, SetupGrant.TERMUX_COMMAND)) }
    fun begin() { context.startForegroundService(Intent(context, AdbPairingService::class.java)) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) begin() else manual = true }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.adb_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.adb_intro))
            Text(stringResource(if (state.connected) R.string.adb_connected else if (state.paired) R.string.adb_paired else R.string.adb_not_paired))
            if (state.message.isNotBlank()) Text(state.message)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }) {
                Text(stringResource(R.string.adb_developer_settings))
            }
            Button(enabled = !state.busy, onClick = {
                if (Build.VERSION.SDK_INT >= 33 && !NotificationManagerCompat.from(context).areNotificationsEnabled())
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                else begin()
            }) { Text(stringResource(R.string.adb_pair_notification)) }
            OutlinedButton(enabled = !state.busy, onClick = { scope.launch { client.connect() } }) { Text(stringResource(R.string.adb_connect)) }
            TextButton(onClick = { manual = !manual }) { Text(stringResource(R.string.adb_manual_ports)) }
            if (manual) {
                Text(stringResource(R.string.adb_split_screen))
                OutlinedTextField(pairPort, { pairPort = it }, label = { Text(stringResource(R.string.adb_pair_port)) }, singleLine = true)
                OutlinedTextField(code, { code = it }, label = { Text(stringResource(R.string.adb_code)) }, singleLine = true)
                Button(enabled = !state.busy, onClick = {
                    val p = runCatching { LocalAdbPolicy.port(pairPort) }.getOrNull()
                    val c = runCatching { LocalAdbPolicy.pairingCode(code) }.getOrNull()
                    error = p == null || c == null
                    if (!error) { code = ""; scope.launch { client.pair(p!!, c!!) } }
                }) { Text(stringResource(R.string.adb_pair)) }
                OutlinedTextField(connectPort, { connectPort = it }, label = { Text(stringResource(R.string.adb_connect_port)) }, singleLine = true)
                OutlinedButton(enabled = !state.busy, onClick = {
                    val p = runCatching { LocalAdbPolicy.port(connectPort) }.getOrNull()
                    error = p == null
                    if (p != null) scope.launch { client.connect(p) }
                }) { Text(stringResource(R.string.adb_connect)) }
                if (error) Text(stringResource(R.string.adb_invalid_input))
            }
            val labels = listOf(R.string.setup_overlay, R.string.setup_screen_control, R.string.adb_secure_settings,
                R.string.adb_termux_commands, R.string.setup_microphone, R.string.setup_notifications, R.string.setup_storage, R.string.setup_battery)
            SetupGrant.entries.forEachIndexed { i, grant ->
                Row {
                    Checkbox(grant in selected, enabled = !state.busy, onCheckedChange = { selected = if (it) selected + grant else selected - grant })
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(labels[i]))
                        state.results[grant]?.let { Text(stringResource(if (it) R.string.setup_enabled else R.string.setup_not_enabled)) }
                    }
                }
            }
            Button(enabled = state.connected && !state.busy && selected.isNotEmpty(), onClick = { scope.launch { client.grant(selected) } }) {
                Text(stringResource(R.string.adb_apply))
            }
            if (state.connected) TextButton(onClick = client::disconnect) { Text(stringResource(R.string.adb_disconnect)) }
        }
    }
}
