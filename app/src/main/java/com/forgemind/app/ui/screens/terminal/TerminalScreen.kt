package com.forgemind.app.ui.screens.terminal

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forgemind.app.ui.MainViewModel
import com.forgemind.app.ui.theme.ForgeColors
import com.forgemind.app.ui.theme.MonoFont

@Composable
fun TerminalScreen(vm: MainViewModel) {
    val tabs by vm.terminalTabs.collectAsState()
    val activeTabId by vm.activeTerminalTabId.collectAsState()
    val activeTab = remember(tabs, activeTabId) { tabs.firstOrNull { it.id == activeTabId } ?: tabs.firstOrNull() }
    val sshProfiles by vm.sshProfiles.collectAsState()

    var commandInput by remember { mutableStateOf("") }
    var showTermuxHelp by remember { mutableStateOf(false) }
    var showSshMenu by remember { mutableStateOf(false) }

    val outputScroll = rememberScrollState()
    LaunchedEffect(activeTab?.output?.length) {
        outputScroll.animateScrollTo(outputScroll.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070A0F))
    ) {
        // Tabs & creation toolbar
        Surface(color = ForgeColors.SurfaceDark) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tabs.forEach { tab ->
                        val selected = tab.id == activeTab?.id
                        Surface(
                            color = if (selected) ForgeColors.AccentBlue.copy(alpha = 0.25f) else ForgeColors.SurfaceElevated,
                            shape = RoundedCornerShape(6.dp),
                            border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, ForgeColors.AccentBlue) else null,
                            modifier = Modifier.clickable { vm.selectTerminalTab(tab.id) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (tab.isAlive) "● ${tab.title}" else "○ ${tab.title}",
                                    fontSize = 11.5.sp,
                                    color = if (tab.isAlive) ForgeColors.TextPrimary else ForgeColors.TextSecondary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    Icons.Rounded.Close,
                                    "Закрыть",
                                    modifier = Modifier
                                        .size(13.dp)
                                        .clickable { vm.closeTerminalTab(tab.id) },
                                    tint = ForgeColors.TextSecondary
                                )
                            }
                        }
                    }
                }

                Row {
                    IconButton(onClick = { vm.openLocalShellTab() }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Rounded.Add, "Новый Local Shell", tint = ForgeColors.AccentBlue)
                    }
                    val enabledSsh = sshProfiles.filter { it.enabled }
                    Box {
                        TextButton(
                            onClick = {
                                if (enabledSsh.size <= 1) {
                                    vm.openSshTerminalTab(enabledSsh.firstOrNull())
                                } else {
                                    showSshMenu = true
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text("+ SSH", fontSize = 12.sp, color = ForgeColors.AccentEmerald)
                        }
                        DropdownMenu(
                            expanded = showSshMenu,
                            onDismissRequest = { showSshMenu = false }
                        ) {
                            enabledSsh.forEach { prof ->
                                DropdownMenuItem(
                                    text = { Text("${prof.name} (${prof.host}:${prof.port})", fontSize = 13.sp) },
                                    onClick = {
                                        showSshMenu = false
                                        vm.openSshTerminalTab(prof)
                                    }
                                )
                            }
                        }
                    }
                    IconButton(onClick = { showTermuxHelp = true }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.HelpOutline, "Настройка Termux / SSH", tint = ForgeColors.TextSecondary)
                    }
                }
            }
        }

        // Terminal output viewport
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            if (activeTab == null) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Rounded.Terminal, null, tint = ForgeColors.AccentBlue, modifier = Modifier.size(40.dp))
                    Text("Нет открытых терминалов", color = ForgeColors.TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { vm.openLocalShellTab() }) { Text("Local Shell") }
                        OutlinedButton(onClick = { vm.openSshTerminalTab() }) { Text("Termux SSH") }
                    }
                }
            } else {
                SelectionContainer {
                    Text(
                        text = activeTab.output,
                        fontFamily = MonoFont,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = Color(0xFFD2F5DC),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(outputScroll)
                    )
                }
            }
        }

        // Quick special keys + command input
        if (activeTab != null) {
            Surface(color = ForgeColors.SurfaceDark) {
                Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        QuickKeyChip("CTRL+C") { vm.sendTerminalCtrlC(activeTab.id) }
                        QuickKeyChip("ESC") { vm.sendTerminalEsc(activeTab.id) }
                        QuickKeyChip("TAB") { vm.sendTerminalTab(activeTab.id) }
                        QuickKeyChip("↑") { vm.sendTerminalUp(activeTab.id) }
                        QuickKeyChip("/") { commandInput += "/" }
                        QuickKeyChip("~") { commandInput += "~" }
                        QuickKeyChip("|") { commandInput += "|" }
                        QuickKeyChip("ls -la") { vm.sendTerminalCommand(activeTab.id, "ls -la") }
                        IconButton(
                            onClick = { vm.clearTerminal(activeTab.id) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Rounded.CleaningServices, "Очистить", tint = ForgeColors.TextSecondary, modifier = Modifier.size(16.dp))
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = commandInput,
                            onValueChange = { commandInput = it },
                            placeholder = { Text("Введите команду shell...", fontSize = 13.sp, fontFamily = MonoFont) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = {
                                if (commandInput.isNotBlank()) {
                                    vm.sendTerminalCommand(activeTab.id, commandInput)
                                    commandInput = ""
                                }
                            }),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                vm.sendTerminalCommand(activeTab.id, commandInput)
                                commandInput = ""
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .background(ForgeColors.AccentEmerald, RoundedCornerShape(10.dp))
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Send, "Выполнить", tint = Color.Black)
                        }
                    }
                }
            }
        }
    }

    if (showTermuxHelp) {
        AlertDialog(
            onDismissRequest = { showTermuxHelp = false },
            title = { Text("Подключение Termux за 4 шага") },
            text = {
                Text(
                    text = """
                        1. В Termux выполните:
                           pkg update && pkg install -y openssh
                        2. Задайте пароль (или ~/.ssh/authorized_keys):
                           passwd
                        3. Запустите SSH-сервер в Termux:
                           sshd -p 2222   (или стандартный порт 8022)
                        4. В ForgeMind (Настройки -> Termux / SSH) включите нужные профили:
                           Termux: 127.0.0.1:2222 (или 8022)
                           SSH внешний: ваш внешний хост и порт 2222 / 22.
                    """.trimIndent(),
                    fontFamily = MonoFont,
                    fontSize = 11.5.sp,
                    color = ForgeColors.TextPrimary
                )
            },
            confirmButton = {
                Button(onClick = { showTermuxHelp = false }) { Text("Понятно") }
            }
        )
    }
}

@Composable
private fun QuickKeyChip(label: String, onClick: () -> Unit) {
    Surface(
        color = ForgeColors.SurfaceElevated,
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, ForgeColors.BorderSubtle),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = label,
            fontFamily = MonoFont,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            color = ForgeColors.TextPrimary,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
        )
    }
}
