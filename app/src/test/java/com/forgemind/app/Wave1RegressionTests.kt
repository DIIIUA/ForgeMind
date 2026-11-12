package com.forgemind.app

import android.content.ContextWrapper
import com.forgemind.app.agent.AgentEngine
import com.forgemind.app.agent.AgentToolRegistry
import com.forgemind.app.bridge.TermuxBridge
import com.forgemind.app.bridge.WorkspaceManager
import com.forgemind.app.data.db.ChatMessageEntity
import com.forgemind.app.data.db.FileSnapshotDao
import com.forgemind.app.data.db.FileSnapshotEntity
import com.forgemind.app.data.db.McpServerDao
import com.forgemind.app.data.db.McpServerEntity
import com.forgemind.app.data.model.AgentSettings
import com.forgemind.app.data.model.LlmRole
import com.forgemind.app.data.model.ProviderConfig
import com.forgemind.app.data.model.ProviderType
import com.forgemind.app.data.model.SearchSettings
import com.forgemind.app.data.model.ToolCallInfo
import com.forgemind.app.data.model.WorkspaceEnvType
import com.forgemind.app.llm.LlmHttpFactory
import com.forgemind.app.llm.LlmStreamEvent
import com.forgemind.app.llm.LlmWireMessage
import com.forgemind.app.llm.OpenAiCompatibleClient
import com.forgemind.app.mcp.McpClientManager
import com.forgemind.app.web.AgentBrowserController
import com.forgemind.app.web.WebScraperEngine
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class Wave1RegressionTests {

    private class DummyMcpDao : McpServerDao {
        private val flow = MutableStateFlow<List<McpServerEntity>>(emptyList())
        override fun observeAll(): Flow<List<McpServerEntity>> = flow
        override suspend fun getAll(): List<McpServerEntity> = emptyList()
        override suspend fun getEnabled(): List<McpServerEntity> = emptyList()
        override suspend fun getById(id: String): McpServerEntity? = null
        override suspend fun upsert(server: McpServerEntity) = Unit
        override suspend fun deleteById(id: String) = Unit
    }

    private class DummySnapshotDao : FileSnapshotDao {
        private val flow = MutableStateFlow<List<FileSnapshotEntity>>(emptyList())
        override fun observeForSession(sessionId: String): Flow<List<FileSnapshotEntity>> = flow
        override suspend fun getById(id: String): FileSnapshotEntity? = null
        override suspend fun getRecentForSession(sessionId: String, limit: Int): List<FileSnapshotEntity> = emptyList()
        override suspend fun upsert(snapshot: FileSnapshotEntity) = Unit
        override suspend fun markRestored(id: String) = Unit
    }

    @Test
    fun `buildWireHistory drops orphan tools, synthesizes dangling calls, and avoids trailing assistant`() {
        val call1 = ToolCallInfo(id = "tc_1", name = "list_files", argumentsJson = """{"path":"."}""")
        val call2 = ToolCallInfo(id = "tc_2", name = "file_read", argumentsJson = """{"path":"a.txt"}""")
        val msgs = listOf(
            // Leading orphaned tool row
            ChatMessageEntity(id = "m0", sessionId = "s", orderIndex = 0, role = LlmRole.TOOL.name, content = "orphan", toolCallId = "old", toolName = "x"),
            ChatMessageEntity(id = "m1", sessionId = "s", orderIndex = 1, role = LlmRole.USER.name, content = "Start"),
            // Assistant with 2 calls where only 1 result was saved before cancel
            ChatMessageEntity(
                id = "m2", sessionId = "s", orderIndex = 2, role = LlmRole.ASSISTANT.name,
                toolCallsJson = LlmHttpFactory.json.encodeToString(listOf(call1, call2))
            ),
            ChatMessageEntity(id = "m3", sessionId = "s", orderIndex = 3, role = LlmRole.TOOL.name, content = "ok", toolCallId = "tc_1", toolName = "list_files"),
            // Reflection assistant response ending the list
            ChatMessageEntity(id = "m4", sessionId = "s", orderIndex = 4, role = LlmRole.ASSISTANT.name, content = "Step 2 plan")
        )

        val wire = AgentEngine.buildWireHistory("sys", "", msgs)
        // Expected: SYSTEM, USER("Start"), ASSISTANT(2 calls), TOOL(tc_1), TOOL(tc_2 interrupted), ASSISTANT("Step 2 plan"), USER(continuation)
        assertEquals(LlmRole.SYSTEM, wire[0].role)
        assertEquals(LlmRole.USER, wire[1].role)
        assertEquals(LlmRole.ASSISTANT, wire[2].role)
        assertEquals(LlmRole.TOOL, wire[3].role)
        assertEquals("tc_1", wire[3].toolCallId)
        assertEquals(LlmRole.TOOL, wire[4].role)
        assertEquals("tc_2", wire[4].toolCallId)
        assertTrue(wire[4].content.contains("Прервано"))
        assertEquals(LlmRole.ASSISTANT, wire[5].role)
        assertEquals(LlmRole.USER, wire.last().role)
    }

    @Test
    fun `safeCompactionSplitIndex never splits between assistant tool calls and tool responses`() {
        val tc = ToolCallInfo(id = "tc_1", name = "list_files", argumentsJson = "{}")
        val msgs = listOf(
            ChatMessageEntity(id = "0", sessionId = "s", orderIndex = 0, role = LlmRole.USER.name, content = "u0"),
            ChatMessageEntity(id = "1", sessionId = "s", orderIndex = 1, role = LlmRole.ASSISTANT.name, toolCallsJson = LlmHttpFactory.json.encodeToString(listOf(tc))),
            ChatMessageEntity(id = "2", sessionId = "s", orderIndex = 2, role = LlmRole.TOOL.name, toolCallId = "tc_1", content = "r1"),
            ChatMessageEntity(id = "3", sessionId = "s", orderIndex = 3, role = LlmRole.TOOL.name, toolCallId = "tc_2", content = "r2"),
            ChatMessageEntity(id = "4", sessionId = "s", orderIndex = 4, role = LlmRole.USER.name, content = "u1"),
            ChatMessageEntity(id = "5", sessionId = "s", orderIndex = 5, role = LlmRole.ASSISTANT.name, content = "a1")
        )

        // keepTail = 3 would land at index 3 (role = TOOL), so must move back to <= 1 (never starting tail on TOOL)
        val splitIdx = AgentEngine.safeCompactionSplitIndex(msgs, keepTail = 3)
        assertTrue(splitIdx <= 1)
        assertFalse(msgs[splitIdx].role == LlmRole.TOOL.name)
    }

    @Test
    fun `agent tools handle bad json and non-primitive args safely and enforce approvals`() = runTest {
        val tempDir = Files.createTempDirectory("forgemind_wave1_tools").toFile()
        try {
            val bridge = TermuxBridge(ContextWrapper(null))
            val mcpManager = McpClientManager(DummyMcpDao(), bridge, tempDir)
            val wm = WorkspaceManager(ContextWrapper(null), bridge, DummySnapshotDao())
            val registry = AgentToolRegistry(
                workspaceManager = wm,
                termuxBridge = bridge,
                mcpManager = mcpManager,
                webScraper = WebScraperEngine(ContextWrapper(null))
            )

            // 1. Truncated malformed JSON returns explicit error instead of running with {}
            val badJsonOut = registry.execute(
                toolName = "list_files",
                argsJson = """{"path": "unterminated""",
                defaultEnv = WorkspaceEnvType.INTERNAL,
                internalProject = "default",
                sessionId = "s1",
                messageId = "m1",
                agentSettings = AgentSettings(),
                searchSettings = SearchSettings()
            )
            assertTrue(badJsonOut.isError)
            assertTrue(badJsonOut.text.contains("JSON"))

            // 2. Non-primitive JSON object inside arguments must not throw IllegalArgumentException
            val objArgOut = registry.execute(
                toolName = "file_info",
                argsJson = """{"path":{"nested":true}}""",
                defaultEnv = WorkspaceEnvType.INTERNAL,
                internalProject = "default",
                sessionId = "s1",
                messageId = "m1",
                agentSettings = AgentSettings(),
                searchSettings = SearchSettings()
            )
            assertTrue(objArgOut.isError)

            // 3. Approval hardening checks
            val safeApproved = AgentSettings(autoApproveSafe = true, autoApproveAll = false)
            val strictSettings = AgentSettings(autoApproveSafe = false, autoApproveAll = false)
            assertTrue(registry.requiresApproval("terminal_send", """{"command":"rm -rf /tmp/x"}""", safeApproved))
            assertTrue(registry.requiresApproval("mcp_connect", """{"name":"x","transport":"SSH_STDIO","url":"python3"}""", safeApproved))
            assertTrue(registry.requiresApproval("terminal_send", """{"command":"ls"}""", strictSettings))
            assertTrue(registry.requiresApproval("browser_agent_eval_js", """{"script":"1+1"}""", strictSettings))
            assertTrue(registry.requiresApproval("mcp_connect", """{"name":"x","transport":"STREAMABLE_HTTP","url":"https://x"}""", strictSettings))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `workspace deleteFile rejects root across all environments`() = runTest {
        val bridge = TermuxBridge(ContextWrapper(null))
        val wm = WorkspaceManager(ContextWrapper(null), bridge, DummySnapshotDao())
        for (env in listOf(WorkspaceEnvType.INTERNAL, WorkspaceEnvType.TERMUX_SSH, WorkspaceEnvType.SAF)) {
            for (badPath in listOf("", "   ", ".", "./", "/", "~")) {
                val res = wm.deleteFile(env, badPath)
                assertTrue("Expected failure for env=$env path='$badPath'", res.isFailure)
            }
        }
    }

    @Test
    fun `openai stream handles tool_calls index collision across argument deltas`() = runTest {
        val server = MockWebServer()
        val sseBody = buildString {
            appendLine("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"first_tool","arguments":"{\"a\":"}}]}}]}""")
            appendLine()
            appendLine("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_2","function":{"name":"second_tool","arguments":"{\"b\":"}}]}}]}""")
            appendLine()
            appendLine("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"2}"}}]},"finish_reason":"tool_calls"}]}""")
            appendLine()
            appendLine("data: [DONE]")
            appendLine()
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody)
        )
        server.start()
        try {
            val client = OpenAiCompatibleClient(OkHttpClient())
            val cfg = ProviderConfig(
                providerId = ProviderType.OPENAI.id,
                apiKeysRaw = "test-key",
                baseUrl = server.url("/v1").toString(),
                modelId = "gpt-test"
            )
            val events = client.streamChat(
                cfg,
                listOf(LlmWireMessage(LlmRole.USER, "hi")),
                emptyList()
            ).toList()

            val readyCalls = events.filterIsInstance<LlmStreamEvent.ToolCallReady>().map { it.toolCall }
            assertEquals(2, readyCalls.size)
            assertEquals("call_1", readyCalls[0].id)
            assertEquals("first_tool", readyCalls[0].name)
            assertEquals("""{"a":""", readyCalls[0].argumentsJson)
            assertEquals("call_2", readyCalls[1].id)
            assertEquals("second_tool", readyCalls[1].name)
            assertEquals("""{"b":2}""", readyCalls[1].argumentsJson)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `passive background levers never switch user screen or remove show_ui isolation`() = runTest {
        val tempDir = Files.createTempDirectory("forgemind_passive_levers").toFile()
        try {
            val fakeContext = object : ContextWrapper(null) {
                override fun getFilesDir(): File = tempDir
            }
            val bridge = TermuxBridge(fakeContext)
            val mcpManager = McpClientManager(DummyMcpDao(), bridge, tempDir)
            val wm = WorkspaceManager(fakeContext, bridge, DummySnapshotDao())
            val projDir = wm.getInternalProjectDir("default")
            File(projDir, "hello.txt").writeText("console.log('ok');")

            val navCalls = mutableListOf<String>()
            var openedTabs = 0
            val browserCtrl = AgentBrowserController(fakeContext)
            val registry = AgentToolRegistry(
                workspaceManager = wm,
                termuxBridge = bridge,
                mcpManager = mcpManager,
                webScraper = WebScraperEngine(fakeContext),
                browserController = browserCtrl,
                onOpenEditorTab = { _, path, _, _ ->
                    openedTabs++
                    path
                },
                onNavigateSection = { sec ->
                    navCalls += sec
                    true
                }
            )

            val passiveSpecs = registry.buildToolSpecs(AgentSettings()).associateBy { it.name }
            for (name in listOf(
                "editor_open_file",
                "preview_server_control",
                "browser_agent_navigate",
                "browser_agent_mode",
                "terminal_open_tab"
            )) {
                val schemaText = passiveSpecs.getValue(name).parametersSchema.toString()
                assertFalse("Tool $name must not expose show_ui", schemaText.contains("\"show_ui\""))
            }

            val editorOut = registry.execute(
                toolName = "editor_open_file",
                argsJson = """{"path":"hello.txt","show_ui":true}""",
                defaultEnv = WorkspaceEnvType.INTERNAL,
                internalProject = "default",
                sessionId = "s1",
                messageId = "m1",
                agentSettings = AgentSettings(autoApproveAll = true),
                searchSettings = SearchSettings()
            )
            assertFalse(editorOut.isError)
            assertEquals(1, openedTabs)
            assertTrue("editor_open_file must stay passive", navCalls.isEmpty())

            val browserResetOut = registry.execute(
                toolName = "browser_agent_mode",
                argsJson = """{"action":"home","show_ui":true}""",
                defaultEnv = WorkspaceEnvType.INTERNAL,
                internalProject = "default",
                sessionId = "s1",
                messageId = "m2",
                agentSettings = AgentSettings(autoApproveAll = true),
                searchSettings = SearchSettings()
            )
            assertFalse(browserResetOut.isError)
            assertFalse(browserCtrl.agentModeEnabled.value)
            assertTrue("browser_agent_mode must stay passive", navCalls.isEmpty())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
