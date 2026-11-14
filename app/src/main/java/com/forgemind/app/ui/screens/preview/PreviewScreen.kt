package com.forgemind.app.ui.screens.preview

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.forgemind.app.R
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.forgemind.app.ui.MainViewModel
import com.forgemind.app.ui.components.ForgeIconButton
import com.forgemind.app.ui.theme.ForgeColors
import com.forgemind.app.web.AgentBrowserController
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewScreen(vm: MainViewModel) {
    val watchAgent by vm.agentBrowserMode.collectAsState()
    val agentTurbo by vm.agentBrowserTurbo.collectAsState()
    val agentTabs by vm.agentBrowserTabs.collectAsState()
    val activeAgentTabId by vm.activeAgentBrowserTabId.collectAsState()
    val agentUrl by vm.aiBrowserUrl.collectAsState()
    val agentTitle by vm.agentBrowserTitle.collectAsState()
    val agentLoading by vm.agentBrowserLoading.collectAsState()
    val agentProgress by vm.agentBrowserProgress.collectAsState()
    val agentCanGoBack by vm.agentBrowserCanGoBack.collectAsState()
    val agentCanGoForward by vm.agentBrowserCanGoForward.collectAsState()
    val lastBanner by vm.agentBrowserBanner.collectAsState()
    val actionLog by vm.agentBrowserLogs.collectAsState()
    val agentConsole by vm.agentBrowserConsole.collectAsState()

    val userTabs by vm.browserController.userTabs.collectAsState()
    val activeUserTabId by vm.browserController.activeUserTabId.collectAsState()
    val userUrl by vm.userBrowserUrl.collectAsState()
    val userTitle by vm.userBrowserTitle.collectAsState()
    val userLoading by vm.userBrowserLoading.collectAsState()
    val userProgress by vm.userBrowserProgress.collectAsState()
    val userCanGoBack by vm.userBrowserCanGoBack.collectAsState()
    val userCanGoForward by vm.userBrowserCanGoForward.collectAsState()
    val userConsole by vm.userBrowserConsole.collectAsState()

    val previewPort by vm.previewPort.collectAsState()

    val currentTabs = if (watchAgent) agentTabs else userTabs
    val activeTabId = if (watchAgent) activeAgentTabId else activeUserTabId
    val maxTabsLimit = if (watchAgent) {
        AgentBrowserController.MAX_AGENT_TABS
    } else {
        AgentBrowserController.MAX_USER_TABS
    }
    val modeAccent = if (watchAgent) ForgeColors.AccentEmerald else ForgeColors.AccentBlue

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
    var showTurboInfoDialog by remember { mutableStateOf(false) }
    // "none", "agent", or "console"
    var activeDrawer by remember { mutableStateOf("none") }
    var lastDrawer by remember { mutableStateOf("agent") }
    if (activeDrawer != "none") {
        lastDrawer = activeDrawer
    }

    var tabsExpanded by remember { mutableStateOf(currentTabs.size > 1) }
    var manualCollapseTabCount by remember(watchAgent) { mutableIntStateOf(-1) }
    LaunchedEffect(watchAgent, currentTabs.size) {
        if (currentTabs.size > 1 && currentTabs.size != manualCollapseTabCount) {
            tabsExpanded = true
        } else if (currentTabs.size <= 1 && manualCollapseTabCount != -2) {
            tabsExpanded = false
        }
    }

    val pulseTransition = rememberInfiniteTransition(label = "agentBrowserPulse")
    val liveDotAlpha by pulseTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "liveDotAlpha"
    )

    LaunchedEffect(watchAgent, activeTabId) {
        urlInput = activeUrl
    }

    LaunchedEffect(activeUrl, isUrlFocused) {
        if (!isUrlFocused && activeUrl != urlInput) {
            urlInput = activeUrl
        }
    }

    fun navigateTo(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return
        vm.openBrowserUrl(trimmed, forAgent = watchAgent)
    }

    fun openNewTabForCurrentMode() {
        if (watchAgent) {
            vm.createNewAgentBrowserTab()
        } else {
            vm.browserController.createOrSelectUserTab()
        }
        urlInput = ""
    }

    val agentHasLiveActivity = agentUrl.isNotBlank() ||
        agentLoading ||
        agentTabs.any { it.url.isNotBlank() || it.isLoading }

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
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                // 1. Address & History Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    ForgeIconButton(
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                        enabled = canGoBack,
                        size = 33.dp,
                        iconSize = 17.dp,
                        onClick = { scope.launch { vm.browserController.goBack(forAgent = watchAgent) } }
                    )
                    ForgeIconButton(
                        icon = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = stringResource(R.string.action_forward),
                        enabled = canGoForward,
                        size = 33.dp,
                        iconSize = 17.dp,
                        onClick = { scope.launch { vm.browserController.goForward(forAgent = watchAgent) } }
                    )
                    ForgeIconButton(
                        icon = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.action_refresh),
                        enabled = activeUrl.isNotBlank(),
                        size = 33.dp,
                        iconSize = 17.dp,
                        onClick = {
                            scope.launch { vm.browserController.reload(forAgent = watchAgent, waitFinishMs = 5_000L) }
                        }
                    )

                    Surface(
                        color = ForgeColors.SurfaceCard,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isUrlFocused) modeAccent else ForgeColors.BorderHighlight.copy(alpha = 0.75f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(start = 10.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                if (urlInput.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.browser_address_hint),
                                        fontSize = 12.sp,
                                        color = ForgeColors.TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                BasicTextField(
                                    value = urlInput,
                                    onValueChange = { urlInput = it },
                                    singleLine = true,
                                    textStyle = TextStyle(
                                        fontSize = 12.5.sp,
                                        color = ForgeColors.TextPrimary
                                    ),
                                    cursorBrush = SolidColor(modeAccent),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                    keyboardActions = KeyboardActions(onGo = { navigateTo(urlInput) }),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onFocusChanged { isUrlFocused = it.isFocused }
                                )
                            }
                            if (urlInput.isNotBlank()) {
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(RoundedCornerShape(7.dp))
                                        .background(modeAccent.copy(alpha = 0.2f))
                                        .clickable { navigateTo(urlInput) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.ArrowForward,
                                        contentDescription = stringResource(R.string.browser_go_desc),
                                        tint = modeAccent,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                    }

                    ForgeIconButton(
                        icon = Icons.Rounded.Home,
                        contentDescription = stringResource(R.string.browser_home),
                        tint = ForgeColors.TextPrimary,
                        size = 33.dp,
                        iconSize = 17.dp,
                        onClick = {
                            urlInput = ""
                            vm.openBrowserUrl("", forAgent = watchAgent)
                        }
                    )
                }

                // 2. Crisp Single-Row Segmented Mode Switcher + Compact Utility Toolbar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        color = ForgeColors.SurfaceCard,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, ForgeColors.BorderHighlight.copy(alpha = 0.72f))
                    ) {
                        Row(
                            modifier = Modifier.padding(2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            val mySelected = !watchAgent
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (mySelected) ForgeColors.AccentBlue.copy(alpha = 0.22f)
                                        else Color.Transparent
                                    )
                                    .clickable { vm.setAgentBrowserMode(false) }
                                    .padding(horizontal = 9.dp, vertical = 5.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.browser_mode_my),
                                    fontFamily = FontFamily.SansSerif,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = if (mySelected) ForgeColors.AccentBlue else ForgeColors.TextSecondary,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }

                            val agentSelected = watchAgent
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (agentSelected) ForgeColors.AccentEmerald.copy(alpha = 0.22f)
                                        else Color.Transparent
                                    )
                                    .clickable { vm.setAgentBrowserMode(true) }
                                    .padding(horizontal = 9.dp, vertical = 5.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.browser_mode_agent_live),
                                        fontFamily = FontFamily.SansSerif,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = if (agentSelected) ForgeColors.AccentEmerald else ForgeColors.TextSecondary,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    if (agentHasLiveActivity) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .graphicsLayer { alpha = if (agentLoading) liveDotAlpha else 1f }
                                                .background(ForgeColors.AccentEmerald, CircleShape)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Compact ⚡ Turbo toggle + info pill
                        Surface(
                            color = if (agentTurbo) ForgeColors.AccentAmber.copy(alpha = 0.20f) else ForgeColors.SurfaceCard,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(
                                1.dp,
                                if (agentTurbo) ForgeColors.AccentAmber.copy(alpha = 0.55f) else ForgeColors.BorderSubtle
                            ),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .height(28.dp)
                                        .clickable { vm.setAgentBrowserTurbo(!agentTurbo) }
                                        .padding(horizontal = 7.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "⚡",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (agentTurbo) ForgeColors.AccentAmber else ForgeColors.TextSecondary
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .height(14.dp)
                                        .background(ForgeColors.BorderSubtle)
                                )
                                Box(
                                    modifier = Modifier
                                        .size(width = 24.dp, height = 28.dp)
                                        .clickable { showTurboInfoDialog = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Rounded.Info,
                                        contentDescription = stringResource(R.string.turbo_info_title),
                                        tint = if (agentTurbo) ForgeColors.AccentAmber else ForgeColors.TextSecondary,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }
                        }

                        // Compact Console / AI Action Log sheet toggle with badge
                        val drawerOpen = activeDrawer != "none"
                        val badgeCount = if (watchAgent) actionLog.size else consoleLogs.size
                        Surface(
                            color = if (drawerOpen) modeAccent.copy(alpha = 0.20f) else ForgeColors.SurfaceCard,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(
                                1.dp,
                                if (drawerOpen) modeAccent.copy(alpha = 0.55f) else ForgeColors.BorderSubtle
                            ),
                            modifier = Modifier
                                .height(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    activeDrawer = if (drawerOpen) {
                                        "none"
                                    } else {
                                        if (watchAgent) "agent" else "console"
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.Terminal,
                                    contentDescription = stringResource(R.string.browser_js_console),
                                    tint = if (drawerOpen) modeAccent else ForgeColors.TextPrimary,
                                    modifier = Modifier.size(13.dp)
                                )
                                if (badgeCount > 0) {
                                    Text(
                                        text = badgeCount.toString(),
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.5.sp,
                                        color = if (drawerOpen) modeAccent else ForgeColors.TextSecondary,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }

                        // Compact Local Preview Server pill
                        val serverRunning = previewPort > 0
                        Surface(
                            color = if (serverRunning) ForgeColors.AccentCyan.copy(alpha = 0.20f) else ForgeColors.SurfaceCard,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(
                                1.dp,
                                if (serverRunning) ForgeColors.AccentCyan.copy(alpha = 0.55f) else ForgeColors.BorderSubtle
                            ),
                            modifier = Modifier
                                .height(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { vm.startStaticPreviewServer(openInBrowser = true) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.FolderOpen,
                                    contentDescription = null,
                                    tint = if (serverRunning) ForgeColors.AccentCyan else ForgeColors.TextSecondary,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = if (serverRunning) ":$previewPort" else stringResource(R.string.browser_local_server),
                                    fontFamily = if (serverRunning) FontFamily.Monospace else FontFamily.SansSerif,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 10.5.sp,
                                    color = if (serverRunning) ForgeColors.AccentCyan else ForgeColors.TextPrimary,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }

                // 3. Collapsible Vertical Column Tabs ("столбиком") Directly Underneath Mode Switcher
                val activeTabInfo = remember(currentTabs, activeTabId) {
                    currentTabs.firstOrNull { it.id == activeTabId } ?: currentTabs.firstOrNull()
                }
                val headerDomain = remember(activeTabInfo?.url, activeUrl) {
                    formatTabDomainPreview(activeTabInfo?.url ?: activeUrl)
                }
                val headerTitle = (activeTabInfo?.title?.takeIf { it.isNotBlank() } ?: pageTitle)
                    .ifBlank { headerDomain.ifBlank { stringResource(R.string.browser_empty_tab_label) } }

                Column(modifier = Modifier.fillMaxWidth()) {
                    Surface(
                        color = ForgeColors.SurfaceCard.copy(alpha = 0.90f),
                        shape = if (tabsExpanded) {
                            RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
                        } else {
                            RoundedCornerShape(9.dp)
                        },
                        border = BorderStroke(
                            1.dp,
                            if (tabsExpanded) modeAccent.copy(alpha = 0.45f) else ForgeColors.BorderSubtle
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .clickable {
                                val next = !tabsExpanded
                                tabsExpanded = next
                                manualCollapseTabCount = if (!next) currentTabs.size else -2
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 9.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.browser_tabs_column_fmt, currentTabs.size.coerceAtLeast(1)),
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.5.sp,
                                color = modeAccent,
                                maxLines = 1,
                                softWrap = false
                            )

                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(12.dp)
                                    .background(ForgeColors.BorderSubtle)
                            )

                            if (isLoading) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .graphicsLayer { alpha = liveDotAlpha }
                                        .background(ForgeColors.AccentAmber, CircleShape)
                                )
                            }

                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text(
                                    text = headerTitle,
                                    fontFamily = FontFamily.SansSerif,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 11.5.sp,
                                    color = ForgeColors.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (headerDomain.isNotBlank() && headerDomain != headerTitle) {
                                    Text(
                                        text = "· $headerDomain",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = ForgeColors.TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            if (currentTabs.size < maxTabsLimit) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(modeAccent.copy(alpha = 0.16f))
                                        .clickable { openNewTabForCurrentMode() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Rounded.Add,
                                        contentDescription = stringResource(R.string.browser_new_tab_btn),
                                        tint = modeAccent,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }

                            Icon(
                                imageVector = if (tabsExpanded) {
                                    Icons.Rounded.KeyboardArrowUp
                                } else {
                                    Icons.Rounded.KeyboardArrowDown
                                },
                                contentDescription = null,
                                tint = ForgeColors.TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = tabsExpanded,
                        enter = expandVertically(tween(180)) + fadeIn(tween(140)),
                        exit = shrinkVertically(tween(140)) + fadeOut(tween(100))
                    ) {
                        Surface(
                            color = ForgeColors.BgDeep.copy(alpha = 0.94f),
                            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 9.dp, bottomEnd = 9.dp),
                            border = BorderStroke(1.dp, ForgeColors.BorderSubtle),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp)
                        ) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 196.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                items(
                                    items = currentTabs,
                                    key = { "${if (watchAgent) "agent" else "user"}_${it.id}" }
                                ) { tab ->
                                    val isSelected = tab.id == activeTabId
                                    val tabDomain = remember(tab.url) { formatTabDomainPreview(tab.url) }
                                    val rowTitle = tab.title.ifBlank {
                                        tabDomain.ifBlank { stringResource(R.string.browser_empty_tab_label) }
                                    }
                                    val monoSubtitle = buildString {
                                        if (watchAgent) {
                                            append("[${tab.id}] ")
                                        }
                                        append(if (tab.url.isNotBlank()) tabDomain.ifBlank { tab.url } else "—")
                                        if (tab.isLoading) {
                                            append(" · ${tab.progress}%")
                                        }
                                    }

                                    Surface(
                                        color = if (isSelected) {
                                            modeAccent.copy(alpha = 0.14f)
                                        } else {
                                            ForgeColors.SurfaceCard.copy(alpha = 0.65f)
                                        },
                                        shape = RoundedCornerShape(7.dp),
                                        border = BorderStroke(
                                            1.dp,
                                            if (isSelected) modeAccent.copy(alpha = 0.45f) else Color.Transparent
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(7.dp))
                                            .clickable {
                                                if (watchAgent) {
                                                    vm.switchAgentBrowserTab(tab.id)
                                                } else {
                                                    vm.browserController.switchUserTab(tab.id)
                                                }
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 4.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            // 3.dp Left Accent Bar on active tab
                                            Box(
                                                modifier = Modifier
                                                    .width(3.dp)
                                                    .height(28.dp)
                                                    .clip(RoundedCornerShape(2.dp))
                                                    .background(if (isSelected) modeAccent else Color.Transparent)
                                            )

                                            Column(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(1.dp)
                                            ) {
                                                Text(
                                                    text = rowTitle,
                                                    fontFamily = FontFamily.SansSerif,
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 12.5.sp,
                                                    color = if (isSelected) {
                                                        ForgeColors.TextPrimary
                                                    } else {
                                                        ForgeColors.TextPrimary.copy(alpha = 0.86f)
                                                    },
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                                ) {
                                                    if (tab.isLoading) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(5.dp)
                                                                .graphicsLayer { alpha = liveDotAlpha }
                                                                .background(ForgeColors.AccentAmber, CircleShape)
                                                        )
                                                    }
                                                    Text(
                                                        text = monoSubtitle,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 10.5.sp,
                                                        color = if (tab.isLoading) {
                                                            ForgeColors.AccentAmber
                                                        } else if (isSelected) {
                                                            modeAccent.copy(alpha = 0.95f)
                                                        } else {
                                                            ForgeColors.TextSecondary
                                                        },
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }

                                            Box(
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .clip(CircleShape)
                                                    .clickable {
                                                        if (watchAgent) {
                                                            vm.closeAgentBrowserTab(tab.id)
                                                        } else {
                                                            vm.browserController.closeUserTab(tab.id)
                                                        }
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    Icons.Rounded.Close,
                                                    contentDescription = null,
                                                    tint = ForgeColors.TextSecondary,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                if (currentTabs.size < maxTabsLimit) {
                                    item(key = "new_tab_action_row") {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(7.dp))
                                                .clickable { openNewTabForCurrentMode() }
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                Icons.Rounded.Add,
                                                contentDescription = null,
                                                tint = modeAccent,
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Text(
                                                text = stringResource(R.string.browser_new_tab_btn),
                                                fontFamily = FontFamily.SansSerif,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 12.sp,
                                                color = modeAccent
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = watchAgent,
            enter = expandVertically(tween(200)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(150)) + fadeOut(tween(120))
        ) {
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
                            .graphicsLayer { alpha = liveDotAlpha }
                            .background(ForgeColors.AccentEmerald, CircleShape)
                    )
                    Text(
                        text = lastBanner ?: stringResource(R.string.browser_agent_banner),
                        fontSize = 11.5.sp,
                        color = ForgeColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = isLoading,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(200))
        ) {
            LinearProgressIndicator(
                progress = { (loadProgress / 100f).coerceIn(0.05f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = modeAccent
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
                    val heroTint = modeAccent
                    Surface(
                        color = heroTint.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, heroTint.copy(alpha = 0.28f)),
                        modifier = Modifier.size(68.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (watchAgent) Icons.Rounded.AutoAwesome else Icons.Rounded.Public,
                                contentDescription = null,
                                tint = heroTint,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (watchAgent) stringResource(R.string.browser_agent_title) else stringResource(R.string.browser_user_title),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = ForgeColors.TextPrimary
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (watchAgent) {
                            stringResource(R.string.browser_agent_desc)
                        } else {
                            stringResource(R.string.browser_user_desc)
                        },
                        fontSize = 13.sp,
                        color = ForgeColors.TextSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(18.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { navigateTo("https://duckduckgo.com") },
                            colors = ButtonDefaults.buttonColors(containerColor = ForgeColors.AccentBlue)
                        ) {
                            Icon(Icons.Rounded.Public, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.browser_open_search), fontSize = 12.5.sp, maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(
                            border = BorderStroke(1.dp, ForgeColors.BorderHighlight),
                            onClick = { vm.startStaticPreviewServer(openInBrowser = true) }
                        ) {
                            Icon(Icons.Rounded.FolderOpen, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.browser_local_ai_files), fontSize = 12.5.sp, color = ForgeColors.TextPrimary, maxLines = 1, softWrap = false)
                        }
                    }
                    if (!watchAgent) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            border = BorderStroke(1.dp, ForgeColors.AccentEmerald.copy(alpha = 0.65f)),
                            onClick = { vm.setAgentBrowserMode(true) }
                        ) {
                            Icon(
                                Icons.Rounded.AutoAwesome,
                                null,
                                tint = ForgeColors.AccentEmerald,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.browser_peek_agent), fontSize = 12.5.sp, color = ForgeColors.TextPrimary, maxLines = 1, softWrap = false)
                        }
                    }
                }
            } else {
                key(watchAgent, activeTabId) {
                    DisposableEffect(watchAgent, activeTabId) {
                        onDispose {
                            vm.browserController.releaseAttachedParent(forAgent = watchAgent)
                        }
                    }
                    AndroidView(
                        factory = {
                            vm.browserController.acquireAttachedWebView(
                                forAgent = watchAgent,
                                urlToLoadIfBlank = activeUrl,
                                tabId = activeTabId
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

        AnimatedVisibility(
            visible = activeDrawer != "none",
            enter = expandVertically(tween(200)) + fadeIn(tween(150)),
            exit = shrinkVertically(tween(160)) + fadeOut(tween(120))
        ) {
            val isAgentLog = lastDrawer == "agent"
            val entries = if (isAgentLog) actionLog else consoleLogs
            Column {
                HorizontalDivider(color = ForgeColors.BorderSubtle)
                Surface(color = ForgeColors.BgDeep, modifier = Modifier.height(185.dp).fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    color = if (isAgentLog) ForgeColors.AccentEmerald.copy(alpha = 0.18f) else ForgeColors.SurfaceCard,
                                    shape = RoundedCornerShape(7.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isAgentLog) ForgeColors.AccentEmerald.copy(alpha = 0.5f) else ForgeColors.BorderSubtle
                                    ),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(7.dp))
                                        .clickable { activeDrawer = "agent" }
                                ) {
                                    Text(
                                        text = stringResource(R.string.browser_agent_log_count, actionLog.size),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isAgentLog) ForgeColors.AccentEmerald else ForgeColors.TextSecondary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                                Surface(
                                    color = if (!isAgentLog) ForgeColors.AccentBlue.copy(alpha = 0.18f) else ForgeColors.SurfaceCard,
                                    shape = RoundedCornerShape(7.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (!isAgentLog) ForgeColors.AccentBlue.copy(alpha = 0.5f) else ForgeColors.BorderSubtle
                                    ),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(7.dp))
                                        .clickable { activeDrawer = "console" }
                                ) {
                                    Text(
                                        text = "${stringResource(R.string.browser_js_console)} (${consoleLogs.size})",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (!isAgentLog) ForgeColors.AccentBlue else ForgeColors.TextSecondary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        if (isAgentLog) vm.browserController.clearActionLog()
                                        else if (watchAgent) vm.browserController.clearLogs()
                                        else vm.browserController.clearUserLogs()
                                    },
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(Icons.Rounded.Delete, stringResource(R.string.action_clear), tint = ForgeColors.TextSecondary, modifier = Modifier.size(16.dp))
                                }
                                IconButton(
                                    onClick = { activeDrawer = "none" },
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(Icons.Rounded.Close, stringResource(R.string.action_cancel), tint = ForgeColors.TextSecondary, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        if (entries.isEmpty()) {
                            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(
                                    if (isAgentLog) stringResource(R.string.browser_no_agent_actions) else stringResource(R.string.browser_no_console_msgs),
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
                                        color = if (log.contains("ERROR", ignoreCase = true) || log.startsWith("ERR:")) {
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

    if (showTurboInfoDialog) {
        AlertDialog(
            onDismissRequest = { showTurboInfoDialog = false },
            title = {
                Text(
                    stringResource(R.string.turbo_info_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val statusTint = if (agentTurbo) ForgeColors.AccentAmber else ForgeColors.TextSecondary
                    Surface(
                        color = statusTint.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, statusTint.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(
                                if (agentTurbo) R.string.turbo_info_status_on else R.string.turbo_info_status_off
                            ),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (agentTurbo) ForgeColors.AccentAmber else ForgeColors.TextPrimary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                    if (agentTabs.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.browser_ai_windows_fmt, agentTabs.size),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ForgeColors.AccentEmerald
                            )
                            agentTabs.forEach { tab ->
                                val label = tab.title.ifBlank { tab.url.ifBlank { stringResource(R.string.browser_empty_tab_label) } }
                                Text(
                                    text = "• [${tab.id}${if (tab.isActive) "*" else ""}] $label",
                                    fontSize = 11.sp,
                                    color = ForgeColors.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.turbo_info_body),
                        fontSize = 12.sp,
                        color = ForgeColors.TextPrimary,
                        lineHeight = 17.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { vm.toggleAgentBrowserTurbo() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (agentTurbo) ForgeColors.SurfaceElevated else ForgeColors.AccentAmber
                    )
                ) {
                    Text(
                        stringResource(
                            if (agentTurbo) R.string.turbo_toggle_turn_off else R.string.turbo_toggle_turn_on
                        ),
                        fontSize = 12.sp,
                        color = if (agentTurbo) ForgeColors.TextPrimary else Color.Black
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showTurboInfoDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

private fun formatTabDomainPreview(rawUrl: String): String {
    val clean = rawUrl.trim()
    if (clean.isBlank() || clean.equals("about:blank", ignoreCase = true)) return ""
    val stripped = clean
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
        .trimEnd('/')
    val slashIdx = stripped.indexOf('/')
    return if (slashIdx < 0) {
        stripped
    } else {
        val host = stripped.substring(0, slashIdx)
        val path = stripped.substring(slashIdx).substringBefore('?').substringBefore('#')
        if (path.length <= 1) host else "$host${path.take(28)}"
    }
}
