package com.forgemind.app

import android.content.ContextWrapper
import androidx.compose.ui.graphics.Color
import com.forgemind.app.agent.AgentToolRegistry
import com.forgemind.app.bridge.TermuxBridge
import com.forgemind.app.bridge.WorkspaceManager
import com.forgemind.app.data.db.FileSnapshotDao
import com.forgemind.app.data.db.FileSnapshotEntity
import com.forgemind.app.data.db.McpServerDao
import com.forgemind.app.data.db.McpServerEntity
import com.forgemind.app.data.model.AgentSettings
import com.forgemind.app.data.model.ProviderAuthMode
import com.forgemind.app.data.model.ProviderCategory
import com.forgemind.app.data.model.ProviderConfig
import com.forgemind.app.data.model.ProviderType
import com.forgemind.app.data.model.UiThemeSettings
import com.forgemind.app.data.model.WireProtocol
import com.forgemind.app.data.model.WorkspaceEnvType
import com.forgemind.app.data.prefs.SettingsRepository
import com.forgemind.app.llm.ModelScanner
import com.forgemind.app.mcp.McpClientManager
import com.forgemind.app.ui.theme.ForgeColors
import com.forgemind.app.web.AgentBrowserController
import com.forgemind.app.web.WebScraperEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs

class SprintNineFeaturesTest {

    private class NoopSnapshotDao : FileSnapshotDao {
        private val flow = MutableStateFlow<List<FileSnapshotEntity>>(emptyList())
        override fun observeForSession(sessionId: String): Flow<List<FileSnapshotEntity>> = flow
        override suspend fun getById(id: String): FileSnapshotEntity? = null
        override suspend fun getRecentForSession(sessionId: String, limit: Int): List<FileSnapshotEntity> = emptyList()
        override suspend fun upsert(snapshot: FileSnapshotEntity) = Unit
        override suspend fun markRestored(id: String) = Unit
    }

    private fun createWorkspaceManager(appFilesDir: File, phoneStorageDir: File): WorkspaceManager {
        val fakeCtx = object : ContextWrapper(null) {
            override fun getFilesDir(): File = appFilesDir
        }
        val bridge = TermuxBridge(fakeCtx)
        return WorkspaceManager(fakeCtx, bridge, NoopSnapshotDao()).apply {
            deviceStorageRootOverride = phoneStorageDir
        }
    }

    @After
    fun resetGlobalTheme() {
        ForgeColors.applyTheme(UiThemeSettings())
    }

    @Test
    fun `parseHexColor and applyTheme parse RGB hex values fallback safely and clamp component alphas`() {
        val fallback = Color(0xFF112233)

        val parsedSix = ForgeColors.parseHexColor(" #10B981 ", fallback, alpha = 0.8f)
        assertEquals("#10B981", ForgeColors.toHexRgb(parsedSix))
        assertEquals(0.8f, parsedSix.alpha, 0.005f)

        val parsedEight = ForgeColors.parseHexColor("#AAEF4444", fallback, alpha = 0.02f)
        assertEquals("#EF4444", ForgeColors.toHexRgb(parsedEight))
        assertEquals(0.12f, parsedEight.alpha, 0.005f)

        val parsedInvalid = ForgeColors.parseHexColor("not-a-hex", fallback, alpha = 5.0f)
        assertEquals("#112233", ForgeColors.toHexRgb(parsedInvalid))
        assertEquals(1.0f, parsedInvalid.alpha, 0.001f)

        val customTheme = UiThemeSettings(
            presetId = "custom_matrix",
            bgDeepHex = "#000000",
            surfaceDarkHex = "#10B981",
            surfaceAlpha = 0.05f, // clamps to 0.20f
            surfaceCardHex = "#8B5CF6",
            cardAlpha = 0.10f, // clamps to 0.20f
            userBubbleHex = "#EF4444",
            userBubbleAlpha = 0.65f,
            aiBubbleHex = "#1E273B",
            aiBubbleAlpha = 0.01f, // clamps to 0.20f
            accentHex = "#F59E0B",
            accentAlpha = 0.10f, // clamps to 0.35f
            borderHex = "#38BDF8",
            borderAlpha = 0.05f, // clamps to 0.15f
            terminalPreset = "matrix",
            terminalBgHex = "#121826",
            terminalBgAlpha = 0.05f, // clamps to 0.25f
            terminalTextHex = "#10B981",
            terminalPromptHex = "#EC4899"
        )
        ForgeColors.applyTheme(customTheme)

        assertEquals("#000000", ForgeColors.toHexRgb(ForgeColors.BgDeep))
        assertEquals(1.0f, ForgeColors.BgDeep.alpha, 0.005f)

        assertEquals("#10B981", ForgeColors.toHexRgb(ForgeColors.SurfaceDark))
        assertEquals(0.20f, ForgeColors.SurfaceDark.alpha, 0.005f)
        assertEquals(0.20f, ForgeColors.SurfaceGlass.alpha, 0.005f)

        assertEquals("#8B5CF6", ForgeColors.toHexRgb(ForgeColors.SurfaceCard))
        assertEquals(0.20f, ForgeColors.SurfaceCard.alpha, 0.005f)
        assertEquals(0.20f, ForgeColors.SurfaceElevated.alpha, 0.005f)

        assertEquals("#EF4444", ForgeColors.toHexRgb(ForgeColors.UserBubbleBg))
        assertTrue(abs(ForgeColors.UserBubbleBg.alpha - 0.65f) < 0.005f)

        assertEquals("#1E273B", ForgeColors.toHexRgb(ForgeColors.AiBubbleBg))
        assertEquals(0.20f, ForgeColors.AiBubbleBg.alpha, 0.005f)

        assertEquals("#F59E0B", ForgeColors.toHexRgb(ForgeColors.AccentBlue))
        assertEquals(0.35f, ForgeColors.AccentBlue.alpha, 0.005f)

        assertEquals("#38BDF8", ForgeColors.toHexRgb(ForgeColors.BorderSubtle))
        assertEquals(0.15f, ForgeColors.BorderSubtle.alpha, 0.005f)

        assertEquals("#121826", ForgeColors.toHexRgb(ForgeColors.TerminalBg))
        assertEquals(0.25f, ForgeColors.TerminalBg.alpha, 0.005f)
        assertEquals(0.25f, ForgeColors.TerminalOutputBoxBg.alpha, 0.005f)
        assertEquals("#10B981", ForgeColors.toHexRgb(ForgeColors.TerminalText))
        assertEquals("#EC4899", ForgeColors.toHexRgb(ForgeColors.TerminalPrompt))
    }

    @Test
    fun `orderedProviderConfigs filters hidden built-ins case-insensitively and guarantees non-empty fallback`() {
        val userCustom = ProviderConfig(
            providerId = "custom_self_hosted",
            customDisplayName = "Self Hosted Gateway",
            baseUrl = "http://192.168.1.77:8000/v1",
            isUserCreated = true,
            categoryOverride = ProviderCategory.CUSTOM
        )
        val configs = SettingsRepository.defaultProviderConfigs() + (userCustom.providerId to userCustom)

        val filtered = SettingsRepository.orderedProviderConfigs(
            configs = configs,
            hiddenBuiltIns = setOf("VENICE", "openai", "custom")
        )
        val ids = filtered.map { it.providerId }
        assertFalse("venice" in ids)
        assertFalse("openai" in ids)
        assertFalse("custom" in ids)
        assertTrue("gemini" in ids)
        assertTrue("custom_self_hosted" in ids)

        // Hide ALL built-ins while userCustom still exists -> returns only userCustom
        val allBuiltInsHidden = ProviderType.entries.mapTo(HashSet()) { it.id.uppercase() }
        val onlyUser = SettingsRepository.orderedProviderConfigs(configs, allBuiltInsHidden)
        assertEquals(listOf("custom_self_hosted"), onlyUser.map { it.providerId })

        // Hide ALL built-ins with zero user providers -> safely falls back to OpenRouter
        val fallbackList = SettingsRepository.orderedProviderConfigs(emptyMap(), allBuiltInsHidden)
        assertEquals(1, fallbackList.size)
        assertEquals(ProviderType.OPENROUTER.id, fallbackList.first().providerId)
    }

    @Test
    fun `shouldShowStatusDot and probeReachability accurately reflect configured and live provider state`() = runTest {
        val untouchedVenice = ProviderConfig(providerId = ProviderType.VENICE.id)
        assertFalse(untouchedVenice.shouldShowStatusDot(activeProviderId = ProviderType.OPENROUTER.id))
        assertTrue(untouchedVenice.shouldShowStatusDot(activeProviderId = ProviderType.VENICE.id))

        val keyedVenice = untouchedVenice.copy(apiKeysRaw = "vk-secret")
        assertTrue(keyedVenice.shouldShowStatusDot(activeProviderId = ProviderType.OPENROUTER.id))

        val customBaseOllama = ProviderConfig(
            providerId = ProviderType.OLLAMA.id,
            baseUrl = "http://192.168.1.25:11434"
        )
        assertTrue(customBaseOllama.shouldShowStatusDot(activeProviderId = ProviderType.OPENROUTER.id))

        val userCreatedOffline = ProviderConfig(
            providerId = "custom_offline_node",
            baseUrl = "https://api.example.com/v1",
            isUserCreated = true
        )
        assertTrue(userCreatedOffline.shouldShowStatusDot(activeProviderId = ProviderType.OPENROUTER.id))

        val scanner = ModelScanner(OkHttpClient())

        // Placeholder / blank / missing required cloud API key -> immediately false
        assertFalse(scanner.probeReachability(ProviderConfig(providerId = ProviderType.CUSTOM.id)))
        assertFalse(scanner.probeReachability(userCreatedOffline))
        assertFalse(scanner.probeReachability(ProviderConfig(providerId = "custom_blank", baseUrl = "   ", isUserCreated = true)))
        assertFalse(scanner.probeReachability(ProviderConfig(providerId = ProviderType.OPENAI.id, apiKeysRaw = "")))
        assertFalse(scanner.probeReachability(ProviderConfig(providerId = ProviderType.ANTHROPIC.id, apiKeysRaw = "")))
        assertFalse(scanner.probeReachability(ProviderConfig(providerId = ProviderType.GEMINI.id, apiKeysRaw = "")))

        // Live local endpoint returning 200 OK vs 500 error
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"data":[{"id":"qwen2.5-coder"}]}""")
            )
            server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Error"))
            server.start()

            val liveCfg = ProviderConfig(
                providerId = "custom_local_vllm",
                baseUrl = server.url("/v1").toString().trimEnd('/'),
                isUserCreated = true,
                wireProtocolOverride = WireProtocol.OPENAI_COMPAT,
                authMode = ProviderAuthMode.NONE
            )
            assertTrue(scanner.probeReachability(liveCfg))
            assertFalse(scanner.probeReachability(liveCfg))
        }
    }

    @Test
    fun `WorkspaceManager supports DEVICE_STORAGE operations and recursive file and folder transfer copy and move`() = runTest {
        val appDir = Files.createTempDirectory("fm_sprint9_internal").toFile()
        val phoneDir = Files.createTempDirectory("fm_sprint9_phone").toFile()
        try {
            val wm = createWorkspaceManager(appDir, phoneDir)
            assertTrue(wm.hasFullDeviceStorageAccess())
            assertEquals(phoneDir.canonicalPath, wm.getDeviceStorageRoot().canonicalPath)

            // 1. Direct DEVICE_STORAGE CRUD, prefix normalization & Android/ exclusion in search
            File(phoneDir, "Android/data/secret").apply { mkdirs() }
            File(phoneDir, "Android/data/secret/hidden.txt").writeText("should skip Android folder")

            wm.createFolder(WorkspaceEnvType.DEVICE_STORAGE, "Download/Notes").getOrThrow()
            wm.writeFile(
                env = WorkspaceEnvType.DEVICE_STORAGE,
                path = "Download/Notes/todo.md",
                content = "Line 1: ফোন/Телефон\nLine 2: Draft"
            ).getOrThrow()

            wm.editFile(
                env = WorkspaceEnvType.DEVICE_STORAGE,
                path = "/storage/emulated/0/Download/Notes/todo.md",
                oldString = "Line 2: Draft",
                newString = "Line 2: Verified"
            ).getOrThrow()

            val readViaSdcardAlias = wm.readRawText(
                env = WorkspaceEnvType.DEVICE_STORAGE,
                path = "/sdcard/Download/Notes/todo.md"
            ).getOrThrow()
            assertTrue(readViaSdcardAlias.contains("Line 2: Verified"))

            val listed = wm.listFiles(WorkspaceEnvType.DEVICE_STORAGE, "Download/Notes").getOrThrow()
            assertEquals(listOf("todo.md"), listed.map { it.name })

            val foundFiles = wm.searchFiles(WorkspaceEnvType.DEVICE_STORAGE, "").getOrThrow()
            assertTrue("Download/Notes/todo.md" in foundFiles)
            assertFalse(foundFiles.any { it.startsWith("Android/") })

            val codeHits = wm.searchCodebase(WorkspaceEnvType.DEVICE_STORAGE, "Verified").getOrThrow()
            assertTrue(codeHits.contains("Download/Notes/todo.md:2:"))

            // Path traversal & root delete safety
            assertTrue(wm.readRawText(WorkspaceEnvType.DEVICE_STORAGE, "../../outside.txt").isFailure)
            assertTrue(wm.deleteFile(WorkspaceEnvType.DEVICE_STORAGE, "").isFailure)
            assertTrue(wm.deleteFile(WorkspaceEnvType.DEVICE_STORAGE, "/storage/emulated/0").isFailure)

            // 2. Single file copy (move = false) and move (move = true) across INTERNAL <-> DEVICE_STORAGE
            wm.writeFile(WorkspaceEnvType.INTERNAL, "hello.txt", "From AI Home").getOrThrow()
            wm.transferFile(
                sourceEnv = WorkspaceEnvType.INTERNAL,
                sourcePath = "hello.txt",
                targetEnv = WorkspaceEnvType.DEVICE_STORAGE,
                targetPath = "Download/hello_copy.txt",
                move = false
            ).getOrThrow()
            assertEquals("From AI Home", wm.readRawText(WorkspaceEnvType.INTERNAL, "hello.txt").getOrThrow())
            assertEquals("From AI Home", wm.readRawText(WorkspaceEnvType.DEVICE_STORAGE, "Download/hello_copy.txt").getOrThrow())

            wm.transferFile(
                sourceEnv = WorkspaceEnvType.DEVICE_STORAGE,
                sourcePath = "Download/hello_copy.txt",
                targetEnv = WorkspaceEnvType.INTERNAL,
                targetPath = "moved_back.txt",
                move = true
            ).getOrThrow()
            assertEquals("From AI Home", wm.readRawText(WorkspaceEnvType.INTERNAL, "moved_back.txt").getOrThrow())
            assertTrue(wm.readRawText(WorkspaceEnvType.DEVICE_STORAGE, "Download/hello_copy.txt").isFailure)

            // 3. Recursive folder copy (move = false) and move (move = true) across environments
            wm.writeFile(WorkspaceEnvType.INTERNAL, "bundle/README.md", "# Bundle").getOrThrow()
            wm.writeFile(WorkspaceEnvType.INTERNAL, "bundle/src/app.kt", "fun main() = Unit").getOrThrow()
            wm.writeFile(WorkspaceEnvType.INTERNAL, "bundle/src/nested/config.json", """{"ok":true}""").getOrThrow()

            // Self-nesting guard within same environment
            assertTrue(
                wm.transferFile(
                    sourceEnv = WorkspaceEnvType.INTERNAL,
                    sourcePath = "bundle",
                    targetEnv = WorkspaceEnvType.INTERNAL,
                    targetPath = "bundle/sub/copy",
                    move = false
                ).isFailure
            )

            // Copy nested folder INTERNAL -> DEVICE_STORAGE
            wm.transferFile(
                sourceEnv = WorkspaceEnvType.INTERNAL,
                sourcePath = "bundle",
                targetEnv = WorkspaceEnvType.DEVICE_STORAGE,
                targetPath = "Projects/bundle_copy",
                move = false
            ).getOrThrow()

            assertEquals("# Bundle", wm.readRawText(WorkspaceEnvType.INTERNAL, "bundle/README.md").getOrThrow())
            assertEquals("# Bundle", wm.readRawText(WorkspaceEnvType.DEVICE_STORAGE, "Projects/bundle_copy/README.md").getOrThrow())
            assertEquals("fun main() = Unit", wm.readRawText(WorkspaceEnvType.DEVICE_STORAGE, "Projects/bundle_copy/src/app.kt").getOrThrow())
            assertEquals("""{"ok":true}""", wm.readRawText(WorkspaceEnvType.DEVICE_STORAGE, "Projects/bundle_copy/src/nested/config.json").getOrThrow())

            // Move nested folder DEVICE_STORAGE -> INTERNAL
            wm.transferFile(
                sourceEnv = WorkspaceEnvType.DEVICE_STORAGE,
                sourcePath = "Projects/bundle_copy",
                targetEnv = WorkspaceEnvType.INTERNAL,
                targetPath = "restored_bundle",
                move = true
            ).getOrThrow()

            assertFalse(File(phoneDir, "Projects/bundle_copy").exists())
            assertEquals("# Bundle", wm.readRawText(WorkspaceEnvType.INTERNAL, "restored_bundle/README.md").getOrThrow())
            assertEquals("fun main() = Unit", wm.readRawText(WorkspaceEnvType.INTERNAL, "restored_bundle/src/app.kt").getOrThrow())
            assertEquals("""{"ok":true}""", wm.readRawText(WorkspaceEnvType.INTERNAL, "restored_bundle/src/nested/config.json").getOrThrow())
        } finally {
            appDir.deleteRecursively()
            phoneDir.deleteRecursively()
        }
    }

    private class NoopMcpServerDao : McpServerDao {
        private val flow = MutableStateFlow<List<McpServerEntity>>(emptyList())
        override fun observeAll(): Flow<List<McpServerEntity>> = flow
        override suspend fun getAll(): List<McpServerEntity> = emptyList()
        override suspend fun getEnabled(): List<McpServerEntity> = emptyList()
        override suspend fun getById(id: String): McpServerEntity? = null
        override suspend fun upsert(server: McpServerEntity) = Unit
        override suspend fun deleteById(id: String) = Unit
    }

    @Test
    fun `tool schemas and environment description expose device_storage and transfer move`() = runTest {
        val appDir = Files.createTempDirectory("forgemind_tools_app").toFile()
        val phoneDir = Files.createTempDirectory("forgemind_tools_phone").toFile()
        try {
            val fakeCtx = object : ContextWrapper(null) {
                override fun getFilesDir(): File = appDir
            }
            val bridge = TermuxBridge(fakeCtx)
            val wm = WorkspaceManager(fakeCtx, bridge, NoopSnapshotDao()).apply {
                deviceStorageRootOverride = phoneDir
            }
            val mcpManager = McpClientManager(NoopMcpServerDao(), bridge, appDir)
            val registry = AgentToolRegistry(
                workspaceManager = wm,
                termuxBridge = bridge,
                mcpManager = mcpManager,
                webScraper = WebScraperEngine(fakeCtx)
            )

            val specs = registry.buildToolSpecs(AgentSettings(enableTools = true))
            val byName = specs.associateBy { it.name }

            for (toolName in listOf("list_files", "file_read", "file_write", "run_command", "file_info", "file_move")) {
                val spec = requireNotNull(byName[toolName]) { "Missing tool $toolName" }
                val props = spec.parametersSchema["properties"]!!.jsonObject
                val targetEnv = props["target_env"]!!.jsonObject
                val enumValues = targetEnv["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
                assertTrue(
                    "Tool '$toolName' target_env enum must include 'device_storage', got: $enumValues",
                    "device_storage" in enumValues
                )
            }

            val transferSpec = requireNotNull(byName["transfer_file"])
            val transferProps = transferSpec.parametersSchema["properties"]!!.jsonObject
            assertTrue("transfer_file must expose 'move' property", "move" in transferProps)
            assertTrue(
                "transfer_file source_env schema must mention device_storage",
                transferProps["source_env"].toString().contains("device_storage")
            )
            assertTrue(
                "transfer_file target_env schema must mention device_storage",
                transferProps["target_env"].toString().contains("device_storage")
            )

            val envDesc = registry.describeConnectedEnvironments(defaultEnv = WorkspaceEnvType.DEVICE_STORAGE)
            assertTrue("Environment description must include device_storage", envDesc.contains("device_storage"))
        } finally {
            appDir.deleteRecursively()
            phoneDir.deleteRecursively()
        }
    }

    @Test
    fun `userTabs in AgentBrowserController stay isolated from agentTabs and small budgets cap interactive elements`() {
        val tempDir = Files.createTempDirectory("forgemind_user_tabs_test").toFile()
        try {
            val fakeCtx = object : ContextWrapper(null) {
                override fun getFilesDir(): File = tempDir
            }
            val browserCtrl = AgentBrowserController(fakeCtx)

            assertEquals(listOf("user_1"), browserCtrl.userTabs.value.map { it.id })
            assertEquals("user_1", browserCtrl.activeUserTabId.value)
            assertEquals(listOf("tab_1"), browserCtrl.agentTabs.value.map { it.id })

            val createdId = browserCtrl.createOrSelectUserTab(
                requestedTabId = "u_docs",
                initialUrl = "https://kotlinlang.org/docs",
                makeActive = true
            )
            assertEquals("u_docs", createdId)
            assertEquals(listOf("user_1", "u_docs"), browserCtrl.userTabs.value.map { it.id })
            assertEquals("u_docs", browserCtrl.activeUserTabId.value)
            assertEquals("https://kotlinlang.org/docs", browserCtrl.userCurrentUrl.value)

            // Agent browser tabs and actionLog remain untouched by user tab operations
            assertEquals(listOf("tab_1"), browserCtrl.agentTabs.value.map { it.id })
            assertTrue(browserCtrl.actionLog.value.isEmpty())

            assertTrue(browserCtrl.switchUserTab("user_1"))
            assertEquals("user_1", browserCtrl.activeUserTabId.value)
            assertEquals("", browserCtrl.userCurrentUrl.value)

            assertTrue(browserCtrl.switchUserTab("u_docs"))
            assertEquals("https://kotlinlang.org/docs", browserCtrl.userCurrentUrl.value)

            browserCtrl.closeUserTab("u_docs")
            assertEquals(listOf("user_1"), browserCtrl.userTabs.value.map { it.id })
            assertEquals("user_1", browserCtrl.activeUserTabId.value)

            // Closing the sole remaining user tab resets it to blank home rather than leaving zero tabs
            browserCtrl.closeUserTab("user_1")
            assertEquals(1, browserCtrl.userTabs.value.size)
            assertEquals("", browserCtrl.userCurrentUrl.value)

            // Proportional interactive element cap for small multi-tab budgets
            val inspectSmall = AgentBrowserController.buildInspectScript(250)
            assertTrue(inspectSmall.contains("var maxEls = 5;"))
            val inspectDefault = AgentBrowserController.buildInspectScript(2000)
            assertTrue(inspectDefault.contains("var maxEls = 80;"))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
