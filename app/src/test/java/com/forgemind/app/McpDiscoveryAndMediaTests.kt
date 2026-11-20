package com.forgemind.app

import android.content.ContextWrapper
import com.forgemind.app.agent.AgentEngine
import com.forgemind.app.agent.AgentToolRegistry
import com.forgemind.app.bridge.TermuxBridge
import com.forgemind.app.bridge.WorkspaceManager
import com.forgemind.app.data.db.FileSnapshotDao
import com.forgemind.app.data.db.FileSnapshotEntity
import com.forgemind.app.data.db.McpServerDao
import com.forgemind.app.data.db.McpServerEntity
import com.forgemind.app.data.model.AgentSettings
import com.forgemind.app.data.model.AttachmentKind
import com.forgemind.app.data.model.McpTransport
import com.forgemind.app.data.model.SearchSettings
import com.forgemind.app.data.model.WorkspaceEnvType
import com.forgemind.app.mcp.McpClientManager
import com.forgemind.app.web.WebScraperEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

class McpDiscoveryAndMediaTests {

    private class FakeMcpServerDao(initial: List<McpServerEntity> = emptyList()) : McpServerDao {
        private val items = linkedMapOf<String, McpServerEntity>().apply {
            initial.forEach { put(it.id, it) }
        }
        private val flow = MutableStateFlow(items.values.toList())

        override fun observeAll(): Flow<List<McpServerEntity>> = flow
        override suspend fun getAll(): List<McpServerEntity> = items.values.toList()
        override suspend fun getEnabled(): List<McpServerEntity> = items.values.filter { it.enabled }
        override suspend fun getById(id: String): McpServerEntity? = items[id]
        override suspend fun upsert(server: McpServerEntity) {
            items[server.id] = server
            flow.value = items.values.toList()
        }
        override suspend fun deleteById(id: String) {
            items.remove(id)
            flow.value = items.values.toList()
        }
    }

    private class FakeFileSnapshotDao : FileSnapshotDao {
        private val flow = MutableStateFlow<List<FileSnapshotEntity>>(emptyList())
        override fun observeForSession(sessionId: String): Flow<List<FileSnapshotEntity>> = flow
        override suspend fun getById(id: String): FileSnapshotEntity? = null
        override suspend fun getRecentForSession(sessionId: String, limit: Int): List<FileSnapshotEntity> = emptyList()
        override suspend fun upsert(snapshot: FileSnapshotEntity) = Unit
        override suspend fun markRestored(id: String) = Unit
    }

    @Test
    fun `mcp discovery follows nextCursor pagination and stops on cycle`() = runTest {
        val tempDir = Files.createTempDirectory("forgemind_mcp_pag_test").toFile()
        val server = MockWebServer()
        // 1. initialize
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","capabilities":{"tools":{}},"serverInfo":{"name":"Portal","version":"1.0"}}}"""
            )
        )
        // 2. notifications/initialized (best-effort)
        server.enqueue(MockResponse().setResponseCode(202).setBody(""))
        // 3. tools/list page 1
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"search","description":"codemode search"},{"name":"list_files","description":"view"}],"nextCursor":"page2"}}"""
            )
        )
        // 4. tools/list page 2
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"jsonrpc":"2.0","id":3,"result":{"tools":[{"name":"read_file","description":"read"},{"name":"custom_ping","description":"ping"}],"nextCursor":"page3"}}"""
            )
        )
        // 5. tools/list page 3 (with duplicate + final)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"jsonrpc":"2.0","id":4,"result":{"tools":[{"name":"custom_ping","description":"dup"},{"name":"run_script","description":"script"}]}}"""
            )
        )
        server.start()
        try {
            val srvEntity = McpServerEntity(
                id = "srv1",
                name = "TestPortal",
                transport = McpTransport.STREAMABLE_HTTP.name,
                endpoint = server.url("/mcp").toString(),
                enabled = true
            )
            val dao = FakeMcpServerDao(listOf(srvEntity))
            val bridge = TermuxBridge(ContextWrapper(null))
            val manager = McpClientManager(dao, bridge, tempDir)

            val res = manager.connectAndDiscover("srv1")
            assertTrue("Expected paginated discovery success: ${res.exceptionOrNull()}", res.isSuccess)
            val tools = res.getOrThrow()
            assertEquals(
                listOf("search", "list_files", "read_file", "custom_ping", "run_script"),
                tools.map { it.name }
            )
            val insight = manager.analyzeServerCatalog(srvEntity, tools)
            assertEquals(5, insight.directToolCount)
            assertTrue(insight.isGatewayOrCodeMode)
            assertTrue(insight.summaryLine.contains("Code Mode"))
        } finally {
            server.shutdown()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `mcp_call_tool attaches webp and png images with unique filenames`() = runTest {
        val tempDir = Files.createTempDirectory("forgemind_mcp_img_test").toFile()
        val server = MockWebServer()
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01)
        val webpBytes = "RIFF\u0000\u0000\u0000\u0000WEBP".toByteArray(Charsets.ISO_8859_1)
        val pngB64 = Base64.getEncoder().encodeToString(pngBytes)
        val webpB64 = Base64.getEncoder().encodeToString(webpBytes)

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "jsonrpc":"2.0",
                  "id":1,
                  "result":{
                    "isError":false,
                    "content":[
                      {"type":"text","text":"shot_id=s1"},
                      {"type":"image","mimeType":"image/webp","data":"$webpB64"},
                      {"type":"image","mimeType":"image/png","data":"$pngB64"}
                    ]
                  }
                }
                """.trimIndent()
            )
        )
        server.start()
        try {
            val srvEntity = McpServerEntity(
                id = "srv_media",
                name = "MediaMcp",
                transport = McpTransport.STREAMABLE_HTTP.name,
                endpoint = server.url("/mcp").toString(),
                enabled = true
            )
            val disabledSrv = McpServerEntity(
                id = "srv_win",
                name = "Windows MCP",
                transport = McpTransport.STREAMABLE_HTTP.name,
                endpoint = "https://example.invalid/mcp",
                enabled = false
            )
            val dao = FakeMcpServerDao(listOf(srvEntity, disabledSrv))
            val bridge = TermuxBridge(ContextWrapper(null))
            val mcpManager = McpClientManager(dao, bridge, tempDir)
            val wm = WorkspaceManager(ContextWrapper(null), bridge, FakeFileSnapshotDao())
            val registry = AgentToolRegistry(
                workspaceManager = wm,
                termuxBridge = bridge,
                mcpManager = mcpManager,
                webScraper = WebScraperEngine(ContextWrapper(null)),
                onOpenBrowserUrl = {}
            )

            val output = registry.execute(
                toolName = "mcp_call_tool",
                argsJson = """{"server":"MediaMcp","tool_name":"capture_image","arguments_json":"{}"}""",
                defaultEnv = WorkspaceEnvType.INTERNAL,
                internalProject = "default",
                sessionId = "sess_1",
                messageId = "msg_1",
                agentSettings = AgentSettings(),
                searchSettings = SearchSettings()
            )

            assertFalse(output.isError)
            assertEquals(2, output.outgoingAttachments.size)
            val webpAtt = output.outgoingAttachments[0]
            val pngAtt = output.outgoingAttachments[1]
            assertEquals(AttachmentKind.IMAGE, webpAtt.kind)
            assertEquals("image/webp", webpAtt.mimeType)
            assertTrue(webpAtt.localPath.endsWith(".webp"))
            assertEquals(AttachmentKind.IMAGE, pngAtt.kind)
            assertEquals("image/png", pngAtt.mimeType)
            assertTrue(pngAtt.localPath.endsWith(".png"))
            assertNotEquals(webpAtt.localPath, pngAtt.localPath)
            assertTrue(File(webpAtt.localPath).readBytes().contentEquals(webpBytes))
            assertTrue(File(pngAtt.localPath).readBytes().contentEquals(pngBytes))

            val envDesc = registry.describeConnectedEnvironments("default", WorkspaceEnvType.INTERNAL)
            assertFalse("Base context must not mention MCP servers before explicit query", envDesc.contains("Windows MCP"))
            assertFalse("Base context must not mention MCP servers before explicit query", envDesc.contains("MediaMcp"))
            assertFalse("Base context must not leak disabled MCP endpoints", envDesc.contains("example.invalid"))
        } finally {
            server.shutdown()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `workspace attachment detection recognizes extensionless png and webp headers`() {
        val tempDir = Files.createTempDirectory("forgemind_magic_test").toFile()
        try {
            val bridge = TermuxBridge(ContextWrapper(null))
            val wm = WorkspaceManager(ContextWrapper(null), bridge, FakeFileSnapshotDao())

            val rawPng = File(tempDir, "screen_capture_blob")
            rawPng.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00))
            val att = wm.buildAttachmentFromLocalFile(rawPng, isFromAssistant = true)
            assertEquals(AttachmentKind.IMAGE, att.kind)
            assertEquals("image/png", att.mimeType)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `public system intro describes core app capabilities without personal servers or secrets`() {
        val intro = AgentEngine.buildEcosystemAndOuterContourGuide()
        assertTrue(intro.contains("Chat, AI Files, Terminal, Browser, Settings"))
        assertTrue(intro.contains("send_file_to_chat"))
        assertTrue(intro.contains("mcp_list_servers"))
        // Repository hygiene: ensure no external personal server mentions or tokens appear
        assertFalse(intro.contains("ui.sh", ignoreCase = true))
        assertFalse(intro.contains("trycloudflare", ignoreCase = true))
        assertFalse(intro.contains("244+"))
    }

    @Test
    fun `mcp server instructions and tool schemas stay out of base context until queried on demand`() = runTest {
        val tempDir = Files.createTempDirectory("forgemind_mcp_lazy_test").toFile()
        try {
            val toolsJson = """[
                {"name":"secret_mcp_tool","description":"Runs a remote diagnostic","inputSchemaJson":"{\"type\":\"object\",\"properties\":{\"target\":{\"type\":\"string\"}},\"required\":[\"target\"]}"},
                {"name":"other_tool","description":"Another remote tool","inputSchemaJson":"{\"type\":\"object\",\"properties\":{}}"}
            ]"""
            val srv = McpServerEntity(
                id = "srv_lazy",
                name = "HeavyMcp",
                transport = McpTransport.STREAMABLE_HTTP.name,
                endpoint = "http://127.0.0.1:9999/mcp",
                enabled = true,
                cachedToolsJson = toolsJson,
                instructions = "SECRET_SERVER_INSTRUCTIONS_ONLY_ON_DEMAND"
            )
            val dao = FakeMcpServerDao(listOf(srv))
            val bridge = TermuxBridge(ContextWrapper(null))
            val mcpManager = McpClientManager(dao, bridge, tempDir)
            val wm = WorkspaceManager(ContextWrapper(null), bridge, FakeFileSnapshotDao())
            val registry = AgentToolRegistry(
                workspaceManager = wm,
                termuxBridge = bridge,
                mcpManager = mcpManager,
                webScraper = WebScraperEngine(ContextWrapper(null)),
                onOpenBrowserUrl = {}
            )

            // 1) Base system environment description must NOT leak MCP server names, URLs, instructions or tool names
            val envDesc = registry.describeConnectedEnvironments("default", WorkspaceEnvType.INTERNAL)
            assertFalse("Base context must not mention MCP server name before query", envDesc.contains("HeavyMcp"))
            assertFalse("Base context must not mention MCP endpoints before query", envDesc.contains("127.0.0.1:9999"))
            assertFalse("Base context must not preload MCP instructions", envDesc.contains("SECRET_SERVER_INSTRUCTIONS_ONLY_ON_DEMAND"))
            assertFalse("Base context must not preload MCP tool names", envDesc.contains("secret_mcp_tool"))

            // 2) Outgoing LLM tool specs must stay strictly built-in (67), NOT bloated by MCP schemas
            val specs = registry.buildToolSpecs(AgentSettings(enableTools = true))
            assertEquals(67, specs.size)
            assertFalse(specs.any { it.name.contains("secret_mcp_tool") })

            // 2b) Calling mcp_list_servers() with no args reveals configured server names only on demand
            val listOut = registry.execute(
                toolName = "mcp_list_servers",
                argsJson = "{}",
                defaultEnv = WorkspaceEnvType.INTERNAL,
                internalProject = "default",
                sessionId = "s1",
                messageId = "m0",
                agentSettings = AgentSettings(),
                searchSettings = SearchSettings()
            )
            assertFalse(listOut.isError)
            assertTrue(listOut.text.contains("HeavyMcp"))

            // 3) Querying the specific server via mcp_list_servers returns its instructions & tool list on demand
            val serverOut = registry.execute(
                toolName = "mcp_list_servers",
                argsJson = """{"server":"HeavyMcp"}""",
                defaultEnv = WorkspaceEnvType.INTERNAL,
                internalProject = "default",
                sessionId = "s1",
                messageId = "m1",
                agentSettings = AgentSettings(),
                searchSettings = SearchSettings()
            )
            assertFalse(serverOut.isError)
            assertTrue(serverOut.text.contains("SECRET_SERVER_INSTRUCTIONS_ONLY_ON_DEMAND"))
            assertTrue(serverOut.text.contains("secret_mcp_tool(target)"))

            // 4) Querying a specific tool_name returns its exact JSON schema
            val schemaOut = registry.execute(
                toolName = "mcp_list_servers",
                argsJson = """{"server":"HeavyMcp","tool_name":"secret_mcp_tool"}""",
                defaultEnv = WorkspaceEnvType.INTERNAL,
                internalProject = "default",
                sessionId = "s1",
                messageId = "m2",
                agentSettings = AgentSettings(),
                searchSettings = SearchSettings()
            )
            assertFalse(schemaOut.isError)
            assertTrue(schemaOut.text.contains("\"required\":[\"target\"]"))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
