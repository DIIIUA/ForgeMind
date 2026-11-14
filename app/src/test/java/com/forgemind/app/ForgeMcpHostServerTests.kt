package com.forgemind.app

import android.content.ContextWrapper
import com.forgemind.app.agent.AgentToolRegistry
import com.forgemind.app.bridge.TermuxBridge
import com.forgemind.app.bridge.WorkspaceManager
import com.forgemind.app.data.db.FileSnapshotDao
import com.forgemind.app.data.db.FileSnapshotEntity
import com.forgemind.app.data.db.McpServerDao
import com.forgemind.app.data.db.McpServerEntity
import com.forgemind.app.data.model.McpTransport
import com.forgemind.app.mcp.ForgeMcpHostServer
import com.forgemind.app.mcp.McpClientManager
import com.forgemind.app.web.WebScraperEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ForgeMcpHostServerTests {

    private class InMemoryMcpDao : McpServerDao {
        private val map = linkedMapOf<String, McpServerEntity>()
        private val flow = MutableStateFlow<List<McpServerEntity>>(emptyList())

        override fun observeAll(): Flow<List<McpServerEntity>> = flow
        override suspend fun getAll(): List<McpServerEntity> = map.values.toList()
        override suspend fun getEnabled(): List<McpServerEntity> = map.values.filter { it.enabled }
        override suspend fun getById(id: String): McpServerEntity? = map[id]
        override suspend fun upsert(server: McpServerEntity) {
            map[server.id] = server
            flow.value = map.values.toList()
        }
        override suspend fun deleteById(id: String) {
            map.remove(id)
            flow.value = map.values.toList()
        }
    }

    private class NoopSnapshotDao : FileSnapshotDao {
        private val flow = MutableStateFlow<List<FileSnapshotEntity>>(emptyList())
        override fun observeForSession(sessionId: String): Flow<List<FileSnapshotEntity>> = flow
        override suspend fun getById(id: String): FileSnapshotEntity? = null
        override suspend fun getRecentForSession(sessionId: String, limit: Int): List<FileSnapshotEntity> = emptyList()
        override suspend fun upsert(snapshot: FileSnapshotEntity) = Unit
        override suspend fun markRestored(id: String) = Unit
    }

    private fun createEnv(tempDir: File): Pair<AgentToolRegistry, McpClientManager> {
        val fakeCtx = object : ContextWrapper(null) {
            override fun getFilesDir(): File = tempDir
        }
        val bridge = TermuxBridge(fakeCtx)
        val dao = InMemoryMcpDao()
        val chatFilesDir = File(tempDir, "chat_files").apply { mkdirs() }
        val mcpManager = McpClientManager(dao, bridge, chatFilesDir)
        val wm = WorkspaceManager(fakeCtx, bridge, NoopSnapshotDao())
        val registry = AgentToolRegistry(
            workspaceManager = wm,
            termuxBridge = bridge,
            mcpManager = mcpManager,
            webScraper = WebScraperEngine(fakeCtx)
        )
        return registry to mcpManager
    }

    @Test
    fun `built-in MCP host server handles health discovery and Cyrillic UTF-8 tool calls end-to-end`() = runTest {
        val tempDir = Files.createTempDirectory("forgemind_mcp_host_e2e").toFile()
        val (registry, clientManager) = createEnv(tempDir)
        val host = ForgeMcpHostServer(toolRegistryProvider = { registry })
        try {
            val boundPort = host.start(port = 0, requireAuth = false, exposeShellAndWrite = true).getOrThrow()
            assertTrue("Expected dynamic loopback port > 0", boundPort > 0)

            val http = OkHttpClient()
            http.newCall(Request.Builder().url("http://127.0.0.1:$boundPort/health").get().build()).execute().use { resp ->
                assertEquals(200, resp.code)
                val root = Json.parseToJsonElement(resp.body!!.string()).jsonObject
                assertEquals("true", root["ok"]?.jsonPrimitive?.content)
                assertEquals(56, root["toolsCount"]?.jsonPrimitive?.int)
            }

            val (srv, tools) = clientManager.connectDynamicServer(
                name = "local-forgemind",
                endpoint = "http://127.0.0.1:$boundPort/mcp",
                transport = McpTransport.STREAMABLE_HTTP
            ).getOrThrow()

            assertEquals(56, tools.size)
            val toolNames = tools.map { it.name }.toSet()
            assertTrue("screen_read_windows" in toolNames)
            assertTrue("screen_act_batch" in toolNames)
            assertTrue("screen_capture" in toolNames)
            assertTrue("web_scrape_site" in toolNames)
            assertTrue("browser_agent_tabs" in toolNames)
            assertTrue("browser_agent_batch" in toolNames)
            assertTrue("browser_agent_markdown" in toolNames)

            val writeRes = clientManager.callTool(
                serverId = srv.id,
                toolName = "file_write",
                argumentsJson = """{"path":"заметка.txt","content":"Привет из встроенного MCP сервера ForgeMind 🚀"}"""
            )
            assertFalse("file_write failed: ${writeRes.text}", writeRes.isError)

            val readRes = clientManager.callTool(
                serverId = srv.id,
                toolName = "file_read",
                argumentsJson = """{"path":"заметка.txt"}"""
            )
            assertFalse("file_read failed: ${readRes.text}", readRes.isError)
            assertTrue(readRes.text.contains("Привет из встроенного MCP сервера ForgeMind 🚀"))
        } finally {
            host.stop()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `built-in MCP host server enforces Bearer token auth and shell-write tool filter`() = runTest {
        val tempDir = Files.createTempDirectory("forgemind_mcp_host_sec").toFile()
        val (registry, clientManager) = createEnv(tempDir)
        val host = ForgeMcpHostServer(toolRegistryProvider = { registry })
        try {
            val secret = "fm_secret_token_9911"
            val boundPort = host.start(
                port = 0,
                requireAuth = true,
                authToken = secret,
                exposeShellAndWrite = false
            ).getOrThrow()

            // 1) Unauthenticated request must return HTTP 401
            val http = OkHttpClient()
            val unauthReq = Request.Builder()
                .url("http://127.0.0.1:$boundPort/mcp")
                .post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""".toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(unauthReq).execute().use { resp ->
                assertEquals(401, resp.code)
            }

            // 2) Authenticated MCP client discovers filtered read/UI-only tools (56 - 16 = 40)
            val (srv, tools) = clientManager.connectDynamicServer(
                name = "secured-forgemind",
                endpoint = "http://127.0.0.1:$boundPort/mcp",
                transport = McpTransport.STREAMABLE_HTTP,
                headersJson = """{"Authorization":"Bearer $secret"}"""
            ).getOrThrow()

            val expectedSafeCount = 56 - AgentToolRegistry.SHELL_AND_WRITE_HOST_TOOLS.size
            assertEquals(expectedSafeCount, tools.size)
            assertFalse(tools.any { it.name == "run_command" })
            assertFalse(tools.any { it.name == "file_write" })
            assertTrue(tools.any { it.name == "screen_act_batch" })
            assertTrue(tools.any { it.name == "screen_capture" })

            // 3) Direct tools/call on blocked write/shell tool must be rejected
            val blockedCall = clientManager.callTool(
                serverId = srv.id,
                toolName = "run_command",
                argumentsJson = """{"command":"echo nope"}"""
            )
            assertTrue(blockedCall.isError)
            assertTrue(blockedCall.text.contains("disabled", ignoreCase = true))
        } finally {
            host.stop()
            tempDir.deleteRecursively()
        }
    }
}
