package com.forgemind.app

import android.app.Application
import com.forgemind.app.agent.AgentEngine
import com.forgemind.app.agent.AgentToolRegistry
import com.forgemind.app.agent.EditorTabSnapshot
import com.forgemind.app.bridge.TerminalSessionManager
import com.forgemind.app.bridge.TermuxBridge
import com.forgemind.app.bridge.WorkspaceManager
import com.forgemind.app.data.archive.ChatArchiveManager
import com.forgemind.app.data.db.ForgeMindDatabase
import com.forgemind.app.data.model.WorkspaceEnvType
import com.forgemind.app.data.prefs.SettingsRepository
import com.forgemind.app.llm.ContextCompactor
import com.forgemind.app.llm.LlmRouter
import com.forgemind.app.llm.ModelScanner
import com.forgemind.app.mcp.McpClientManager
import com.forgemind.app.ui.AppSection
import com.forgemind.app.ui.OpenEditorTab
import com.forgemind.app.voice.AudioRecorder
import com.forgemind.app.voice.SttManager
import com.forgemind.app.voice.TtsManager
import com.forgemind.app.web.AgentBrowserController
import com.forgemind.app.web.LocalPreviewServer
import com.forgemind.app.web.WebScraperEngine
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.eclipse.tm4e.core.registry.IThemeSource

class AppContainer(app: Application) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val db: ForgeMindDatabase by lazy { ForgeMindDatabase.build(app) }
    val settingsRepo: SettingsRepository by lazy { SettingsRepository(app) }
    val termuxBridge: TermuxBridge by lazy { TermuxBridge(app) }
    val workspaceManager: WorkspaceManager by lazy {
        WorkspaceManager(app, termuxBridge, db.snapshotDao())
    }
    val terminalManager: TerminalSessionManager by lazy {
        TerminalSessionManager(termuxBridge) {
            workspaceManager.getInternalProjectDir("default")
        }
    }
    val mcpManager: McpClientManager by lazy {
        McpClientManager(db.mcpServerDao(), termuxBridge, workspaceManager.chatFilesDir)
    }
    val webScraper: WebScraperEngine by lazy { WebScraperEngine(app) }
    val localPreviewServer: LocalPreviewServer by lazy { LocalPreviewServer() }
    val browserController: AgentBrowserController by lazy { AgentBrowserController(app) }
    val archiveManager: ChatArchiveManager by lazy {
        ChatArchiveManager(db, workspaceManager.chatFilesDir)
    }

    val selectedSection = kotlinx.coroutines.flow.MutableStateFlow(AppSection.CHAT)
    val editorTabs = kotlinx.coroutines.flow.MutableStateFlow<List<OpenEditorTab>>(emptyList())
    val activeEditorTabId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val previewPort = kotlinx.coroutines.flow.MutableStateFlow(0)

    val llmRouter: LlmRouter by lazy { LlmRouter() }
    val modelScanner: ModelScanner by lazy { ModelScanner() }
    val compactor: ContextCompactor by lazy { ContextCompactor(llmRouter) }
    val audioRecorder: AudioRecorder by lazy { AudioRecorder() }
    val sttManager: SttManager by lazy { SttManager(app, settingsRepo) }
    val ttsManager: TtsManager by lazy { TtsManager(app) }

    val toolRegistry: AgentToolRegistry by lazy {
        AgentToolRegistry(
            workspaceManager = workspaceManager,
            termuxBridge = termuxBridge,
            mcpManager = mcpManager,
            webScraper = webScraper,
            browserController = browserController,
            localPreviewServer = localPreviewServer,
            terminalManager = terminalManager,
            archiveManager = archiveManager,
            db = db,
            settingsRepo = settingsRepo,
            ttsManager = ttsManager,
            onOpenBrowserUrl = { url ->
                appScope.launch(Dispatchers.Main) {
                    browserController.navigate(url, enableAgentMode = false, waitFinishMs = 6000L)
                }
            },
            onNavigateSection = { sectionName ->
                val sec = AppSection.entries.firstOrNull { it.name.equals(sectionName, ignoreCase = true) }
                    ?: return@AgentToolRegistry false
                selectedSection.value = sec
                true
            },
            onOpenEditorTab = { env, path, content, sshId ->
                val cleanName = path.substringAfterLast('/').ifBlank { path }
                val existing = editorTabs.value.firstOrNull {
                    it.path == path && it.env == env && it.sshServerId == sshId
                }
                if (existing != null) {
                    val updated = existing.copy(
                        content = content,
                        isDirty = false,
                        version = existing.version + 1
                    )
                    editorTabs.value = editorTabs.value.map { if (it.id == existing.id) updated else it }
                    // Keep user's current active tab untouched when updating files in the background
                } else {
                    val tab = OpenEditorTab(
                        id = java.util.UUID.randomUUID().toString(),
                        title = cleanName,
                        path = path,
                        env = env,
                        content = content,
                        sshServerId = sshId
                    )
                    editorTabs.value = editorTabs.value + tab
                    // Never steal or hijack user's active view/tab when agent opens a file in the background
                }
                cleanName
            },
            onListEditorTabs = {
                val activeId = activeEditorTabId.value
                editorTabs.value.map { tab ->
                    EditorTabSnapshot(
                        id = tab.id,
                        title = tab.title,
                        path = tab.path,
                        env = tab.env,
                        isDirty = tab.isDirty,
                        isActive = tab.id == activeId,
                        content = tab.content
                    )
                }
            },
            onPreviewPortChanged = { port -> previewPort.value = port },
            onCompactContext = { sid -> agentEngine.compactSessionNow(sid) }
        )
    }
    val agentEngine: AgentEngine by lazy {
        AgentEngine(
            context = app,
            sessionDao = db.sessionDao(),
            messageDao = db.messageDao(),
            settingsRepo = settingsRepo,
            llmRouter = llmRouter,
            compactor = compactor,
            toolRegistry = toolRegistry
        )
    }
}

class ForgeMindApp : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        runCatching { PDFBoxResourceLoader.init(applicationContext) }
        initTextMate()

        appScope.launch {
            runCatching { container.terminalManager.ensureAiShellStarted() }
            container.settingsRepo.loadAll()
            container.settingsRepo.safTreeUri.value?.let { uriStr ->
                runCatching {
                    container.workspaceManager.safRootUri = android.net.Uri.parse(uriStr)
                }
            }
            val sshProfiles = container.db.sshProfileDao().getAll()
            if (sshProfiles.isEmpty()) {
                val termuxDef = TermuxBridge.defaultTermuxProfile()
                val externalDef = TermuxBridge.defaultExternalSshProfile()
                container.db.sshProfileDao().upsert(termuxDef)
                container.db.sshProfileDao().upsert(externalDef)
                container.termuxBridge.updateProfiles(listOf(termuxDef, externalDef))
            } else {
                val updatedList = sshProfiles.toMutableList()
                val untouchedLegacy = updatedList.firstOrNull {
                    it.id == "default_termux" &&
                        it.port == 8022 &&
                        it.username.isBlank() &&
                        it.password.isBlank() &&
                        it.privateKeyPem.isBlank()
                }
                if (untouchedLegacy != null) {
                    val upgraded = TermuxBridge.defaultTermuxProfile().copy(isDefault = untouchedLegacy.isDefault)
                    container.db.sshProfileDao().upsert(upgraded)
                    val idx = updatedList.indexOfFirst { it.id == untouchedLegacy.id }
                    if (idx >= 0) updatedList[idx] = upgraded
                }
                if (updatedList.size == 1 && updatedList.none { it.id == "external_ssh" }) {
                    val ext = TermuxBridge.defaultExternalSshProfile()
                    container.db.sshProfileDao().upsert(ext)
                    updatedList += ext
                }
                container.termuxBridge.updateProfiles(updatedList)
            }
            container.termuxBridge.updateMcpServers(container.db.mcpServerDao().getAll())
        }
    }

    private fun initTextMate() {
        runCatching {
            FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(assets))
            GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
            val themes = listOf("ayu-dark", "darcula", "quietlight", "solarized_dark")
            val reg = ThemeRegistry.getInstance()
            for (name in themes) {
                val path = "textmate/$name.json"
                val src = IThemeSource.fromInputStream(assets.open(path), path, null)
                reg.loadTheme(ThemeModel(src, name))
            }
            reg.setTheme("ayu-dark")
        }
    }
}
