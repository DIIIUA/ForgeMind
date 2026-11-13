package com.forgemind.app.ui.screens.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forgemind.app.R
import com.forgemind.app.bridge.AiTerminalState
import com.forgemind.app.bridge.TerminalTabState
import com.forgemind.app.bridge.TerminalViewMode
import com.forgemind.app.ui.MainViewModel
import com.forgemind.app.ui.components.copyToClipboard
import com.forgemind.app.ui.theme.ForgeColors
import com.forgemind.app.ui.theme.MonoFont

@Composable
fun TerminalScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val viewMode by vm.terminalViewMode.collectAsState()
    val aiState by vm.aiTerminalState.collectAsState()
    val tabs by vm.terminalTabs.collectAsState()
    val activeId by vm.activeTerminalTabId.collectAsState()
    val activeTab = remember(tabs, activeId) { tabs.firstOrNull { it.id == activeId } }

    var commandInput by remember { mutableStateOf("") }
    var showHelpDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ForgeColors.TerminalBg)
    ) {
        // Mode Switcher Bar: Watch Agent Live vs Type Manually
        Surface(color = ForgeColors.SurfaceDark) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = { showHelpDialog = true }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.AutoMirrored.Rounded.HelpOutline,
                        stringResource(R.string.term_help_desc),
                        tint = ForgeColors.TextSecondary
                    )
                }
            }
        }
        HorizontalDivider(color = ForgeColors.BorderSubtle)

        if (viewMode == TerminalViewMode.WATCH_AGENT) {
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
                onSwitchManual = {
                    vm.setTerminalViewMode(TerminalViewMode.MANUAL)
                    if (tabs.isEmpty()) vm.openLocalShellTab()
                },
                modifier = Modifier.weight(1f)
            )
        } else {
            ManualTerminalPane(
                tabs = tabs,
                activeTab = activeTab,
                commandInput = commandInput,
                onCommandChange = { commandInput = it },
                onSelectTab = { vm.selectTerminalTab(it) },
                onCloseTab = { vm.closeTerminalTab(it) },
                onOpenLocal = { vm.openLocalShellTab() },
                onOpenSsh = { vm.openSshTerminalTab() },
                onClearTab = { id -> vm.clearTerminal(id) },
                onCopyTab = { txt ->
                    copyToClipboard(context, "Terminal", txt)
                    vm.showToast(context.getString(R.string.copied_to_clipboard))
                },
                onCtrlC = { id -> vm.sendTerminalCtrlC(id) },
                onTabKey = { id -> vm.sendTerminalTab(id) },
                onEscKey = { id -> vm.sendTerminalEsc(id) },
                onUpKey = { id -> vm.sendTerminalUp(id) },
                onSendCommand = { id, cmd ->
                    vm.sendTerminalCommand(id, cmd)
                    commandInput = ""
                },
                modifier = Modifier.weight(1f)
            )
        }
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { Text(stringResource(R.string.term_help_title)) },
            text = {
                Text(
                    text = stringResource(R.string.term_help_body),
                    fontFamily = MonoFont,
                    fontSize = 12.sp
                )
            },
            confirmButton = {
                Button(onClick = { showHelpDialog = false }) {
                    Text(stringResource(R.string.action_understood))
                }
            }
        )
    }
}

@Composable
private fun AgentLiveTerminalPane(
    state: AiTerminalState,
    onRestart: () -> Unit,
    onCopy: () -> Unit,
    onClear: () -> Unit,
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
                            color = ForgeColors.AccentCyan,
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
                        color = ForgeColors.AccentAmber,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onRestart,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.term_ai_shell_restart), fontSize = 11.sp)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = onCopy, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Rounded.ContentCopy, stringResource(R.string.action_copy), tint = ForgeColors.AccentBlue, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onClear, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Rounded.ClearAll, stringResource(R.string.action_clear), tint = ForgeColors.TextSecondary, modifier = Modifier.size(17.dp))
                    }
                }
            }
        }

        // Live Stream Output Box
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .background(Color(0xFF06090D), RoundedCornerShape(8.dp))
        ) {
            if (state.outputLog.isBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.term_ai_shell_empty),
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        color = ForgeColors.TextSecondary
                    )
                }
            } else {
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(logScroll)
                            .padding(10.dp)
                    ) {
                        Text(
                            text = state.outputLog,
                            fontFamily = MonoFont,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = ForgeColors.TerminalText
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
                OutlinedButton(
                    onClick = onSwitchManual,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(stringResource(R.string.term_switch_manual), fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun ManualTerminalPane(
    tabs: List<TerminalTabState>,
    activeTab: TerminalTabState?,
    commandInput: String,
    onCommandChange: (String) -> Unit,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onOpenLocal: () -> Unit,
    onOpenSsh: () -> Unit,
    onClearTab: (String) -> Unit,
    onCopyTab: (String) -> Unit,
    onCtrlC: (String) -> Unit,
    onTabKey: (String) -> Unit,
    onEscKey: (String) -> Unit,
    onUpKey: (String) -> Unit,
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
                OutlinedButton(
                    onClick = onOpenLocal,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Rounded.Add, null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.term_open_local_shell), fontSize = 11.5.sp)
                }

                OutlinedButton(
                    onClick = onOpenSsh,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Rounded.Terminal, null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.term_open_ssh), fontSize = 11.5.sp)
                }

                tabs.forEach { tab ->
                    val selected = tab.id == activeTab?.id
                    Surface(
                        color = if (selected) ForgeColors.AccentBlue.copy(alpha = 0.25f) else ForgeColors.SurfaceElevated,
                        shape = RoundedCornerShape(8.dp),
                        border = if (selected) BorderStroke(1.dp, ForgeColors.AccentBlue) else null,
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
                            Text(tab.title, fontSize = 11.5.sp, color = ForgeColors.TextPrimary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                Icons.Rounded.Close,
                                stringResource(R.string.action_close),
                                tint = ForgeColors.TextSecondary,
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .clickable { onCloseTab(tab.id) }
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
                        Button(onClick = onOpenLocal) {
                            Text(stringResource(R.string.term_new_local_shell))
                        }
                        OutlinedButton(onClick = onOpenSsh) {
                            Text(stringResource(R.string.term_open_ssh))
                        }
                    }
                }
            }
        } else {
            SelectionContainer(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(outputScroll)
                    .padding(10.dp)
            ) {
                Text(
                    text = activeTab.output.ifBlank { "${activeTab.title} ready...\n" },
                    fontFamily = MonoFont,
                    fontSize = 12.5.sp,
                    lineHeight = 17.5.sp,
                    color = ForgeColors.TerminalText
                )
            }

            // Extra control keys row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ForgeColors.SurfaceDark)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                QuickKeyChip("Ctrl+C") { onCtrlC(activeTab.id) }
                QuickKeyChip("Tab") { onTabKey(activeTab.id) }
                QuickKeyChip("Esc") { onEscKey(activeTab.id) }
                QuickKeyChip("↑") { onUpKey(activeTab.id) }
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
                    placeholder = { Text(stringResource(R.string.term_cmd_hint), fontSize = 13.sp, fontFamily = MonoFont) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = MonoFont, fontSize = 13.5.sp, color = ForgeColors.TextPrimary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        onSendCommand(activeTab.id, commandInput)
                    }),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                IconButton(onClick = { onSendCommand(activeTab.id, commandInput) }) {
                    Icon(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.action_run), tint = ForgeColors.AccentBlue)
                }
            }
        }
    }
}

@Composable
private fun QuickKeyChip(label: String, onClick: () -> Unit) {
    Surface(
        color = ForgeColors.SurfaceElevated,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            fontFamily = MonoFont,
            fontSize = 11.5.sp,
            color = ForgeColors.TextPrimary,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
        )
    }
}
