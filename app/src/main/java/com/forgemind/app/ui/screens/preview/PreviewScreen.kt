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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val activeUrl by vm.aiBrowserUrl.collectAsState()
    val agentMode by vm.agentBrowserMode.collectAsState()
    val pageTitle by vm.agentBrowserTitle.collectAsState()
    val isLoading by vm.agentBrowserLoading.collectAsState()
    val loadProgress by vm.agentBrowserProgress.collectAsState()
    val lastBanner by vm.agentBrowserBanner.collectAsState()
    val actionLog by vm.agentBrowserLogs.collectAsState()
    val consoleLogs by vm.agentBrowserConsole.collectAsState()
    val previewPort by vm.previewPort.collectAsState()

    val scope = rememberCoroutineScope()
    var urlInput by remember { mutableStateOf("") }
    // "none", "agent", or "console"
    var activeDrawer by remember { mutableStateOf("none") }

    LaunchedEffect(activeUrl) {
        if (activeUrl != urlInput) {
            urlInput = activeUrl
        }
    }

    fun navigateTo(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return
        vm.openBrowserUrl(trimmed)
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
                        onClick = { scope.launch { vm.browserController.goBack() } },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            "Назад",
                            tint = ForgeColors.TextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = { scope.launch { vm.browserController.goForward() } },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowForward,
                            "Вперёд",
                            tint = ForgeColors.TextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            if (activeUrl.isNotBlank()) {
                                scope.launch { vm.browserController.reload(waitFinishMs = 5_000L) }
                            }
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Refresh,
                            "Обновить",
                            tint = ForgeColors.TextPrimary,
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
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(
                        onClick = {
                            urlInput = ""
                            vm.openBrowserUrl("")
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
                        selected = !agentMode,
                        onClick = { vm.setAgentBrowserMode(false) },
                        label = { Text("Обычный", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ForgeColors.AccentBlue.copy(alpha = 0.22f),
                            selectedLabelColor = ForgeColors.AccentBlue
                        ),
                        modifier = Modifier.height(28.dp)
                    )
                    FilterChip(
                        selected = agentMode,
                        onClick = { vm.setAgentBrowserMode(!agentMode) },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp)
                            )
                        },
                        label = { Text("Агентный (Live)", fontSize = 11.sp) },
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

        if (agentMode || !lastBanner.isNullOrBlank()) {
            Surface(
                color = if (agentMode) ForgeColors.AccentEmerald.copy(alpha = 0.14f) else ForgeColors.SurfaceDark,
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
                            .background(
                                if (agentMode) ForgeColors.AccentEmerald else ForgeColors.AccentBlue,
                                CircleShape
                            )
                    )
                    Text(
                        text = lastBanner ?: "Агентный режим включён — действия ИИ отображаются в реальном времени",
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
                color = if (agentMode) ForgeColors.AccentEmerald else ForgeColors.AccentBlue
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
                        if (agentMode) Icons.Rounded.AutoAwesome else Icons.Rounded.Public,
                        contentDescription = null,
                        tint = if (agentMode) ForgeColors.AccentEmerald else ForgeColors.AccentBlue,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (agentMode) "Агентный браузер (Live)" else "Браузер ИИ",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = ForgeColors.TextPrimary
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (agentMode) {
                            "Единый живой WebView: вы можете наблюдать прямо здесь, как ИИ открывает сайты, читает DOM, вводит текст, кликает и снимает скриншоты."
                        } else {
                            "Здесь открываются веб-страницы, с которыми работает ИИ (browser_open / browser_agent_*), локальный HTTP-сервер и любые сайты."
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
                    if (!agentMode) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { vm.setAgentBrowserMode(true) }) {
                            Icon(
                                Icons.Rounded.AutoAwesome,
                                null,
                                tint = ForgeColors.AccentEmerald,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Включить Агентный (Live)", fontSize = 12.5.sp)
                        }
                    }
                }
            } else {
                DisposableEffect(Unit) {
                    onDispose {
                        vm.browserController.releaseAttachedParent()
                    }
                }
                AndroidView(
                    factory = {
                        vm.browserController.acquireAttachedWebView(activeUrl)
                    },
                    update = {
                        // Shared live WebView is controlled directly by AgentBrowserController.
                    },
                    modifier = Modifier.fillMaxSize()
                )
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
                            if (isAgentLog) "Ход Агентного браузера (${entries.size})" else "JS Консоль (${entries.size})",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isAgentLog) ForgeColors.AccentEmerald else ForgeColors.TextSecondary
                        )
                        IconButton(
                            onClick = {
                                if (isAgentLog) vm.browserController.clearActionLog()
                                else vm.browserController.clearLogs()
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
