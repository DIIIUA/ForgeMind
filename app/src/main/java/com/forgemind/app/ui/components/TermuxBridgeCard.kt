package com.forgemind.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.forgemind.app.R
import com.forgemind.app.bridge.RemoteTermuxClient
import com.forgemind.app.ui.MainViewModel
import kotlinx.coroutines.launch

@Composable
fun TermuxBridgeCard(vm: MainViewModel, compact: Boolean = false) {
    val context = LocalContext.current
    val client = remember { RemoteTermuxClient.get(context) }
    val status by client.status.collectAsState()
    val preparing by vm.setupPreparingTermux.collectAsState()
    val result by vm.setupTermuxResult.collectAsState()
    val scope = rememberCoroutineScope()
    if (compact) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(status, modifier = Modifier.weight(1f), maxLines = 2, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = vm.setupWizard::reopen) { Text(stringResource(R.string.setup_title)) }
        }
        return
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.daemon_status), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.daemon_scope))
            Text(result ?: status)
            if (preparing) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(enabled = !preparing, onClick = vm::prepareTermuxForSetup) { Text(stringResource(R.string.daemon_prepare)) }
            OutlinedButton(onClick = { scope.launch { client.available() } }) { Text(stringResource(R.string.adb_connect)) }
            TextButton(onClick = vm.setupWizard::reopen) { Text(stringResource(R.string.setup_title)) }
        }
    }
}
