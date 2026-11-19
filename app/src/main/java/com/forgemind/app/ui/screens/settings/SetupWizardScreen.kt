package com.forgemind.app.ui.screens.settings

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.forgemind.app.R
import com.forgemind.app.accessibility.ForgeAccessibilityService
import com.forgemind.app.i18n.AppLanguage
import com.forgemind.app.setup.SetupStep
import com.forgemind.app.setup.SetupMode
import com.forgemind.app.ui.MainViewModel
import com.forgemind.app.ui.screens.chat.SemanticModelDialog
import com.forgemind.app.ui.theme.ForgeColors

@StringRes
private fun SetupStep.title(): Int = when (this) {
    SetupStep.WELCOME -> R.string.setup_welcome
    SetupStep.MODELS -> R.string.settings_tab_providers
    SetupStep.ENVIRONMENT -> R.string.settings_tab_envs
    SetupStep.PERMISSIONS -> R.string.setup_permissions
    SetupStep.MCP -> R.string.settings_tab_mcp
    SetupStep.VOICE -> R.string.settings_tab_voice
    SetupStep.AGENT -> R.string.settings_tab_agent_web
    SetupStep.APPEARANCE -> R.string.settings_group_appearance
    SetupStep.REVIEW -> R.string.setup_review
}

@StringRes
private fun SetupStep.description(): Int = when (this) {
    SetupStep.WELCOME -> R.string.setup_welcome_body
    SetupStep.MODELS -> R.string.setup_models_body
    SetupStep.ENVIRONMENT -> R.string.setup_environment_body
    SetupStep.PERMISSIONS -> R.string.setup_permissions_body
    SetupStep.MCP -> R.string.setup_mcp_body
    SetupStep.VOICE -> R.string.setup_voice_body
    SetupStep.AGENT -> R.string.setup_agent_body
    SetupStep.APPEARANCE -> R.string.setup_appearance_body
    SetupStep.REVIEW -> R.string.setup_review_body
}

@Composable
fun SetupWizardScreen(
    vm: MainViewModel,
    onPickSafFolder: () -> Unit,
    onExportZip: () -> Unit,
    onImportZip: () -> Unit,
    onLanguageChanged: () -> Unit
) {
    val progress by vm.setupWizard.progress.collectAsState()
    val transferBusy by vm.settingsTransferBusy.collectAsState()
    val step = progress.step
    val steps = SetupStep.entries
    val index = steps.indexOf(step)
    BackHandler {
        if (!transferBusy) {
            if (index > 0) vm.setupWizard.visit(steps[index - 1]) else vm.setupWizard.defer()
        }
    }
    Column(Modifier.fillMaxSize().testTag("setup_wizard")) {
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleLarge,
            color = ForgeColors.TextPrimary, modifier = Modifier.padding(16.dp))
        LinearProgressIndicator(progress = { (index + 1f) / steps.size }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            steps.forEach { target ->
                FilterChip(selected = step == target, enabled = !transferBusy,
                    onClick = { vm.setupWizard.visit(target) }, label = { Text(stringResource(target.title())) })
            }
        }
        key(step) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.setup_step_count, index + 1, steps.size), color = ForgeColors.TextSecondary)
                Text(stringResource(step.title()), style = MaterialTheme.typography.headlineSmall,
                    color = ForgeColors.TextPrimary)
                Text(stringResource(step.description()), color = ForgeColors.TextSecondary)
                if (step !in setOf(SetupStep.WELCOME, SetupStep.PERMISSIONS, SetupStep.REVIEW)) {
                    Text(stringResource(R.string.setup_save_hint), style = MaterialTheme.typography.bodySmall,
                        color = ForgeColors.AccentAmber)
                }
                when (step) {
                    SetupStep.WELCOME -> {
                        SetupMode.entries.forEach { mode ->
                            FilterChip(selected = progress.mode == mode, onClick = { vm.setupWizard.mode(mode) },
                                label = { Text(stringResource(if (mode == SetupMode.MANUAL)
                                    R.string.setup_manual else R.string.setup_automatic)) })
                        }
                        Text(stringResource(if (progress.mode == SetupMode.AUTOMATIC)
                            R.string.setup_automatic_body else R.string.setup_manual_body), color = ForgeColors.TextSecondary)
                        SetupLanguage(vm, onLanguageChanged)
                        SettingsTransferControls(vm)
                    }
                    SetupStep.MODELS -> ProvidersSettingsPane(vm)
                    SetupStep.ENVIRONMENT -> {
                        TermuxSetup(vm)
                        EnvironmentsSettingsPane(vm, onPickSafFolder, showLegacyBridge = false)
                    }
                    SetupStep.PERMISSIONS -> SetupPermissions(vm)
                    SetupStep.MCP -> McpSettingsPane(vm)
                    SetupStep.VOICE -> VoiceSettingsPane(vm)
                    SetupStep.AGENT -> {
                        var semanticDialog by remember { mutableStateOf(false) }
                        OutlinedButton(onClick = { semanticDialog = true }) { Text(stringResource(R.string.setup_semantic)) }
                        if (semanticDialog) SemanticModelDialog(vm) { semanticDialog = false }
                        AgentAndWebSettingsPane(vm, onExportZip, onImportZip)
                    }
                    SetupStep.APPEARANCE -> {
                        SetupLanguage(vm, onLanguageChanged)
                        ThemeSettingsPane(vm)
                    }
                    SetupStep.REVIEW -> SetupReview(vm)
                }
            }
        }
        HorizontalDivider(color = ForgeColors.Divider)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { vm.setupWizard.defer() }, enabled = !transferBusy,
                modifier = Modifier.testTag("setup_later")) { Text(stringResource(R.string.setup_later)) }
            Row {
                if (index > 0) TextButton(onClick = { vm.setupWizard.visit(steps[index - 1]) }, enabled = !transferBusy) {
                    Text(stringResource(R.string.action_back))
                }
                Button(onClick = {
                    if (step == SetupStep.REVIEW) vm.setupWizard.complete()
                    else vm.setupWizard.visit(steps[index + 1])
                }, enabled = !transferBusy, modifier = Modifier.testTag("setup_next")) {
                    Text(stringResource(if (step == SetupStep.REVIEW) R.string.setup_finish else R.string.setup_next))
                }
            }
        }
    }
}

@Composable
private fun SetupLanguage(vm: MainViewModel, onLanguageChanged: () -> Unit) {
    val current by vm.appLanguage.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            val language = AppLanguage.fromCode(current)
            Text(stringResource(R.string.settings_language) + ": " +
                if (language == AppLanguage.SYSTEM) stringResource(R.string.settings_lang_system) else language.displayName)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            AppLanguage.entries.forEach { language ->
                DropdownMenuItem(text = { Text(if (language == AppLanguage.SYSTEM)
                    stringResource(R.string.settings_lang_system) else language.displayName) }, onClick = {
                    expanded = false
                    vm.setAppLanguage(language.code)
                    onLanguageChanged()
                })
            }
        }
    }
}

/** Re-read actual system grants after returning from Android Settings or a permission dialog. */
@Composable
private fun resumeRevision(): Int {
    val owner = LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return revision
}

private fun openSettings(context: Context, vm: MainViewModel, intent: Intent) {
    runCatching { context.startActivity(intent) }.onFailure {
        vm.showToast(context.getString(R.string.setup_settings_unavailable))
    }
}

private fun appSettings(context: Context, packageName: String = context.packageName) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

@Composable
private fun SetupPermissionCard(@StringRes title: Int, @StringRes explanation: Int, granted: Boolean,
    onOpen: () -> Unit, onAppSettings: (() -> Unit)? = null) {
    Card(colors = CardDefaults.cardColors(containerColor = ForgeColors.SurfaceCard), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(title), fontWeight = FontWeight.SemiBold)
            Text(stringResource(explanation), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(if (granted) R.string.setup_enabled else R.string.setup_not_enabled),
                color = if (granted) ForgeColors.AccentEmerald else ForgeColors.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpen) { Text(stringResource(R.string.setup_configure)) }
                onAppSettings?.let { action ->
                    TextButton(onClick = action) { Text(stringResource(R.string.setup_app_settings)) }
                }
            }
        }
    }
}

@Composable
private fun SetupPermissions(vm: MainViewModel) {
    val context = LocalContext.current
    val resume = resumeRevision()
    var permissionRevision by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionRevision++ }
    val requestBatch = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionRevision++ }
    val progress by vm.setupWizard.progress.collectAsState()
    val connected by ForgeAccessibilityService.isConnected.collectAsState()
    val grants = remember(resume, permissionRevision) {
        listOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            NotificationManagerCompat.from(context).areNotificationsEnabled(),
            Settings.canDrawOverlays(context),
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName),
            Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
        )
    }
    val details = { openSettings(context, vm, appSettings(context)) }
    if (progress.mode == SetupMode.AUTOMATIC) {
        LocalAdbSetupCard()
        Text(stringResource(R.string.setup_auto_permissions), color = ForgeColors.TextSecondary)
        Button(onClick = {
            val missing = buildList {
                if (!grants[0]) add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (missing.isNotEmpty()) requestBatch.launch(missing.toTypedArray())
        }, enabled = !grants[0] || (Build.VERSION.SDK_INT >= 33 && !grants[1])) {
            Text(stringResource(R.string.setup_request_basic_permissions))
        }
    }
    SetupPermissionCard(R.string.setup_screen_control, R.string.setup_screen_control_body, connected,
        { ForgeAccessibilityService.openAccessibilitySettings(context) })
    SetupPermissionCard(R.string.setup_microphone, R.string.setup_microphone_body, grants[0],
        { launcher.launch(Manifest.permission.RECORD_AUDIO) }, details)
    SetupPermissionCard(R.string.setup_notifications, R.string.setup_notifications_body, grants[1], {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else openSettings(context, vm, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    })
    SetupPermissionCard(R.string.setup_overlay, R.string.setup_overlay_body, grants[2], {
        openSettings(context, vm, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
    })
    SetupPermissionCard(R.string.setup_battery, R.string.setup_battery_body, grants[3], {
        openSettings(context, vm, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }, details)
    if (Build.VERSION.SDK_INT >= 30) SetupPermissionCard(R.string.setup_storage, R.string.setup_storage_body, grants[4], {
        openSettings(context, vm, Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")))
    }, details)
}

@Composable
private fun TermuxSetup(vm: MainViewModel) {
    val context = LocalContext.current
    val resume = resumeRevision()
    var revision by remember { mutableIntStateOf(0) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { revision++ }
    val installed = remember(resume) { runCatching { context.packageManager.getPackageInfo("com.termux", 0) }.isSuccess }
    val granted = remember(resume, revision) { vm.isTermuxIntentReady() }
    val progress by vm.setupWizard.progress.collectAsState()
    val preparing by vm.setupPreparingTermux.collectAsState()
    val preparationResult by vm.setupTermuxResult.collectAsState()
    LaunchedEffect(progress.mode, installed, granted) {
        if (progress.mode == SetupMode.AUTOMATIC && installed && granted && !vm.setupWizard.preparationAttempted) {
            vm.prepareTermuxForSetup()
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = ForgeColors.SurfaceCard), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Termux", style = MaterialTheme.typography.titleMedium)
            Text(stringResource(if (installed) R.string.setup_termux_present else R.string.setup_termux_missing))
            Button(onClick = {
                val intent = context.packageManager.getLaunchIntentForPackage("com.termux")
                    ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/termux/termux-app#installation"))
                openSettings(context, vm, intent)
            }) { Text(stringResource(if (installed) R.string.setup_open_termux else R.string.setup_get_termux)) }
            if (installed) {
                Text(stringResource(if (granted) R.string.setup_termux_granted else R.string.setup_termux_permission_needed))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { request.launch("com.termux.permission.RUN_COMMAND") }) {
                        Text(stringResource(R.string.setup_termux_grant))
                    }
                    TextButton(onClick = { openSettings(context, vm, appSettings(context)) }) {
                        Text(stringResource(R.string.setup_app_settings))
                    }
                }
                Text(stringResource(R.string.setup_termux_external))
                OutlinedButton(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                        ClipData.newPlainText("Termux", "mkdir -p ~/.termux && printf '\\nallow-external-apps = true\\n' >> ~/.termux/termux.properties"))
                    vm.showToast(context.getString(R.string.copied_to_clipboard))
                }) { Text(stringResource(R.string.setup_copy_permission_command)) }
                TextButton(onClick = { openSettings(context, vm, appSettings(context, "com.termux")) }) {
                    Text(stringResource(R.string.setup_termux_background))
                }
                if (progress.mode == SetupMode.AUTOMATIC) {
                    Text(stringResource(R.string.daemon_scope))
                    if (preparing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    preparationResult?.let { Text(it) }
                    if (!preparing && preparationResult == null && vm.setupWizard.preparationAttempted) {
                        Text(stringResource(R.string.setup_install_unknown))
                    }
                    OutlinedButton(onClick = vm::prepareTermuxForSetup, enabled = granted && !preparing) {
                        Text(stringResource(if (preparing) R.string.setup_preparing else R.string.daemon_prepare))
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupReview(vm: MainViewModel) {
    val providers by vm.providerConfigs.collectAsState()
    val activeProvider by vm.activeProviderId.collectAsState()
    val ssh by vm.sshProfiles.collectAsState()
    val connectedSsh by vm.connectedSshProfileIds.collectAsState()
    val servers by vm.mcpServers.collectAsState()
    val host by vm.mcpHostRuntime.collectAsState()
    val saf by vm.safTreeUri.collectAsState()
    val a11y by ForgeAccessibilityService.isConnected.collectAsState()
    val semantic by vm.semanticModelReady.collectAsState()
    val configuredMcp = servers.count { it.enabled }
    val lastSuccessfulMcp = servers.count { it.enabled && it.lastConnectedAt != null && it.lastError.isNullOrBlank() }
    val rows = listOf(
        R.string.settings_tab_providers to providers[activeProvider]?.modelId.orEmpty().ifBlank { stringResource(R.string.setup_not_selected) },
        R.string.settings_tab_envs to stringResource(R.string.setup_ssh_summary, ssh.count { it.enabled }, connectedSsh.size),
        R.string.settings_tab_mcp to stringResource(R.string.setup_mcp_summary, configuredMcp, lastSuccessfulMcp),
        R.string.setup_mcp_host to stringResource(if (host.isRunning) R.string.setup_running else R.string.setup_not_running),
        R.string.setup_screen_control to stringResource(if (a11y) R.string.setup_enabled else R.string.setup_not_enabled),
        R.string.setup_folder to stringResource(if (saf.isNullOrBlank()) R.string.setup_not_selected else R.string.setup_selected),
        R.string.setup_semantic to stringResource(if (semantic) R.string.setup_installed else R.string.setup_not_installed)
    )
    rows.forEach { (label, value) ->
        Card(colors = CardDefaults.cardColors(containerColor = ForgeColors.SurfaceCard), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(label), fontWeight = FontWeight.SemiBold)
                Text(value, color = ForgeColors.TextSecondary)
            }
        }
    }
    Text(stringResource(R.string.setup_review_checks), color = ForgeColors.TextSecondary)
}
