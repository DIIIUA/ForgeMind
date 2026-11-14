package com.forgemind.app.ui

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.StringRes
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.forgemind.app.ForgeMindApp
import com.forgemind.app.R
import com.forgemind.app.bridge.InAppDocumentParser
import com.forgemind.app.bridge.TermuxBridge
import com.forgemind.app.bridge.TerminalViewMode
import com.forgemind.app.bridge.WorkspaceManager
import com.forgemind.app.i18n.LocaleHelper
import com.forgemind.app.data.db.ChatFolderEntity
import com.forgemind.app.data.db.ChatMessageEntity
import com.forgemind.app.data.db.ChatSessionEntity
import com.forgemind.app.data.db.FileSnapshotEntity
import com.forgemind.app.data.db.McpServerEntity
import com.forgemind.app.data.db.SshProfileEntity
import com.forgemind.app.data.model.AgentSettings
import com.forgemind.app.data.model.ChatAttachment
import com.forgemind.app.data.model.ConversationBranching
import com.forgemind.app.data.model.McpHostSettings
import com.forgemind.app.data.model.McpToolInfo
import com.forgemind.app.data.model.McpTransport
import com.forgemind.app.data.model.ModelInfo
import com.forgemind.app.data.model.ProviderAuthMode
import com.forgemind.app.data.model.ProviderCategory
import com.forgemind.app.data.model.ProviderConfig
import com.forgemind.app.data.model.ProviderType
import com.forgemind.app.data.model.ReasoningEffort
import com.forgemind.app.data.model.ResolvedBranchView
import com.forgemind.app.data.model.SearchSettings
import com.forgemind.app.data.model.SttProviderType
import com.forgemind.app.data.model.TtsProviderType
import com.forgemind.app.data.model.UiThemeSettings
import com.forgemind.app.data.model.VoiceSettings
import com.forgemind.app.data.model.WorkspaceEnvType
import com.forgemind.app.data.model.WorkspaceFileItem
import com.forgemind.app.data.prefs.SettingsRepository
import com.forgemind.app.llm.ConnectionTestResult
import com.forgemind.app.llm.DiscoveredLanAiServer
import com.forgemind.app.llm.LanAiScanner
import com.forgemind.app.voice.SttFallbackPolicy
import com.forgemind.app.voice.TtsManager
import com.forgemind.app.voice.XttsHealthStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class EditorTabKind {
    TEXT_CODE,
    MARKDOWN,
    IMAGE,
    PDF,
    DOCUMENT,
    ARCHIVE,
    BINARY
}

data class OpenEditorTab(
    val id: String,
    val title: String,
    val path: String,
    val env: WorkspaceEnvType,
    val content: String = "",
    val isDirty: Boolean = false,
    val sshServerId: String? = null,
    val version: Long = 0L,
    val kind: EditorTabKind = EditorTabKind.TEXT_CODE,
    val localCachePath: String? = null,
    val sizeBytes: Long = 0L,
    val mimeType: String = "application/octet-stream",
    val subtitle: String? = null
)

enum class AppSection(val label: String) {
    CHAT("Chat"),
    FILES("AI Files"),
    TERMINAL("Terminal"),
    PREVIEW("Browser"),
    SETTINGS("Settings")
}

data class FileClipboardEntry(
    val item: WorkspaceFileItem,
    val isCut: Boolean
)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val c = (application as ForgeMindApp).container

    private val localizedAppContext: Context
        get() = LocaleHelper.wrapContext(getApplication())

    private fun str(@StringRes resId: Int, vararg args: Any): String =
        if (args.isEmpty()) localizedAppContext.getString(resId)
        else localizedAppContext.getString(resId, *args)

    val providerConfigs = c.settingsRepo.providerConfigs
    val activeProviderId = c.settingsRepo.activeProviderId
    val hiddenBuiltInProviders: StateFlow<Set<String>> = c.settingsRepo.hiddenBuiltInProviders
    val uiThemeSettings: StateFlow<UiThemeSettings> = c.settingsRepo.uiThemeSettings
    val connectionStripExpanded: StateFlow<Boolean> = c.settingsRepo.connectionStripExpanded
    val voiceSettings = c.settingsRepo.voiceSettings
    val searchSettings = c.settingsRepo.searchSettings
    val agentSettings = c.settingsRepo.agentSettings
    val mcpHostSettings = c.settingsRepo.mcpHostSettings
    val mcpHostRuntime = c.mcpHostServer.runtimeState
    val editorTheme = c.settingsRepo.editorTheme
    val safTreeUri = c.settingsRepo.safTreeUri
    val favoriteModels = c.settingsRepo.favoriteModels

    private val _providerHealth = MutableStateFlow<Map<String, Boolean?>>(emptyMap())
    val providerHealth: StateFlow<Map<String, Boolean?>> = _providerHealth.asStateFlow()

    val isAgentRunning = c.agentEngine.isRunning
    val agentStatusBanner = c.agentEngine.statusBanner
    val pendingApprovals = c.agentEngine.pendingApprovals

    val isRecording = c.audioRecorder.isRecording
    val voiceAmplitude = c.audioRecorder.amplitude
    val isSpeakingTts = c.ttsManager.isSpeaking
    val speakingMessageId = c.ttsManager.speakingMessageId

    val terminalTabs = c.terminalManager.tabs
    val activeTerminalTabId = c.terminalManager.activeTabId
    val aiTerminalState = c.terminalManager.aiTerminalState
    val terminalViewMode = c.terminalManager.viewMode

    private val _appLanguage = MutableStateFlow(LocaleHelper.getSavedLanguageCode(application))
    val appLanguage: StateFlow<String> = _appLanguage.asStateFlow()

    val browserController = c.browserController
    val aiBrowserUrl: StateFlow<String> = c.browserController.currentUrl
    val agentBrowserMode: StateFlow<Boolean> = c.browserController.agentModeEnabled
    val agentBrowserTurbo: StateFlow<Boolean> = c.browserController.turboModeEnabled
    val agentBrowserTabs: StateFlow<List<com.forgemind.app.web.AgentBrowserTabInfo>> = c.browserController.agentTabs
    val activeAgentBrowserTabId: StateFlow<String> = c.browserController.activeAgentTabId
    val agentBrowserTitle: StateFlow<String> = c.browserController.pageTitle
    val agentBrowserLoading: StateFlow<Boolean> = c.browserController.isLoading
    val agentBrowserProgress: StateFlow<Int> = c.browserController.loadProgress
    val agentBrowserCanGoBack: StateFlow<Boolean> = c.browserController.agentCanGoBack
    val agentBrowserCanGoForward: StateFlow<Boolean> = c.browserController.agentCanGoForward
    val agentBrowserBanner: StateFlow<String?> = c.browserController.lastActionBanner
    val agentBrowserLogs: StateFlow<List<String>> = c.browserController.actionLog
    val agentBrowserConsole: StateFlow<List<String>> = c.browserController.consoleLogs

    val userBrowserUrl: StateFlow<String> = c.browserController.userCurrentUrl
    val userBrowserTitle: StateFlow<String> = c.browserController.userPageTitle
    val userBrowserLoading: StateFlow<Boolean> = c.browserController.userIsLoading
    val userBrowserProgress: StateFlow<Int> = c.browserController.userLoadProgress
    val userBrowserCanGoBack: StateFlow<Boolean> = c.browserController.userCanGoBack
    val userBrowserCanGoForward: StateFlow<Boolean> = c.browserController.userCanGoForward
    val userBrowserConsole: StateFlow<List<String>> = c.browserController.userConsoleLogs

    val activeSessions = c.db.sessionDao().observeByArchive(false)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val archivedSessions = c.db.sessionDao().observeByArchive(true)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val folders = c.db.folderDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val mcpServers = c.db.mcpServerDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val sshProfiles = c.db.sshProfileDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val connectedSshProfileIds: StateFlow<Set<String>> = c.termuxBridge.connectedProfileIds
    val sshReachability: StateFlow<Map<String, Boolean>> = c.termuxBridge.sshReachability

    val selectedSection: StateFlow<AppSection> = c.selectedSection.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    val currentSession: StateFlow<ChatSessionEntity?> = _currentSessionId.flatMapLatest { sid ->
        if (sid == null) flowOf(null) else c.db.sessionDao().observeById(sid)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val currentMessages: StateFlow<List<ChatMessageEntity>> = _currentSessionId.flatMapLatest { sid ->
        if (sid == null) flowOf(emptyList()) else c.db.messageDao().observeForSession(sid)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val resolvedBranchView: StateFlow<ResolvedBranchView> = combine(
        currentMessages,
        currentSession
    ) { msgs, session ->
        ConversationBranching.resolve(msgs, session?.branchSelectionsJson.orEmpty())
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ResolvedBranchView(emptyList(), emptyMap(), emptyMap())
    )

    val currentSnapshots: StateFlow<List<FileSnapshotEntity>> = _currentSessionId.flatMapLatest { sid ->
        if (sid == null) flowOf(emptyList()) else c.db.snapshotDao().observeForSession(sid)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _composerText = MutableStateFlow("")
    val composerText: StateFlow<String> = _composerText.asStateFlow()

    private val _draftAttachments = MutableStateFlow<List<ChatAttachment>>(emptyList())
    val draftAttachments: StateFlow<List<ChatAttachment>> = _draftAttachments.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _scannedModels = MutableStateFlow<Map<String, List<ModelInfo>>>(emptyMap())
    val scannedModels: StateFlow<Map<String, List<ModelInfo>>> = _scannedModels.asStateFlow()

    private val _xttsVoices = MutableStateFlow<List<String>>(emptyList())
    val xttsVoices: StateFlow<List<String>> = _xttsVoices.asStateFlow()

    private val _xttsDefaultVoice = MutableStateFlow<String?>(null)
    val xttsDefaultVoice: StateFlow<String?> = _xttsDefaultVoice.asStateFlow()

    private val _edgeTtsVoices = MutableStateFlow(com.forgemind.app.voice.MicrosoftEdgeTtsClient.CURATED_VOICES)
    val edgeTtsVoices: StateFlow<List<com.forgemind.app.voice.EdgeNeuralVoice>> = _edgeTtsVoices.asStateFlow()

    // Explorer & Editor state
    private val _explorerEnv = MutableStateFlow(WorkspaceEnvType.INTERNAL)
    val explorerEnv: StateFlow<WorkspaceEnvType> = _explorerEnv.asStateFlow()

    private val _explorerSshProfileId = MutableStateFlow<String?>(null)
    val explorerSshProfileId: StateFlow<String?> = _explorerSshProfileId.asStateFlow()

    private val _explorerPath = MutableStateFlow("")
    val explorerPath: StateFlow<String> = _explorerPath.asStateFlow()

    private val _explorerItems = MutableStateFlow<List<WorkspaceFileItem>>(emptyList())
    val explorerItems: StateFlow<List<WorkspaceFileItem>> = _explorerItems.asStateFlow()

    private val _explorerError = MutableStateFlow<String?>(null)
    val explorerError: StateFlow<String?> = _explorerError.asStateFlow()

    private val _explorerLoading = MutableStateFlow(false)
    val explorerLoading: StateFlow<Boolean> = _explorerLoading.asStateFlow()

    // Dual-pane (2nd explorer window) & clipboard state
    private val _splitExplorerEnabled = MutableStateFlow(false)
    val splitExplorerEnabled: StateFlow<Boolean> = _splitExplorerEnabled.asStateFlow()

    private val _secondExplorerEnv = MutableStateFlow(WorkspaceEnvType.DEVICE_STORAGE)
    val secondExplorerEnv: StateFlow<WorkspaceEnvType> = _secondExplorerEnv.asStateFlow()

    private val _secondExplorerSshProfileId = MutableStateFlow<String?>(null)
    val secondExplorerSshProfileId: StateFlow<String?> = _secondExplorerSshProfileId.asStateFlow()

    private val _secondExplorerPath = MutableStateFlow("")
    val secondExplorerPath: StateFlow<String> = _secondExplorerPath.asStateFlow()

    private val _secondExplorerItems = MutableStateFlow<List<WorkspaceFileItem>>(emptyList())
    val secondExplorerItems: StateFlow<List<WorkspaceFileItem>> = _secondExplorerItems.asStateFlow()

    private val _secondExplorerLoading = MutableStateFlow(false)
    val secondExplorerLoading: StateFlow<Boolean> = _secondExplorerLoading.asStateFlow()

    private val _secondExplorerError = MutableStateFlow<String?>(null)
    val secondExplorerError: StateFlow<String?> = _secondExplorerError.asStateFlow()

    private val _fileClipboard = MutableStateFlow<FileClipboardEntry?>(null)
    val fileClipboard: StateFlow<FileClipboardEntry?> = _fileClipboard.asStateFlow()

    private var pendingUploadSecondPane: Boolean = false
    private var pendingSafSecondPane: Boolean = false
    private var sshReachabilityJob: Job? = null
    private var fullProviderProbeJob: Job? = null
    private val singleProviderProbeJobs = mutableMapOf<String, Job>()

    val editorTabs: StateFlow<List<OpenEditorTab>> = c.editorTabs.asStateFlow()

    val activeEditorTabId: StateFlow<String?> = c.activeEditorTabId.asStateFlow()

    val previewPort: StateFlow<Int> = c.previewPort.asStateFlow()

    private val _searchResults = MutableStateFlow<List<ChatSessionEntity>>(emptyList())
    val searchResults: StateFlow<List<ChatSessionEntity>> = _searchResults.asStateFlow()

    init {
        viewModelScope.launch {
            c.db.messageDao().resetStaleStreaming()
            c.settingsRepo.loadAll()
            val savedId = c.settingsRepo.activeSessionIdFlow().first()
            val existing = savedId?.let { c.db.sessionDao().getById(it) }
                ?: c.db.sessionDao().observeByArchive(false).first().firstOrNull()
            if (existing != null) {
                _currentSessionId.value = existing.id
            } else {
                createNewSession()
            }
            probeProvidersHealth()
            refreshExplorer()
        }

        // Sync SSH profiles pool into TermuxBridge whenever DB changes
        viewModelScope.launch {
            sshProfiles.collect { profiles ->
                if (profiles.isNotEmpty()) {
                    c.termuxBridge.updateProfiles(profiles)
                    val enabled = profiles.filter { it.enabled }
                    val defaultSshId = enabled.firstOrNull { it.isDefault }?.id
                        ?: enabled.firstOrNull()?.id
                        ?: profiles.firstOrNull()?.id
                    val curId = _explorerSshProfileId.value
                    if (curId == null || enabled.none { it.id == curId }) {
                        _explorerSshProfileId.value = defaultSshId
                    }
                    val curSecondId = _secondExplorerSshProfileId.value
                    if (curSecondId == null || enabled.none { it.id == curSecondId }) {
                        _secondExplorerSshProfileId.value = defaultSshId
                    }
                }
                sshReachabilityJob?.cancel()
                sshReachabilityJob = launch {
                    c.termuxBridge.probeEnabledProfilesReachability()
                }
            }
        }

        // Sync MCP servers into TermuxBridge for automatic Cloudflare Access token reuse
        viewModelScope.launch {
            mcpServers.collect { servers ->
                c.termuxBridge.updateMcpServers(servers)
            }
        }

        // Observe TTS errors into toast
        viewModelScope.launch {
            c.ttsManager.lastError.collect { err ->
                if (!err.isNullOrBlank()) {
                    _toastMessage.value = err
                    c.ttsManager.clearError()
                }
            }
        }
    }

    fun selectSection(section: AppSection) {
        c.selectedSection.value = section
        if (section == AppSection.FILES) {
            refreshExplorer()
            if (_splitExplorerEnabled.value) {
                refreshSecondExplorer()
            }
        }
    }

    fun clearToast() {
        _toastMessage.value = null
    }

    fun showToast(msg: String) {
        _toastMessage.value = msg
    }

    // ---- Chat Sessions & Folders ----
    fun createNewSession(folderId: String? = null) {
        viewModelScope.launch {
            val prov = c.settingsRepo.getActiveProviderConfig()
            val id = UUID.randomUUID().toString()
            val session = ChatSessionEntity(
                id = id,
                title = str(R.string.floating_new_chat),
                folderId = folderId,
                providerId = prov.providerId,
                modelId = prov.modelId,
                workspaceEnv = WorkspaceEnvType.INTERNAL.id,
                workspacePath = "default"
            )
            c.db.sessionDao().upsert(session)
            _currentSessionId.value = id
            c.settingsRepo.setActiveSessionId(id)
        }
    }

    fun selectSession(sessionId: String) {
        _currentSessionId.value = sessionId
        viewModelScope.launch { c.settingsRepo.setActiveSessionId(sessionId) }
    }

    fun toggleArchiveSession(sessionId: String, archived: Boolean) {
        viewModelScope.launch {
            c.db.sessionDao().setArchived(sessionId, archived)
            if (archived && _currentSessionId.value == sessionId) {
                val next = c.db.sessionDao().observeByArchive(false).first().firstOrNull { it.id != sessionId }
                if (next != null) selectSession(next.id) else createNewSession()
            }
            _toastMessage.value = str(if (archived) R.string.toast_chat_archived else R.string.toast_chat_unarchived)
        }
    }

    fun togglePinSession(sessionId: String, pinned: Boolean) {
        viewModelScope.launch { c.db.sessionDao().setPinned(sessionId, pinned) }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            c.db.sessionDao().deleteById(sessionId)
            if (_currentSessionId.value == sessionId) {
                val next = c.db.sessionDao().observeByArchive(false).first().firstOrNull()
                if (next != null) selectSession(next.id) else createNewSession()
            }
        }
    }

    fun createFolder(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            c.db.folderDao().upsert(ChatFolderEntity(UUID.randomUUID().toString(), clean))
        }
    }

    fun assignSessionFolder(sessionId: String, folderId: String?) {
        viewModelScope.launch {
            val s = c.db.sessionDao().getById(sessionId) ?: return@launch
            c.db.sessionDao().upsert(s.copy(folderId = folderId))
        }
    }

    fun selectMessageBranch(parentKey: String, childId: String) {
        viewModelScope.launch {
            val sid = _currentSessionId.value ?: return@launch
            val s = c.db.sessionDao().getById(sid) ?: return@launch
            val nextJson = ConversationBranching.withSelection(s.branchSelectionsJson, parentKey, childId)
            c.db.sessionDao().updateBranchSelections(sid, nextJson)
        }
    }

    fun deleteSessions(ids: Set<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            c.db.sessionDao().deleteByIds(ids.toList())
            if (_currentSessionId.value in ids) {
                val next = c.db.sessionDao().observeByArchive(false).first().firstOrNull { it.id !in ids }
                if (next != null) selectSession(next.id) else createNewSession()
            }
            _toastMessage.value = str(R.string.toast_batch_deleted_fmt, ids.size)
        }
    }

    fun archiveSessions(ids: Set<String>, archived: Boolean) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            c.db.sessionDao().setArchivedForIds(ids.toList(), archived)
            if (archived && _currentSessionId.value in ids) {
                val next = c.db.sessionDao().observeByArchive(false).first().firstOrNull { it.id !in ids }
                if (next != null) selectSession(next.id) else createNewSession()
            }
            _toastMessage.value = str(
                if (archived) R.string.toast_batch_archived_fmt else R.string.toast_batch_unarchived_fmt,
                ids.size
            )
        }
    }

    fun assignSessionsFolder(ids: Set<String>, folderId: String?) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            c.db.sessionDao().assignFolderForIds(ids.toList(), folderId)
            _toastMessage.value = str(R.string.toast_batch_moved_folder_fmt, ids.size)
        }
    }

    fun toggleFavoriteModel(providerId: String, modelId: String) {
        viewModelScope.launch {
            c.settingsRepo.toggleFavoriteModel(providerId, modelId)
        }
    }

    fun updateReasoningEffort(providerId: String, effort: ReasoningEffort) {
        viewModelScope.launch {
            val cfg = c.settingsRepo.getProviderConfig(providerId)
            c.settingsRepo.saveProviderConfig(cfg.withReasoningEffort(effort))
        }
    }

    fun searchSessions(query: String) {
        viewModelScope.launch {
            _searchResults.value = if (query.isBlank()) emptyList() else c.db.sessionDao().search(query.trim())
        }
    }

    fun updateSessionModel(providerId: String, modelId: String) {
        viewModelScope.launch {
            c.settingsRepo.setActiveProvider(providerId)
            val cfg = c.settingsRepo.getProviderConfig(providerId)
            val scannedCtx = _scannedModels.value[providerId]
                ?.firstOrNull { it.id == modelId }
                ?.contextLength
            val nextCtx = cfg.resolveContextWindowForModel(modelId, scannedCtx)
            val updatedCfg = cfg.copy(modelId = modelId, contextWindow = nextCtx)
            c.settingsRepo.saveProviderConfig(updatedCfg)
            probeProvidersHealth(providerId)
            val sid = _currentSessionId.value
            val s = sid?.let { c.db.sessionDao().getById(it) }
            if (s != null) {
                c.db.sessionDao().upsert(s.copy(providerId = providerId, modelId = modelId))
            }
            if (updatedCfg.autoContextWindow &&
                !updatedCfg.isManualContextForModel(modelId) &&
                scannedCtx == null
            ) {
                val probedCtx = c.modelScanner.probeModelContextLength(updatedCfg, modelId)
                if (probedCtx != null && probedCtx >= 1_024) {
                    val existingModels = _scannedModels.value[providerId].orEmpty()
                    val updatedList = if (existingModels.any { it.id == modelId }) {
                        existingModels.map { m ->
                            if (m.id == modelId) m.copy(contextLength = probedCtx) else m
                        }
                    } else {
                        existingModels + ModelInfo(id = modelId, name = modelId, contextLength = probedCtx)
                    }
                    _scannedModels.value = _scannedModels.value + (providerId to updatedList)
                    val latestCfg = c.settingsRepo.getProviderConfig(providerId)
                    if (latestCfg.modelId == modelId && !latestCfg.isManualContextForModel(modelId)) {
                        c.settingsRepo.saveProviderConfig(latestCfg.copy(contextWindow = probedCtx))
                    }
                }
            }
        }
    }

    fun setModelContextWindowAuto(providerId: String, modelId: String) {
        viewModelScope.launch {
            val cfg = c.settingsRepo.getProviderConfig(providerId)
            val cleanModel = modelId.trim().ifBlank { cfg.modelId }
            val cleanedOverrides = cfg.modelContextOverrides - cleanModel
            val scannedCtx = _scannedModels.value[providerId]
                ?.firstOrNull { it.id == cleanModel }
                ?.contextLength
            val baseAuto = cfg.copy(
                autoContextWindow = true,
                modelContextOverrides = cleanedOverrides
            )
            val resolvedCtx = baseAuto.resolveContextWindowForModel(cleanModel, scannedCtx)
            val saved = if (baseAuto.modelId == cleanModel || cleanModel.isEmpty()) {
                baseAuto.copy(contextWindow = resolvedCtx)
            } else {
                baseAuto
            }
            c.settingsRepo.saveProviderConfig(saved)
            if (scannedCtx == null && cleanModel.isNotEmpty()) {
                val probed = c.modelScanner.probeModelContextLength(saved, cleanModel)
                if (probed != null && probed >= 1_024) {
                    val curList = _scannedModels.value[providerId].orEmpty()
                    val nextList = if (curList.any { it.id == cleanModel }) {
                        curList.map { if (it.id == cleanModel) it.copy(contextLength = probed) else it }
                    } else {
                        curList + ModelInfo(id = cleanModel, name = cleanModel, contextLength = probed)
                    }
                    _scannedModels.value = _scannedModels.value + (providerId to nextList)
                    val latest = c.settingsRepo.getProviderConfig(providerId)
                    if (latest.modelId == cleanModel && !latest.isManualContextForModel(cleanModel)) {
                        c.settingsRepo.saveProviderConfig(latest.copy(contextWindow = probed))
                    }
                }
            }
        }
    }

    fun setModelContextWindowManual(providerId: String, modelId: String, tokens: Int) {
        val safeTokens = tokens.coerceIn(1_024, 4_000_000)
        viewModelScope.launch {
            val cfg = c.settingsRepo.getProviderConfig(providerId)
            val cleanModel = modelId.trim().ifBlank { cfg.modelId.trim() }
            val nextOverrides = if (cleanModel.isNotEmpty()) {
                cfg.modelContextOverrides + (cleanModel to safeTokens)
            } else {
                cfg.modelContextOverrides
            }
            val nextCfg = cfg.copy(
                contextWindow = if (cfg.modelId.trim() == cleanModel || cleanModel.isEmpty()) safeTokens else cfg.contextWindow,
                modelContextOverrides = nextOverrides
            )
            c.settingsRepo.saveProviderConfig(nextCfg)
        }
    }

    fun updateSessionWorkspaceEnv(env: WorkspaceEnvType) {
        viewModelScope.launch {
            val sid = _currentSessionId.value ?: return@launch
            val s = c.db.sessionDao().getById(sid) ?: return@launch
            c.db.sessionDao().upsert(s.copy(workspaceEnv = env.id))
            _explorerEnv.value = env
            _explorerPath.value = ""
            refreshExplorer()
        }
    }

    fun updateSessionCustomPrompt(prompt: String, sessionId: String? = null) {
        viewModelScope.launch {
            val sid = sessionId ?: _currentSessionId.value ?: return@launch
            val s = c.db.sessionDao().getById(sid) ?: return@launch
            c.db.sessionDao().upsert(s.copy(customSystemPrompt = prompt))
            _toastMessage.value = str(R.string.toast_chat_prompt_saved)
        }
    }

    // ---- Chat Messaging & Agent ----
    fun updateComposerText(text: String) {
        _composerText.value = text
    }

    fun addAttachmentFromUri(uri: Uri) {
        viewModelScope.launch {
            val proj = currentSession.value?.workspacePath ?: "default"
            c.workspaceManager.importUriToChatAttachment(uri, proj).fold(
                onSuccess = { att ->
                    _draftAttachments.value = _draftAttachments.value + att
                    refreshExplorer()
                    _toastMessage.value = str(R.string.toast_attached_to_home_fmt, att.name)
                },
                onFailure = { _toastMessage.value = str(R.string.toast_attach_err_fmt, it.message.orEmpty()) }
            )
        }
    }

    suspend fun listFilesForAttachPicker(
        env: WorkspaceEnvType,
        path: String = "",
        sshServerId: String? = null
    ): Result<List<WorkspaceFileItem>> {
        val proj = currentSession.value?.workspacePath ?: "default"
        return c.workspaceManager.listFiles(env, path, proj, sshServer = sshServerId).map { list ->
            if (env == WorkspaceEnvType.TERMUX_SSH && sshServerId != null) {
                list.map { it.copy(sshServerId = sshServerId) }
            } else list
        }
    }

    fun attachWorkspaceItemInChat(item: WorkspaceFileItem) {
        if (item.isDirectory) return
        viewModelScope.launch {
            val proj = currentSession.value?.workspacePath ?: "default"
            c.workspaceManager.prepareAssistantOutgoingFile(
                item.envType, item.path, proj, sshServer = item.sshServerId
            ).fold(
                onSuccess = { att ->
                    _draftAttachments.value = _draftAttachments.value + att.copy(isFromAssistant = false)
                    _toastMessage.value = str(R.string.toast_attached_ai_file_fmt, item.name)
                },
                onFailure = { _toastMessage.value = str(R.string.toast_read_file_err_fmt, it.message.orEmpty()) }
            )
        }
    }

    fun addOutgoingAttachmentFromWorkspace(item: WorkspaceFileItem) {
        viewModelScope.launch {
            val proj = currentSession.value?.workspacePath ?: "default"
            c.workspaceManager.prepareAssistantOutgoingFile(
                item.envType, item.path, proj, sshServer = item.sshServerId
            ).fold(
                onSuccess = { att ->
                    _draftAttachments.value = _draftAttachments.value + att.copy(isFromAssistant = false)
                    c.selectedSection.value = AppSection.CHAT
                    _toastMessage.value = str(R.string.toast_file_attached_fmt, item.name)
                },
                onFailure = { _toastMessage.value = str(R.string.toast_read_file_err_fmt, it.message.orEmpty()) }
            )
        }
    }

    fun removeDraftAttachment(id: String) {
        _draftAttachments.value = _draftAttachments.value.filterNot { it.id == id }
    }

    fun sendMessage(overrideText: String? = null) {
        val sid = _currentSessionId.value ?: return
        val text = (overrideText ?: _composerText.value).trim()
        val atts = _draftAttachments.value
        if (text.isEmpty() && atts.isEmpty()) return

        if (overrideText == null) _composerText.value = ""
        _draftAttachments.value = emptyList()

        c.agentEngine.sendUserTurn(sid, text, atts) { replyText ->
            val v = c.settingsRepo.voiceSettings.value
            if (v.autoSpeakReplies && replyText.isNotBlank()) {
                c.ttsManager.speak(null, replyText, v)
            }
        }
    }

    fun retryMessage(message: ChatMessageEntity) {
        val sid = _currentSessionId.value ?: return
        c.agentEngine.retryFromMessage(sid, message) { replyText ->
            val v = c.settingsRepo.voiceSettings.value
            if (v.autoSpeakReplies && replyText.isNotBlank()) {
                c.ttsManager.speak(null, replyText, v)
            }
        }
    }

    fun editUserMessage(message: ChatMessageEntity, newText: String, resend: Boolean) {
        val sid = _currentSessionId.value ?: return
        c.agentEngine.editUserMessage(sid, message, newText, resend) { replyText ->
            val v = c.settingsRepo.voiceSettings.value
            if (v.autoSpeakReplies && replyText.isNotBlank()) {
                c.ttsManager.speak(null, replyText, v)
            }
        }
        if (!resend) {
            _toastMessage.value = str(R.string.toast_message_saved)
        }
    }

    fun stopAgent() = c.agentEngine.cancelRun()
    fun resolveApproval(id: String, allow: Boolean) = c.agentEngine.resolveApproval(id, allow)

    fun compactContextNow() {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            _toastMessage.value = str(R.string.toast_compacting)
            c.agentEngine.compactSessionNow(sid).fold(
                onSuccess = { _toastMessage.value = it },
                onFailure = { _toastMessage.value = str(R.string.toast_compact_err_fmt, it.message.orEmpty()) }
            )
        }
    }

    fun restoreSnapshot(snapshotId: String) {
        viewModelScope.launch {
            val proj = currentSession.value?.workspacePath ?: "default"
            c.workspaceManager.restoreSnapshot(snapshotId, proj).fold(
                onSuccess = {
                    _toastMessage.value = it
                    refreshExplorer()
                },
                onFailure = { _toastMessage.value = str(R.string.toast_revert_err_fmt, it.message.orEmpty()) }
            )
        }
    }

    fun exportAttachmentToUri(localPath: String, targetUri: Uri) {
        viewModelScope.launch {
            c.workspaceManager.exportLocalFileToUri(localPath, targetUri).fold(
                onSuccess = { _toastMessage.value = str(R.string.toast_file_saved) },
                onFailure = { _toastMessage.value = str(R.string.toast_save_err_fmt, it.message.orEmpty()) }
            )
        }
    }

    // ---- Voice (STT & TTS) ----
    fun toggleVoiceRecording() {
        if (c.audioRecorder.isRecording.value) {
            viewModelScope.launch {
                val wav = c.audioRecorder.stopRecording()
                if (wav == null) {
                    _toastMessage.value = str(R.string.toast_recording_empty)
                    return@launch
                }
                val v = c.settingsRepo.voiceSettings.value
                _toastMessage.value = str(R.string.toast_transcribing_voice)
                val res = if (v.sttProvider == SttProviderType.ANDROID_SPEECH) {
                    val primary = c.sttManager.recognizeWithAndroidSpeech(v.sttLanguage)
                    if (primary.isFailure && SttFallbackPolicy.shouldTryCloudFallback(v.sttProvider, v.sttAutoFallback)) {
                        c.sttManager.transcribeCloudFallback(wav, v)
                    } else primary
                } else {
                    val primary = c.sttManager.transcribeAudioFile(wav, v)
                    if (primary.isFailure && SttFallbackPolicy.shouldTryAndroidFallback(v.sttProvider, v.sttAutoFallback)) {
                        c.sttManager.recognizeWithAndroidSpeech(v.sttLanguage)
                    } else primary
                }
                runCatching { wav.delete() }
                res.fold(
                    onSuccess = { recognized ->
                        if (v.autoSendAfterVoice) {
                            sendMessage(recognized)
                        } else {
                            val cur = _composerText.value
                            _composerText.value = if (cur.isBlank()) recognized else "$cur $recognized"
                        }
                        _toastMessage.value = null
                    },
                    onFailure = { _toastMessage.value = "STT: ${it.message}" }
                )
            }
        } else {
            val v = c.settingsRepo.voiceSettings.value
            if (v.sttProvider == SttProviderType.ANDROID_SPEECH && !v.sttAutoFallback) {
                viewModelScope.launch {
                    c.sttManager.recognizeWithAndroidSpeech(v.sttLanguage).fold(
                        onSuccess = { recognized ->
                            if (v.autoSendAfterVoice) sendMessage(recognized)
                            else _composerText.value = recognized
                        },
                        onFailure = { _toastMessage.value = "STT: ${it.message}" }
                    )
                }
            } else {
                // Cloud STT, or offline STT with cloud fallback enabled: record to a wav file
                // so the fallback engine has audio to transcribe if the primary engine fails.
                val out = File(getApplication<ForgeMindApp>().cacheDir, "voice_in_${System.currentTimeMillis()}.wav")
                c.audioRecorder.startRecording(out).onFailure {
                    _toastMessage.value = str(R.string.toast_mic_denied_fmt, it.message.orEmpty())
                }
            }
        }
    }

    fun speakMessage(messageId: String?, text: String) {
        if (c.ttsManager.isSpeaking.value && c.ttsManager.speakingMessageId.value == messageId) {
            c.ttsManager.stop()
        } else {
            c.ttsManager.speak(messageId, text, c.settingsRepo.voiceSettings.value)
        }
    }

    fun stopSpeaking() = c.ttsManager.stop()

    fun updateLiveTtsSpeed(speed: Float) = c.ttsManager.updateLiveSpeed(speed)

    fun setTtsSpeed(speed: Float) {
        val clamped = TtsManager.clampTtsSpeed(speed)
        c.ttsManager.updateLiveSpeed(clamped)
        val cur = c.settingsRepo.voiceSettings.value
        if (kotlin.math.abs(cur.ttsSpeed - clamped) >= 0.005f) {
            viewModelScope.launch {
                c.settingsRepo.saveVoiceSettings(c.settingsRepo.voiceSettings.value.copy(ttsSpeed = clamped))
            }
        }
    }

    fun cycleTtsSpeed() {
        val presets = listOf(0.85f, 1.0f, 1.2f, 1.4f, 1.6f, 2.0f)
        val cur = c.settingsRepo.voiceSettings.value.ttsSpeed
        val next = presets.firstOrNull { it > cur + 0.05f } ?: presets.first()
        setTtsSpeed(next)
    }

    fun loadEdgeTtsVoices(forceRefresh: Boolean = true, onDone: (String) -> Unit = {}) {
        viewModelScope.launch {
            c.ttsManager.loadMicrosoftEdgeVoices(forceRefresh = forceRefresh).fold(
                onSuccess = { list ->
                    if (list.isNotEmpty()) {
                        _edgeTtsVoices.value = list
                    }
                    onDone(str(R.string.edge_tts_voices_loaded_fmt, list.size))
                },
                onFailure = { err ->
                    onDone(str(R.string.toast_generic_err_fmt, err.message.orEmpty()))
                }
            )
        }
    }

    fun testXttsAndLoadVoices(settings: VoiceSettings, onResult: (XttsHealthStatus) -> Unit) {
        viewModelScope.launch {
            val res = c.ttsManager.testAndLoadXttsVoices(settings)
            if (res.voices.isNotEmpty()) {
                _xttsVoices.value = res.voices
                _xttsDefaultVoice.value = res.defaultVoice ?: res.voices.firstOrNull()
                val cur = c.settingsRepo.voiceSettings.value
                val defaultVoice = _xttsDefaultVoice.value
                if (!defaultVoice.isNullOrBlank()) {
                    if (settings.ttsProvider == TtsProviderType.XTTS_EXTERNAL) {
                        if (cur.xttsExternalVoice.isBlank()) {
                            c.settingsRepo.saveVoiceSettings(cur.copy(xttsExternalVoice = defaultVoice))
                        }
                    } else {
                        if (cur.xttsVoice.isBlank()) {
                            c.settingsRepo.saveVoiceSettings(cur.copy(xttsVoice = defaultVoice))
                        }
                    }
                }
            }
            onResult(res)
        }
    }

    // ---- File Explorer & Code Editor ----
    private fun activeExplorerSshId(): String? =
        if (_explorerEnv.value == WorkspaceEnvType.TERMUX_SSH) _explorerSshProfileId.value else null

    private fun activeSecondExplorerSshId(): String? =
        if (_secondExplorerEnv.value == WorkspaceEnvType.TERMUX_SSH) _secondExplorerSshProfileId.value else null

    fun setSplitExplorerEnabled(enabled: Boolean) {
        val wasEnabled = _splitExplorerEnabled.value
        _splitExplorerEnabled.value = enabled
        if (enabled && (!wasEnabled || _secondExplorerItems.value.isEmpty())) {
            refreshSecondExplorer()
        }
    }

    fun toggleSplitExplorer() {
        setSplitExplorerEnabled(!_splitExplorerEnabled.value)
    }

    fun selectExplorerEnv(env: WorkspaceEnvType) {
        _explorerEnv.value = env
        _explorerPath.value = ""
        refreshExplorer()
    }

    fun selectExplorerSshProfile(profileId: String) {
        _explorerSshProfileId.value = profileId
        _explorerEnv.value = WorkspaceEnvType.TERMUX_SSH
        _explorerPath.value = ""
        refreshExplorer()
    }

    fun navigateExplorer(path: String) {
        _explorerPath.value = path
        refreshExplorer()
    }

    fun navigateExplorerUp() {
        val cur = _explorerPath.value.trimEnd('/')
        if (cur.isBlank() || cur == "/") return
        val parent = cur.substringBeforeLast('/', "")
        _explorerPath.value = if (cur.startsWith("/") && parent.isBlank()) "/" else parent
        refreshExplorer()
    }

    fun refreshExplorer() {
        viewModelScope.launch {
            _explorerLoading.value = true
            _explorerError.value = null
            val proj = currentSession.value?.workspacePath ?: "default"
            val sshId = activeExplorerSshId()
            c.workspaceManager.listFiles(_explorerEnv.value, _explorerPath.value, proj, sshServer = sshId).fold(
                onSuccess = { list ->
                    _explorerItems.value = if (sshId != null) {
                        list.map { it.copy(sshServerId = sshId) }
                    } else list
                },
                onFailure = {
                    _explorerItems.value = emptyList()
                    _explorerError.value = it.message ?: str(R.string.err_dir_read)
                }
            )
            _explorerLoading.value = false
        }
    }

    fun selectSecondExplorerEnv(env: WorkspaceEnvType) {
        _secondExplorerEnv.value = env
        _secondExplorerPath.value = ""
        refreshSecondExplorer()
    }

    fun selectSecondExplorerSshProfile(profileId: String) {
        _secondExplorerSshProfileId.value = profileId
        _secondExplorerEnv.value = WorkspaceEnvType.TERMUX_SSH
        _secondExplorerPath.value = ""
        refreshSecondExplorer()
    }

    fun navigateSecondExplorer(path: String) {
        _secondExplorerPath.value = path
        refreshSecondExplorer()
    }

    fun navigateSecondExplorerUp() {
        val cur = _secondExplorerPath.value.trimEnd('/')
        if (cur.isBlank() || cur == "/") return
        val parent = cur.substringBeforeLast('/', "")
        _secondExplorerPath.value = if (cur.startsWith("/") && parent.isBlank()) "/" else parent
        refreshSecondExplorer()
    }

    fun refreshSecondExplorer() {
        viewModelScope.launch {
            _secondExplorerLoading.value = true
            _secondExplorerError.value = null
            val proj = currentSession.value?.workspacePath ?: "default"
            val sshId = activeSecondExplorerSshId()
            c.workspaceManager.listFiles(_secondExplorerEnv.value, _secondExplorerPath.value, proj, sshServer = sshId).fold(
                onSuccess = { list ->
                    _secondExplorerItems.value = if (sshId != null) {
                        list.map { it.copy(sshServerId = sshId) }
                    } else list
                },
                onFailure = {
                    _secondExplorerItems.value = emptyList()
                    _secondExplorerError.value = it.message ?: str(R.string.err_dir_read)
                }
            )
            _secondExplorerLoading.value = false
        }
    }

    fun prepareExplorerUpload(toSecondPane: Boolean) {
        pendingUploadSecondPane = toSecondPane
    }

    fun prepareExplorerSafPick(forSecondPane: Boolean) {
        pendingSafSecondPane = forSecondPane
    }

    fun uploadFileToExplorer(uri: Uri, toSecondPane: Boolean = pendingUploadSecondPane) {
        pendingUploadSecondPane = false
        viewModelScope.launch {
            val proj = currentSession.value?.workspacePath ?: "default"
            val targetEnv = if (toSecondPane) _secondExplorerEnv.value else _explorerEnv.value
            val targetPath = if (toSecondPane) _secondExplorerPath.value else _explorerPath.value
            val sshId = if (toSecondPane) activeSecondExplorerSshId() else activeExplorerSshId()
            if (toSecondPane) _secondExplorerLoading.value = true else _explorerLoading.value = true
            c.workspaceManager.importDeviceUriToEnv(uri, targetEnv, targetPath, proj, sshServer = sshId).fold(
                onSuccess = {
                    _toastMessage.value = it
                    refreshExplorer()
                    if (_splitExplorerEnabled.value || toSecondPane) {
                        refreshSecondExplorer()
                    }
                },
                onFailure = { _toastMessage.value = str(R.string.toast_upload_err_fmt, it.message.orEmpty()) }
            )
            if (toSecondPane) _secondExplorerLoading.value = false else _explorerLoading.value = false
        }
    }

    fun createFileOrFolder(name: String, isDir: Boolean, inSecondPane: Boolean = false) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val targetEnv = if (inSecondPane) _secondExplorerEnv.value else _explorerEnv.value
        val base = if (inSecondPane) _secondExplorerPath.value else _explorerPath.value
        val full = c.workspaceManager.joinEnvPath(targetEnv, base, clean)
        val proj = currentSession.value?.workspacePath ?: "default"
        val sshId = if (inSecondPane) activeSecondExplorerSshId() else activeExplorerSshId()
        viewModelScope.launch {
            val res = if (isDir) {
                c.workspaceManager.createFolder(targetEnv, full, proj, sshServer = sshId)
            } else {
                c.workspaceManager.writeFile(targetEnv, full, "", saveSnapshot = false, internalProject = proj, sshServer = sshId)
                    .map { str(R.string.toast_created_fmt, clean) }
            }
            res.fold(
                onSuccess = {
                    _toastMessage.value = it
                    refreshExplorer()
                    if (_splitExplorerEnabled.value || inSecondPane) {
                        refreshSecondExplorer()
                    }
                },
                onFailure = { _toastMessage.value = str(R.string.toast_generic_err_fmt, it.message.orEmpty()) }
            )
        }
    }

    fun deleteExplorerItem(item: WorkspaceFileItem) {
        val proj = currentSession.value?.workspacePath ?: "default"
        viewModelScope.launch {
            c.workspaceManager.deleteFile(item.envType, item.path, proj, sshServer = item.sshServerId).fold(
                onSuccess = {
                    if (_fileClipboard.value?.item?.let {
                            it.envType == item.envType && it.path == item.path && it.sshServerId == item.sshServerId
                        } == true
                    ) {
                        _fileClipboard.value = null
                    }
                    _toastMessage.value = it
                    refreshExplorer()
                    if (_splitExplorerEnabled.value) {
                        refreshSecondExplorer()
                    }
                },
                onFailure = { _toastMessage.value = str(R.string.toast_delete_err_fmt, it.message.orEmpty()) }
            )
        }
    }

    fun copyOrCutToClipboard(item: WorkspaceFileItem, isCut: Boolean) {
        _fileClipboard.value = FileClipboardEntry(item = item, isCut = isCut)
    }

    fun clearFileClipboard() {
        _fileClipboard.value = null
    }

    fun pasteClipboardToExplorer(toSecondPane: Boolean = false) {
        val entry = _fileClipboard.value ?: return
        val targetEnv = if (toSecondPane) _secondExplorerEnv.value else _explorerEnv.value
        val targetDir = if (toSecondPane) _secondExplorerPath.value else _explorerPath.value
        val targetSsh = if (toSecondPane) activeSecondExplorerSshId() else activeExplorerSshId()
        transferExplorerItem(
            item = entry.item,
            targetEnv = targetEnv,
            targetSshServerId = targetSsh,
            targetDirPath = targetDir,
            move = entry.isCut
        )
    }

    fun transferBetweenExplorerPanes(
        item: WorkspaceFileItem,
        toSecondPane: Boolean,
        move: Boolean
    ) {
        val targetEnv = if (toSecondPane) _secondExplorerEnv.value else _explorerEnv.value
        val targetDir = if (toSecondPane) _secondExplorerPath.value else _explorerPath.value
        val targetSsh = if (toSecondPane) activeSecondExplorerSshId() else activeExplorerSshId()
        transferExplorerItem(
            item = item,
            targetEnv = targetEnv,
            targetSshServerId = targetSsh,
            targetDirPath = targetDir,
            move = move
        )
    }

    fun transferExplorerItem(
        item: WorkspaceFileItem,
        targetEnv: WorkspaceEnvType,
        targetSshServerId: String? = null,
        targetDirPath: String = "",
        move: Boolean = false
    ) {
        val proj = currentSession.value?.workspacePath ?: "default"
        val destPath = c.workspaceManager.joinEnvPath(targetEnv, targetDirPath, item.name)
        viewModelScope.launch {
            c.workspaceManager.transferFile(
                sourceEnv = item.envType,
                sourcePath = item.path,
                targetEnv = targetEnv,
                targetPath = destPath,
                internalProject = proj,
                sourceSshServer = item.sshServerId,
                targetSshServer = targetSshServerId,
                move = move
            ).fold(
                onSuccess = {
                    if (move && _fileClipboard.value?.item?.let {
                            it.envType == item.envType && it.path == item.path && it.sshServerId == item.sshServerId
                        } == true
                    ) {
                        _fileClipboard.value = null
                    }
                    _toastMessage.value = str(R.string.files_transfer_done_fmt, item.name)
                    refreshExplorer()
                    refreshSecondExplorer()
                },
                onFailure = { _toastMessage.value = str(R.string.toast_transfer_err_fmt, it.message.orEmpty()) }
            )
        }
    }

    fun openFileInEditor(item: WorkspaceFileItem, inSecondPane: Boolean = false) {
        if (item.isDirectory) {
            if (inSecondPane) navigateSecondExplorer(item.path) else navigateExplorer(item.path)
            return
        }
        val existing = c.editorTabs.value.firstOrNull {
            it.path == item.path && it.env == item.envType && it.sshServerId == item.sshServerId
        }
        if (existing != null) {
            c.activeEditorTabId.value = existing.id
            return
        }
        val proj = currentSession.value?.workspacePath ?: "default"
        val appCtx = getApplication<ForgeMindApp>()
        val cacheKey = "${item.envType.id}:${item.sshServerId.orEmpty()}:${item.path}:${item.lastModified}:${item.sizeBytes}"
        viewModelScope.launch {
            c.workspaceManager.readRawBytes(item.envType, item.path, proj, sshServer = item.sshServerId).fold(
                onSuccess = { bytes ->
                    val tab = withContext(Dispatchers.IO) {
                        val mime = WorkspaceManager.guessMimeType(item.name)
                        val cachedFile = InAppDocumentParser.cacheBytesForPreview(
                            cacheDir = appCtx.cacheDir,
                            uniqueKey = cacheKey,
                            fileName = item.name,
                            bytes = bytes
                        )
                        when {
                            InAppDocumentParser.isImageExtension(item.name) -> {
                                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeFile(cachedFile.absolutePath, opts)
                                val dims = if (opts.outWidth > 0 && opts.outHeight > 0) {
                                    "${opts.outWidth}x${opts.outHeight}"
                                } else null
                                OpenEditorTab(
                                    id = UUID.randomUUID().toString(),
                                    title = item.name,
                                    path = item.path,
                                    env = item.envType,
                                    sshServerId = item.sshServerId,
                                    kind = EditorTabKind.IMAGE,
                                    localCachePath = cachedFile.absolutePath,
                                    sizeBytes = bytes.size.toLong(),
                                    mimeType = mime,
                                    subtitle = dims
                                )
                            }
                            InAppDocumentParser.isPdfExtension(item.name) -> {
                                val parsed = InAppDocumentParser.parsePdf(bytes)
                                OpenEditorTab(
                                    id = UUID.randomUUID().toString(),
                                    title = item.name,
                                    path = item.path,
                                    env = item.envType,
                                    content = parsed.text,
                                    sshServerId = item.sshServerId,
                                    kind = EditorTabKind.PDF,
                                    localCachePath = cachedFile.absolutePath,
                                    sizeBytes = bytes.size.toLong(),
                                    mimeType = "application/pdf",
                                    subtitle = if (parsed.pageCount > 0) str(R.string.viewer_pages_count_fmt, parsed.pageCount) else null
                                )
                            }
                            InAppDocumentParser.isOfficeDocumentExtension(item.name) -> {
                                val parsed = InAppDocumentParser.parseOfficeOrStructuredDocument(item.name, bytes)
                                OpenEditorTab(
                                    id = UUID.randomUUID().toString(),
                                    title = item.name,
                                    path = item.path,
                                    env = item.envType,
                                    content = parsed.text,
                                    sshServerId = item.sshServerId,
                                    kind = EditorTabKind.DOCUMENT,
                                    localCachePath = cachedFile.absolutePath,
                                    sizeBytes = bytes.size.toLong(),
                                    mimeType = mime,
                                    subtitle = InAppDocumentParser.extensionOf(item.name).uppercase()
                                )
                            }
                            InAppDocumentParser.isArchiveExtension(item.name) -> {
                                val parsed = InAppDocumentParser.listArchiveEntries(bytes)
                                OpenEditorTab(
                                    id = UUID.randomUUID().toString(),
                                    title = item.name,
                                    path = item.path,
                                    env = item.envType,
                                    content = parsed.text,
                                    sshServerId = item.sshServerId,
                                    kind = EditorTabKind.ARCHIVE,
                                    localCachePath = cachedFile.absolutePath,
                                    sizeBytes = bytes.size.toLong(),
                                    mimeType = mime,
                                    subtitle = str(R.string.viewer_archive_entries_fmt, parsed.entryCount)
                                )
                            }
                            WorkspaceManager.looksBinaryBytes(bytes) -> {
                                OpenEditorTab(
                                    id = UUID.randomUUID().toString(),
                                    title = item.name,
                                    path = item.path,
                                    env = item.envType,
                                    sshServerId = item.sshServerId,
                                    kind = EditorTabKind.BINARY,
                                    localCachePath = cachedFile.absolutePath,
                                    sizeBytes = bytes.size.toLong(),
                                    mimeType = mime
                                )
                            }
                            InAppDocumentParser.isMarkdownExtension(item.name) -> {
                                OpenEditorTab(
                                    id = UUID.randomUUID().toString(),
                                    title = item.name,
                                    path = item.path,
                                    env = item.envType,
                                    content = bytes.toString(Charsets.UTF_8),
                                    sshServerId = item.sshServerId,
                                    kind = EditorTabKind.MARKDOWN,
                                    localCachePath = cachedFile.absolutePath,
                                    sizeBytes = bytes.size.toLong(),
                                    mimeType = "text/markdown"
                                )
                            }
                            else -> {
                                OpenEditorTab(
                                    id = UUID.randomUUID().toString(),
                                    title = item.name,
                                    path = item.path,
                                    env = item.envType,
                                    content = bytes.toString(Charsets.UTF_8),
                                    sshServerId = item.sshServerId,
                                    kind = EditorTabKind.TEXT_CODE,
                                    localCachePath = cachedFile.absolutePath,
                                    sizeBytes = bytes.size.toLong(),
                                    mimeType = mime
                                )
                            }
                        }
                    }
                    c.editorTabs.value = c.editorTabs.value + tab
                    c.activeEditorTabId.value = tab.id
                },
                onFailure = { _toastMessage.value = str(R.string.toast_open_err_fmt, it.message.orEmpty()) }
            )
        }
    }

    fun selectEditorTab(id: String?) {
        c.activeEditorTabId.value = id?.ifBlank { null }
    }

    fun closeEditorTab(id: String) {
        val rest = c.editorTabs.value.filterNot { it.id == id }
        c.editorTabs.value = rest
        if (c.activeEditorTabId.value == id) {
            c.activeEditorTabId.value = rest.lastOrNull()?.id
        }
    }

    fun updateEditorContent(tabId: String, newText: String) {
        c.editorTabs.value = c.editorTabs.value.map { t ->
            if (t.id == tabId && t.content != newText) t.copy(content = newText, isDirty = true) else t
        }
    }

    fun saveActiveEditorTab() {
        val tab = c.editorTabs.value.firstOrNull { it.id == c.activeEditorTabId.value } ?: return
        val proj = currentSession.value?.workspacePath ?: "default"
        viewModelScope.launch {
            c.workspaceManager.writeFile(
                tab.env,
                tab.path,
                tab.content,
                saveSnapshot = false,
                internalProject = proj,
                sshServer = tab.sshServerId
            ).fold(
                onSuccess = {
                    c.editorTabs.value = c.editorTabs.value.map {
                        if (it.id == tab.id) it.copy(isDirty = false) else it
                    }
                    _toastMessage.value = str(R.string.toast_saved_name_fmt, tab.title)
                    refreshExplorer()
                },
                onFailure = { _toastMessage.value = str(R.string.toast_save_err_fmt, it.message.orEmpty()) }
            )
        }
    }

    fun attachTabToChat(tab: OpenEditorTab) {
        val item = WorkspaceFileItem(
            name = tab.title,
            path = tab.path,
            isDirectory = false,
            sizeBytes = tab.sizeBytes,
            envType = tab.env,
            sshServerId = tab.sshServerId
        )
        addOutgoingAttachmentFromWorkspace(item)
    }

    // ---- Terminal ----
    fun setTerminalViewMode(mode: TerminalViewMode) = c.terminalManager.setViewMode(mode)
    fun restartAiShell() { viewModelScope.launch { c.terminalManager.restartAiShell() } }
    fun clearAiTerminalLog() = c.terminalManager.clearAiTerminalLog()
    fun openLocalShellTab() {
        val proj = currentSession.value?.workspacePath ?: "default"
        val dir = c.workspaceManager.getInternalProjectDir(proj)
        c.terminalManager.openLocalShell(dir)
    }

    fun openSshTerminalTab(profile: SshProfileEntity? = null) {
        val prof = profile ?: c.termuxBridge.activeProfile
        c.terminalManager.openSshTerminal(prof, prof.name)
    }

    fun selectTerminalTab(id: String) = c.terminalManager.selectTab(id)
    fun closeTerminalTab(id: String) = c.terminalManager.closeTab(id)
    fun sendTerminalCommand(id: String, cmd: String) = c.terminalManager.sendLine(id, cmd)
    fun sendTerminalCtrlC(id: String) = c.terminalManager.sendCtrlC(id)
    fun sendTerminalTab(id: String) = c.terminalManager.sendTab(id)
    fun sendTerminalEsc(id: String) = c.terminalManager.sendEsc(id)
    fun sendTerminalUp(id: String) = c.terminalManager.sendUpArrow(id)
    fun clearTerminal(id: String) = c.terminalManager.clearOutput(id)

    // ---- Browser & Local HTTP Preview ----
    fun setAgentBrowserMode(enabled: Boolean) {
        c.browserController.setAgentMode(enabled)
    }

    fun setAgentBrowserTurbo(enabled: Boolean) {
        c.browserController.setTurboMode(enabled)
    }

    fun toggleAgentBrowserTurbo() {
        c.browserController.setTurboMode(!c.browserController.turboModeEnabled.value)
    }

    fun switchAgentBrowserTab(tabId: String) {
        c.browserController.switchAgentTab(tabId)
    }

    fun createNewAgentBrowserTab() {
        c.browserController.createOrSelectTab(requestedTabId = null, initialUrl = "", makeActive = true)
    }

    fun closeAgentBrowserTab(tabId: String) {
        c.browserController.closeAgentTab(tabId)
    }

    fun openBrowserUrl(url: String, forAgent: Boolean = false) {
        val clean = url.trim()
        if (clean.isBlank()) {
            if (forAgent) c.browserController.resetAgentBrowser()
            else c.browserController.resetUserToHome()
        } else {
            viewModelScope.launch {
                if (forAgent) {
                    c.browserController.navigate(clean, enableAgentMode = false)
                } else {
                    c.browserController.navigateFromUser(clean)
                }
            }
        }
    }

    fun startStaticPreviewServer(openInBrowser: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val proj = currentSession.value?.workspacePath ?: "default"
            val dir = c.workspaceManager.getInternalProjectDir(proj)
            val port = c.localPreviewServer.start(dir)
            c.previewPort.value = port
            if (openInBrowser && port > 0) {
                val url = "http://127.0.0.1:$port/"
                if (agentBrowserMode.value) {
                    c.browserController.navigate(url, enableAgentMode = false)
                } else {
                    c.browserController.navigateFromUser(url)
                }
            }
        }
    }

    // ---- Settings, Providers, MCP, SSH, Archive ----
    fun setAppLanguage(code: String) {
        LocaleHelper.setSavedLanguageCode(getApplication(), code)
        _appLanguage.value = LocaleHelper.getSavedLanguageCode(getApplication())
    }

    fun setConnectionStripExpanded(expanded: Boolean) {
        viewModelScope.launch {
            c.settingsRepo.setConnectionStripExpanded(expanded)
        }
    }

    fun saveUiThemeSettings(settings: UiThemeSettings) {
        viewModelScope.launch {
            c.settingsRepo.saveUiThemeSettings(settings)
        }
    }

    fun resetUiThemeSettings() {
        viewModelScope.launch {
            c.settingsRepo.resetUiThemeSettings()
        }
    }

    fun probeProvidersHealth(onlyProviderId: String? = null) {
        if (onlyProviderId == null) {
            fullProviderProbeJob?.cancel()
        } else {
            singleProviderProbeJobs[onlyProviderId]?.cancel()
        }
        val job = viewModelScope.launch {
            val configs = c.settingsRepo.providerConfigs.value
            val hidden = c.settingsRepo.hiddenBuiltInProviders.value
            val activeId = c.settingsRepo.activeProviderId.value
            val visible = SettingsRepository.orderedProviderConfigs(configs, hidden)
            val targets = if (onlyProviderId != null) {
                listOfNotNull(
                    visible.firstOrNull { it.providerId == onlyProviderId }
                        ?: configs[onlyProviderId]
                )
            } else {
                visible.filter { it.shouldShowStatusDot(activeId) }
            }
            if (onlyProviderId == null) {
                val dotIds = targets.map { it.providerId }.toSet()
                val cleaned = _providerHealth.value.filterKeys { it in dotIds }.toMutableMap()
                targets.forEach { cleaned[it.providerId] = null }
                _providerHealth.value = cleaned
            } else if (targets.isNotEmpty()) {
                val next = _providerHealth.value.toMutableMap()
                targets.forEach { next[it.providerId] = null }
                _providerHealth.value = next
            }
            if (targets.isEmpty()) return@launch
            val probed = coroutineScope {
                targets.map { cfg ->
                    async { cfg.providerId to c.modelScanner.probeReachability(cfg) }
                }.awaitAll()
            }
            _providerHealth.value = _providerHealth.value + probed
        }
        if (onlyProviderId == null) {
            fullProviderProbeJob = job
        } else {
            singleProviderProbeJobs[onlyProviderId] = job
        }
    }

    fun saveProviderConfig(config: ProviderConfig, makeActive: Boolean = false) {
        viewModelScope.launch {
            c.settingsRepo.saveProviderConfig(config)
            val shouldSyncCurrentSession = makeActive ||
                c.settingsRepo.activeProviderId.value == config.providerId ||
                currentSession.value?.providerId == config.providerId
            if (makeActive) {
                c.settingsRepo.setActiveProvider(config.providerId)
            }
            if (shouldSyncCurrentSession) {
                val sid = _currentSessionId.value
                val s = sid?.let { c.db.sessionDao().getById(it) }
                if (s != null && (makeActive || s.providerId == config.providerId)) {
                    c.db.sessionDao().upsert(
                        s.copy(
                            providerId = config.providerId,
                            modelId = config.modelId.ifBlank { s.modelId }
                        )
                    )
                }
            }
            _toastMessage.value = str(
                R.string.toast_provider_saved_fmt,
                config.resolvedDisplayName(localizedAppContext)
            )
            probeProvidersHealth(config.providerId)
        }
    }

    fun removeProvider(providerId: String) {
        viewModelScope.launch {
            val removed = c.settingsRepo.removeProvider(providerId)
            if (!removed) return@launch
            val sid = _currentSessionId.value
            val s = sid?.let { c.db.sessionDao().getById(it) }
            if (s != null && s.providerId.equals(providerId, ignoreCase = true)) {
                val fallbackCfg = c.settingsRepo.getActiveProviderConfig()
                c.db.sessionDao().upsert(
                    s.copy(providerId = fallbackCfg.providerId, modelId = fallbackCfg.modelId)
                )
            }
            _providerHealth.value = _providerHealth.value - providerId
            _toastMessage.value = str(R.string.prov_deleted_toast)
            probeProvidersHealth()
        }
    }

    fun deleteCustomProvider(providerId: String) {
        removeProvider(providerId)
    }

    fun resetBuiltInProviders(clearApiKeys: Boolean = false) {
        viewModelScope.launch {
            c.settingsRepo.resetBuiltInProviders(clearApiKeys)
            _toastMessage.value = str(R.string.prov_reset_done_toast)
            probeProvidersHealth()
        }
    }

    fun testProviderConnection(config: ProviderConfig, onResult: (ConnectionTestResult) -> Unit) {
        viewModelScope.launch {
            _providerHealth.value = _providerHealth.value + (config.providerId to null)
            val res = c.llmRouter.testConnection(config)
            _providerHealth.value = _providerHealth.value + (config.providerId to res.success)
            onResult(res)
        }
    }

    fun scanLanAiServers(customHost: String = "", onResult: (List<DiscoveredLanAiServer>) -> Unit) {
        viewModelScope.launch {
            val found = c.lanAiScanner.scanNetwork(customHost)
            onResult(found)
        }
    }

    fun probeSpecificLanHost(customHost: String, onResult: (List<DiscoveredLanAiServer>) -> Unit) {
        viewModelScope.launch {
            val found = c.lanAiScanner.probeSpecificHost(customHost)
            onResult(found)
        }
    }

    fun connectDirectLanInput(rawInput: String, onResult: (ProviderConfig?) -> Unit = {}) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            onResult(null)
            return
        }
        viewModelScope.launch {
            val probed = c.lanAiScanner.probeSpecificHost(trimmed)
            val server = probed.firstOrNull() ?: LanAiScanner.buildDirectEndpointServer(trimmed)
            if (server == null) {
                onResult(null)
                return@launch
            }
            val cfg = connectDiscoveredLanServer(server, server.models.firstOrNull())
            onResult(cfg)
        }
    }

    fun connectDiscoveredLanServer(
        server: DiscoveredLanAiServer,
        selectedModel: String? = null
    ): ProviderConfig {
        val chosenModel = selectedModel?.trim()?.takeIf { it.isNotEmpty() }
            ?: server.models.firstOrNull()
            ?: server.suggestedProviderType.defaultModel
        val existingBuiltIn = c.settingsRepo.getProviderConfig(server.suggestedProviderType.id)
        val targetId = if (server.host == "127.0.0.1" && server.suggestedProviderType == ProviderType.OLLAMA) {
            ProviderType.OLLAMA.id
        } else if (!existingBuiltIn.isReadyForUse() &&
            existingBuiltIn.baseUrl == server.suggestedProviderType.defaultBaseUrl
        ) {
            server.suggestedProviderType.id
        } else {
            val slug = "${server.host.replace('.', '_').replace(':', '_')}_${server.port}"
            "lan_${server.suggestedProviderType.id}_$slug"
        }
        val isBuiltInTarget = ProviderType.findById(targetId) != null
        val prev = c.settingsRepo.getProviderConfig(targetId)
        val mergedSaved = (listOf(chosenModel) + server.models + prev.savedModels)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(24)
        val baseCfg = prev.copy(
            providerId = targetId,
            baseUrl = server.baseUrl,
            modelId = chosenModel,
            customDisplayName = if (isBuiltInTarget) prev.customDisplayName else server.engineLabel,
            isUserCreated = !isBuiltInTarget,
            categoryOverride = ProviderCategory.LOCAL_LAN,
            wireProtocolOverride = server.wireProtocol,
            authMode = if (prev.parsedKeys().isEmpty()) ProviderAuthMode.NONE else prev.authMode,
            savedModels = mergedSaved
        )
        val detectedCtx = server.modelContextLengths[chosenModel]
        val resolvedCtx = baseCfg.resolveContextWindowForModel(chosenModel, detectedCtx)
        val cfg = baseCfg.copy(contextWindow = resolvedCtx)
        if (server.models.isNotEmpty()) {
            _scannedModels.value = _scannedModels.value + (
                targetId to server.models.map { m ->
                    ModelInfo(
                        id = m,
                        name = m,
                        contextLength = server.modelContextLengths[m]
                    )
                }
            )
        }
        saveProviderConfig(cfg, makeActive = true)
        return cfg
    }

    fun scanModelsForProvider(config: ProviderConfig, onDone: (String) -> Unit = {}) {
        viewModelScope.launch {
            c.modelScanner.scanModels(config).fold(
                onSuccess = { list ->
                    _scannedModels.value = _scannedModels.value + (config.providerId to list)
                    val latest = c.settingsRepo.getProviderConfig(config.providerId)
                    val activeModelInfo = list.firstOrNull { it.id == latest.modelId }
                    if (activeModelInfo?.contextLength != null &&
                        activeModelInfo.contextLength >= 1_024 &&
                        latest.autoContextWindow &&
                        !latest.isManualContextForModel(latest.modelId) &&
                        latest.contextWindow != activeModelInfo.contextLength
                    ) {
                        c.settingsRepo.saveProviderConfig(latest.copy(contextWindow = activeModelInfo.contextLength))
                    }
                    onDone(str(R.string.scan_models_found_fmt, list.size))
                },
                onFailure = { onDone(str(R.string.scan_err_fmt, it.message.orEmpty())) }
            )
        }
    }

    fun saveVoiceSettings(settings: VoiceSettings) {
        viewModelScope.launch {
            c.settingsRepo.saveVoiceSettings(settings)
            _toastMessage.value = str(R.string.toast_voice_saved)
        }
    }

    fun saveSearchSettings(settings: SearchSettings) {
        viewModelScope.launch {
            c.settingsRepo.saveSearchSettings(settings)
            _toastMessage.value = str(R.string.toast_search_saved)
        }
    }

    fun saveAgentSettings(settings: AgentSettings) {
        viewModelScope.launch {
            c.settingsRepo.saveAgentSettings(settings)
            _toastMessage.value = str(R.string.toast_agent_saved)
        }
    }

    fun setSafFolderUri(uri: Uri, setAsChatEnv: Boolean = false) {
        val forSecondPane = pendingSafSecondPane && !setAsChatEnv
        pendingSafSecondPane = false
        viewModelScope.launch {
            val uriStr = uri.toString()
            c.workspaceManager.safRootUri = uri
            c.settingsRepo.setSafTreeUri(uriStr)
            if (forSecondPane) {
                _secondExplorerEnv.value = WorkspaceEnvType.SAF
                _secondExplorerPath.value = ""
            } else {
                _explorerEnv.value = WorkspaceEnvType.SAF
                _explorerPath.value = ""
            }
            if (setAsChatEnv) {
                val sid = _currentSessionId.value
                val session = sid?.let { c.db.sessionDao().getById(it) }
                if (session != null) {
                    c.db.sessionDao().upsert(session.copy(workspaceEnv = WorkspaceEnvType.SAF.id))
                }
            }
            refreshExplorer()
            if (_splitExplorerEnabled.value || forSecondPane) {
                refreshSecondExplorer()
            }
            _toastMessage.value = str(R.string.toast_saf_connected_fmt, formatSafTreeLabelLocalized(localizedAppContext, uriStr))
        }
    }

    fun clearSafFolderUri() {
        viewModelScope.launch {
            c.workspaceManager.safRootUri = null
            c.settingsRepo.setSafTreeUri(null)
            if (_explorerEnv.value == WorkspaceEnvType.SAF) {
                _explorerEnv.value = WorkspaceEnvType.INTERNAL
                _explorerPath.value = ""
                refreshExplorer()
            }
            if (_secondExplorerEnv.value == WorkspaceEnvType.SAF) {
                _secondExplorerEnv.value = WorkspaceEnvType.DEVICE_STORAGE
                _secondExplorerPath.value = ""
                if (_splitExplorerEnabled.value) {
                    refreshSecondExplorer()
                }
            }
            val sid = _currentSessionId.value
            val session = sid?.let { c.db.sessionDao().getById(it) }
            if (session != null && session.workspaceEnv == WorkspaceEnvType.SAF.id) {
                c.db.sessionDao().upsert(session.copy(workspaceEnv = WorkspaceEnvType.INTERNAL.id))
            }
            _toastMessage.value = str(R.string.toast_saf_cleared)
        }
    }

    fun upsertMcpServer(
        id: String? = null,
        name: String,
        transport: McpTransport,
        endpoint: String,
        headersJson: String
    ) {
        viewModelScope.launch {
            val cleanEndpoint = endpoint.trim()
            val existing = id?.let { c.db.mcpServerDao().getById(it) }
            val sameEndpoint = existing != null &&
                existing.endpoint.trim().trimEnd('/').equals(cleanEndpoint.trimEnd('/'), ignoreCase = true)
            val server = McpServerEntity(
                id = id ?: UUID.randomUUID().toString(),
                name = name.trim(),
                transport = transport.name,
                endpoint = cleanEndpoint,
                headersJson = headersJson.ifBlank { "{}" },
                enabled = true,
                cachedToolsJson = if (sameEndpoint) existing!!.cachedToolsJson else "[]",
                instructions = if (sameEndpoint) existing!!.instructions else "",
                lastConnectedAt = if (sameEndpoint) existing!!.lastConnectedAt else null
            )
            c.db.mcpServerDao().upsert(server)
            testMcpServer(server.id)
        }
    }

    fun toggleMcpServer(server: McpServerEntity, enabled: Boolean) {
        viewModelScope.launch {
            c.db.mcpServerDao().upsert(server.copy(enabled = enabled))
            if (!enabled) {
                c.mcpManager.resetServerSession(server.id)
            } else if (server.cachedToolsJson.isBlank() || server.cachedToolsJson == "[]") {
                runCatching { c.mcpManager.connectAndDiscover(server.id) }
            }
        }
    }

    fun deleteMcpServer(id: String) {
        viewModelScope.launch {
            c.mcpManager.resetServerSession(id)
            c.db.mcpServerDao().deleteById(id)
        }
    }

    fun testMcpServer(serverId: String) {
        viewModelScope.launch {
            _toastMessage.value = str(R.string.toast_mcp_connecting)
            c.mcpManager.connectAndDiscover(serverId).fold(
                onSuccess = { tools ->
                    _toastMessage.value = str(R.string.toast_mcp_connected_fmt, tools.size)
                },
                onFailure = { _toastMessage.value = str(R.string.toast_mcp_err_fmt, it.message.orEmpty()) }
            )
        }
    }

    fun updateMcpHostSettings(settings: McpHostSettings, showSavedToast: Boolean = false) {
        viewModelScope.launch {
            c.settingsRepo.saveMcpHostSettings(settings)
            if (showSavedToast) {
                val port = settings.port.coerceIn(1024, 65535)
                _toastMessage.value = str(R.string.toast_mcp_host_saved_fmt, port)
            }
        }
    }

    fun regenerateMcpHostToken() {
        viewModelScope.launch {
            c.settingsRepo.regenerateMcpHostToken()
            _toastMessage.value = str(R.string.toast_mcp_host_token_regen)
        }
    }

    fun saveSshProfile(profile: SshProfileEntity, makeDefault: Boolean = false) {
        viewModelScope.launch {
            if (makeDefault) {
                c.db.sshProfileDao().clearDefault()
            }
            val saved = if (makeDefault) profile.copy(isDefault = true, enabled = true) else profile
            c.db.sshProfileDao().upsert(saved)
            c.termuxBridge.updateProfiles(c.db.sshProfileDao().getAll())
            val hostLabel = saved.host.ifBlank { str(R.string.toast_host_not_set) }
            _toastMessage.value = if (saved.isDefault) {
                str(R.string.toast_ssh_saved_primary_fmt, saved.name, hostLabel, saved.port)
            } else {
                str(R.string.toast_ssh_saved_fmt, saved.name, hostLabel, saved.port)
            }
        }
    }

    fun toggleSshProfileEnabled(profile: SshProfileEntity, enabled: Boolean) {
        viewModelScope.launch {
            val updated = profile.copy(enabled = enabled)
            c.db.sshProfileDao().upsert(updated)
            c.termuxBridge.updateProfiles(c.db.sshProfileDao().getAll())
        }
    }

    fun saveAndActivateSshProfile(profile: SshProfileEntity) {
        saveSshProfile(profile.copy(enabled = true), makeDefault = true)
    }

    fun activateSshProfile(id: String) {
        viewModelScope.launch {
            val profile = c.db.sshProfileDao().getAll().firstOrNull { it.id == id } ?: return@launch
            c.db.sshProfileDao().clearDefault()
            val updated = profile.copy(isDefault = true, enabled = true)
            c.db.sshProfileDao().upsert(updated)
            c.termuxBridge.updateProfiles(c.db.sshProfileDao().getAll())
            _toastMessage.value = str(R.string.toast_ssh_primary_fmt, updated.name, updated.host, updated.port)
        }
    }

    fun deleteSshProfile(id: String) {
        viewModelScope.launch {
            val before = c.db.sshProfileDao().getAll()
            val removing = before.firstOrNull { it.id == id }
            c.db.sshProfileDao().deleteById(id)
            if (removing?.isDefault == true) {
                val remaining = c.db.sshProfileDao().getAll()
                val next = remaining.firstOrNull() ?: TermuxBridge.defaultTermuxProfile().also {
                    c.db.sshProfileDao().upsert(it)
                }
                val activeNext = next.copy(isDefault = true, enabled = true)
                c.db.sshProfileDao().upsert(activeNext)
            }
            c.termuxBridge.updateProfiles(c.db.sshProfileDao().getAll())
            _toastMessage.value = str(R.string.toast_ssh_deleted)
        }
    }

    fun testSshConnection(profile: SshProfileEntity, onResult: (String) -> Unit) {
        viewModelScope.launch {
            c.termuxBridge.testSshConnection(profile).fold(
                onSuccess = { onResult(it) },
                onFailure = { onResult(str(R.string.toast_generic_err_fmt, it.message.orEmpty())) }
            )
        }
    }

    fun refreshConnectionIndicators() {
        c.termuxBridge.refreshConnectionStates()
        probeProvidersHealth()
        viewModelScope.launch {
            val enabledServers = c.db.mcpServerDao().getAll().filter { it.enabled }
            var okCount = 0
            var failCount = 0
            enabledServers.forEach { srv ->
                val res = c.mcpManager.connectAndDiscover(srv.id)
                if (res.isSuccess) okCount++ else failCount++
            }
            val sshReach = c.termuxBridge.probeEnabledProfilesReachability()
            val sshProf = c.termuxBridge.activeProfile
            val sshRes = if (sshProf.enabled && sshProf.host.isNotBlank() && sshProf.username.isNotBlank()) {
                c.termuxBridge.testSshConnection(sshProf)
            } else null
            val mcpSummary = when {
                enabledServers.isEmpty() -> str(R.string.status_mcp_disabled)
                failCount == 0 -> "MCP: $okCount/$okCount"
                else -> str(R.string.status_mcp_counts_fmt, okCount, failCount)
            }
            val sshSummary = when {
                sshRes != null && sshRes.isSuccess -> str(R.string.status_ssh_ok)
                sshRes == null && sshReach.values.any { it } -> str(R.string.status_ssh_ok)
                sshRes == null -> str(R.string.status_ssh_not_set)
                else -> str(R.string.status_ssh_err)
            }
            _toastMessage.value = "$mcpSummary • $sshSummary"
        }
    }

    fun isTermuxIntentReady(): Boolean =
        c.termuxBridge.isTermuxInstalled() && c.termuxBridge.hasTermuxPermission()

    fun decodeMcpTools(raw: String): List<McpToolInfo> =
        c.mcpManager.decodeTools(raw)

    fun exportAllArchiveZip(targetUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                getApplication<ForgeMindApp>().contentResolver.openOutputStream(targetUri)?.use { out ->
                    c.archiveManager.exportFullZip(out)
                } ?: error(str(R.string.toast_cannot_open_write))
            }.fold(
                onSuccess = { stats ->
                    _toastMessage.value = str(
                        R.string.toast_export_zip_ok_fmt,
                        stats.sessionsImported,
                        stats.messagesImported,
                        stats.filesRestored
                    )
                },
                onFailure = { _toastMessage.value = str(R.string.toast_export_err_fmt, it.message.orEmpty()) }
            )
        }
    }

    fun importAllArchiveZip(sourceUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                getApplication<ForgeMindApp>().contentResolver.openInputStream(sourceUri)?.use { inp ->
                    c.archiveManager.importFullZip(inp)
                } ?: error(str(R.string.toast_cannot_open_archive))
            }.fold(
                onSuccess = { stats ->
                    _toastMessage.value = str(
                        R.string.toast_import_zip_ok_fmt,
                        stats.sessionsImported,
                        stats.messagesImported,
                        stats.filesRestored
                    )
                    refreshExplorer()
                },
                onFailure = { _toastMessage.value = str(R.string.toast_import_err_fmt, it.message.orEmpty()) }
            )
        }
    }

    fun exportCurrentSessionMarkdown(targetUri: Uri) {
        val s = currentSession.value ?: return
        val msgs = currentMessages.value
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val md = c.archiveManager.exportSessionToMarkdown(s, msgs)
                getApplication<ForgeMindApp>().contentResolver.openOutputStream(targetUri)?.use { out ->
                    out.write(md.toByteArray(Charsets.UTF_8))
                } ?: error(str(R.string.toast_open_err_fmt, targetUri.toString()))
            }.fold(
                onSuccess = { _toastMessage.value = str(R.string.toast_exported_md) },
                onFailure = { _toastMessage.value = str(R.string.toast_export_err_fmt, it.message.orEmpty()) }
            )
        }
    }

    fun exportSelectedArchiveZip(targetUri: Uri, ids: Set<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                getApplication<ForgeMindApp>().contentResolver.openOutputStream(targetUri)?.use { out ->
                    c.archiveManager.exportFullZip(out, sessionIds = ids)
                } ?: error(str(R.string.toast_cannot_open_write))
            }.fold(
                onSuccess = { stats ->
                    _toastMessage.value = str(
                        R.string.toast_export_zip_ok_fmt,
                        stats.sessionsImported,
                        stats.messagesImported,
                        stats.filesRestored
                    )
                },
                onFailure = { _toastMessage.value = str(R.string.toast_export_err_fmt, it.message.orEmpty()) }
            )
        }
    }

    fun exportSessionsMarkdownToFolder(treeUri: Uri, ids: Set<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val app = getApplication<ForgeMindApp>()
                val rootDoc = DocumentFile.fromTreeUri(app, treeUri)
                    ?: error(str(R.string.err_dir_read))
                val sessions = c.db.sessionDao().getAll().filter { it.id in ids }
                var written = 0
                for (session in sessions) {
                    val msgs = c.db.messageDao().getForSession(session.id)
                    val md = c.archiveManager.exportSessionToMarkdown(session, msgs)
                    val safeTitle = c.archiveManager.sanitizeFileName(session.title, "chat")
                    val shortId = session.id.take(6)
                    val fileName = "${safeTitle}_$shortId.md"
                    rootDoc.findFile(fileName)?.delete()
                    val docFile = rootDoc.createFile("text/markdown", fileName)
                        ?: rootDoc.createFile("application/octet-stream", fileName)
                        ?: continue
                    app.contentResolver.openOutputStream(docFile.uri)?.use { out ->
                        out.write(md.toByteArray(Charsets.UTF_8))
                        written++
                    }
                }
                written
            }.fold(
                onSuccess = { count ->
                    _toastMessage.value = str(R.string.toast_batch_saved_md_folder_fmt, count)
                },
                onFailure = { _toastMessage.value = str(R.string.toast_export_err_fmt, it.message.orEmpty()) }
            )
        }
    }
}
