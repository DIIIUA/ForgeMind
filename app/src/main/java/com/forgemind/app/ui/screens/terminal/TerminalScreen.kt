package com.forgemind.app.ui.screens.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import com.forgemind.app.R
import com.forgemind.app.bridge.AiTerminalState
import com.forgemind.app.bridge.TerminalTabState
import com.forgemind.app.bridge.TerminalViewMode
import com.forgemind.app.data.model.UiThemeSettings
import com.forgemind.app.ui.MainViewModel
import com.forgemind.app.ui.components.ForgeActionPill
import com.forgemind.app.ui.components.ForgeIconButton
import com.forgemind.app.ui.components.copyToClipboard
import com.forgemind.app.ui.theme.ForgeColors
import com.forgemind.app.ui.theme.MonoFont
import kotlin.math.roundToInt

@Composable
fun TerminalScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val viewMode by vm.terminalViewMode.collectAsState()
    val aiState by vm.aiTerminalState.collectAsState()
    val tabs by vm.terminalTabs.collectAsState()
    val terminalBusy by vm.terminalBusy.collectAsState()
    val activeId by vm.activeTerminalTabId.collectAsState()
    val uiTheme by vm.uiThemeSettings.collectAsState()
    val sharedAgentView = com.forgemind.app.BuildConfig.TERMUX_PLUGIN
    val bridgeClient = remember { com.forgemind.app.bridge.RemoteTermuxClient.get(context) }
    val bridgeStatus by bridgeClient.status.collectAsState()
    val directTermux = remember(bridgeStatus) { sharedAgentView || bridgeClient.installed() }
    // A null selection is intentional after closing the last manual session.
    // Do not silently fall back to the agent/server terminal.
    val activeTab = remember(tabs, activeId) { tabs.firstOrNull { it.id == activeId } }
    LaunchedEffect(sharedAgentView) {
        if (sharedAgentView) vm.openTermuxTerminalTab()
    }
    LaunchedEffect(directTermux, viewMode) {
        if (directTermux && !sharedAgentView && viewMode == TerminalViewMode.MANUAL) {
            // Reattach the helper's saved sessions once per app lifetime. Closing
            // the last tab afterwards must leave it closed, not recreate it.
            vm.restoreTermuxTerminalsOnce()
        }
    }

    var commandInput by remember { mutableStateOf("") }
    var showHelpDialog by remember { mutableStateOf(false) }
    var showColorsDialog by remember { mutableStateOf(false) }
    var sessionAction by remember { mutableStateOf<Pair<TerminalTabState, Boolean>?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ForgeColors.TerminalBg)
    ) {
        com.forgemind.app.ui.components.TermuxBridgeCard(vm, compact = true)
        // Mode Switcher Bar: Watch Agent Live vs Type Manually
        Surface(color = ForgeColors.SurfaceDark) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (sharedAgentView) {
                    Text("Termux", color = ForgeColors.TerminalPrompt, fontSize = 14.sp)
                } else {
                FilterChip(
                    selected = viewMode == TerminalViewMode.WATCH_AGENT,
                    onClick = { vm.setTerminalViewMode(TerminalViewMode.WATCH_AGENT) },
                    leadingIcon = {
                        Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(16.dp))
                    },
                    label = {
                        Text(
                            stringResource(R.string.term_mode_watch_agent),
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }
                )
                FilterChip(
                    selected = viewMode == TerminalViewMode.MANUAL,
                    onClick = { vm.setTerminalViewMode(TerminalViewMode.MANUAL) },
                    leadingIcon = {
                        Icon(Icons.Rounded.Keyboard, null, modifier = Modifier.size(16.dp))
                    },
                    label = {
                        Text(
                            stringResource(R.string.term_mode_manual),
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }
                )
                }
                Spacer(modifier = Modifier.weight(1f))
                ForgeIconButton(
                    icon = Icons.Rounded.Palette,
                    contentDescription = stringResource(R.string.term_colors_btn_desc),
                    tint = ForgeColors.TerminalPrompt,
                    size = 34.dp,
                    iconSize = 18.dp,
                    onClick = { showColorsDialog = true }
                )
                ForgeIconButton(
                    icon = Icons.AutoMirrored.Rounded.HelpOutline,
                    contentDescription = stringResource(R.string.term_help_desc),
                    tint = ForgeColors.AccentBlue,
                    size = 34.dp,
                    iconSize = 18.dp,
                    onClick = { showHelpDialog = true }
                )
            }
        }
        HorizontalDivider(color = ForgeColors.BorderSubtle)

        if (viewMode == TerminalViewMode.WATCH_AGENT && !sharedAgentView) {
            AgentLiveTerminalPane(
                state = aiState,
                onRestart = { vm.restartAiShell() },
                onCopy = {
                    if (aiState.outputLog.isNotBlank()) {
                        copyToClipboard(context, "AI Shell", aiState.outputLog)
                        vm.showToast(context.getString(R.string.copied_to_clipboard))
                    }
                },
                onClear = { vm.clearAiTerminalLog() },
                onRestore = { vm.restoreAiTerminalLog() },
                onSwitchManual = {
                    vm.setTerminalViewMode(TerminalViewMode.MANUAL)
                    if (tabs.isEmpty() && !directTermux) vm.openLocalShellTab()
                },
                modifier = Modifier.weight(1f)
            )
        } else {
            ManualTerminalPane(
                directTermux = directTermux,
                tabs = tabs,
                activeTab = activeTab,
                commandInput = commandInput,
                onCommandChange = { commandInput = it },
                onSelectTab = { vm.setTerminalViewMode(TerminalViewMode.MANUAL); vm.selectTerminalTab(it) },
                onCloseTab = { id ->
                    val tab = tabs.firstOrNull { it.id == id }
                    if (tab?.managedSession != null) sessionAction = tab to false else vm.closeTerminalTab(id)
                },
                onOpenLocal = { vm.setTerminalViewMode(TerminalViewMode.MANUAL); vm.openLocalShellTab() },
                onOpenSsh = { vm.setTerminalViewMode(TerminalViewMode.MANUAL); vm.openSshTerminalTab() },
                onOpenTermux = { vm.openTermuxTerminalTab(newSession = true) },
                onOpenAgent = { vm.openAgentTerminalTab() },
                onRestartSession = { tab -> sessionAction = tab to true },
                onKeepSession = { tab -> vm.keepTerminalSession(tab.id, tab.managedSession?.keep != true) },
                terminalBusy = terminalBusy,
                onOpenInTermux = { vm.openSessionInTermux() },
                onClearTab = { id -> vm.clearTerminal(id) },
                onCopyTab = { txt ->
                    copyToClipboard(context, "Terminal", txt)
                    vm.showToast(context.getString(R.string.copied_to_clipboard))
                },
                onShellWindow = { vm.selectTermuxShellWindow() },
                onCtrlC = { id -> vm.sendTerminalCtrlC(id) },
                onTabKey = { id -> vm.sendTerminalTab(id) },
                onEscKey = { id -> vm.sendTerminalEsc(id) },
                onUpKey = { id -> vm.sendTerminalUp(id) },
                onDownKey = { id -> vm.sendTerminalDown(id) },
                onLeftKey = { id -> vm.sendTerminalLeft(id) },
                onRightKey = { id -> vm.sendTerminalRight(id) },
                onSendCommand = { id, cmd ->
                    vm.sendTerminalCommand(id, cmd)
                    commandInput = ""
                },
                modifier = Modifier.weight(1f)
            )
        }
    }

    sessionAction?.let { (tab, restart) ->
        val displayTitle = terminalTabTitle(tab)
        AlertDialog(
            onDismissRequest = { sessionAction = null },
            title = { Text(stringResource(if (restart) R.string.term_restart_session else R.string.term_close_session)) },
            text = { Text(stringResource(if (restart && tab.managedSession?.keep == true)
                R.string.term_restart_kept_body else R.string.term_close_session_body, displayTitle)) },
            confirmButton = { TextButton(onClick = {
                sessionAction = null
                if (restart) vm.restartTerminalSession(tab.id) else vm.closeTerminalTab(tab.id)
            }, enabled = !terminalBusy) { Text(stringResource(if (restart) R.string.term_restart_session else R.string.action_close)) } },
            dismissButton = { TextButton(onClick = { sessionAction = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    if (showHelpDialog) {
        val termuxOneLiner = if (sharedAgentView) "pkg install -y tmux python git curl"
            else "pkg update -y && pkg install -y openssh && passwd && sshd -p 2222"
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { Text(stringResource(R.string.term_help_title)) },
            text = {
                Text(
                    text = stringResource(if (sharedAgentView) R.string.term_help_shared else R.string.term_help_body),
                    fontFamily = MonoFont,
                    fontSize = 12.sp
                )
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        copyToClipboard(context, "Termux SSH", termuxOneLiner)
                        vm.showToast(context.getString(R.string.copied_to_clipboard))
                    }
                ) {
                    Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(stringResource(R.string.guide_copy_termux_cmd), fontSize = 11.5.sp)
                }
            },
            confirmButton = {
                Button(onClick = { showHelpDialog = false }) {
                    Text(stringResource(R.string.action_understood))
                }
            }
        )
    }

    if (showColorsDialog) {
        TerminalQuickColorsDialog(
            theme = uiTheme,
            onSaveTheme = { vm.saveUiThemeSettings(it) },
            onDismiss = { showColorsDialog = false }
        )
    }
}

@Composable
private fun AgentLiveTerminalPane(
    state: AiTerminalState,
    onRestart: () -> Unit,
    onCopy: () -> Unit,
    onClear: () -> Unit,
    onRestore: () -> Unit,
    onSwitchManual: () -> Unit,
    modifier: Modifier = Modifier
) {
    val logScroll = rememberScrollState()
    LaunchedEffect(state.outputLog.length) {
        logScroll.animateScrollTo(logScroll.maxValue)
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Embedded Shell Status Header Card
        Surface(
            color = ForgeColors.SurfaceCard,
            border = BorderStroke(
                1.dp,
                if (state.isRunningCommand) ForgeColors.AccentEmerald.copy(alpha = 0.6f)
                else ForgeColors.BorderSubtle
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.Terminal, null, tint = ForgeColors.AccentEmerald, modifier = Modifier.size(17.dp))
                        Text(
                            stringResource(R.string.term_ai_shell_title),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ForgeColors.TextPrimary
                        )
                    }
                    Surface(
                        color = when {
                            state.isRunningCommand -> ForgeColors.AccentEmerald.copy(alpha = 0.2f)
                            state.isAlive -> ForgeColors.AccentBlue.copy(alpha = 0.18f)
                            else -> ForgeColors.SurfaceElevated
                        },
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (state.isRunningCommand) {
                                CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp, color = ForgeColors.AccentEmerald)
                                Text(stringResource(R.string.term_ai_shell_running), fontSize = 10.5.sp, color = ForgeColors.AccentEmerald)
                            } else if (state.isAlive) {
                                Text(stringResource(R.string.term_ai_shell_active), fontSize = 10.5.sp, color = ForgeColors.AccentBlue)
                            } else {
                                Text(stringResource(R.string.term_ai_shell_stopped), fontSize = 10.5.sp, color = ForgeColors.TextSecondary)
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    state.pid?.let { pid ->
                        Text("PID $pid", fontFamily = MonoFont, fontSize = 10.5.sp, color = ForgeColors.TextSecondary)
                    }
                    if (state.workDir.isNotBlank()) {
                        Text(
                            text = state.workDir,
                            fontFamily = MonoFont,
                            fontSize = 10.5.sp,
                            color = ForgeColors.TerminalPrompt,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    state.lastExitCode?.let { code ->
                        Text(
                            text = "exit=$code",
                            fontFamily = MonoFont,
                            fontSize = 10.5.sp,
                            color = if (code == 0) ForgeColors.AccentEmerald else ForgeColors.DangerRed
                        )
                    }
                    if (state.usedSshFallback) {
                        Surface(
                            color = ForgeColors.AccentAmber.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(5.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.term_ai_shell_fallback),
                                fontSize = 10.sp,
                                color = ForgeColors.AccentAmber,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                if (!state.currentCommand.isNullOrBlank()) {
                    Text(
                        text = "$ ${state.currentCommand}",
                        fontFamily = MonoFont,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ForgeColors.TerminalPrompt,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ForgeActionPill(
                        text = stringResource(R.string.term_ai_shell_restart),
                        icon = Icons.Rounded.Refresh,
                        minHeight = 32.dp,
                        onClick = onRestart
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    ForgeIconButton(
                        icon = Icons.Rounded.ContentCopy,
                        contentDescription = stringResource(R.string.action_copy),
                        tint = ForgeColors.AccentBlue,
                        size = 32.dp,
                        iconSize = 16.dp,
                        onClick = onCopy
                    )
                    if (state.canRestoreLog) {
                        ForgeActionPill(
                            text = stringResource(R.string.term_restore_output),
                            minHeight = 32.dp,
                            active = true,
                            onClick = onRestore
                        )
                    }
                    // Labelled: an icon-only clear looked like a menu and wiped the log with no way back.
                    ForgeActionPill(
                        text = stringResource(R.string.action_clear),
                        icon = Icons.Rounded.ClearAll,
                        minHeight = 32.dp,
                        enabled = state.outputLog.isNotBlank(),
                        onClick = onClear
                    )
                }
            }
        }

        // Live Stream Output Box
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .background(ForgeColors.TerminalOutputBoxBg, RoundedCornerShape(8.dp))
                .border(1.dp, ForgeColors.BorderSubtle.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
        ) {
            if (state.outputLog.isBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.term_ai_shell_empty),
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                            color = ForgeColors.TextSecondary
                        )
                        if (state.canRestoreLog) {
                            ForgeActionPill(
                                text = stringResource(R.string.term_restore_output),
                                icon = Icons.Rounded.Refresh,
                                active = true,
                                onClick = onRestore
                            )
                        }
                    }
                }
            } else {
                val styledLog = rememberStyledTerminalOutput(
                    rawText = state.outputLog,
                    textColor = ForgeColors.TerminalText,
                    promptColor = ForgeColors.TerminalPrompt
                )
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(logScroll)
                            .padding(10.dp)
                    ) {
                        Text(
                            text = styledLog,
                            fontFamily = MonoFont,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                }
            }
        }

        // Read-only watch mode footer + manual switch button
        Surface(color = ForgeColors.SurfaceDark, modifier = Modifier.padding(top = 6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.term_watch_footer),
                    fontSize = 11.sp,
                    color = ForgeColors.TextSecondary,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(8.dp))
                ForgeActionPill(
                    text = stringResource(R.string.term_switch_manual),
                    icon = Icons.Rounded.Keyboard,
                    minHeight = 32.dp,
                    onClick = onSwitchManual
                )
            }
        }
    }
}

@Composable
private fun ManualTerminalPane(
    directTermux: Boolean,
    tabs: List<TerminalTabState>,
    activeTab: TerminalTabState?,
    commandInput: String,
    onCommandChange: (String) -> Unit,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onOpenLocal: () -> Unit,
    onOpenSsh: () -> Unit,
    onOpenTermux: () -> Unit,
    onOpenAgent: () -> Unit,
    onRestartSession: (TerminalTabState) -> Unit,
    onKeepSession: (TerminalTabState) -> Unit,
    terminalBusy: Boolean,
    onOpenInTermux: () -> Unit,
    onClearTab: (String) -> Unit,
    onCopyTab: (String) -> Unit,
    onShellWindow: () -> Unit,
    onCtrlC: (String) -> Unit,
    onTabKey: (String) -> Unit,
    onEscKey: (String) -> Unit,
    onUpKey: (String) -> Unit,
    onDownKey: (String) -> Unit,
    onLeftKey: (String) -> Unit,
    onRightKey: (String) -> Unit,
    onSendCommand: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val outputScroll = rememberScrollState()
    LaunchedEffect(activeTab?.output?.length) {
        outputScroll.animateScrollTo(outputScroll.maxValue)
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Tab bar for Local & SSH interactive shells
        Surface(color = ForgeColors.SurfaceDark) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!directTermux) {
                ForgeActionPill(
                    text = stringResource(R.string.term_open_local_shell),
                    icon = Icons.Rounded.Add,
                    minHeight = 32.dp,
                    onClick = onOpenLocal
                )

                ForgeActionPill(
                    text = stringResource(R.string.term_open_ssh),
                    icon = Icons.Rounded.Terminal,
                    accentColor = ForgeColors.AccentEmerald,
                    minHeight = 32.dp,
                    onClick = onOpenSsh
                )
                }

                if (directTermux) {
                    ForgeActionPill(
                        text = stringResource(R.string.term_new_session), icon = Icons.Rounded.Add,
                        minHeight = 36.dp, onClick = onOpenTermux, enabled = !terminalBusy
                    )
                    ForgeActionPill(
                        text = stringResource(R.string.term_agent_session), icon = Icons.Rounded.AutoAwesome,
                        minHeight = 36.dp, onClick = onOpenAgent, enabled = !terminalBusy
                    )
                    ForgeActionPill(
                        text = stringResource(R.string.term_open_in_termux),
                        minHeight = 36.dp, onClick = onOpenInTermux, enabled = activeTab?.sharedSession != null && !terminalBusy
                    )
                    ForgeActionPill(text = stringResource(R.string.term_open_local_shell),
                        minHeight = 36.dp, onClick = onOpenLocal)
                    ForgeActionPill(text = stringResource(R.string.term_open_ssh),
                        minHeight = 36.dp, onClick = onOpenSsh)
                }
            }
        }
        if (terminalBusy) androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (activeTab?.managedSession != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ForgeActionPill(text = stringResource(R.string.term_restart_session), icon = Icons.Rounded.Refresh,
                    minHeight = 36.dp, enabled = !terminalBusy, onClick = { onRestartSession(activeTab) })
                FilterChip(selected = activeTab.managedSession.keep, enabled = !terminalBusy,
                    onClick = { onKeepSession(activeTab) },
                    label = { Text(stringResource(R.string.term_keep_session)) },
                    leadingIcon = { if (activeTab.managedSession.keep) Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) })
            }
        }
        Surface(color = ForgeColors.SurfaceDark) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEach { tab ->
                    val selected = tab.id == activeTab?.id
                    val displayTitle = terminalTabTitle(tab)
                    Surface(
                        color = if (selected) ForgeColors.AccentBlue.copy(alpha = 0.25f) else ForgeColors.SurfaceElevated,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(
                            1.dp,
                            if (selected) ForgeColors.AccentBlue else ForgeColors.BorderHighlight.copy(alpha = 0.65f)
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelectTab(tab.id) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(
                                        if (tab.isAlive) ForgeColors.AccentEmerald else ForgeColors.DangerRed,
                                        CircleShape
                                    )
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(displayTitle + if (tab.managedSession?.keep == true) " ✓" else "", fontSize = 11.5.sp, color = ForgeColors.TextPrimary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                Icons.Rounded.Close,
                                stringResource(R.string.term_close_tab_description, displayTitle),
                                tint = ForgeColors.TextSecondary,
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .clickable(enabled = !terminalBusy) { onCloseTab(tab.id) }
                            )
                        }
                    }
                }
            }
        }

        // Body
        if (activeTab == null) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Terminal, null, tint = ForgeColors.AccentBlue, modifier = Modifier.size(44.dp))
                    Text(stringResource(R.string.term_no_tabs), color = ForgeColors.TextSecondary, fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (directTermux) {
                            Button(onClick = onOpenTermux, enabled = !terminalBusy) { Text(stringResource(R.string.term_new_session)) }
                        } else {
                        Button(onClick = onOpenLocal) {
                            Text(stringResource(R.string.term_new_local_shell))
                        }
                        OutlinedButton(onClick = onOpenSsh) {
                            Text(stringResource(R.string.term_open_ssh))
                        }
                        }
                    }
                }
            }
        } else {
            val native = activeTab.nativeSession
            if (native != null) {
                com.forgemind.app.ui.components.LiveTerminalView(
                    session = native, revision = activeTab.revision,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            } else {
            val rawManualOut = activeTab.output.ifBlank { "$ ${activeTab.title} ready...\n" }
            val styledManualOut = rememberStyledTerminalOutput(
                rawText = rawManualOut,
                textColor = ForgeColors.TerminalText,
                promptColor = ForgeColors.TerminalPrompt
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(ForgeColors.TerminalOutputBoxBg)
            ) {
                SelectionContainer(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(outputScroll)
                        .padding(10.dp)
                ) {
                    Text(
                        text = styledManualOut,
                        fontFamily = MonoFont,
                        fontSize = 12.5.sp,
                        lineHeight = 17.5.sp
                    )
                }
            }

            }

            // These keys stay visible together, including on narrow screens.
            Row(Modifier.fillMaxWidth().background(ForgeColors.SurfaceDark).padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                QuickKeyChip("Ctrl+C") { onCtrlC(activeTab.id) }
                QuickKeyChip("Tab") { onTabKey(activeTab.id) }
                QuickKeyChip("Esc") { onEscKey(activeTab.id) }
                QuickKeyChip("←") { onLeftKey(activeTab.id) }
                QuickKeyChip("↓") { onDownKey(activeTab.id) }
                QuickKeyChip("↑") { onUpKey(activeTab.id) }
                QuickKeyChip("→") { onRightKey(activeTab.id) }
            }
            // Less frequent actions can scroll independently of the arrow keys.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ForgeColors.SurfaceDark)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (activeTab.mode == com.forgemind.app.bridge.TerminalMode.TERMUX_PTY) {
                    QuickKeyChip(stringResource(R.string.term_shell_window), onClick = onShellWindow)
                    QuickKeyChip("Ctrl+B") { activeTab.nativeSession?.write("\u0002") }
                }
                QuickKeyChip("/") { onCommandChange(commandInput + "/") }
                QuickKeyChip("-") { onCommandChange(commandInput + "-") }
                QuickKeyChip("ls -la") { onSendCommand(activeTab.id, "ls -la") }
                QuickKeyChip("pwd") { onSendCommand(activeTab.id, "pwd") }
                QuickKeyChip(stringResource(R.string.action_copy)) { onCopyTab(activeTab.output) }
                QuickKeyChip(stringResource(R.string.action_clear)) { onClearTab(activeTab.id) }
            }

            // Command input
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ForgeColors.SurfaceDark)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = commandInput,
                    onValueChange = onCommandChange,
                    prefix = {
                        Text(
                            text = "$ ",
                            fontFamily = MonoFont,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = ForgeColors.TerminalPrompt
                        )
                    },
                    placeholder = { Text(stringResource(R.string.term_cmd_hint), fontSize = 13.sp, fontFamily = MonoFont) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = MonoFont, fontSize = 13.5.sp, color = ForgeColors.TerminalText),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        onSendCommand(activeTab.id, commandInput)
                    }),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                ForgeIconButton(
                    icon = Icons.AutoMirrored.Rounded.Send,
                    contentDescription = stringResource(R.string.action_run),
                    tint = ForgeColors.AccentBlue,
                    active = commandInput.isNotBlank(),
                    size = 44.dp,
                    iconSize = 20.dp,
                    onClick = { onSendCommand(activeTab.id, commandInput) }
                )
            }
        }
    }
}

@Composable
private fun terminalTabTitle(tab: TerminalTabState): String = when {
    tab.managedSession != null -> stringResource(R.string.term_session_number, tab.managedSession.number)
    tab.sharedSession == com.forgemind.app.bridge.SharedTermuxRuntime.SESSION -> stringResource(R.string.term_agent_session)
    else -> tab.title
}

@Composable
private fun QuickKeyChip(label: String, onClick: () -> Unit) {
    Surface(
        color = ForgeColors.SurfaceElevated,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, ForgeColors.BorderHighlight.copy(alpha = 0.7f)),
        modifier = Modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            fontFamily = MonoFont,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            color = ForgeColors.TextPrimary,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun rememberStyledTerminalOutput(
    rawText: String,
    textColor: Color,
    promptColor: Color
): AnnotatedString {
    return remember(rawText, textColor, promptColor) {
        buildAnnotatedString {
            val lines = rawText.split('\n')
            lines.forEachIndexed { idx, line ->
                val trimmedStart = line.trimStart()
                if (trimmedStart.startsWith("$ ") || trimmedStart == "$") {
                    withStyle(SpanStyle(color = promptColor, fontWeight = FontWeight.SemiBold)) {
                        append(line)
                    }
                } else {
                    withStyle(SpanStyle(color = textColor)) {
                        append(line)
                    }
                }
                if (idx < lines.lastIndex) {
                    append('\n')
                }
            }
        }
    }
}

private data class QuickTermPreset(
    val id: String,
    val title: String,
    val bgHex: String,
    val textHex: String,
    val promptHex: String
)

private val QUICK_TERM_PRESETS = listOf(
    QuickTermPreset("classic", "Classic Dark", "#0A0F18", "#DCE7F3", "#38BDF8"),
    QuickTermPreset("matrix", "Matrix Green", "#030A05", "#4ADE80", "#10B981"),
    QuickTermPreset("amber_crt", "Amber CRT", "#120C02", "#FBBF24", "#F59E0B"),
    QuickTermPreset("oled_black", "OLED Black", "#000000", "#E2E8F0", "#38BDF8"),
    QuickTermPreset("cyberpunk", "Cyberpunk", "#0D0618", "#F472B6", "#06B6D4"),
    QuickTermPreset("dracula", "Dracula", "#1E1F29", "#F8F8F2", "#A855F7")
)

private val QUICK_TERM_SWATCHES = listOf(
    "#EF4444", "#EC4899", "#F97316", "#F59E0B", "#EAB308", "#10B981",
    "#14B8A6", "#06B6D4", "#38BDF8", "#4F8CFF", "#6366F1", "#8B5CF6",
    "#A855F7", "#000000", "#0B0F17", "#121826", "#1E273B", "#E2E8F0"
)

private enum class QuickTermTarget(@StringRes val labelRes: Int, val supportsAlpha: Boolean) {
    BG(R.string.theme_term_bg, true),
    TEXT(R.string.theme_term_text, false),
    PROMPT(R.string.theme_term_prompt, false)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TerminalQuickColorsDialog(
    theme: UiThemeSettings,
    onSaveTheme: (UiThemeSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTarget by remember { mutableStateOf(QuickTermTarget.BG) }
    val activeHex = when (selectedTarget) {
        QuickTermTarget.BG -> theme.terminalBgHex
        QuickTermTarget.TEXT -> theme.terminalTextHex
        QuickTermTarget.PROMPT -> theme.terminalPromptHex
    }
    var hexInput by remember(selectedTarget, activeHex) { mutableStateOf(activeHex.uppercase()) }

    fun applyHex(hex: String) {
        val updated = when (selectedTarget) {
            QuickTermTarget.BG -> theme.copy(terminalPreset = "custom", terminalBgHex = hex)
            QuickTermTarget.TEXT -> theme.copy(terminalPreset = "custom", terminalTextHex = hex)
            QuickTermTarget.PROMPT -> theme.copy(terminalPreset = "custom", terminalPromptHex = hex)
        }
        onSaveTheme(updated)
    }

    fun normalizeHex(raw: String): String? {
        val cleaned = raw.trim().removePrefix("#").uppercase()
        val expanded = when (cleaned.length) {
            3 -> buildString { cleaned.forEach { append(it); append(it) } }
            6 -> cleaned
            8 -> cleaned.takeLast(6)
            else -> return null
        }
        return if (expanded.all { it in '0'..'9' || it in 'A'..'F' }) "#$expanded" else null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.theme_section_terminal),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Live preview box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ForgeColors.TerminalOutputBoxBg, RoundedCornerShape(8.dp))
                        .border(1.dp, ForgeColors.BorderSubtle, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "$ ls -la ~/workspace",
                            fontFamily = MonoFont,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ForgeColors.TerminalPrompt
                        )
                        Text(
                            text = "drwxr-xr-x  6 agent  staff  192 Nov 14 main.kt",
                            fontFamily = MonoFont,
                            fontSize = 11.sp,
                            color = ForgeColors.TerminalText
                        )
                    }
                }

                // 6 Presets
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    QUICK_TERM_PRESETS.forEach { preset ->
                        val isSelected = theme.terminalPreset == preset.id
                        val pBg = ForgeColors.parseHexColor(preset.bgHex, ForgeColors.TerminalBg)
                        val pPrompt = ForgeColors.parseHexColor(preset.promptHex, ForgeColors.TerminalPrompt)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                onSaveTheme(
                                    theme.copy(
                                        terminalPreset = preset.id,
                                        terminalBgHex = preset.bgHex,
                                        terminalBgAlpha = 1.0f,
                                        terminalTextHex = preset.textHex,
                                        terminalPromptHex = preset.promptHex
                                    )
                                )
                            },
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSelected,
                                borderColor = ForgeColors.BorderHighlight.copy(alpha = 0.65f),
                                selectedBorderColor = pPrompt
                            ),
                            label = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .background(pBg, CircleShape)
                                            .border(1.5.dp, pPrompt, CircleShape)
                                    )
                                    Text(preset.title, fontSize = 11.sp, maxLines = 1, softWrap = false)
                                }
                            }
                        )
                    }
                }

                HorizontalDivider(color = ForgeColors.BorderSubtle)

                // Target component selector: Background / Text / Prompt ($)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    QuickTermTarget.entries.forEach { target ->
                        val sel = selectedTarget == target
                        val cHex = when (target) {
                            QuickTermTarget.BG -> theme.terminalBgHex
                            QuickTermTarget.TEXT -> theme.terminalTextHex
                            QuickTermTarget.PROMPT -> theme.terminalPromptHex
                        }
                        val dotColor = ForgeColors.parseHexColor(cHex, ForgeColors.TerminalPrompt)
                        FilterChip(
                            selected = sel,
                            onClick = { selectedTarget = target },
                            label = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(9.dp)
                                            .background(dotColor, CircleShape)
                                            .border(1.dp, Color.White.copy(alpha = 0.45f), CircleShape)
                                    )
                                    Text(stringResource(target.labelRes), fontSize = 11.sp, maxLines = 1, softWrap = false)
                                }
                            }
                        )
                    }
                }

                // 18 Quick Swatches
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    QUICK_TERM_SWATCHES.forEach { hex ->
                        val swatchColor = ForgeColors.parseHexColor(hex, ForgeColors.TerminalPrompt)
                        val isSel = activeHex.equals(hex, ignoreCase = true)
                        val checkColor = if (swatchColor.luminance() > 0.55f) Color(0xFF0B0F17) else Color.White
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(swatchColor, CircleShape)
                                .border(
                                    width = if (isSel) 2.5.dp else 1.dp,
                                    color = if (isSel) ForgeColors.AccentBlue else Color.White.copy(alpha = 0.35f),
                                    shape = CircleShape
                                )
                                .clickable {
                                    hexInput = hex
                                    applyHex(hex)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSel) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = checkColor,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }

                if (selectedTarget.supportsAlpha) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(R.string.theme_opacity_fmt, (theme.terminalBgAlpha * 100f).roundToInt()),
                            fontSize = 11.5.sp,
                            color = ForgeColors.TextPrimary
                        )
                        Slider(
                            value = theme.terminalBgAlpha.coerceIn(0.25f, 1.0f),
                            onValueChange = { alpha ->
                                onSaveTheme(
                                    theme.copy(
                                        terminalPreset = "custom",
                                        terminalBgAlpha = alpha.coerceIn(0.25f, 1.0f)
                                    )
                                )
                            },
                            valueRange = 0.25f..1.0f
                        )
                    }
                }

                // HEX input
                val normalized = normalizeHex(hexInput)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = hexInput,
                        onValueChange = { raw ->
                            hexInput = raw.filter { it == '#' || it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }.take(9)
                        },
                        label = { Text(stringResource(R.string.theme_hex_label)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    ForgeActionPill(
                        text = stringResource(R.string.theme_apply_hex),
                        accentColor = ForgeColors.AccentBlue,
                        enabled = normalized != null,
                        minHeight = 42.dp,
                        onClick = {
                            normalized?.let { valid ->
                                hexInput = valid
                                applyHex(valid)
                            }
                        }
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    val classic = QUICK_TERM_PRESETS.first()
                    onSaveTheme(
                        theme.copy(
                            terminalPreset = classic.id,
                            terminalBgHex = classic.bgHex,
                            terminalBgAlpha = 1.0f,
                            terminalTextHex = classic.textHex,
                            terminalPromptHex = classic.promptHex
                        )
                    )
                }
            ) {
                Text(stringResource(R.string.theme_reset_all_btn), fontSize = 11.5.sp)
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}
