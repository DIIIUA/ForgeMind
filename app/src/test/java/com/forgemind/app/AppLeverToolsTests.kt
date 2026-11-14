package com.forgemind.app

import android.content.ContextWrapper
import com.forgemind.app.agent.AgentToolRegistry
import com.forgemind.app.bridge.TermuxBridge
import com.forgemind.app.bridge.WorkspaceManager
import com.forgemind.app.data.db.FileSnapshotDao
import com.forgemind.app.data.db.FileSnapshotEntity
import com.forgemind.app.data.db.McpServerDao
import com.forgemind.app.data.db.McpServerEntity
import com.forgemind.app.data.model.AgentSettings
import com.forgemind.app.mcp.McpClientManager
import com.forgemind.app.web.AgentBrowserController
import com.forgemind.app.web.WebScraperEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AppLeverToolsTests {

    private class EmptyMcpServerDao : McpServerDao {
        private val flow = MutableStateFlow<List<McpServerEntity>>(emptyList())
        override fun observeAll(): Flow<List<McpServerEntity>> = flow
        override suspend fun getAll(): List<McpServerEntity> = emptyList()
        override suspend fun getEnabled(): List<McpServerEntity> = emptyList()
        override suspend fun getById(id: String): McpServerEntity? = null
        override suspend fun upsert(server: McpServerEntity) = Unit
        override suspend fun deleteById(id: String) = Unit
    }

    private class EmptySnapshotDao : FileSnapshotDao {
        private val flow = MutableStateFlow<List<FileSnapshotEntity>>(emptyList())
        override fun observeForSession(sessionId: String): Flow<List<FileSnapshotEntity>> = flow
        override suspend fun getById(id: String): FileSnapshotEntity? = null
        override suspend fun getRecentForSession(sessionId: String, limit: Int): List<FileSnapshotEntity> = emptyList()
        override suspend fun upsert(snapshot: FileSnapshotEntity) = Unit
        override suspend fun markRestored(id: String) = Unit
    }

    @Test
    fun `agent browser normalizeUrl handles URLs domains and queries`() {
        assertEquals("", AgentBrowserController.normalizeUrl("   "))
        assertEquals("https://example.com/test", AgentBrowserController.normalizeUrl("https://example.com/test"))
        assertEquals("http://127.0.0.1:8765/", AgentBrowserController.normalizeUrl("http://127.0.0.1:8765/"))
        assertEquals("about:blank", AgentBrowserController.normalizeUrl("about:blank"))
        assertEquals("https://example.org", AgentBrowserController.normalizeUrl("example.org"))
        assertEquals(
            "https://duckduckgo.com/?q=kotlin+compose+webview",
            AgentBrowserController.normalizeUrl("kotlin compose webview")
        )
    }

    @Test
    fun `agent browser decodeJsResultString unescapes JSON-encoded strings cleanly`() {
        assertEquals("null", AgentBrowserController.decodeJsResultString("null"))
        assertEquals("123", AgentBrowserController.decodeJsResultString("123"))
        assertEquals(
            "hello\n\"world\"\t\\A",
            AgentBrowserController.decodeJsResultString("\"hello\\n\\\"world\\\"\\t\\\\\\u0041\"")
        )
    }

    @Test
    fun `tool registry exposes 56 unique built-in tools without profile setup tools`() = runTest {
        val tempDir = Files.createTempDirectory("forgemind_tools_spec_test").toFile()
        try {
            val bridge = TermuxBridge(ContextWrapper(null))
            val mcpManager = McpClientManager(EmptyMcpServerDao(), bridge, tempDir)
            val wm = WorkspaceManager(ContextWrapper(null), bridge, EmptySnapshotDao())
            val registry = AgentToolRegistry(
                workspaceManager = wm,
                termuxBridge = bridge,
                mcpManager = mcpManager,
                webScraper = WebScraperEngine(ContextWrapper(null))
            )

            val specs = registry.buildToolSpecs(AgentSettings(enableTools = true))
            val names = specs.map { it.name }

            assertEquals(56, names.size)
            assertEquals(56, names.toSet().size)

            val expectedLevers = listOf(
                "file_info", "file_move", "snapshot_list", "snapshot_restore",
                "editor_open_file", "editor_list_tabs", "preview_server_control",
                "browser_agent_tabs", "browser_agent_navigate", "browser_agent_inspect", "browser_agent_markdown",
                "browser_agent_act", "browser_agent_batch",
                "browser_agent_eval_js", "browser_agent_screenshot", "browser_agent_mode",
                "terminal_list_tabs", "terminal_open_tab", "terminal_send",
                "chat_list_sessions", "chat_search_history", "chat_read_session",
                "chat_manage", "chat_export_markdown", "context_compact",
                "app_navigate", "voice_speak", "screen_read_windows", "screen_act_tag",
                "screen_act_batch", "screen_capture", "web_scrape_site"
            )
            for (lever in expectedLevers) {
                assertTrue("Missing expected lever tool: $lever", lever in names)
            }

            val forbiddenProfileTools = listOf(
                "ssh_profile_create", "ssh_profile_update", "ssh_add", "provider_configure",
                "api_key_set", "mcp_add_server", "mcp_remove_server"
            )
            for (forbidden in forbiddenProfileTools) {
                assertFalse("Profile setup tool should not exist: $forbidden", forbidden in names)
            }

            val strictSettings = AgentSettings(autoApproveSafe = false, autoApproveAll = false)
            val safeApprovedSettings = AgentSettings(autoApproveSafe = true, autoApproveAll = false)

            assertTrue(registry.requiresApproval("file_move", "{}", strictSettings))
            assertTrue(registry.requiresApproval("snapshot_restore", "{}", strictSettings))
            assertFalse(registry.requiresApproval("file_move", "{}", safeApprovedSettings))
            assertFalse(registry.requiresApproval("snapshot_restore", "{}", safeApprovedSettings))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
