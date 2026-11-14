package com.forgemind.app

import android.content.ContextWrapper
import com.forgemind.app.agent.AgentEngine
import com.forgemind.app.bridge.TermuxBridge
import com.forgemind.app.data.db.ChatMessageEntity
import com.forgemind.app.data.db.McpServerDao
import com.forgemind.app.data.db.McpServerEntity
import com.forgemind.app.data.model.LlmRole
import com.forgemind.app.data.model.McpTransport
import com.forgemind.app.data.model.ToolCallInfo
import com.forgemind.app.llm.LlmHttpFactory
import com.forgemind.app.mcp.McpClientManager
import com.forgemind.app.web.AgentBrowserController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LiveTranscriptRegressionTests {

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

    @Test
    fun `extractInlineFallbackToolCalls parses DeepSeek DSML multi-invoke blocks and strips XML`() {
        val raw = """
            Saving project summary and checking browser state.
            <｜DSML｜ calls>
            <｜DSML｜ invoke name="file_write">
            <｜DSML｜ parameter name="arguments" string="false">{"path": "/workspace/README.md", "lines": 42}</｜DSML｜ parameter>
            <｜DSML｜ parameter name="key" string="true">note-1</｜DSML｜ parameter>
            </｜DSML｜ invoke>
            <｜DSML｜ invoke name="browser_agent_mode">
            <｜DSML｜ parameter name="action" string="true">status</｜DSML｜ parameter>
            </｜DSML｜ invoke>
            </｜DSML｜ calls>
        """.trimIndent()

        val parsed = AgentEngine.extractInlineFallbackToolCalls(
            raw,
            setOf("file_write", "browser_agent_mode")
        )
        assertEquals("Saving project summary and checking browser state.", parsed.cleanedText)
        assertFalse(parsed.cleanedText.contains("DSML"))
        assertEquals(2, parsed.toolCalls.size)

        assertEquals("file_write", parsed.toolCalls[0].name)
        val firstArgs = LlmHttpFactory.json.parseToJsonElement(parsed.toolCalls[0].argumentsJson).jsonObject
        val nested = firstArgs["arguments"]!!.jsonObject
        assertEquals("/workspace/README.md", nested["path"]!!.jsonPrimitive.content)
        assertEquals("42", nested["lines"]!!.jsonPrimitive.content)
        assertEquals("note-1", firstArgs["key"]!!.jsonPrimitive.content)

        assertEquals("browser_agent_mode", parsed.toolCalls[1].name)
        val secondArgs = LlmHttpFactory.json.parseToJsonElement(parsed.toolCalls[1].argumentsJson).jsonObject
        assertEquals("status", secondArgs["action"]!!.jsonPrimitive.content)
    }

    @Test
    fun `extractInlineFallbackToolCalls parses known bracket pseudo-calls and ignores unknown prose`() {
        val raw = """
            Reading documentation next.
            [Call file_read: {"path": "/docs/FAST.md", "offset": 0, "length": 15000}]
            [Вызов search_files: {"query": "architecture", "limit": 10}]
            [Call unknown_tool: {"foo": "bar"}]
        """.trimIndent()

        val parsed = AgentEngine.extractInlineFallbackToolCalls(
            raw,
            setOf("file_read", "search_files")
        )
        assertEquals(2, parsed.toolCalls.size)
        assertEquals("file_read", parsed.toolCalls[0].name)
        assertEquals("search_files", parsed.toolCalls[1].name)
        assertFalse(parsed.cleanedText.contains("file_read"))
        assertFalse(parsed.cleanedText.contains("search_files"))
        assertTrue(parsed.cleanedText.contains("unknown_tool"))
    }

    @Test
    fun `stripLiveDsmlPreview hides streaming DSML tail without corrupting normal text`() {
        val midStream = "Preparing file write\n\n<｜DSML｜ calls>\n<｜DSML｜ invoke name=\"file_write\">"
        assertEquals("Preparing file write", AgentEngine.stripLiveDsmlPreview(midStream))

        val partialTag = "Checking output <｜DSM"
        assertEquals("Checking output", AgentEngine.stripLiveDsmlPreview(partialTag))

        val cleanText = "Regular answer text comparing a | b with a list."
        assertEquals(cleanText, AgentEngine.stripLiveDsmlPreview(cleanText))
    }

    @Test
    fun `buildReflectionWireHistory avoids bracket call format that triggers model imitation`() {
        val call = ToolCallInfo(id = "tc1", name = "run_command", argumentsJson = """{"command":"pwd"}""")
        val msgs = listOf(
            ChatMessageEntity(id = "u1", sessionId = "s", orderIndex = 1, role = LlmRole.USER.name, content = "Check dir"),
            ChatMessageEntity(
                id = "a1",
                sessionId = "s",
                orderIndex = 2,
                role = LlmRole.ASSISTANT.name,
                content = "Checking.",
                toolCallsJson = LlmHttpFactory.json.encodeToString(listOf(call))
            ),
            ChatMessageEntity(
                id = "t1",
                sessionId = "s",
                orderIndex = 3,
                role = LlmRole.TOOL.name,
                content = "/workspace",
                toolCallId = "tc1",
                toolName = "run_command"
            )
        )
        val wire = AgentEngine.buildReflectionWireHistory("SYS", "", msgs, callsInStreak = 5, threshold = 5)
        val assistantMsg = wire.first { it.role == LlmRole.ASSISTANT }
        assertTrue(assistantMsg.content.contains("run_command"))
        assertFalse(assistantMsg.content.contains("[Вызов "))
        assertFalse(assistantMsg.content.contains("[Call "))
    }

    @Test
    fun `browser inspect and act scripts clear stale indices and guard prototype value setters`() {
        val inspectJs = AgentBrowserController.buildInspectScript(8000)
        assertTrue(inspectJs.contains("removeAttribute('data-fm-agent-idx')"))

        val actJs = AgentBrowserController.buildActScript("""{"action":"type","index":13,"value":"docs"}""")
        assertTrue(actJs.contains("querySelectorAll('[data-fm-agent-idx=\"' + cfg.index + '\"]')"))
        assertTrue(actJs.contains("isNodeVisible"))
        assertTrue(actJs.contains("Target element is not editable"))
        assertTrue(actJs.contains("tagUpper === 'INPUT'"))
        assertTrue(actJs.contains("tagUpper === 'TEXTAREA'"))
        assertTrue(actJs.contains("catch (_)"))
    }

    @Test
    fun `mcp client auto-initializes on HTTP 400 missing Mcp-Session-Id and retries with session header`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error":"One Mcp-Session-Id is required; initialize first","code":"invalid_request"}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Mcp-Session-Id", "sess-recovered-42")
                .setBody(
                    """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","capabilities":{"tools":{}},"serverInfo":{"name":"StatefulMcp","version":"1.0"}}}"""
                )
        )
        server.enqueue(MockResponse().setResponseCode(202).setBody(""))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"pong-ok"}],"isError":false}}"""
                )
        )
        val tempDir = Files.createTempDirectory("forgemind_reinit_test").toFile()
        server.start()
        try {
            val entity = McpServerEntity(
                id = "stateful_srv",
                name = "StatefulServer",
                transport = McpTransport.STREAMABLE_HTTP.name,
                endpoint = server.url("/mcp").toString(),
                enabled = true
            )
            val dao = FakeMcpServerDao(listOf(entity))
            val bridge = TermuxBridge(ContextWrapper(null))
            val manager = McpClientManager(dao, bridge, tempDir)

            val res = manager.callTool("stateful_srv", "ping", "{}")
            assertFalse(res.isError)
            assertEquals("pong-ok", res.text)

            assertEquals(4, server.requestCount)
            val req1 = server.takeRequest()
            assertNull(req1.getHeader("Mcp-Session-Id"))
            val req2 = server.takeRequest()
            assertTrue(req2.body.readUtf8().contains("\"initialize\""))
            val req3 = server.takeRequest()
            assertEquals("sess-recovered-42", req3.getHeader("Mcp-Session-Id"))
            val req4 = server.takeRequest()
            assertEquals("sess-recovered-42", req4.getHeader("Mcp-Session-Id"))
            assertTrue(req4.body.readUtf8().contains("\"tools/call\""))
        } finally {
            server.shutdown()
            tempDir.deleteRecursively()
        }
    }
}
