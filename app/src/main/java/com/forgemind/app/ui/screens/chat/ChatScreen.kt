package com.forgemind.app.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forgemind.app.bridge.WorkspaceManager
import com.forgemind.app.data.db.ChatFolderEntity
import com.forgemind.app.data.db.ChatMessageEntity
import com.forgemind.app.data.db.ChatSessionEntity
import com.forgemind.app.data.model.ChatAttachment
import com.forgemind.app.data.model.LlmRole
import com.forgemind.app.data.model.ModelInfo
import com.forgemind.app.data.model.ProviderConfig
import com.forgemind.app.data.model.ProviderType
import com.forgemind.app.data.model.WorkspaceEnvType
import com.forgemind.app.data.model.WorkspaceFileItem
import com.forgemind.app.ui.AppSection
import com.forgemind.app.ui.MainViewModel
import com.forgemind.app.ui.components.ChatMessageBubble
import com.forgemind.app.ui.components.ConnectionStatusStrip
import com.forgemind.app.ui.components.ContextUsageBar
import com.forgemind.app.ui.components.ModelSelectorButton
import com.forgemind.app.ui.components.PendingApprovalBanner
import com.forgemind.app.ui.components.ScrollToBottomFab
import com.forgemind.app.ui.components.formatBytes
import com.forgemind.app.ui.theme.ForgeColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: MainViewModel,
    onPickAttachments: () -> Unit,
    onRequestVoiceRecord: () -> Unit,
    onSaveAttachment: (ChatAttachment) -> Unit,
    onExportMarkdown: () -> Unit,
    onExportZip: () -> Unit,
    onImportZip: () -> Unit,
    onPickSafFolder: () -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val currentSession by vm.currentSession.collectAsState()
    val messages by vm.currentMessages.collectAsState()
    val activeSessions by vm.activeSessions.collectAsState()
    val archivedSessions by vm.archivedSessions.collectAsState()
    val folders by vm.folders.collectAsState()
    val searchResults by vm.searchResults.collectAsState()
    val providerConfigs by vm.providerConfigs.collectAsState()
    val scannedModels by vm.scannedModels.collectAsState()
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

    var showModelSheet by remember { mutableStateOf(false) }
    var showPromptDialog by remember { mutableStateOf(false) }
    var showAiAttachDialog by remember { mutableStateOf(false) }
    var editingMessage by remember { mutableStateOf<ChatMessageEntity?>(null) }

    val session = currentSession
    val activeProvId = session?.providerId ?: ProviderType.OPENROUTER.id
    val activeProvType = ProviderType.fromId(activeProvId)
    val activeProvCfg = providerConfigs[activeProvId] ?: ProviderConfig(activeProvId)
    val contextWindow = activeProvCfg.contextWindow

    val visibleMessages = remember(messages) {
        messages.filter { it.role != LlmRole.TOOL.name && it.role != LlmRole.SYSTEM.name }
    }
    val mcpServers by vm.mcpServers.collectAsState()
    val sshProfiles by vm.sshProfiles.collectAsState()
    val connectedSshIds by vm.connectedSshProfileIds.collectAsState()
    var showConnectionDetails by remember { mutableStateOf(false) }

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
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Rounded.Menu, "Чаты и архив", tint = ForgeColors.TextPrimary)
                        }
                        Column(
                            modifier = Modifier
                                .clickable { showModelSheet = true }
                                .padding(horizontal = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = (session?.modelId ?: activeProvCfg.modelId).substringAfterLast('/'),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ForgeColors.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 155.dp)
                                )
                                Icon(
                                    Icons.Rounded.KeyboardArrowDown,
                                    null,
                                    tint = ForgeColors.AccentBlue,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Text(
                                text = activeProvType.displayName,
                                fontSize = 10.5.sp,
                                color = ForgeColors.TextSecondary
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isSpeakingTts) {
                            val speedLabel = if ((voiceSettings.ttsSpeed * 100f + 0.5f).toInt() % 10 == 0) {
                                String.format(java.util.Locale.US, "%.1fx", voiceSettings.ttsSpeed)
                            } else {
                                String.format(java.util.Locale.US, "%.2fx", voiceSettings.ttsSpeed)
                            }
                            Surface(
                                color = ForgeColors.AccentAmber.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .padding(end = 4.dp)
                                    .clickable { vm.cycleTtsSpeed() }
                            ) {
                                Text(
                                    text = speedLabel,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ForgeColors.AccentAmber,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            IconButton(onClick = { vm.stopSpeaking() }) {
                                Icon(Icons.AutoMirrored.Rounded.VolumeOff, "Остановить речь", tint = ForgeColors.AccentAmber)
                            }
                        }

                        IconButton(onClick = { showPromptDialog = true }) {
                            Icon(Icons.Rounded.EditNote, "Системный промпт чата", tint = ForgeColors.TextSecondary)
                        }
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
                expanded = showConnectionDetails,
                onToggleExpand = { showConnectionDetails = !showConnectionDetails },
                onRefreshAll = { vm.refreshConnectionIndicators() },
                onToggleMcp = { srv, enabled -> vm.toggleMcpServer(srv, enabled) },
                onTestMcp = { id -> vm.testMcpServer(id) },
                onTestSsh = { prof ->
                    vm.testSshConnection(prof) { res -> vm.showToast(res) }
                },
                onOpenSettings = {
                    showConnectionDetails = false
                    vm.selectSection(AppSection.SETTINGS)
                }
            )

            HorizontalDivider(color = ForgeColors.BorderSubtle.copy(alpha = 0.35f))

            ContextUsageBar(
                estimatedTokens = session?.estimatedContextTokens ?: 0,
                contextWindow = contextWindow,
                onCompactClick = { vm.compactContextNow() }
            )

            // Agent active banner
            if (isAgentRunning) {
                Surface(color = Color(0xFF13223D), modifier = Modifier.fillMaxWidth()) {
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
                                text = statusBanner ?: "Агент работает...",
                                fontSize = 12.sp,
                                color = ForgeColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(
                            onClick = { vm.stopAgent() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("Стоп", color = ForgeColors.DangerRed, fontSize = 12.sp)
                        }
                    }
                }
            }

            // Pending tool approval
            pendingApprovals.firstOrNull()?.let { pending ->
                PendingApprovalBanner(
                    approval = pending,
                    onAllow = { vm.resolveApproval(pending.id, true) },
                    onDeny = { vm.resolveApproval(pending.id, false) }
                )
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
                        Text(
                            text = "ForgeMind AI",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = ForgeColors.TextPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Полный агент с доступом к Termux/SSH, редактором кода, вебом, голосом XTTS-v2/Whisper и MCP-серверами.",
                            fontSize = 13.5.sp,
                            color = ForgeColors.TextSecondary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        if (activeProvCfg.parsedKeys().isEmpty() && activeProvType != ProviderType.OLLAMA) {
                            OutlinedButton(onClick = { vm.selectSection(AppSection.SETTINGS) }) {
                                Icon(Icons.Rounded.Tune, null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Добавить API-ключ в Настройках")
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
                            val isAssistantInterrupted = !isUserMsg &&
                                (msg.isError || msg.content.trimEnd().endsWith("*(остановлено)*"))
                            val isLastUserWithoutReply = isUserMsg && idx == visibleMessages.lastIndex
                            val nextMsg = visibleMessages.getOrNull(idx + 1)
                            val nextIsFailedAssistant = isUserMsg && nextMsg != null &&
                                nextMsg.role != LlmRole.USER.name &&
                                (nextMsg.isError || nextMsg.content.trimEnd().endsWith("*(остановлено)*"))
                            val canRetry = !isAgentRunning &&
                                (isAssistantInterrupted || isLastUserWithoutReply || nextIsFailedAssistant)
                            val canEdit = !isAgentRunning && isUserMsg

                            ChatMessageBubble(
                                message = msg,
                                isSpeakingThis = isSpeakingTts && speakingMessageId == msg.id,
                                canRetry = canRetry,
                                canEdit = canEdit,
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
                    if (draftAttachments.isNotEmpty()) {
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
                                    shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, ForgeColors.BorderSubtle)
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
                                            "Удалить",
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

                    if (isRecording) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                                .background(Color(0xFF2B1319), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(ForgeColors.DangerRed)
                            )
                            Text("Запись голоса...", fontSize = 12.sp, color = ForgeColors.TextPrimary)
                            LinearProgressIndicator(
                                progress = { voiceAmplitude.coerceIn(0.05f, 1f) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(4.dp),
                                color = ForgeColors.DangerRed
                            )
                            Text("Стоп", fontSize = 11.sp, color = ForgeColors.AccentAmber, modifier = Modifier.clickable { onRequestVoiceRecord() })
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconButton(onClick = { showAiAttachDialog = true }, modifier = Modifier.size(42.dp)) {
                            Icon(Icons.Rounded.Add, "Прикрепить файл из папки ИИ", tint = ForgeColors.TextPrimary)
                        }

                        IconButton(
                            onClick = onRequestVoiceRecord,
                            modifier = Modifier
                                .size(42.dp)
                                .background(
                                    if (isRecording) ForgeColors.DangerRed.copy(alpha = 0.25f) else Color.Transparent,
                                    CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = if (isRecording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                                contentDescription = "Голосовой ввод",
                                tint = if (isRecording) ForgeColors.DangerRed else ForgeColors.TextSecondary
                            )
                        }

                        OutlinedTextField(
                            value = composerText,
                            onValueChange = { vm.updateComposerText(it) },
                            placeholder = { Text("Задача или команда...", fontSize = 13.5.sp, maxLines = 1) },
                            maxLines = 5,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp)
                        )

                        if (isAgentRunning) {
                            IconButton(
                                onClick = { vm.stopAgent() },
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(ForgeColors.DangerRed, CircleShape)
                            ) {
                                Icon(Icons.Rounded.Stop, "Остановить", tint = Color.White)
                            }
                        } else {
                            IconButton(
                                onClick = { vm.sendMessage() },
                                enabled = composerText.isNotBlank() || draftAttachments.isNotEmpty(),
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(
                                        if (composerText.isNotBlank() || draftAttachments.isNotEmpty()) ForgeColors.AccentBlue else ForgeColors.SurfaceElevated,
                                        CircleShape
                                    )
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.Send, "Отправить", tint = Color.White)
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
            scannedModels = scannedModels,
            onScanModels = { cfg, cb -> vm.scanModelsForProvider(cfg, cb) },
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

    if (showPromptDialog) {
        var promptDraft by remember(session?.id) { mutableStateOf(session?.customSystemPrompt ?: "") }
        AlertDialog(
            onDismissRequest = { showPromptDialog = false },
            title = { Text("Системный промпт этого чата") },
            text = {
                OutlinedTextField(
                    value = promptDraft,
                    onValueChange = { promptDraft = it },
                    placeholder = { Text("Особые инструкции для текущей сессии...") },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    vm.updateSessionCustomPrompt(promptDraft)
                    showPromptDialog = false
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { showPromptDialog = false }) { Text("Отмена") }
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
            title = { Text("Редактировать сообщение") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draftText,
                        onValueChange = { draftText = it },
                        placeholder = { Text("Текст сообщения...") },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "«Сохранить и отправить» обновит текст и перезапустит ответ ИИ с этого места.",
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
                    ) { Text("Сохранить", fontSize = 12.sp) }
                    Button(
                        enabled = draftText.isNotBlank() && !isAgentRunning,
                        onClick = {
                            vm.editUserMessage(targetMsg, draftText, resend = true)
                            editingMessage = null
                        }
                    ) { Text("И отправить", fontSize = 12.sp) }
                }
            },
            dismissButton = {
                TextButton(onClick = { editingMessage = null }) { Text("Отмена") }
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
        title = { Text("Файлы для ИИ", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
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
                        label = { Text("Домашняя ИИ", fontSize = 11.5.sp) }
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
                                if (hasSaf) "Папка Android" else "Выбрать папку...",
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
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Наверх", modifier = Modifier.size(16.dp))
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
                        "Папка пуста. Выберите файл с телефона — он сохранится прямо в домашнюю директорию ИИ.",
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
                    Text("С телефона (в домашнюю папку ИИ)", fontSize = 11.5.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Готово") }
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
    onExportMarkdown: () -> Unit,
    onExportZip: () -> Unit,
    onImportZip: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var showArchiveTab by remember { mutableStateOf(false) }
    var selectedFolderId by remember { mutableStateOf<String?>(null) }
    var showNewFolderDialog by remember { mutableStateOf(false) }

    val baseList = when {
        query.isNotBlank() -> searchResults
        showArchiveTab -> archivedSessions
        selectedFolderId != null -> activeSessions.filter { it.folderId == selectedFolderId }
        else -> activeSessions
    }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Чаты и Архив", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ForgeColors.TextPrimary)
            Row {
                IconButton(onClick = { showNewFolderDialog = true }) {
                    Icon(Icons.Rounded.CreateNewFolder, "Новая папка", tint = ForgeColors.TextSecondary)
                }
                IconButton(onClick = { onNewChat(selectedFolderId) }) {
                    Icon(Icons.Rounded.Add, "Новый чат", tint = ForgeColors.AccentBlue)
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                onSearchQuery(it)
            },
            placeholder = { Text("Поиск по чатам...", fontSize = 13.sp) },
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
                label = { Text("Все (${activeSessions.size})", fontSize = 11.5.sp) }
            )
            FilterChip(
                selected = showArchiveTab,
                onClick = {
                    showArchiveTab = true
                    selectedFolderId = null
                },
                leadingIcon = { Icon(Icons.Rounded.Archive, null, modifier = Modifier.size(14.dp)) },
                label = { Text("Архив (${archivedSessions.size})", fontSize = 11.5.sp) }
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
                DrawerSessionRow(
                    session = s,
                    isSelected = s.id == currentSessionId,
                    folders = folders,
                    onClick = { onSelectSession(s.id) },
                    onTogglePin = { onTogglePin(s.id, !s.isPinned) },
                    onToggleArchive = { onToggleArchive(s.id, !s.isArchived) },
                    onAssignFolder = { fid -> onAssignFolder(s.id, fid) },
                    onDelete = { onDeleteSession(s.id) }
                )
            }
        }

        HorizontalDivider(color = ForgeColors.BorderSubtle, modifier = Modifier.padding(vertical = 8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onExportMarkdown) {
                Icon(Icons.Rounded.Download, null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("MD", fontSize = 11.5.sp)
            }
            TextButton(onClick = onExportZip) {
                Icon(Icons.Rounded.FolderZip, null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Бэкап ZIP", fontSize = 11.5.sp)
            }
            TextButton(onClick = onImportZip) {
                Icon(Icons.Rounded.UploadFile, null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Восстан.", fontSize = 11.5.sp)
            }
        }
    }

    if (showNewFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("Новая папка чатов") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    placeholder = { Text("Например: Работа / Сервер") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    onCreateFolder(folderName)
                    showNewFolderDialog = false
                }) { Text("Создать") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun DrawerSessionRow(
    session: ChatSessionEntity,
    isSelected: Boolean,
    folders: List<ChatFolderEntity>,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleArchive: () -> Unit,
    onAssignFolder: (String?) -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var folderDialog by remember { mutableStateOf(false) }

    Surface(
        color = if (isSelected) ForgeColors.AccentBlue.copy(alpha = 0.16f) else Color.Transparent,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (session.isPinned) {
                Icon(
                    Icons.Rounded.PushPin,
                    null,
                    tint = ForgeColors.AccentAmber,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title,
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
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Rounded.MoreVert, null, tint = ForgeColors.TextSecondary, modifier = Modifier.size(16.dp))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (session.isPinned) "Открепить" else "Закрепить") },
                        leadingIcon = { Icon(Icons.Rounded.PushPin, null) },
                        onClick = {
                            menuOpen = false
                            onTogglePin()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Переместить в папку") },
                        leadingIcon = { Icon(Icons.Rounded.Folder, null) },
                        onClick = {
                            menuOpen = false
                            folderDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (session.isArchived) "Вернуть из Архива" else "В Архив") },
                        leadingIcon = { Icon(if (session.isArchived) Icons.Rounded.Unarchive else Icons.Rounded.Archive, null) },
                        onClick = {
                            menuOpen = false
                            onToggleArchive()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Удалить", color = ForgeColors.DangerRed) },
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

    if (folderDialog) {
        AlertDialog(
            onDismissRequest = { folderDialog = false },
            title = { Text("Выберите папку") },
            text = {
                Column {
                    TextButton(onClick = {
                        onAssignFolder(null)
                        folderDialog = false
                    }) { Text("Без папки") }
                    folders.forEach { f ->
                        TextButton(onClick = {
                            onAssignFolder(f.id)
                            folderDialog = false
                        }) { Text(f.name) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { folderDialog = false }) { Text("Закрыть") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerBottomSheet(
    currentProviderId: String,
    currentModelId: String,
    providerConfigs: Map<String, ProviderConfig>,
    scannedModels: Map<String, List<ModelInfo>>,
    onScanModels: (ProviderConfig, (String) -> Unit) -> Unit,
    onSelectModel: (String, String) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedProv by remember { mutableStateOf(currentProviderId) }
    val cfg = providerConfigs[selectedProv] ?: ProviderConfig(selectedProv)
    var customModel by remember(selectedProv) {
        mutableStateOf(if (selectedProv == currentProviderId) currentModelId else cfg.modelId)
    }
    var scanNote by remember(selectedProv) { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ForgeColors.SurfaceDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Провайдер и модель", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ForgeColors.TextPrimary)
                TextButton(onClick = onOpenSettings) { Text("Ключи API ->") }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ProviderType.entries.forEach { p ->
                    val hasKey = (providerConfigs[p.id]?.parsedKeys()?.isNotEmpty() == true) || p == ProviderType.OLLAMA
                    FilterChip(
                        selected = selectedProv == p.id,
                        onClick = { selectedProv = p.id },
                        label = {
                            Text(
                                text = if (hasKey) "● ${p.displayName}" else p.displayName,
                                fontSize = 12.sp
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = customModel,
                    onValueChange = { customModel = it },
                    label = { Text("Model ID") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        scanning = true
                        onScanModels(cfg) { msg ->
                            scanning = false
                            scanNote = msg
                        }
                    },
                    enabled = !scanning
                ) {
                    if (scanning) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Icon(Icons.Rounded.Refresh, "Сканировать", modifier = Modifier.size(18.dp))
                    }
                }
            }

            scanNote?.let {
                Text(it, fontSize = 11.5.sp, color = ForgeColors.AccentEmerald, modifier = Modifier.padding(top = 4.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))

            val modelsList = scannedModels[selectedProv].orEmpty()
            ModelSelectorButton(
                models = modelsList,
                selectedModelId = customModel.ifBlank { cfg.modelId },
                scanning = scanning,
                onRequestScan = {
                    scanning = true
                    onScanModels(cfg) { msg ->
                        scanning = false
                        scanNote = msg
                    }
                },
                onSelectModel = { model ->
                    customModel = model.id
                    onSelectModel(selectedProv, model.id)
                }
            )

            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = { onSelectModel(selectedProv, customModel.ifBlank { cfg.modelId }) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = ForgeColors.AccentBlue)
            ) {
                Text("Использовать модель")
            }
        }
    }
}
