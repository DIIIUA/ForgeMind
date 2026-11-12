package com.forgemind.app.ui.screens.preview

import android.annotation.SuppressLint
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.forgemind.app.ui.MainViewModel
import com.forgemind.app.ui.theme.ForgeColors
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewScreen(vm: MainViewModel) {
    val watchAgent by vm.agentBrowserMode.collectAsState()
    val agentUrl by vm.aiBrowserUrl.collectAsState()
    val agentTitle by vm.agentBrowserTitle.collectAsState()
    val agentLoading by vm.agentBrowserLoading.collectAsState()
    val agentProgress by vm.agentBrowserProgress.collectAsState()
    val agentCanGoBack by vm.agentBrowserCanGoBack.collectAsState()
    val agentCanGoForward by vm.agentBrowserCanGoForward.collectAsState()
    val lastBanner by vm.agentBrowserBanner.collectAsState()
    val actionLog by vm.agentBrowserLogs.collectAsState()
    val agentConsole by vm.agentBrowserConsole.collectAsState()

    val userUrl by vm.userBrowserUrl.collectAsState()
    val userTitle by vm.userBrowserTitle.collectAsState()
    val userLoading by vm.userBrowserLoading.collectAsState()
    val userProgress by vm.userBrowserProgress.collectAsState()
    val userCanGoBack by vm.userBrowserCanGoBack.collectAsState()
    val userCanGoForward by vm.userBrowserCanGoForward.collectAsState()
    val userConsole by vm.userBrowserConsole.collectAsState()

    val previewPort by vm.previewPort.collectAsState()

    val activeUrl = if (watchAgent) agentUrl else userUrl
    val pageTitle = if (watchAgent) agentTitle else userTitle
    val isLoading = if (watchAgent) agentLoading else userLoading
    val loadProgress = if (watchAgent) agentProgress else userProgress
    val canGoBack = if (watchAgent) agentCanGoBack else userCanGoBack
    val canGoForward = if (watchAgent) agentCanGoForward else userCanGoForward
    val consoleLogs = if (watchAgent) agentConsole else userConsole

    val scope = rememberCoroutineScope()
    var urlInput by remember { mutableStateOf("") }
    var isUrlFocused by remember { mutableStateOf(false) }
    // "none", "agent", or "console"
    var activeDrawer by remember { mutableStateOf("none") }

    LaunchedEffect(activeUrl, watchAgent, isUrlFocused) {
        if (!isUrlFocused && activeUrl != urlInput) {
            urlInput = activeUrl
        }
    }

    fun navigateTo(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return
        vm.openBrowserUrl(trimmed, forAgent = watchAgent)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ForgeColors.BgDeep)
    ) {
        Surface(color = ForgeColors.SurfaceDark) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconButton(
                        enabled = canGoBack,
                        onClick = { scope.launch { vm.browserController.goBack(forAgent = watchAgent) } },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            "Назад",
                            tint = if (canGoBack) ForgeColors.TextPrimary else ForgeColors.TextSecondary.copy(alpha = 0.35f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        enabled = canGoForward,
                        onClick = { scope.launch { vm.browserController.goForward(forAgent = watchAgent) } },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowForward,
                            "Вперёд",
                            tint = if (canGoForward) ForgeColors.TextPrimary else ForgeColors.TextSecondary.copy(alpha = 0.35f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        enabled = activeUrl.isNotBlank(),
                        onClick = {
                            scope.launch { vm.browserController.reload(forAgent = watchAgent, waitFinishMs = 5_000L) }
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Refresh,
                            "Обновить",
                            tint = if (activeUrl.isNotBlank()) ForgeColors.TextPrimary else ForgeColors.TextSecondary.copy(alpha = 0.35f),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        singleLine = true,
                        placeholder = { Text("Адрес сайта или поиск...", fontSize = 12.sp) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { navigateTo(urlInput) }),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { isUrlFocused = it.isFocused }
                    )

                    IconButton(
                        onClick = {
                            urlInput = ""
                            vm.openBrowserUrl("", forAgent = watchAgent)
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Home,
                            "Стартовая страница",
                            tint = ForgeColors.TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            activeDrawer = if (activeDrawer == "console") "none" else "console"
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Terminal,
                            "JS Консоль",
                            tint = if (activeDrawer == "console") ForgeColors.AccentEmerald else ForgeColors.TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Mode switcher + Agent Live + Preview server bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = !watchAgent,
                        onClick = { vm.setAgentBrowserMode(false) },
                        label = { Text("Обычный", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ForgeColors.AccentBlue.copy(alpha = 0.22f),
                            selectedLabelColor = ForgeColors.AccentBlue
                        ),
                        modifier = Modifier.height(28.dp)
                    )
                    FilterChip(
                        selected = watchAgent,
                        onClick = { vm.setAgentBrowserMode(!watchAgent) },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp)
                            )
                        },
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text("Агентный (Live)", fontSize = 11.sp)
                                if (agentUrl.isNotBlank() || agentLoading) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(ForgeColors.AccentEmerald, CircleShape)
                                    )
                                }
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ForgeColors.AccentEmerald.copy(alpha = 0.24f),
                            selectedLabelColor = ForgeColors.AccentEmerald,
                            selectedLeadingIconColor = ForgeColors.AccentEmerald
                        ),
                        modifier = Modifier.height(28.dp)
                    )
                    FilterChip(
                        selected = activeDrawer == "agent",
                        onClick = {
                            activeDrawer = if (activeDrawer == "agent") "none" else "agent"
                        },
                        label = { Text("Ход ИИ (${actionLog.size})", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ForgeColors.AccentBlue.copy(alpha = 0.20f),
                            selectedLabelColor = ForgeColors.AccentBlue
                        ),
                        modifier = Modifier.height(28.dp)
                    )
                    OutlinedButton(
                        onClick = { vm.startStaticPreviewServer(openInBrowser = true) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(Icons.Rounded.FolderOpen, null, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (previewPort > 0) "HTTP :$previewPort" else "Лок. сервер",
                            fontSize = 11.sp
                        )
                    }
                }

                if (pageTitle.isNotBlank() && activeUrl.isNotBlank()) {
                    Text(
                        text = pageTitle,
                        fontSize = 11.sp,
                        color = ForgeColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                }
            }
        }

        if (watchAgent) {
            Surface(
                color = ForgeColors.AccentEmerald.copy(alpha = 0.14f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(ForgeColors.AccentEmerald, CircleShape)
                    )
                    Text(
                        text = lastBanner ?: "Просмотр фонового браузера ИИ (ваш обычный браузер не затронут)",
                        fontSize = 11.5.sp,
                        color = ForgeColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (isLoading) {
            LinearProgressIndicator(
                progress = { (loadProgress / 100f).coerceIn(0.05f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = if (watchAgent) ForgeColors.AccentEmerald else ForgeColors.AccentBlue
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (activeUrl.isBlank()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        if (watchAgent) Icons.Rounded.AutoAwesome else Icons.Rounded.Public,
                        contentDescription = null,
                        tint = if (watchAgent) ForgeColors.AccentEmerald else ForgeColors.AccentBlue,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (watchAgent) "Агентный браузер (Live)" else "Ваш браузер",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = ForgeColors.TextPrimary
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (watchAgent) {
                            "Изолированный живой WebView ИИ: вы видите изнутри, как агент открывает сайты, читает DOM и кликает, не мешая вашему браузеру."
                        } else {
                            "Ваш личный браузер. Агентный браузер ИИ работает полностью в фоне на своём отдельном WebView и не пересекается с вашей вкладкой."
                        },
                        fontSize = 13.sp,
                        color = ForgeColors.TextSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { navigateTo("https://duckduckgo.com") },
                            colors = ButtonDefaults.buttonColors(containerColor = ForgeColors.AccentBlue)
                        ) {
                            Icon(Icons.Rounded.Public, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Открыть поиск", fontSize = 12.5.sp)
                        }
                        OutlinedButton(onClick = { vm.startStaticPreviewServer(openInBrowser = true) }) {
                            Icon(Icons.Rounded.FolderOpen, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Локальные файлы ИИ", fontSize = 12.5.sp)
                        }
                    }
                    if (!watchAgent) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { vm.setAgentBrowserMode(true) }) {
                            Icon(
                                Icons.Rounded.AutoAwesome,
                                null,
                                tint = ForgeColors.AccentEmerald,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Заглянуть в Агентный (Live)", fontSize = 12.5.sp)
                        }
                    }
                }
            } else {
                key(watchAgent) {
                    DisposableEffect(watchAgent) {
                        onDispose {
                            vm.browserController.releaseAttachedParent(forAgent = watchAgent)
                        }
                    }
                    AndroidView(
                        factory = {
                            vm.browserController.acquireAttachedWebView(
                                forAgent = watchAgent,
                                urlToLoadIfBlank = activeUrl
                            )
                        },
                        update = {
                            // WebView state is driven directly by AgentBrowserController.
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        if (activeDrawer != "none") {
            val isAgentLog = activeDrawer == "agent"
            val entries = if (isAgentLog) actionLog else consoleLogs
            HorizontalDivider(color = ForgeColors.BorderSubtle)
            Surface(color = ForgeColors.BgDeep, modifier = Modifier.height(180.dp).fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (isAgentLog) "Ход Агентного браузера (${entries.size})"
                            else if (watchAgent) "JS Консоль ИИ (${entries.size})"
                            else "JS Консоль (${entries.size})",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isAgentLog) ForgeColors.AccentEmerald else ForgeColors.TextSecondary
                        )
                        IconButton(
                            onClick = {
                                if (isAgentLog) vm.browserController.clearActionLog()
                                else if (watchAgent) vm.browserController.clearLogs()
                                else vm.browserController.clearUserLogs()
                            },
                            modifier = Modifier.size(26.dp)
                        ) {
                            Icon(Icons.Rounded.Delete, "Очистить", tint = ForgeColors.TextSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                    if (entries.isEmpty()) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                if (isAgentLog) "Пока нет действий ИИ в браузере" else "Сообщений консоли пока нет",
                                fontSize = 11.5.sp,
                                color = ForgeColors.TextSecondary
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(entries) { log ->
                                Text(
                                    log,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (log.contains("ERROR") || log.contains("ошиб", ignoreCase = true)) {
                                        ForgeColors.DangerRed
                                    } else {
                                        ForgeColors.TextPrimary
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
