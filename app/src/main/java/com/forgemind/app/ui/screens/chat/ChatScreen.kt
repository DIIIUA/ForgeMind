package com.forgemind.app.ui.screens.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PictureInPicture
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forgemind.app.R
import com.forgemind.app.bridge.WorkspaceManager
import com.forgemind.app.data.db.ChatFolderEntity
import com.forgemind.app.data.db.ChatMessageEntity
import com.forgemind.app.data.db.ChatSessionEntity
import com.forgemind.app.data.model.ChatAttachment
import com.forgemind.app.data.model.ContextWindowResolver
import com.forgemind.app.data.model.LlmRole
import com.forgemind.app.data.model.ModelInfo
import com.forgemind.app.data.model.ProviderCategory
import com.forgemind.app.data.model.ProviderConfig
import com.forgemind.app.data.model.ProviderType
import com.forgemind.app.data.model.ReasoningEffort
import com.forgemind.app.data.model.WorkspaceEnvType
import com.forgemind.app.data.model.WorkspaceFileItem
import com.forgemind.app.data.prefs.SettingsRepository
import com.forgemind.app.llm.ModelScanner
import com.forgemind.app.ui.AppSection
import com.forgemind.app.ui.MainViewModel
import com.forgemind.app.ui.localizedName
import com.forgemind.app.ui.resolvedDisplayName
import com.forgemind.app.ui.components.ChatMessageBubble
import com.forgemind.app.ui.components.ConnectionStatusStrip
import com.forgemind.app.ui.components.ContextUsageBar
import com.forgemind.app.ui.components.ForgeActionPill
import com.forgemind.app.ui.components.ForgeIconButton
import com.forgemind.app.ui.components.PendingApprovalBanner
import com.forgemind.app.ui.components.PreparedModelCatalog
import com.forgemind.app.ui.components.ScrollToBottomFab
import com.forgemind.app.ui.components.formatBytes
import com.forgemind.app.ui.theme.ForgeColors
import com.forgemind.app.ui.theme.MonoFont
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    vm: MainViewModel,
    onPickAttachments: () -> Unit,
    onRequestVoiceRecord: () -> Unit,
    onSaveAttachment: (ChatAttachment) -> Unit,
    onExportMarkdown: () -> Unit,
    onExportZip: () -> Unit,
    onExportSelectedZip: (Set<String>) -> Unit = {},
    onExportSelectedToDeviceFolder: (Set<String>) -> Unit = {},
    onImportZip: () -> Unit,
    onPickSafFolder: () -> Unit,
    onToggleFloatingChat: () -> Unit = {},
    isFloatingChatActive: Boolean = false
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val currentSession by vm.currentSession.collectAsState()
    val branchView by vm.resolvedBranchView.collectAsState()
    val activeSessions by vm.activeSessions.collectAsState()
    val archivedSessions by vm.archivedSessions.collectAsState()
    val folders by vm.folders.collectAsState()
    val searchResults by vm.searchResults.collectAsState()
    val providerConfigs by vm.providerConfigs.collectAsState()
    val hiddenBuiltInProviders by vm.hiddenBuiltInProviders.collectAsState()
    val providerHealth by vm.providerHealth.collectAsState()
    val scannedModels by vm.scannedModels.collectAsState()
    val favoriteModels by vm.favoriteModels.collectAsState()
    val safTreeUri by vm.safTreeUri.collectAsState()

    val isAgentRunning by vm.isAgentRunning.collectAsState()
    val statusBanner by vm.agentStatusBanner.collectAsState()
    val pendingApprovals by vm.pendingApprovals.collectAsState()
    val composerText by vm.composerText.collectAsState()
    val draftAttachments by vm.draftAttachments.collectAsState()
    val isRecording by vm.isRecording.collectAsState()
    val voiceAmplitude by vm.voiceAmplitude.collectAsState()
    val isSpeakingTts by vm.isSpeakingTts.collectAsState()
    val speakingMessageId by vm.speakingMessageId.collectAsState()
    val voiceSettings by vm.voiceSettings.collectAsState()
    val agentBrowserTurbo by vm.agentBrowserTurbo.collectAsState()
    val agentBrowserTabs by vm.agentBrowserTabs.collectAsState()

    var showModelSheet by remember { mutableStateOf(false) }
    var promptDialogSession by remember { mutableStateOf<ChatSessionEntity?>(null) }
    var showCompactConfirmDialog by remember { mutableStateOf(false) }
    var showAiAttachDialog by remember { mutableStateOf(false) }
    var showTurboInfoDialog by remember { mutableStateOf(false) }
    var editingMessage by remember { mutableStateOf<ChatMessageEntity?>(null) }

    val orderedVisibleProviders = remember(providerConfigs, hiddenBuiltInProviders) {
        SettingsRepository.orderedProviderConfigs(providerConfigs, hiddenBuiltInProviders)
    }
    val session = currentSession
    val rawProvId = session?.providerId ?: ProviderType.OPENROUTER.id
    val activeProvCfg = orderedVisibleProviders.firstOrNull { it.providerId == rawProvId }
        ?: providerConfigs[rawProvId]?.takeIf { rawProvId.lowercase() !in hiddenBuiltInProviders }
        ?: orderedVisibleProviders.firstOrNull()
        ?: (providerConfigs[rawProvId] ?: ProviderConfig(rawProvId))
    val activeProvId = activeProvCfg.providerId
    val activeProvType = ProviderType.fromId(activeProvId)
    val contextWindow = activeProvCfg.contextWindow

    val visibleMessages = remember(branchView.activeMessages) {
        branchView.activeMessages.filter { it.role != LlmRole.TOOL.name && it.role != LlmRole.SYSTEM.name }
    }
    val mcpServers by vm.mcpServers.collectAsState()
    val sshProfiles by vm.sshProfiles.collectAsState()
    val connectedSshIds by vm.connectedSshProfileIds.collectAsState()
    val sshReachability by vm.sshReachability.collectAsState()
    val mcpHostSettings by vm.mcpHostSettings.collectAsState()
    val mcpHostRuntime by vm.mcpHostRuntime.collectAsState()
    val connectionStripExpanded by vm.connectionStripExpanded.collectAsState()

    val listState = rememberLazyListState()
    var autoFollowBottom by remember(session?.id) { mutableStateOf(true) }
    var isProgrammaticScroll by remember { mutableStateOf(false) }
    val isAtBottom by remember {
        derivedStateOf {
            !listState.canScrollForward
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canGoDown) ->
                if (scrolling && !isProgrammaticScroll) {
                    autoFollowBottom = !canGoDown
                }
            }
    }
    val lastMsg = visibleMessages.lastOrNull()
    LaunchedEffect(visibleMessages.size, lastMsg?.id) {
        if (visibleMessages.isNotEmpty() && (lastMsg?.role == LlmRole.USER.name || autoFollowBottom)) {
            if (lastMsg?.role == LlmRole.USER.name) autoFollowBottom = true
            isProgrammaticScroll = true
            try {
                listState.scrollToItem(visibleMessages.size)
            } finally {
                isProgrammaticScroll = false
            }
        }
    }
    LaunchedEffect(
        lastMsg?.id,
        lastMsg?.content?.length,
        lastMsg?.thinking?.length,
        lastMsg?.toolCardsJson?.length,
        lastMsg?.attachmentsJson?.length,
        listState.layoutInfo.viewportSize.height
    ) {
        if (visibleMessages.isNotEmpty() && autoFollowBottom && !listState.isScrollInProgress) {
            isProgrammaticScroll = true
            try {
                listState.scrollToItem(visibleMessages.size)
            } finally {
                isProgrammaticScroll = false
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = ForgeColors.SurfaceDark,
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                ChatDrawerContent(
                    activeSessions = activeSessions,
                    archivedSessions = archivedSessions,
                    folders = folders,
                    searchResults = searchResults,
                    currentSessionId = session?.id,
                    onSearchQuery = { vm.searchSessions(it) },
                    onNewChat = { folderId ->
                        vm.createNewSession(folderId)
                        scope.launch { drawerState.close() }
                    },
                    onSelectSession = { sid ->
                        vm.selectSession(sid)
                        scope.launch { drawerState.close() }
                    },
                    onCreateFolder = { vm.createFolder(it) },
                    onAssignFolder = { sid, fid -> vm.assignSessionFolder(sid, fid) },
                    onTogglePin = { sid, pinned -> vm.togglePinSession(sid, pinned) },
                    onToggleArchive = { sid, arch -> vm.toggleArchiveSession(sid, arch) },
                    onDeleteSession = { vm.deleteSession(it) },
                    onEditInstruction = { targetSession -> promptDialogSession = targetSession },
                    onBatchDelete = { ids -> vm.deleteSessions(ids) },
                    onBatchArchive = { ids, arch -> vm.archiveSessions(ids, arch) },
                    onBatchAssignFolder = { ids, fid -> vm.assignSessionsFolder(ids, fid) },
                    onBatchExportZip = onExportSelectedZip,
                    onBatchExportDeviceFolder = onExportSelectedToDeviceFolder,
                    onExportMarkdown = onExportMarkdown,
                    onExportZip = onExportZip,
                    onImportZip = onImportZip
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ForgeColors.BgDeep)
        ) {
            // Top bar
            Surface(color = ForgeColors.SurfaceDark) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        ForgeIconButton(
                            icon = Icons.Rounded.Menu,
                            contentDescription = stringResource(R.string.chat_open_drawer),
                            onClick = { scope.launch { drawerState.open() } },
                            size = 36.dp,
                            iconSize = 19.dp
                        )
                        val sheetArrowRotation by animateFloatAsState(
                            targetValue = if (showModelSheet) 180f else 0f,
                            animationSpec = tween(180, easing = FastOutSlowInEasing),
                            label = "topModelArrow"
                        )
                        Surface(
                            color = ForgeColors.SurfaceElevated,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, ForgeColors.BorderHighlight.copy(alpha = 0.85f)),
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { showModelSheet = true }
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val readyDotColor = resolveProviderStatusDotColor(
                                        config = activeProvCfg,
                                        activeProviderId = activeProvId,
                                        providerHealth = providerHealth
                                    ) ?: ForgeColors.AccentAmber
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(readyDotColor, CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = (session?.modelId ?: activeProvCfg.modelId).substringAfterLast('/'),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = ForgeColors.TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (activeProvCfg.enableThinking) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            Icons.Rounded.Psychology,
                                            contentDescription = null,
                                            tint = ForgeColors.AccentAmber,
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                    Icon(
                                        Icons.Rounded.KeyboardArrowDown,
                                        null,
                                        tint = ForgeColors.AccentBlue,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .rotate(sheetArrowRotation)
                                    )
                                }
                                val provDisplay = activeProvCfg.resolvedDisplayName(LocalContext.current)
                                val catBadge = when (activeProvCfg.effectiveCategory()) {
                                    ProviderCategory.LOCAL_LAN -> "LAN · "
                                    ProviderCategory.CUSTOM -> "CUSTOM · "
                                    ProviderCategory.CLOUD -> ""
                                }
                                Text(
                                    text = "$catBadge$provDisplay",
                                    fontSize = 10.sp,
                                    color = ForgeColors.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 12.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        val openAiWindowsCount = agentBrowserTabs.size
                        if (agentBrowserTurbo || openAiWindowsCount > 1) {
                            val badgeTint = if (agentBrowserTurbo) ForgeColors.AccentAmber else ForgeColors.AccentEmerald
                            val badgeLabel = when {
                                isSpeakingTts && agentBrowserTurbo -> "⚡"
                                agentBrowserTurbo && openAiWindowsCount > 1 -> "⚡·$openAiWindowsCount"
                                agentBrowserTurbo -> "⚡ Turbo"
                                else -> "AI·$openAiWindowsCount"
                            }
                            Surface(
                                color = badgeTint.copy(alpha = 0.18f),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, badgeTint.copy(alpha = 0.6f)),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { showTurboInfoDialog = true }
                            ) {
                                Text(
                                    text = badgeLabel,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = badgeTint,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                )
                            }
                        }

                        if (isSpeakingTts) {
                            val speedLabel = if ((voiceSettings.ttsSpeed * 100f + 0.5f).toInt() % 10 == 0) {
                                String.format(java.util.Locale.US, "%.1fx", voiceSettings.ttsSpeed)
                            } else {
                                String.format(java.util.Locale.US, "%.2fx", voiceSettings.ttsSpeed)
                            }
                            Surface(
                                color = ForgeColors.AccentAmber.copy(alpha = 0.18f),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, ForgeColors.AccentAmber.copy(alpha = 0.65f)),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { vm.cycleTtsSpeed() }
                            ) {
                                Text(
                                    text = speedLabel,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ForgeColors.AccentAmber,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp)
                                )
                            }
                            ForgeIconButton(
                                icon = Icons.AutoMirrored.Rounded.VolumeOff,
                                contentDescription = stringResource(R.string.tts_stop_speaking),
                                onClick = { vm.stopSpeaking() },
                                active = true,
                                activeColor = ForgeColors.AccentAmber,
                                tint = ForgeColors.AccentAmber,
                                size = 36.dp,
                                iconSize = 18.dp
                            )
                        }

                        ForgeIconButton(
                            icon = Icons.Rounded.PictureInPicture,
                            contentDescription = stringResource(R.string.chat_floating_btn),
                            onClick = onToggleFloatingChat,
                            active = isFloatingChatActive,
                            activeColor = ForgeColors.AccentEmerald,
                            tint = if (isFloatingChatActive) ForgeColors.AccentEmerald else ForgeColors.TextPrimary,
                            size = 36.dp,
                            iconSize = 18.dp
                        )
                    }
                }
            }

            HorizontalDivider(color = ForgeColors.BorderSubtle.copy(alpha = 0.45f))

            ConnectionStatusStrip(
                mcpServers = mcpServers,
                sshProfiles = sshProfiles,
                connectedSshIds = connectedSshIds,
                safTreeUri = safTreeUri,
                isTermuxIntentReady = remember { vm.isTermuxIntentReady() },
                decodeTools = { vm.decodeMcpTools(it) },
                expanded = connectionStripExpanded,
                onExpandedChange = vm::setConnectionStripExpanded,
                sshReachability = sshReachability,
                mcpHostSettings = mcpHostSettings,
                mcpHostRuntime = mcpHostRuntime,
                onRefreshAll = { vm.refreshConnectionIndicators() },
                onToggleMcp = { srv, enabled -> vm.toggleMcpServer(srv, enabled) },
                onTestMcp = { id -> vm.testMcpServer(id) },
                onTestSsh = { prof ->
                    vm.testSshConnection(prof) { res -> vm.showToast(res) }
                },
                onOpenSettings = {
                    vm.setConnectionStripExpanded(false)
                    vm.selectSection(AppSection.SETTINGS)
                }
            )

            HorizontalDivider(color = ForgeColors.BorderSubtle.copy(alpha = 0.35f))

            ContextUsageBar(
                estimatedTokens = session?.estimatedContextTokens ?: 0,
                contextWindow = contextWindow,
                onCompactClick = { showCompactConfirmDialog = true }
            )

            // Agent active banner (smooth expand + progress bar)
            AnimatedVisibility(
                visible = isAgentRunning,
                enter = expandVertically(tween(200, easing = FastOutSlowInEasing)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(170, easing = FastOutSlowInEasing)) + fadeOut(tween(130))
            ) {
                Surface(color = Color(0xFF13223D), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp),
                            color = ForgeColors.AccentBlue,
                            trackColor = Color.Transparent
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = ForgeColors.AccentBlue
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = statusBanner ?: stringResource(R.string.chat_agent_working),
                                    fontSize = 12.sp,
                                    color = ForgeColors.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            ForgeActionPill(
                                text = stringResource(R.string.action_stop_full),
                                icon = Icons.Rounded.Stop,
                                onClick = { vm.stopAgent() },
                                active = true,
                                accentColor = ForgeColors.DangerRed,
                                contentColor = ForgeColors.DangerRed,
                                minHeight = 28.dp
                            )
                        }
                    }
                }
            }

            // Pending tool approval
            AnimatedVisibility(
                visible = pendingApprovals.isNotEmpty(),
                enter = expandVertically(tween(210, easing = FastOutSlowInEasing)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(170, easing = FastOutSlowInEasing)) + fadeOut(tween(130))
            ) {
                pendingApprovals.firstOrNull()?.let { pending ->
                    PendingApprovalBanner(
                        approval = pending,
                        onAllow = { vm.resolveApproval(pending.id, true) },
                        onDeny = { vm.resolveApproval(pending.id, false) }
                    )
                }
            }

            // Messages feed
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (visibleMessages.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(ForgeColors.AccentBlue.copy(alpha = 0.14f), CircleShape)
                                .border(1.dp, ForgeColors.AccentBlue.copy(alpha = 0.35f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = ForgeColors.AccentBlue,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "ForgeMind AI",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = ForgeColors.TextPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.chat_hero_desc),
                            fontSize = 13.5.sp,
                            color = ForgeColors.TextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                stringResource(R.string.chat_chip_env_title) to stringResource(R.string.chat_chip_env_prompt),
                                stringResource(R.string.chat_chip_files_title) to stringResource(R.string.chat_chip_files_prompt),
                                stringResource(R.string.chat_chip_mcp_title) to stringResource(R.string.chat_chip_mcp_prompt)
                            ).forEach { (chipTitle, presetPrompt) ->
                                FilterChip(
                                    selected = false,
                                    onClick = { vm.updateComposerText(presetPrompt) },
                                    label = { Text(chipTitle, fontSize = 12.sp) }
                                )
                            }
                        }
                        if (activeProvCfg.requiresApiKey() && activeProvCfg.parsedKeys().isEmpty()) {
                            Spacer(modifier = Modifier.height(14.dp))
                            OutlinedButton(onClick = { vm.selectSection(AppSection.SETTINGS) }) {
                                Icon(Icons.Rounded.Tune, null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.chat_add_key_cta))
                            }
                        } else if (!activeProvCfg.isReadyForUse()) {
                            Spacer(modifier = Modifier.height(14.dp))
                            OutlinedButton(onClick = { vm.selectSection(AppSection.SETTINGS) }) {
                                Icon(Icons.Rounded.Tune, null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.chat_setup_lan_cta))
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        itemsIndexed(visibleMessages, key = { _, it -> it.id }) { idx, msg ->
                            val isUserMsg = msg.role == LlmRole.USER.name
                            val hasCards = msg.toolCardsJson.isNotBlank() && msg.toolCardsJson != "[]"
                            val isAssistantInterrupted = !isUserMsg && !msg.isStreaming && (
                                msg.isError ||
                                    msg.content.trimEnd().endsWith("*(stopped)*") ||
                                    msg.content.trimEnd().endsWith("*(остановлено)*") ||
                                    (msg.content.isBlank() && !hasCards)
                                )
                            val isLastUserWithoutReply = isUserMsg && idx == visibleMessages.lastIndex
                            val isFinalAssistantInTurn = !isUserMsg && !msg.isStreaming && (
                                idx == visibleMessages.lastIndex ||
                                    visibleMessages[idx + 1].role == LlmRole.USER.name
                                )
                            val canRetry = !isAgentRunning && (isFinalAssistantInTurn || isAssistantInterrupted || isLastUserWithoutReply)
                            val canEdit = !isAgentRunning && isUserMsg
                            val switchInfo = branchView.switchInfoByMessageId[msg.id]

                            ChatMessageBubble(
                                message = msg,
                                isSpeakingThis = isSpeakingTts && speakingMessageId == msg.id,
                                canRetry = canRetry,
                                canEdit = canEdit,
                                branchSwitch = switchInfo,
                                onSelectBranch = { pKey, childId -> vm.selectMessageBranch(pKey, childId) },
                                onRetryClick = { vm.retryMessage(msg) },
                                onEditClick = { editingMessage = msg },
                                onSpeakClick = { vm.speakMessage(msg.id, msg.content) },
                                onCopyText = { vm.showToast(it) },
                                onSaveAttachment = onSaveAttachment,
                                onRestoreSnapshot = { vm.restoreSnapshot(it) },
                                onError = { vm.showToast(it) }
                            )
                        }
                        item(key = "__bottom_anchor__") {
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }
                    ScrollToBottomFab(
                        visible = !isAtBottom && visibleMessages.isNotEmpty(),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 12.dp, bottom = 10.dp),
                        onClick = {
                            autoFollowBottom = true
                            scope.launch {
                                isProgrammaticScroll = true
                                try {
                                    listState.animateScrollToItem(visibleMessages.size)
                                } finally {
                                    isProgrammaticScroll = false
                                }
                            }
                        }
                    )
                }
            }

            // Bottom composer
            HorizontalDivider(color = ForgeColors.BorderSubtle.copy(alpha = 0.55f))
            Surface(color = ForgeColors.SurfaceDark) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                    AnimatedVisibility(
                        visible = draftAttachments.isNotEmpty(),
                        enter = expandVertically(tween(180)) + fadeIn(tween(160)),
                        exit = shrinkVertically(tween(150)) + fadeOut(tween(120))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            draftAttachments.forEach { att ->
                                Surface(
                                    color = ForgeColors.SurfaceElevated,
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, ForgeColors.BorderSubtle)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Rounded.AttachFile, null, modifier = Modifier.size(14.dp), tint = ForgeColors.AccentBlue)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "${att.name} (${formatBytes(att.sizeBytes)})",
                                            fontSize = 11.5.sp,
                                            color = ForgeColors.TextPrimary,
                                            maxLines = 1,
                                            modifier = Modifier.widthIn(max = 160.dp),
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            Icons.Rounded.Close,
                                            stringResource(R.string.action_delete),
                                            tint = ForgeColors.TextSecondary,
                                            modifier = Modifier
                                                .size(15.dp)
                                                .clickable { vm.removeDraftAttachment(att.id) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = isRecording,
                        enter = expandVertically(tween(180)) + fadeIn(tween(160)),
                        exit = shrinkVertically(tween(150)) + fadeOut(tween(120))
                    ) {
                        val recTransition = rememberInfiniteTransition(label = "recPulse")
                        val recAlpha by recTransition.animateFloat(
                            initialValue = 0.35f,
                            targetValue = 1.0f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(550, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "recDotAlpha"
                        )
                        val smoothedAmp by animateFloatAsState(
                            targetValue = voiceAmplitude.coerceIn(0.06f, 1f),
                            animationSpec = tween(90),
                            label = "recAmp"
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                                .background(Color(0xFF2B1319), RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .alpha(recAlpha)
                                    .clip(CircleShape)
                                    .background(ForgeColors.DangerRed)
                            )
                            Text(stringResource(R.string.chat_recording), fontSize = 12.sp, color = ForgeColors.TextPrimary)
                            LinearProgressIndicator(
                                progress = { smoothedAmp },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = ForgeColors.DangerRed
                            )
                            Text(stringResource(R.string.action_stop_full), fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = ForgeColors.AccentAmber, modifier = Modifier.clickable { onRequestVoiceRecord() })
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ForgeIconButton(
                            icon = Icons.Rounded.Add,
                            contentDescription = stringResource(R.string.chat_attach_desc),
                            onClick = { showAiAttachDialog = true },
                            tint = ForgeColors.TextPrimary,
                            size = 42.dp,
                            iconSize = 21.dp,
                            cornerRadius = 12.dp
                        )

                        ForgeIconButton(
                            icon = if (isRecording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                            contentDescription = stringResource(R.string.chat_voice_input_desc),
                            onClick = onRequestVoiceRecord,
                            active = isRecording,
                            activeColor = ForgeColors.DangerRed,
                            tint = if (isRecording) ForgeColors.DangerRed else ForgeColors.AccentCyan,
                            size = 42.dp,
                            iconSize = 20.dp,
                            cornerRadius = 12.dp
                        )

                        OutlinedTextField(
                            value = composerText,
                            onValueChange = { vm.updateComposerText(it) },
                            placeholder = { Text(stringResource(R.string.chat_input_hint), fontSize = 13.5.sp, maxLines = 1) },
                            maxLines = 5,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp)
                        )

                        val canSend = composerText.isNotBlank() || draftAttachments.isNotEmpty()
                        val actionBtnBg by animateColorAsState(
                            targetValue = when {
                                isAgentRunning -> ForgeColors.DangerRed
                                canSend -> ForgeColors.AccentBlue
                                else -> ForgeColors.SurfaceElevated
                            },
                            animationSpec = tween(200),
                            label = "composerActionBg"
                        )
                        val actionBtnBorder = when {
                            isAgentRunning -> ForgeColors.DangerRed
                            canSend -> ForgeColors.AccentBlue
                            else -> ForgeColors.BorderHighlight
                        }
                        IconButton(
                            onClick = {
                                if (isAgentRunning) vm.stopAgent() else vm.sendMessage()
                            },
                            enabled = isAgentRunning || canSend,
                            modifier = Modifier
                                .size(44.dp)
                                .background(actionBtnBg, CircleShape)
                                .border(1.dp, actionBtnBorder, CircleShape)
                        ) {
                            AnimatedContent(
                                targetState = isAgentRunning,
                                transitionSpec = {
                                    (fadeIn(tween(150)) + scaleIn(tween(150), 0.78f))
                                        .togetherWith(fadeOut(tween(120)) + scaleOut(tween(120), 0.78f))
                                },
                                label = "composerActionIcon"
                            ) { running ->
                                Icon(
                                    imageVector = if (running) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.Send,
                                    contentDescription = if (running) stringResource(R.string.action_stop) else stringResource(R.string.action_send),
                                    tint = if (running || canSend) Color.White else ForgeColors.TextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showModelSheet) {
        ModelPickerBottomSheet(
            currentProviderId = activeProvId,
            currentModelId = session?.modelId ?: activeProvCfg.modelId,
            providerConfigs = providerConfigs,
            hiddenBuiltInProviders = hiddenBuiltInProviders,
            providerHealth = providerHealth,
            scannedModels = scannedModels,
            favoriteModels = favoriteModels,
            onScanModels = { cfg, cb -> vm.scanModelsForProvider(cfg, cb) },
            onToggleFavorite = { provId, modelId -> vm.toggleFavoriteModel(provId, modelId) },
            onUpdateReasoningEffort = { provId, effort -> vm.updateReasoningEffort(provId, effort) },
            onSetContextAuto = { provId, modelId -> vm.setModelContextWindowAuto(provId, modelId) },
            onSetContextManual = { provId, modelId, tokens -> vm.setModelContextWindowManual(provId, modelId, tokens) },
            onSelectModel = { provId, modelId ->
                vm.updateSessionModel(provId, modelId)
                showModelSheet = false
            },
            onOpenSettings = {
                showModelSheet = false
                vm.selectSection(AppSection.SETTINGS)
            },
            onDismiss = { showModelSheet = false }
        )
    }

    if (showCompactConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showCompactConfirmDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.compact_confirm_title),
                    fontSize = 16.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.compact_confirm_body),
                    fontSize = 13.sp,
                    color = ForgeColors.TextPrimary,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCompactConfirmDialog = false
                        vm.compactContextNow()
                    }
                ) {
                    Text(stringResource(R.string.compact_confirm_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCompactConfirmDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
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
                    val statusTint = if (agentBrowserTurbo) ForgeColors.AccentAmber else ForgeColors.TextSecondary
                    Surface(
                        color = statusTint.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, statusTint.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(
                                if (agentBrowserTurbo) R.string.turbo_info_status_on else R.string.turbo_info_status_off
                            ),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (agentBrowserTurbo) ForgeColors.AccentAmber else ForgeColors.TextPrimary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                    if (agentBrowserTabs.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.browser_ai_windows_fmt, agentBrowserTabs.size),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ForgeColors.AccentEmerald
                            )
                            agentBrowserTabs.forEach { tab ->
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
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(onClick = {
                        showTurboInfoDialog = false
                        vm.setAgentBrowserMode(true)
                        vm.selectSection(AppSection.PREVIEW)
                    }) {
                        Text(stringResource(R.string.turbo_open_agent_browser), fontSize = 12.sp, maxLines = 1)
                    }
                    Button(
                        onClick = { vm.toggleAgentBrowserTurbo() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (agentBrowserTurbo) ForgeColors.SurfaceElevated else ForgeColors.AccentAmber
                        )
                    ) {
                        Text(
                            stringResource(
                                if (agentBrowserTurbo) R.string.turbo_toggle_turn_off else R.string.turbo_toggle_turn_on
                            ),
                            fontSize = 12.sp,
                            maxLines = 1,
                            color = if (agentBrowserTurbo) ForgeColors.TextPrimary else Color.Black
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showTurboInfoDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    promptDialogSession?.let { targetSession ->
        var promptDraft by remember(targetSession.id, targetSession.customSystemPrompt) {
            mutableStateOf(targetSession.customSystemPrompt)
        }
        val rolePresets = listOf(
            stringResource(R.string.role_preset_clear) to "",
            stringResource(R.string.role_preset_coder_label) to stringResource(R.string.role_preset_coder_prompt),
            stringResource(R.string.role_preset_research_label) to stringResource(R.string.role_preset_research_prompt),
            stringResource(R.string.role_preset_concise_label) to stringResource(R.string.role_preset_concise_prompt),
            stringResource(R.string.role_preset_operator_label) to stringResource(R.string.role_preset_operator_prompt)
        )
        AlertDialog(
            onDismissRequest = { promptDialogSession = null },
            title = { Text(stringResource(R.string.chat_prompt_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        rolePresets.forEach { (label, presetText) ->
                            FilterChip(
                                selected = promptDraft.trim() == presetText.trim() && (presetText.isNotEmpty() || promptDraft.isBlank()),
                                onClick = { promptDraft = presetText },
                                label = { Text(label, fontSize = 11.5.sp) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = promptDraft,
                        onValueChange = { promptDraft = it },
                        placeholder = { Text(stringResource(R.string.chat_prompt_dialog_hint)) },
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    vm.updateSessionCustomPrompt(promptDraft, targetSession.id)
                    promptDialogSession = null
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { promptDialogSession = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    if (showAiAttachDialog) {
        AiHomeAttachDialog(
            vm = vm,
            safTreeUri = safTreeUri,
            onPickFromDevice = {
                showAiAttachDialog = false
                onPickAttachments()
            },
            onPickSafFolder = {
                showAiAttachDialog = false
                onPickSafFolder()
            },
            onDismiss = { showAiAttachDialog = false }
        )
    }

    editingMessage?.let { targetMsg ->
        var draftText by remember(targetMsg.id) { mutableStateOf(targetMsg.content) }
        AlertDialog(
            onDismissRequest = { editingMessage = null },
            title = { Text(stringResource(R.string.chat_edit_msg_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draftText,
                        onValueChange = { draftText = it },
                        placeholder = { Text(stringResource(R.string.chat_edit_msg_hint)) },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(R.string.chat_edit_msg_note),
                        fontSize = 11.5.sp,
                        color = ForgeColors.TextSecondary
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        enabled = draftText.isNotBlank(),
                        onClick = {
                            vm.editUserMessage(targetMsg, draftText, resend = false)
                            editingMessage = null
                        }
                    ) { Text(stringResource(R.string.action_save), fontSize = 12.sp) }
                    Button(
                        enabled = draftText.isNotBlank() && !isAgentRunning,
                        onClick = {
                            vm.editUserMessage(targetMsg, draftText, resend = true)
                            editingMessage = null
                        }
                    ) { Text(stringResource(R.string.chat_and_send), fontSize = 12.sp) }
                }
            },
            dismissButton = {
                TextButton(onClick = { editingMessage = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@Composable
private fun AiHomeAttachDialog(
    vm: MainViewModel,
    safTreeUri: String?,
    onPickFromDevice: () -> Unit,
    onPickSafFolder: () -> Unit,
    onDismiss: () -> Unit
) {
    val sshProfiles by vm.sshProfiles.collectAsState()
    val hasSaf = !safTreeUri.isNullOrBlank()
    var activeEnv by remember {
        mutableStateOf(WorkspaceEnvType.INTERNAL)
    }
    var activeSshId by remember { mutableStateOf<String?>(null) }
    var currentPath by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<WorkspaceFileItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var attachedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(activeEnv, currentPath, activeSshId) {
        loading = true
        errorMsg = null
        vm.listFilesForAttachPicker(activeEnv, currentPath, activeSshId).fold(
            onSuccess = { items = it },
            onFailure = {
                items = emptyList()
                errorMsg = it.message
            }
        )
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_files_dialog_title), fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = activeEnv == WorkspaceEnvType.INTERNAL,
                        onClick = {
                            activeEnv = WorkspaceEnvType.INTERNAL
                            activeSshId = null
                            currentPath = ""
                        },
                        label = { Text(stringResource(R.string.env_internal), fontSize = 11.5.sp) }
                    )
                    FilterChip(
                        selected = activeEnv == WorkspaceEnvType.DEVICE_STORAGE,
                        onClick = {
                            activeEnv = WorkspaceEnvType.DEVICE_STORAGE
                            activeSshId = null
                            currentPath = ""
                        },
                        label = { Text(stringResource(R.string.env_device_storage), fontSize = 11.5.sp) }
                    )
                    FilterChip(
                        selected = activeEnv == WorkspaceEnvType.SAF,
                        onClick = {
                            if (!hasSaf) {
                                onPickSafFolder()
                            } else {
                                activeEnv = WorkspaceEnvType.SAF
                                activeSshId = null
                                currentPath = ""
                            }
                        },
                        label = {
                            Text(
                                if (hasSaf) stringResource(R.string.env_saf_short) else stringResource(R.string.chat_select_folder),
                                fontSize = 11.5.sp
                            )
                        }
                    )
                    sshProfiles.filter { it.enabled }.forEach { prof ->
                        FilterChip(
                            selected = activeEnv == WorkspaceEnvType.TERMUX_SSH && activeSshId == prof.id,
                            onClick = {
                                activeEnv = WorkspaceEnvType.TERMUX_SSH
                                activeSshId = prof.id
                                currentPath = ""
                            },
                            label = { Text(prof.name, fontSize = 11.5.sp) }
                        )
                    }
                }

                if (currentPath.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconButton(
                            onClick = {
                                val trimmed = currentPath.trimEnd('/')
                                currentPath = if (trimmed.contains('/')) trimmed.substringBeforeLast('/') else ""
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_up), modifier = Modifier.size(16.dp))
                        }
                        Text(
                            currentPath,
                            fontSize = 11.sp,
                            color = ForgeColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (loading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 18.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                } else if (errorMsg != null) {
                    Text(errorMsg.orEmpty(), fontSize = 12.sp, color = ForgeColors.DangerRed)
                } else if (items.isEmpty()) {
                    Text(
                        stringResource(R.string.chat_folder_empty_ai),
                        fontSize = 12.sp,
                        color = ForgeColors.TextSecondary,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(items, key = { "${it.envType.id}:${it.path}" }) { item ->
                            val key = "${item.envType.id}:${item.path}"
                            val picked = key in attachedKeys
                            Surface(
                                color = if (picked) ForgeColors.AccentBlue.copy(alpha = 0.16f) else ForgeColors.SurfaceElevated,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (item.isDirectory) {
                                            currentPath = item.path
                                        } else {
                                            vm.attachWorkspaceItemInChat(item)
                                            attachedKeys = attachedKeys + key
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = when {
                                            item.isDirectory -> Icons.Rounded.Folder
                                            picked -> Icons.Rounded.CheckCircle
                                            else -> Icons.Rounded.AttachFile
                                        },
                                        contentDescription = null,
                                        tint = when {
                                            item.isDirectory -> ForgeColors.AccentAmber
                                            picked -> ForgeColors.AccentEmerald
                                            else -> ForgeColors.AccentBlue
                                        },
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            item.name,
                                            fontSize = 12.5.sp,
                                            color = ForgeColors.TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (!item.isDirectory) {
                                            Text(formatBytes(item.sizeBytes), fontSize = 10.sp, color = ForgeColors.TextSecondary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = ForgeColors.BorderSubtle)
                OutlinedButton(
                    onClick = onPickFromDevice,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.UploadFile, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.chat_from_phone_to_home), fontSize = 11.5.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
        }
    )
}

@Composable
private fun ChatDrawerContent(
    activeSessions: List<ChatSessionEntity>,
    archivedSessions: List<ChatSessionEntity>,
    folders: List<ChatFolderEntity>,
    searchResults: List<ChatSessionEntity>,
    currentSessionId: String?,
    onSearchQuery: (String) -> Unit,
    onNewChat: (String?) -> Unit,
    onSelectSession: (String) -> Unit,
    onCreateFolder: (String) -> Unit,
    onAssignFolder: (String, String?) -> Unit,
    onTogglePin: (String, Boolean) -> Unit,
    onToggleArchive: (String, Boolean) -> Unit,
    onDeleteSession: (String) -> Unit,
    onEditInstruction: (ChatSessionEntity) -> Unit,
    onBatchDelete: (Set<String>) -> Unit,
    onBatchArchive: (Set<String>, Boolean) -> Unit,
    onBatchAssignFolder: (Set<String>, String?) -> Unit,
    onBatchExportZip: (Set<String>) -> Unit,
    onBatchExportDeviceFolder: (Set<String>) -> Unit,
    onExportMarkdown: () -> Unit,
    onExportZip: () -> Unit,
    onImportZip: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var showArchiveTab by remember { mutableStateOf(false) }
    var selectedFolderId by remember { mutableStateOf<String?>(null) }
    var showNewFolderDialog by remember { mutableStateOf(false) }

    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBatchFolderDialog by remember { mutableStateOf(false) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }

    val baseList = when {
        query.isNotBlank() -> searchResults
        showArchiveTab -> archivedSessions
        selectedFolderId != null -> activeSessions.filter { it.folderId == selectedFolderId }
        else -> activeSessions
    }

    fun exitMultiSelect() {
        multiSelectMode = false
        selectedIds = emptySet()
    }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(12.dp)
    ) {
        if (multiSelectMode) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { exitMultiSelect() }) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.action_cancel), tint = ForgeColors.TextSecondary)
                    }
                    Text(
                        text = stringResource(R.string.drawer_selected_count_fmt, selectedIds.size),
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = ForgeColors.AccentBlue
                    )
                }
                IconButton(
                    onClick = {
                        val allIds = baseList.map { it.id }.toSet()
                        selectedIds = if (selectedIds.containsAll(allIds) && allIds.isNotEmpty()) {
                            emptySet()
                        } else {
                            allIds
                        }
                    }
                ) {
                    Icon(Icons.Rounded.SelectAll, stringResource(R.string.drawer_select_all), tint = ForgeColors.AccentBlue)
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.drawer_title), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ForgeColors.TextPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ForgeIconButton(
                        icon = Icons.Rounded.Checklist,
                        contentDescription = stringResource(R.string.drawer_multiselect_btn),
                        onClick = { multiSelectMode = true },
                        size = 34.dp,
                        iconSize = 17.dp
                    )
                    ForgeIconButton(
                        icon = Icons.Rounded.CreateNewFolder,
                        contentDescription = stringResource(R.string.drawer_new_folder),
                        onClick = { showNewFolderDialog = true },
                        size = 34.dp,
                        iconSize = 17.dp
                    )
                    ForgeIconButton(
                        icon = Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.floating_new_chat),
                        onClick = { onNewChat(selectedFolderId) },
                        active = true,
                        activeColor = ForgeColors.AccentBlue,
                        tint = ForgeColors.AccentBlue,
                        size = 34.dp,
                        iconSize = 18.dp
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                onSearchQuery(it)
            },
            placeholder = { Text(stringResource(R.string.drawer_search_chats), fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, modifier = Modifier.size(18.dp)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = !showArchiveTab && selectedFolderId == null,
                onClick = {
                    showArchiveTab = false
                    selectedFolderId = null
                },
                label = { Text(stringResource(R.string.drawer_tab_all, activeSessions.size), fontSize = 11.5.sp) }
            )
            FilterChip(
                selected = showArchiveTab,
                onClick = {
                    showArchiveTab = true
                    selectedFolderId = null
                },
                leadingIcon = { Icon(Icons.Rounded.Archive, null, modifier = Modifier.size(14.dp)) },
                label = { Text(stringResource(R.string.drawer_tab_archive, archivedSessions.size), fontSize = 11.5.sp) }
            )
            folders.forEach { folder ->
                FilterChip(
                    selected = !showArchiveTab && selectedFolderId == folder.id,
                    onClick = {
                        showArchiveTab = false
                        selectedFolderId = folder.id
                    },
                    leadingIcon = { Icon(Icons.Rounded.Folder, null, modifier = Modifier.size(14.dp)) },
                    label = { Text(folder.name, fontSize = 11.5.sp) }
                )
            }
        }

        HorizontalDivider(color = ForgeColors.BorderSubtle, modifier = Modifier.padding(vertical = 8.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(baseList, key = { it.id }) { s ->
                val checked = s.id in selectedIds
                DrawerSessionRow(
                    session = s,
                    isSelected = s.id == currentSessionId,
                    multiSelectMode = multiSelectMode,
                    isChecked = checked,
                    folders = folders,
                    onClick = {
                        if (multiSelectMode) {
                            selectedIds = if (checked) selectedIds - s.id else selectedIds + s.id
                        } else {
                            onSelectSession(s.id)
                        }
                    },
                    onLongPress = {
                        if (!multiSelectMode) {
                            multiSelectMode = true
                        }
                        selectedIds = selectedIds + s.id
                    },
                    onTogglePin = { onTogglePin(s.id, !s.isPinned) },
                    onToggleArchive = { onToggleArchive(s.id, !s.isArchived) },
                    onAssignFolder = { fid -> onAssignFolder(s.id, fid) },
                    onEditInstruction = { onEditInstruction(s) },
                    onDelete = { onDeleteSession(s.id) }
                )
            }
        }

        HorizontalDivider(color = ForgeColors.BorderSubtle, modifier = Modifier.padding(vertical = 8.dp))

        if (multiSelectMode) {
            val hasSelection = selectedIds.isNotEmpty()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        enabled = hasSelection,
                        onClick = { showBatchFolderDialog = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.FolderOpen, null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.drawer_batch_to_folder), fontSize = 11.sp, maxLines = 1)
                    }
                    OutlinedButton(
                        enabled = hasSelection,
                        onClick = {
                            val ids = selectedIds
                            exitMultiSelect()
                            onBatchArchive(ids, !showArchiveTab)
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            if (showArchiveTab) Icons.Rounded.Unarchive else Icons.Rounded.Archive,
                            null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            stringResource(if (showArchiveTab) R.string.drawer_unarchive else R.string.drawer_batch_archive),
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        enabled = hasSelection,
                        onClick = {
                            val ids = selectedIds
                            exitMultiSelect()
                            onBatchExportZip(ids)
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.FolderZip, null, modifier = Modifier.size(15.dp), tint = ForgeColors.AccentEmerald)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.drawer_batch_one_zip), fontSize = 11.sp, maxLines = 1)
                    }
                    OutlinedButton(
                        enabled = hasSelection,
                        onClick = { showBatchDeleteConfirm = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ForgeColors.DangerRed),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.Delete, null, modifier = Modifier.size(15.dp), tint = ForgeColors.DangerRed)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.action_delete), fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ForgeActionPill(
                    text = "MD",
                    icon = Icons.Rounded.Download,
                    onClick = onExportMarkdown,
                    accentColor = ForgeColors.AccentBlue,
                    horizontalPadding = 8.dp
                )
                ForgeActionPill(
                    text = stringResource(R.string.drawer_backup_zip),
                    icon = Icons.Rounded.FolderZip,
                    onClick = onExportZip,
                    accentColor = ForgeColors.AccentEmerald,
                    modifier = Modifier.weight(1f),
                    horizontalPadding = 6.dp
                )
                ForgeActionPill(
                    text = stringResource(R.string.drawer_restore_zip),
                    icon = Icons.Rounded.UploadFile,
                    onClick = onImportZip,
                    accentColor = ForgeColors.AccentAmber,
                    modifier = Modifier.weight(1f),
                    horizontalPadding = 6.dp
                )
            }
        }
    }

    if (showNewFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text(stringResource(R.string.drawer_new_folder_title)) },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    placeholder = { Text(stringResource(R.string.drawer_new_folder_hint)) },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    onCreateFolder(folderName)
                    showNewFolderDialog = false
                }) { Text(stringResource(R.string.action_create)) }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    if (showBatchFolderDialog) {
        var inlineFolderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showBatchFolderDialog = false },
            title = { Text(stringResource(R.string.drawer_batch_folder_dialog_title, selectedIds.size)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = {
                            val ids = selectedIds
                            showBatchFolderDialog = false
                            exitMultiSelect()
                            onBatchExportDeviceFolder(ids)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.FolderOpen, null, tint = ForgeColors.AccentAmber, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.drawer_batch_save_device_folder), fontSize = 12.sp)
                    }
                    HorizontalDivider(color = ForgeColors.BorderSubtle, modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        stringResource(R.string.drawer_batch_app_folder_header),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ForgeColors.TextSecondary
                    )
                    TextButton(onClick = {
                        val ids = selectedIds
                        showBatchFolderDialog = false
                        exitMultiSelect()
                        onBatchAssignFolder(ids, null)
                    }) { Text(stringResource(R.string.label_no_folder)) }
                    folders.forEach { f ->
                        TextButton(onClick = {
                            val ids = selectedIds
                            showBatchFolderDialog = false
                            exitMultiSelect()
                            onBatchAssignFolder(ids, f.id)
                        }) {
                            Icon(Icons.Rounded.Folder, null, modifier = Modifier.size(15.dp), tint = ForgeColors.AccentBlue)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(f.name)
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = inlineFolderName,
                            onValueChange = { inlineFolderName = it },
                            placeholder = { Text(stringResource(R.string.drawer_new_folder_hint), fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            enabled = inlineFolderName.isNotBlank(),
                            onClick = {
                                onCreateFolder(inlineFolderName)
                                inlineFolderName = ""
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                        ) { Text("+") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBatchFolderDialog = false }) { Text(stringResource(R.string.action_close)) }
            }
        )
    }

    if (showBatchDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text(stringResource(R.string.drawer_batch_delete_title, selectedIds.size)) },
            text = { Text(stringResource(R.string.drawer_batch_delete_body)) },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = ForgeColors.DangerRed),
                    onClick = {
                        val ids = selectedIds
                        showBatchDeleteConfirm = false
                        exitMultiSelect()
                        onBatchDelete(ids)
                    }
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerSessionRow(
    session: ChatSessionEntity,
    isSelected: Boolean,
    multiSelectMode: Boolean = false,
    isChecked: Boolean = false,
    folders: List<ChatFolderEntity>,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
    onTogglePin: () -> Unit,
    onToggleArchive: () -> Unit,
    onAssignFolder: (String?) -> Unit,
    onEditInstruction: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var folderDialog by remember { mutableStateOf(false) }

    val rowHighlight = when {
        multiSelectMode && isChecked -> ForgeColors.AccentBlue.copy(alpha = 0.22f)
        isSelected -> ForgeColors.AccentBlue.copy(alpha = 0.14f)
        else -> Color.Transparent
    }

    Surface(
        color = rowHighlight,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (multiSelectMode) {
                Checkbox(
                    checked = isChecked,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            } else if (session.isPinned) {
                Icon(
                    Icons.Rounded.PushPin,
                    null,
                    tint = ForgeColors.AccentAmber,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                val localizedTitle = if (
                    session.title.equals("Новый чат", ignoreCase = true) ||
                    session.title.equals("New chat", ignoreCase = true)
                ) {
                    stringResource(R.string.floating_new_chat)
                } else session.title
                Text(
                    text = localizedTitle,
                    fontSize = 13.5.sp,
                    color = ForgeColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = session.modelId.substringAfterLast('/'),
                    fontSize = 10.5.sp,
                    color = ForgeColors.TextSecondary,
                    maxLines = 1
                )
            }
            if (!multiSelectMode) {
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.MoreVert, null, tint = ForgeColors.TextSecondary, modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.drawer_chat_instruction)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.EditNote,
                                    null,
                                    tint = if (!session.customSystemPrompt.isNullOrBlank()) {
                                        ForgeColors.AccentBlue
                                    } else {
                                        ForgeColors.TextPrimary
                                    }
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onEditInstruction()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (session.isPinned) stringResource(R.string.drawer_unpin) else stringResource(R.string.drawer_pin)) },
                            leadingIcon = { Icon(Icons.Rounded.PushPin, null) },
                            onClick = {
                                menuOpen = false
                                onTogglePin()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.drawer_move_folder)) },
                            leadingIcon = { Icon(Icons.Rounded.Folder, null) },
                            onClick = {
                                menuOpen = false
                                folderDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (session.isArchived) stringResource(R.string.drawer_unarchive) else stringResource(R.string.drawer_archive)) },
                            leadingIcon = { Icon(if (session.isArchived) Icons.Rounded.Unarchive else Icons.Rounded.Archive, null) },
                            onClick = {
                                menuOpen = false
                                onToggleArchive()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete), color = ForgeColors.DangerRed) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = ForgeColors.DangerRed) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }

    if (folderDialog) {
        AlertDialog(
            onDismissRequest = { folderDialog = false },
            title = { Text(stringResource(R.string.drawer_pick_folder)) },
            text = {
                Column {
                    TextButton(onClick = {
                        onAssignFolder(null)
                        folderDialog = false
                    }) { Text(stringResource(R.string.label_no_folder)) }
                    folders.forEach { f ->
                        TextButton(onClick = {
                            onAssignFolder(f.id)
                            folderDialog = false
                        }) { Text(f.name) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { folderDialog = false }) { Text(stringResource(R.string.action_close)) }
            }
        )
    }
}

internal fun resolveProviderStatusDotColor(
    config: ProviderConfig,
    activeProviderId: String,
    providerHealth: Map<String, Boolean?>
): Color? = when (providerHealth[config.providerId]) {
    true -> ForgeColors.AccentEmerald
    false -> ForgeColors.DangerRed
    null -> if (config.shouldShowStatusDot(activeProviderId)) ForgeColors.AccentAmber else null
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ModelPickerBottomSheet(
    currentProviderId: String,
    currentModelId: String,
    providerConfigs: Map<String, ProviderConfig>,
    hiddenBuiltInProviders: Set<String> = emptySet(),
    providerHealth: Map<String, Boolean?> = emptyMap(),
    scannedModels: Map<String, List<ModelInfo>>,
    favoriteModels: List<String>,
    onScanModels: (ProviderConfig, (String) -> Unit) -> Unit,
    onToggleFavorite: (String, String) -> Unit,
    onUpdateReasoningEffort: (String, ReasoningEffort) -> Unit,
    onSetContextAuto: (String, String) -> Unit = { _, _ -> },
    onSetContextManual: (String, String, Int) -> Unit = { _, _, _ -> },
    onSelectModel: (String, String) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val allOrderedProviders = remember(providerConfigs, hiddenBuiltInProviders) {
        SettingsRepository.orderedProviderConfigs(providerConfigs, hiddenBuiltInProviders)
    }
    var selectedProv by remember {
        mutableStateOf(
            if (allOrderedProviders.any { it.providerId == currentProviderId } || allOrderedProviders.isEmpty()) {
                currentProviderId
            } else {
                allOrderedProviders.first().providerId
            }
        )
    }
    LaunchedEffect(allOrderedProviders) {
        if (allOrderedProviders.isNotEmpty() && allOrderedProviders.none { it.providerId == selectedProv }) {
            selectedProv = allOrderedProviders.first().providerId
        }
    }
    var provCategoryFilter by remember { mutableStateOf<ProviderCategory?>(null) }
    val visibleProviders = remember(allOrderedProviders, provCategoryFilter) {
        if (provCategoryFilter == null) allOrderedProviders
        else allOrderedProviders.filter { it.effectiveCategory() == provCategoryFilter }
    }
    val cfg = providerConfigs[selectedProv] ?: ProviderConfig(selectedProv)
    val provType = ProviderType.fromId(selectedProv)
    var customModel by remember(selectedProv) {
        mutableStateOf(if (selectedProv == currentProviderId) currentModelId else cfg.modelId)
    }
    var showCustomField by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember(selectedProv) { mutableStateOf<String?>(null) }
    var onlyFree by remember(selectedProv) { mutableStateOf(false) }
    var scanNote by remember(selectedProv) { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }

    val catalog = remember(scannedModels[selectedProv], cfg.savedModels) {
        PreparedModelCatalog.from(
            ModelScanner.mergeSavedModels(scannedModels[selectedProv].orEmpty(), cfg)
        )
    }

    // Auto-scan live models when switching to a configured provider with an empty cache
    LaunchedEffect(selectedProv) {
        val rawScanned = scannedModels[selectedProv].orEmpty()
        val hasCreds = cfg.isReadyForUse() || provType == ProviderType.OPENROUTER
        if (rawScanned.isEmpty() && hasCreds && !scanning) {
            scanning = true
            onScanModels(cfg) { msg ->
                scanning = false
                scanNote = msg
            }
        }
    }

    val favIdsForSelectedProv = remember(favoriteModels, selectedProv) {
        val prefix = "$selectedProv::"
        favoriteModels.mapNotNullTo(HashSet()) { token ->
            if (token.startsWith(prefix)) token.substring(prefix.length).takeIf { it.isNotEmpty() } else null
        }
    }

    val filteredItems = remember(catalog, searchQuery, selectedCategory, onlyFree, favIdsForSelectedProv) {
        catalog.filter(
            query = searchQuery,
            selectedCategory = selectedCategory,
            onlyFree = onlyFree,
            favoriteIdsForProvider = favIdsForSelectedProv
        )
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ForgeColors.SurfaceDark
    ) {
        val sheetCtx = LocalContext.current
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header row: Title + Scan + API Keys / Settings
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.chat_provider_modal_title),
                    fontSize = 16.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = ForgeColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (scanning) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(ForgeColors.SurfaceElevated, RoundedCornerShape(10.dp))
                                .border(1.dp, ForgeColors.BorderHighlight, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = ForgeColors.AccentBlue)
                        }
                    } else {
                        ForgeIconButton(
                            icon = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.chat_scan_desc),
                            onClick = {
                                scanning = true
                                onScanModels(cfg) { msg ->
                                    scanning = false
                                    scanNote = msg
                                }
                            },
                            tint = ForgeColors.AccentBlue,
                            size = 34.dp,
                            iconSize = 18.dp
                        )
                    }
                    ForgeActionPill(
                        text = stringResource(R.string.chat_api_keys_cta),
                        icon = Icons.Rounded.Tune,
                        onClick = onOpenSettings,
                        minHeight = 34.dp
                    )
                }
            }

            // 1-Tap Favorites bar across all providers
            val visibleFavorites = remember(favoriteModels, hiddenBuiltInProviders) {
                favoriteModels.distinct().mapNotNull { token ->
                    val favProv = token.substringBefore("::", "")
                    val favModel = token.substringAfter("::", "")
                    if (favProv.isNotBlank() && favModel.isNotBlank() && favProv.lowercase() !in hiddenBuiltInProviders) {
                        Triple(token, favProv, favModel)
                    } else {
                        null
                    }
                }
            }
            if (visibleFavorites.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(visibleFavorites, key = { it.first }) { (_, favProv, favModel) ->
                        val isCurrentFav = favProv == currentProviderId && favModel == currentModelId
                        val pShort = (providerConfigs[favProv] ?: ProviderConfig(favProv)).resolvedDisplayName(sheetCtx)
                        FilterChip(
                            selected = isCurrentFav,
                            onClick = { onSelectModel(favProv, favModel) },
                            leadingIcon = {
                                Icon(Icons.Rounded.Star, null, tint = ForgeColors.AccentAmber, modifier = Modifier.size(14.dp))
                            },
                            label = {
                                Text(
                                    "${favModel.substringAfterLast('/')} · $pShort",
                                    fontSize = 11.5.sp,
                                    maxLines = 1
                                )
                            }
                        )
                    }
                }
            }

            // Provider category filter (All / Cloud / Local LAN / Custom)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    null to stringResource(R.string.prov_cat_all),
                    ProviderCategory.CLOUD to stringResource(R.string.prov_cat_cloud),
                    ProviderCategory.LOCAL_LAN to stringResource(R.string.prov_cat_lan),
                    ProviderCategory.CUSTOM to stringResource(R.string.prov_cat_custom)
                ).forEach { (cat, label) ->
                    val sel = provCategoryFilter == cat
                    ForgeActionPill(
                        text = label,
                        active = sel,
                        accentColor = ForgeColors.AccentBlue,
                        onClick = {
                            provCategoryFilter = cat
                            val nextList = if (cat == null) allOrderedProviders else allOrderedProviders.filter { it.effectiveCategory() == cat }
                            if (nextList.isNotEmpty() && nextList.none { it.providerId == selectedProv }) {
                                selectedProv = nextList.first().providerId
                            }
                        },
                        minHeight = 28.dp,
                        fontSize = 11.sp,
                        horizontalPadding = 9.dp,
                        verticalPadding = 4.dp
                    )
                }
            }

            // Provider selector chips (Built-ins + User-created LAN & Custom providers)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                visibleProviders.forEach { pCfg ->
                    val dotColor = resolveProviderStatusDotColor(
                        config = pCfg,
                        activeProviderId = currentProviderId,
                        providerHealth = providerHealth
                    )
                    val provTitle = pCfg.resolvedDisplayName(sheetCtx)
                    FilterChip(
                        selected = selectedProv == pCfg.providerId,
                        onClick = { selectedProv = pCfg.providerId },
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                if (dotColor != null) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .background(dotColor, CircleShape)
                                    )
                                }
                                Text(
                                    text = provTitle,
                                    fontSize = 11.5.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    )
                }
            }

            // Reasoning Effort quick bar
            if (provType.supportsThinking) {
                val activeEffort = cfg.reasoningEffort()
                Surface(
                    color = ForgeColors.SurfaceCard,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, ForgeColors.BorderSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.padding(top = 3.dp)
                        ) {
                            Icon(Icons.Rounded.Psychology, null, tint = ForgeColors.AccentAmber, modifier = Modifier.size(15.dp))
                            Text(stringResource(R.string.reasoning_effort_label), fontSize = 11.5.sp, color = ForgeColors.TextSecondary)
                        }
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            listOf(
                                ReasoningEffort.OFF to stringResource(R.string.reasoning_off),
                                ReasoningEffort.LOW to stringResource(R.string.reasoning_low),
                                ReasoningEffort.MEDIUM to stringResource(R.string.reasoning_medium),
                                ReasoningEffort.HIGH to stringResource(R.string.reasoning_high)
                            ).forEach { (eff, label) ->
                                val sel = activeEffort == eff
                                Surface(
                                    color = if (sel) ForgeColors.AccentAmber.copy(alpha = 0.22f) else ForgeColors.SurfaceElevated,
                                    shape = RoundedCornerShape(7.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (sel) ForgeColors.AccentAmber else ForgeColors.BorderHighlight.copy(alpha = 0.6f)
                                    ),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(7.dp))
                                        .clickable { onUpdateReasoningEffort(selectedProv, eff) }
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 10.5.sp,
                                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                                        color = if (sel) ForgeColors.AccentAmber else ForgeColors.TextPrimary,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Context Window quick selector (Auto per model + 1-tap presets + Custom)
            val activeModelForProv = (if (selectedProv == currentProviderId) currentModelId else cfg.modelId)
                .ifBlank { cfg.modelId }
            val scannedCtxForActive = catalog.byId[activeModelForProv.trim()]?.contextLength
            val isManualCtx = cfg.isManualContextForModel(activeModelForProv)
            val effectiveCtxTokens = if (selectedProv == currentProviderId && cfg.modelId == activeModelForProv) {
                cfg.contextWindow
            } else {
                cfg.resolveContextWindowForModel(activeModelForProv, scannedCtxForActive)
            }
            var showCustomCtxInput by remember(selectedProv) { mutableStateOf(false) }
            var customCtxDigits by remember(selectedProv, effectiveCtxTokens) {
                mutableStateOf(effectiveCtxTokens.toString())
            }

            Surface(
                color = ForgeColors.SurfaceCard,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, ForgeColors.BorderSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val ctxSummary = if (!isManualCtx) {
                            "${stringResource(R.string.ctx_window_quick_label)}: ${stringResource(R.string.ctx_window_auto)} · ${ContextWindowResolver.formatTokensShort(effectiveCtxTokens)}"
                        } else {
                            "${stringResource(R.string.ctx_window_quick_label)}: ${ContextWindowResolver.formatTokensShort(effectiveCtxTokens)}"
                        }
                        Text(
                            text = ctxSummary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ForgeColors.AccentCyan,
                            maxLines = 1,
                            softWrap = false
                        )
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            val autoSel = !isManualCtx && !showCustomCtxInput
                            Surface(
                                color = if (autoSel) ForgeColors.AccentCyan.copy(alpha = 0.22f) else ForgeColors.SurfaceElevated,
                                shape = RoundedCornerShape(7.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (autoSel) ForgeColors.AccentCyan else ForgeColors.BorderHighlight.copy(alpha = 0.6f)
                                ),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(7.dp))
                                    .clickable {
                                        showCustomCtxInput = false
                                        onSetContextAuto(selectedProv, activeModelForProv)
                                    }
                            ) {
                                Text(
                                    text = stringResource(R.string.ctx_window_auto),
                                    fontSize = 10.5.sp,
                                    fontWeight = if (autoSel) FontWeight.Bold else FontWeight.Medium,
                                    color = if (autoSel) ForgeColors.AccentCyan else ForgeColors.TextPrimary,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }

                            ContextWindowResolver.PRESET_WINDOWS.forEach { presetTokens ->
                                val sel = isManualCtx && !showCustomCtxInput && effectiveCtxTokens == presetTokens
                                Surface(
                                    color = if (sel) ForgeColors.AccentBlue.copy(alpha = 0.22f) else ForgeColors.SurfaceElevated,
                                    shape = RoundedCornerShape(7.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (sel) ForgeColors.AccentBlue else ForgeColors.BorderHighlight.copy(alpha = 0.6f)
                                    ),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(7.dp))
                                        .clickable {
                                            showCustomCtxInput = false
                                            onSetContextManual(selectedProv, activeModelForProv, presetTokens)
                                        }
                                ) {
                                    Text(
                                        text = ContextWindowResolver.formatTokensShort(presetTokens),
                                        fontSize = 10.5.sp,
                                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                                        color = if (sel) ForgeColors.AccentBlue else ForgeColors.TextPrimary,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Surface(
                                color = if (showCustomCtxInput) ForgeColors.AccentAmber.copy(alpha = 0.22f) else ForgeColors.SurfaceElevated,
                                shape = RoundedCornerShape(7.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (showCustomCtxInput) ForgeColors.AccentAmber else ForgeColors.BorderHighlight.copy(alpha = 0.6f)
                                ),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(7.dp))
                                    .clickable { showCustomCtxInput = !showCustomCtxInput }
                            ) {
                                Text(
                                    text = stringResource(R.string.ctx_window_custom_btn),
                                    fontSize = 10.5.sp,
                                    fontWeight = if (showCustomCtxInput) FontWeight.Bold else FontWeight.Medium,
                                    color = if (showCustomCtxInput) ForgeColors.AccentAmber else ForgeColors.TextPrimary,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    AnimatedVisibility(visible = showCustomCtxInput) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedTextField(
                                value = customCtxDigits,
                                onValueChange = { customCtxDigits = it.filter { ch -> ch.isDigit() }.take(8) },
                                label = { Text(stringResource(R.string.ctx_window_custom_label), fontSize = 11.sp) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    val parsed = customCtxDigits.toIntOrNull()?.coerceIn(1_024, 4_000_000)
                                    if (parsed != null) {
                                        onSetContextManual(selectedProv, activeModelForProv, parsed)
                                        showCustomCtxInput = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ForgeColors.AccentBlue)
                            ) {
                                Text(stringResource(R.string.action_apply), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Inline Search + Custom ID toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    leadingIcon = { Icon(Icons.Rounded.Search, null, modifier = Modifier.size(17.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(26.dp)) {
                                Icon(Icons.Rounded.Close, null, modifier = Modifier.size(15.dp))
                            }
                        }
                    },
                    placeholder = { Text(stringResource(R.string.model_search_hint), fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = { showCustomField = !showCustomField },
                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Rounded.Edit, null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("ID", fontSize = 11.5.sp)
                }
            }

            AnimatedVisibility(visible = showCustomField) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = customModel,
                        onValueChange = { customModel = it },
                        label = { Text(stringResource(R.string.model_custom_id_label)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { onSelectModel(selectedProv, customModel.ifBlank { cfg.modelId }) },
                        colors = ButtonDefaults.buttonColors(containerColor = ForgeColors.AccentBlue)
                    ) {
                        Text(stringResource(R.string.action_apply), fontSize = 12.sp)
                    }
                }
            }

            // Virtualized Category + FREE filter chips
            if (catalog.categories.size > 1 || catalog.hasAnyFree) {
                val allFilterLabel = stringResource(R.string.model_filter_all)
                val countSuffix = if (filteredItems.size != catalog.size) {
                    "${filteredItems.size}/${catalog.size}"
                } else {
                    "${catalog.size}"
                }
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item(key = "__all__") {
                        FilterChip(
                            selected = selectedCategory == null && !onlyFree,
                            onClick = {
                                selectedCategory = null
                                onlyFree = false
                            },
                            label = { Text("$allFilterLabel ($countSuffix)", fontSize = 11.sp) }
                        )
                    }
                    if (catalog.hasAnyFree) {
                        item(key = "__free__") {
                            FilterChip(
                                selected = onlyFree,
                                onClick = { onlyFree = !onlyFree },
                                label = { Text("FREE", fontSize = 11.sp, color = ForgeColors.AccentEmerald) }
                            )
                        }
                    }
                    items(catalog.categories, key = { "cat_$it" }) { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = if (selectedCategory == cat) null else cat },
                            label = { Text(cat, fontSize = 11.sp) }
                        )
                    }
                }
            }

            scanNote?.let {
                Text(it, fontSize = 11.sp, color = ForgeColors.AccentEmerald)
            }

            // Unified inline model list (no second popup dialog!)
            Surface(
                color = ForgeColors.SurfaceCard,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, ForgeColors.BorderSubtle),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 330.dp)
            ) {
                when {
                    filteredItems.isEmpty() && scanning -> {
                        Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(26.dp))
                        }
                    }
                    filteredItems.isEmpty() -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = if (catalog.isEmpty) cfg.modelId else stringResource(R.string.model_no_matches),
                                fontFamily = if (catalog.isEmpty) MonoFont else null,
                                color = ForgeColors.TextSecondary,
                                fontSize = 12.5.sp
                            )
                            Button(
                                onClick = {
                                    val chosen = searchQuery.trim().ifBlank { customModel.ifBlank { cfg.modelId } }
                                    onSelectModel(selectedProv, chosen)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ForgeColors.AccentBlue)
                            ) {
                                Text(stringResource(R.string.chat_use_model), fontSize = 12.sp)
                            }
                        }
                    }
                    else -> {
                        val activeCleanForProv = (if (selectedProv == currentProviderId) currentModelId else cfg.modelId).trim()
                        val effCategoryForProv = cfg.effectiveCategory()
                        val selectedDesc = stringResource(R.string.model_selected_desc)
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(
                                items = filteredItems,
                                key = { it.model.id },
                                contentType = { "chat_model_row" }
                            ) { item ->
                                val m = item.model
                                val isSelected = m.id == activeCleanForProv
                                val isFav = m.id in favIdsForSelectedProv
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectModel(selectedProv, m.id) }
                                        .background(if (isSelected) ForgeColors.AccentBlue.copy(alpha = 0.15f) else Color.Transparent)
                                        .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = item.shortTitle,
                                                fontSize = 13.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (item.isFree) ForgeColors.AccentEmerald else ForgeColors.TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            m.pricingHint?.takeIf { it.isNotBlank() }?.let { hint ->
                                                Surface(
                                                    color = ForgeColors.SurfaceElevated,
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = hint,
                                                        fontSize = 9.5.sp,
                                                        color = if (item.isFree) ForgeColors.AccentEmerald else ForgeColors.AccentCyan,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                            val manualRowCtx = cfg.modelContextOverrides[m.id]
                                            val rowCtxTokens = manualRowCtx
                                                ?: m.contextLength?.takeIf { it > 0 }
                                                ?: ContextWindowResolver.inferContextWindow(
                                                    modelId = m.id,
                                                    providerType = provType,
                                                    category = effCategoryForProv
                                                )
                                            val rowCtxPrefix = when {
                                                manualRowCtx != null -> ""
                                                m.contextLength != null && m.contextLength > 0 -> ""
                                                else -> "~"
                                            }
                                            Text(
                                                text = "$rowCtxPrefix${ContextWindowResolver.formatTokensShort(rowCtxTokens)}",
                                                fontSize = 10.sp,
                                                color = if (manualRowCtx != null) ForgeColors.AccentBlue else ForgeColors.TextSecondary
                                            )
                                        }
                                        if (item.showSubtitleId) {
                                            Text(
                                                text = m.id,
                                                fontFamily = MonoFont,
                                                fontSize = 10.sp,
                                                color = ForgeColors.TextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    if (isSelected) {
                                        Icon(
                                            Icons.Rounded.Check,
                                            contentDescription = selectedDesc,
                                            tint = ForgeColors.AccentBlue,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { onToggleFavorite(selectedProv, m.id) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isFav) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                            contentDescription = null,
                                            tint = if (isFav) ForgeColors.AccentAmber else ForgeColors.TextSecondary.copy(alpha = 0.6f),
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                }
                                HorizontalDivider(color = ForgeColors.BorderSubtle.copy(alpha = 0.35f))
                            }
                        }
                    }
                }
            }
        }
    }
}
