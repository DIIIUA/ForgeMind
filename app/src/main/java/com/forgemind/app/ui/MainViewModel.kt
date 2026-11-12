package com.forgemind.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.forgemind.app.ForgeMindApp
import com.forgemind.app.bridge.TermuxBridge
import com.forgemind.app.bridge.WorkspaceManager
import com.forgemind.app.data.db.ChatFolderEntity
import com.forgemind.app.data.db.ChatMessageEntity
import com.forgemind.app.data.db.ChatSessionEntity
import com.forgemind.app.data.db.FileSnapshotEntity
import com.forgemind.app.data.db.McpServerEntity
import com.forgemind.app.data.db.SshProfileEntity
import com.forgemind.app.data.model.AgentSettings
import com.forgemind.app.data.model.ChatAttachment
import com.forgemind.app.data.model.McpToolInfo
import com.forgemind.app.data.model.McpTransport
import com.forgemind.app.data.model.ModelInfo
import com.forgemind.app.data.model.ProviderConfig
import com.forgemind.app.data.model.ProviderType
import com.forgemind.app.data.model.SearchSettings
import com.forgemind.app.data.model.SttProviderType
import com.forgemind.app.data.model.TtsProviderType
import com.forgemind.app.data.model.VoiceSettings
import com.forgemind.app.data.model.WorkspaceEnvType
import com.forgemind.app.data.model.WorkspaceFileItem
import com.forgemind.app.voice.TtsManager
import com.forgemind.app.voice.XttsHealthStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class OpenEditorTab(
    val id: String,
    val title: String,
    val path: String,
    val env: WorkspaceEnvType,
    val content: String,
    val isDirty: Boolean = false,
    val sshServerId: String? = null
)

enum class AppSection(val label: String) {
    CHAT("Чат"),
    FILES("Файлы ИИ"),
    PREVIEW("Браузер"),
    SETTINGS("Настройки")
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val c = (application as ForgeMindApp).container

    val providerConfigs = c.settingsRepo.providerConfigs
    val activeProviderId = c.settingsRepo.activeProviderId
    val voiceSettings = c.settingsRepo.voiceSettings
    val searchSettings = c.settingsRepo.searchSettings
    val agentSettings = c.settingsRepo.agentSettings
    val editorTheme = c.settingsRepo.editorTheme
    val safTreeUri = c.settingsRepo.safTreeUri

    val isAgentRunning = c.agentEngine.isRunning
    val agentStatusBanner = c.agentEngine.statusBanner
    val pendingApprovals = c.agentEngine.pendingApprovals

    val isRecording = c.audioRecorder.isRecording
    val voiceAmplitude = c.audioRecorder.amplitude
    val isSpeakingTts = c.ttsManager.isSpeaking
    val speakingMessageId = c.ttsManager.speakingMessageId

    val terminalTabs = c.terminalManager.tabs
    val activeTerminalTabId = c.terminalManager.activeTabId
    val aiBrowserUrl: StateFlow<String> = c.aiBrowserUrl.asStateFlow()

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

    private val _selectedSection = MutableStateFlow(AppSection.CHAT)
    val selectedSection: StateFlow<AppSection> = _selectedSection.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    val currentSession: StateFlow<ChatSessionEntity?> = _currentSessionId.flatMapLatest { sid ->
        if (sid == null) flowOf(null) else c.db.sessionDao().observeById(sid)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val currentMessages: StateFlow<List<ChatMessageEntity>> = _currentSessionId.flatMapLatest { sid ->
        if (sid == null) flowOf(emptyList()) else c.db.messageDao().observeForSession(sid)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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

    private val _editorTabs = MutableStateFlow<List<OpenEditorTab>>(emptyList())
    val editorTabs: StateFlow<List<OpenEditorTab>> = _editorTabs.asStateFlow()

    private val _activeEditorTabId = MutableStateFlow<String?>(null)
    val activeEditorTabId: StateFlow<String?> = _activeEditorTabId.asStateFlow()

    private val _previewPort = MutableStateFlow(0)
    val previewPort: StateFlow<Int> = _previewPort.asStateFlow()

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
            refreshExplorer()
        }

        // Sync SSH profiles pool into TermuxBridge whenever DB changes
        viewModelScope.launch {
            sshProfiles.collect { profiles ->
                if (profiles.isNotEmpty()) {
                    c.termuxBridge.updateProfiles(profiles)
                    val curId = _explorerSshProfileId.value
                    val enabled = profiles.filter { it.enabled }
                    if (curId == null || enabled.none { it.id == curId }) {
                        _explorerSshProfileId.value = enabled.firstOrNull { it.isDefault }?.id
                            ?: enabled.firstOrNull()?.id
                            ?: profiles.firstOrNull()?.id
                    }
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
        _selectedSection.value = section
        if (section == AppSection.FILES) refreshExplorer()
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
                title = "Новый чат",
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
            _toastMessage.value = if (archived) "Чат перемещён в Архив" else "Чат восстановлен из Архива"
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

    fun searchSessions(query: String) {
        viewModelScope.launch {
            _searchResults.value = if (query.isBlank()) emptyList() else c.db.sessionDao().search(query.trim())
        }
    }

    fun updateSessionModel(providerId: String, modelId: String) {
        viewModelScope.launch {
            c.settingsRepo.setActiveProvider(providerId)
            val cfg = c.settingsRepo.getProviderConfig(providerId)
            c.settingsRepo.saveProviderConfig(cfg.copy(modelId = modelId))
            val sid = _currentSessionId.value ?: return@launch
            val s = c.db.sessionDao().getById(sid) ?: return@launch
            c.db.sessionDao().upsert(s.copy(providerId = providerId, modelId = modelId))
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

    fun updateSessionCustomPrompt(prompt: String) {
        viewModelScope.launch {
            val sid = _currentSessionId.value ?: return@launch
            val s = c.db.sessionDao().getById(sid) ?: return@launch
            c.db.sessionDao().upsert(s.copy(customSystemPrompt = prompt))
            _toastMessage.value = "Системный промпт чата сохранён"
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
                    _toastMessage.value = "Добавлено в домашнюю папку ИИ и прикреплено: ${att.name}"
                },
                onFailure = { _toastMessage.value = "Ошибка прикрепления: ${it.message}" }
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
                    _toastMessage.value = "Прикреплено из папки ИИ: ${item.name}"
                },
                onFailure = { _toastMessage.value = "Ошибка чтения файла: ${it.message}" }
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
                    _selectedSection.value = AppSection.CHAT
                    _toastMessage.value = "Файл '${item.name}' прикреплён к сообщению"
                },
                onFailure = { _toastMessage.value = "Ошибка чтения файла: ${it.message}" }
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

    fun stopAgent() = c.agentEngine.cancelRun()
    fun resolveApproval(id: String, allow: Boolean) = c.agentEngine.resolveApproval(id, allow)

    fun compactContextNow() {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            _toastMessage.value = "Сжатие контекста..."
            c.agentEngine.compactSessionNow(sid).fold(
                onSuccess = { _toastMessage.value = it },
                onFailure = { _toastMessage.value = "Ошибка сжатия: ${it.message}" }
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
                onFailure = { _toastMessage.value = "Ошибка отката: ${it.message}" }
            )
        }
    }

    fun exportAttachmentToUri(localPath: String, targetUri: Uri) {
        viewModelScope.launch {
            c.workspaceManager.exportLocalFileToUri(localPath, targetUri).fold(
                onSuccess = { _toastMessage.value = "Файл сохранён!" },
                onFailure = { _toastMessage.value = "Ошибка сохранения: ${it.message}" }
            )
        }
    }

    // ---- Voice (STT & TTS) ----
    fun toggleVoiceRecording() {
        if (c.audioRecorder.isRecording.value) {
            viewModelScope.launch {
                val wav = c.audioRecorder.stopRecording()
                if (wav == null) {
                    _toastMessage.value = "Запись пуста"
                    return@launch
                }
                val v = c.settingsRepo.voiceSettings.value
                _toastMessage.value = "Распознавание речи..."
                val res = if (v.sttProvider == SttProviderType.ANDROID_SPEECH) {
                    c.sttManager.recognizeWithAndroidSpeech(v.sttLanguage)
                } else {
                    c.sttManager.transcribeAudioFile(wav, v)
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
            if (v.sttProvider == SttProviderType.ANDROID_SPEECH) {
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
                val out = File(getApplication<ForgeMindApp>().cacheDir, "voice_in_${System.currentTimeMillis()}.wav")
                c.audioRecorder.startRecording(out).onFailure {
                    _toastMessage.value = "Нет доступа к микрофону: ${it.message}"
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
                        if (cur.xttsExternalVoice.isBlank() || cur.xttsExternalVoice.equals("Venice_selected", ignoreCase = true)) {
                            c.settingsRepo.saveVoiceSettings(cur.copy(xttsExternalVoice = defaultVoice))
                        }
                    } else {
                        if (cur.xttsVoice.isBlank() || cur.xttsVoice.equals("Venice_selected", ignoreCase = true)) {
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
                    _explorerError.value = it.message ?: "Ошибка чтения каталога"
                }
            )
            _explorerLoading.value = false
        }
    }

    fun uploadFileToExplorer(uri: Uri) {
        viewModelScope.launch {
            val proj = currentSession.value?.workspacePath ?: "default"
            val sshId = activeExplorerSshId()
            _explorerLoading.value = true
            c.workspaceManager.importDeviceUriToEnv(uri, _explorerEnv.value, _explorerPath.value, proj, sshServer = sshId).fold(
                onSuccess = {
                    _toastMessage.value = it
                    refreshExplorer()
                },
                onFailure = { _toastMessage.value = "Ошибка загрузки: ${it.message}" }
            )
            _explorerLoading.value = false
        }
    }

    fun createFileOrFolder(name: String, isDir: Boolean) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val base = _explorerPath.value.trimEnd('/')
        val full = if (base.isEmpty()) clean else "$base/$clean"
        val proj = currentSession.value?.workspacePath ?: "default"
        val sshId = activeExplorerSshId()
        viewModelScope.launch {
            val res = if (isDir) {
                c.workspaceManager.createFolder(_explorerEnv.value, full, proj, sshServer = sshId)
            } else {
                c.workspaceManager.writeFile(_explorerEnv.value, full, "", saveSnapshot = false, internalProject = proj, sshServer = sshId)
                    .map { "Файл $clean создан" }
            }
            res.fold(
                onSuccess = {
                    _toastMessage.value = it
                    refreshExplorer()
                },
                onFailure = { _toastMessage.value = "Ошибка: ${it.message}" }
            )
        }
    }

    fun deleteExplorerItem(item: WorkspaceFileItem) {
        val proj = currentSession.value?.workspacePath ?: "default"
        viewModelScope.launch {
            c.workspaceManager.deleteFile(item.envType, item.path, proj, sshServer = item.sshServerId).fold(
                onSuccess = {
                    _toastMessage.value = it
                    refreshExplorer()
                },
                onFailure = { _toastMessage.value = "Ошибка удаления: ${it.message}" }
            )
        }
    }

    fun transferExplorerItem(
        item: WorkspaceFileItem,
        targetEnv: WorkspaceEnvType,
        targetSshServerId: String? = null
    ) {
        val proj = currentSession.value?.workspacePath ?: "default"
        viewModelScope.launch {
            c.workspaceManager.transferFile(
                sourceEnv = item.envType,
                sourcePath = item.path,
                targetEnv = targetEnv,
                targetPath = item.name,
                internalProject = proj,
                sourceSshServer = item.sshServerId,
                targetSshServer = targetSshServerId
            ).fold(
                onSuccess = { _toastMessage.value = it },
                onFailure = { _toastMessage.value = "Ошибка переноса: ${it.message}" }
            )
        }
    }

    fun openFileInEditor(item: WorkspaceFileItem) {
        if (item.isDirectory) {
            navigateExplorer(item.path)
            return
        }
        val existing = _editorTabs.value.firstOrNull {
            it.path == item.path && it.env == item.envType && it.sshServerId == item.sshServerId
        }
        if (existing != null) {
            _activeEditorTabId.value = existing.id
            return
        }
        val proj = currentSession.value?.workspacePath ?: "default"
        viewModelScope.launch {
            c.workspaceManager.readRawBytes(item.envType, item.path, proj, sshServer = item.sshServerId).fold(
                onSuccess = { bytes ->
                    if (com.forgemind.app.bridge.WorkspaceManager.looksBinaryBytes(bytes)) {
                        _toastMessage.value = "Бинарный файл (${bytes.size} Б) нельзя открыть как текст"
                        return@fold
                    }
                    val tab = OpenEditorTab(
                        id = UUID.randomUUID().toString(),
                        title = item.name,
                        path = item.path,
                        env = item.envType,
                        content = bytes.toString(Charsets.UTF_8),
                        sshServerId = item.sshServerId
                    )
                    _editorTabs.value = _editorTabs.value + tab
                    _activeEditorTabId.value = tab.id
                },
                onFailure = { _toastMessage.value = "Ошибка открытия файла: ${it.message}" }
            )
        }
    }

    fun selectEditorTab(id: String) {
        _activeEditorTabId.value = id
    }

    fun closeEditorTab(id: String) {
        val rest = _editorTabs.value.filterNot { it.id == id }
        _editorTabs.value = rest
        if (_activeEditorTabId.value == id) {
            _activeEditorTabId.value = rest.lastOrNull()?.id
        }
    }

    fun updateEditorContent(tabId: String, newText: String) {
        _editorTabs.value = _editorTabs.value.map { t ->
            if (t.id == tabId && t.content != newText) t.copy(content = newText, isDirty = true) else t
        }
    }

    fun saveActiveEditorTab() {
        val tab = _editorTabs.value.firstOrNull { it.id == _activeEditorTabId.value } ?: return
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
                    _editorTabs.value = _editorTabs.value.map {
                        if (it.id == tab.id) it.copy(isDirty = false) else it
                    }
                    _toastMessage.value = "Сохранено: ${tab.title}"
                    refreshExplorer()
                },
                onFailure = { _toastMessage.value = "Ошибка сохранения: ${it.message}" }
            )
        }
    }

    // ---- Terminal ----
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
    fun openBrowserUrl(url: String) {
        val clean = url.trim()
        c.aiBrowserUrl.value = if (clean.isBlank() || clean.startsWith("http://") || clean.startsWith("https://")) {
            clean
        } else {
            "https://$clean"
        }
    }

    fun startStaticPreviewServer(openInBrowser: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val proj = currentSession.value?.workspacePath ?: "default"
            val dir = c.workspaceManager.getInternalProjectDir(proj)
            val port = c.localPreviewServer.start(dir)
            _previewPort.value = port
            if (openInBrowser && port > 0) {
                c.aiBrowserUrl.value = "http://127.0.0.1:$port/"
            }
        }
    }

    // ---- Settings, Providers, MCP, SSH, Archive ----
    fun saveProviderConfig(config: ProviderConfig) {
        viewModelScope.launch {
            c.settingsRepo.saveProviderConfig(config)
            _toastMessage.value = "Настройки ${ProviderType.fromId(config.providerId).displayName} сохранены"
        }
    }

    fun scanModelsForProvider(config: ProviderConfig, onDone: (String) -> Unit = {}) {
        viewModelScope.launch {
            c.modelScanner.scanModels(config).fold(
                onSuccess = { list ->
                    _scannedModels.value = _scannedModels.value + (config.providerId to list)
                    onDone("Найдено моделей: ${list.size}")
                },
                onFailure = { onDone("Ошибка сканирования: ${it.message}") }
            )
        }
    }

    fun saveVoiceSettings(settings: VoiceSettings) {
        viewModelScope.launch {
            c.settingsRepo.saveVoiceSettings(settings)
            _toastMessage.value = "Голосовые настройки сохранены"
        }
    }

    fun saveSearchSettings(settings: SearchSettings) {
        viewModelScope.launch {
            c.settingsRepo.saveSearchSettings(settings)
            _toastMessage.value = "Настройки поиска сохранены"
        }
    }

    fun saveAgentSettings(settings: AgentSettings) {
        viewModelScope.launch {
            c.settingsRepo.saveAgentSettings(settings)
            _toastMessage.value = "Настройки агента сохранены"
        }
    }

    fun setSafFolderUri(uri: Uri, setAsChatEnv: Boolean = false) {
        viewModelScope.launch {
            val uriStr = uri.toString()
            c.workspaceManager.safRootUri = uri
            c.settingsRepo.setSafTreeUri(uriStr)
            _explorerEnv.value = WorkspaceEnvType.SAF
            _explorerPath.value = ""
            if (setAsChatEnv) {
                val sid = _currentSessionId.value
                val session = sid?.let { c.db.sessionDao().getById(it) }
                if (session != null) {
                    c.db.sessionDao().upsert(session.copy(workspaceEnv = WorkspaceEnvType.SAF.id))
                }
            }
            refreshExplorer()
            _toastMessage.value = "Папка Android (SAF) подключена для ИИ: ${WorkspaceManager.formatSafTreeLabel(uriStr)}"
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
            val sid = _currentSessionId.value
            val session = sid?.let { c.db.sessionDao().getById(it) }
            if (session != null && session.workspaceEnv == WorkspaceEnvType.SAF.id) {
                c.db.sessionDao().upsert(session.copy(workspaceEnv = WorkspaceEnvType.INTERNAL.id))
            }
            _toastMessage.value = "SAF-папка сброшена"
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
            val server = McpServerEntity(
                id = id ?: UUID.randomUUID().toString(),
                name = name.trim(),
                transport = transport.name,
                endpoint = endpoint.trim(),
                headersJson = headersJson.ifBlank { "{}" },
                enabled = true
            )
            c.db.mcpServerDao().upsert(server)
            testMcpServer(server.id)
        }
    }

    fun toggleMcpServer(server: McpServerEntity, enabled: Boolean) {
        viewModelScope.launch {
            c.db.mcpServerDao().upsert(server.copy(enabled = enabled))
        }
    }

    fun deleteMcpServer(id: String) {
        viewModelScope.launch { c.db.mcpServerDao().deleteById(id) }
    }

    fun testMcpServer(serverId: String) {
        viewModelScope.launch {
            _toastMessage.value = "Подключение к MCP-серверу..."
            c.mcpManager.connectAndDiscover(serverId).fold(
                onSuccess = { tools ->
                    _toastMessage.value = "MCP подключён! Инструментов: ${tools.size}"
                },
                onFailure = { _toastMessage.value = "Ошибка MCP: ${it.message}" }
            )
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
            val stateNote = if (saved.isDefault) "сохранён и сделан основным" else "сохранён"
            _toastMessage.value = "SSH-сервер '${saved.name}' (${saved.host.ifBlank { "не указан" }}:${saved.port}) $stateNote"
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
            _toastMessage.value = "Основной SSH-сервер: '${updated.name}' (${updated.host}:${updated.port})"
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
            _toastMessage.value = "SSH-профиль удалён"
        }
    }

    fun testSshConnection(profile: SshProfileEntity, onResult: (String) -> Unit) {
        viewModelScope.launch {
            c.termuxBridge.testSshConnection(profile).fold(
                onSuccess = { onResult(it) },
                onFailure = { onResult("Ошибка: ${it.message}") }
            )
        }
    }

    fun refreshConnectionIndicators() {
        c.termuxBridge.refreshConnectionStates()
        viewModelScope.launch {
            val enabledServers = c.db.mcpServerDao().getAll().filter { it.enabled }
            var okCount = 0
            var failCount = 0
            enabledServers.forEach { srv ->
                val res = c.mcpManager.connectAndDiscover(srv.id)
                if (res.isSuccess) okCount++ else failCount++
            }
            val sshProf = c.termuxBridge.activeProfile
            val sshRes = if (sshProf.enabled && sshProf.host.isNotBlank() && sshProf.username.isNotBlank()) {
                c.termuxBridge.testSshConnection(sshProf)
            } else null
            val mcpSummary = when {
                enabledServers.isEmpty() -> "MCP отключены"
                failCount == 0 -> "MCP: $okCount/$okCount"
                else -> "MCP: $okCount ок, $failCount ош."
            }
            val sshSummary = when {
                sshRes == null -> "SSH не настроен"
                sshRes.isSuccess -> "SSH: ОК"
                else -> "SSH: ошибка"
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
                } ?: error("Не удалось открыть файл для записи")
            }.fold(
                onSuccess = { stats ->
                    _toastMessage.value = "Архив сохранён: чатов ${stats.sessionsImported}, сообщений ${stats.messagesImported}, файлов ${stats.filesRestored}"
                },
                onFailure = { _toastMessage.value = "Ошибка экспорта: ${it.message}" }
            )
        }
    }

    fun importAllArchiveZip(sourceUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                getApplication<ForgeMindApp>().contentResolver.openInputStream(sourceUri)?.use { inp ->
                    c.archiveManager.importFullZip(inp)
                } ?: error("Не удалось открыть архив")
            }.fold(
                onSuccess = { stats ->
                    _toastMessage.value = "Восстановлено: чатов ${stats.sessionsImported}, сообщений ${stats.messagesImported}, файлов ${stats.filesRestored}"
                },
                onFailure = { _toastMessage.value = "Ошибка импорта: ${it.message}" }
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
                } ?: error("Ошибка открытия файла")
            }.fold(
                onSuccess = { _toastMessage.value = "Экспортировано в Markdown!" },
                onFailure = { _toastMessage.value = "Ошибка экспорта: ${it.message}" }
            )
        }
    }
}
