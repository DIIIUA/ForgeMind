package com.forgemind.app

import com.forgemind.app.agent.AgentEngine
import com.forgemind.app.agent.AgentToolRegistry
import com.forgemind.app.data.db.ChatMessageEntity
import com.forgemind.app.data.model.ContextWindowResolver
import com.forgemind.app.data.model.LlmRole
import com.forgemind.app.data.model.ProviderCategory
import com.forgemind.app.data.model.ProviderConfig
import com.forgemind.app.data.model.ProviderType
import com.forgemind.app.data.model.ToolCallInfo
import com.forgemind.app.data.model.WireProtocol
import com.forgemind.app.llm.LanAiScanner
import com.forgemind.app.llm.LlmHttpFactory
import com.forgemind.app.llm.LlmStreamEvent
import com.forgemind.app.llm.LlmToolSpec
import com.forgemind.app.llm.LlmWireMessage
import com.forgemind.app.llm.ModelScanner
import com.forgemind.app.llm.OpenAiCompatibleClient
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class UniversalToolAndContextTests {

    private fun readRawHttpRequest(input: InputStream): Pair<String, String>? {
        val headerBuf = ByteArrayOutputStream()
        var state = 0
        while (true) {
            val b = input.read()
            if (b < 0) return if (headerBuf.size() == 0) null else headerBuf.toString(Charsets.UTF_8) to ""
            headerBuf.write(b)
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> 4
                else -> 0
            }
            if (state == 4) break
        }
        val headersText = headerBuf.toString(Charsets.UTF_8)
        val lines = headersText.split("\r\n")
        val requestLine = lines.firstOrNull().orEmpty()
        val contentLen = lines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        if (contentLen <= 0) return requestLine to ""
        val bodyBytes = ByteArray(contentLen)
        var offset = 0
        while (offset < contentLen) {
            val n = input.read(bodyBytes, offset, contentLen - offset)
            if (n <= 0) break
            offset += n
        }
        return requestLine to String(bodyBytes, 0, offset, Charsets.UTF_8)
    }

    private val sampleKnownTools = setOf(
        "run_command",
        "run_command_async",
        "list_files",
        "file_read",
        "file_write",
        "file_edit",
        "web_search",
        "mcp_call_tool"
    )

    @Test
    fun `LanAiScanner parses targeted host input and probes specific endpoint with context length`() = runBlocking {
        val parsed = LanAiScanner.parseTargetInput("http://192.168.1.77:1234/v1/")
        assertNotNull(parsed)
        assertEquals("http", parsed!!.scheme)
        assertEquals("192.168.1.77", parsed.host)
        assertEquals(1234, parsed.explicitPort)
        assertEquals("/v1", parsed.path)

        val directOllama = LanAiScanner.buildDirectEndpointServer("100.88.1.9:11434")
        assertNotNull(directOllama)
        assertEquals("http://100.88.1.9:11434", directOllama!!.baseUrl)
        assertEquals(WireProtocol.OLLAMA_NATIVE, directOllama.wireProtocol)

        val directCustom = LanAiScanner.buildDirectEndpointServer("192.168.0.50:8080")
        assertNotNull(directCustom)
        assertEquals("http://192.168.0.50:8080/v1", directCustom!!.baseUrl)
        assertEquals(ProviderType.LLAMA_CPP, directCustom.suggestedProviderType)

        // Live targeted probe against a local mock LM Studio / OpenAI server
        ServerSocket(0).use { srv ->
            val port = srv.localPort
            val serverThread = thread(start = true) {
                while (!srv.isClosed) {
                    val sock = runCatching { srv.accept() }.getOrNull() ?: break
                    sock.use { s ->
                        s.soTimeout = 1500
                        val req = runCatching { readRawHttpRequest(s.getInputStream()) }.getOrNull() ?: return@use
                        val (reqLine, _) = req
                        val out = s.getOutputStream()
                        if (reqLine.contains("/v1/models")) {
                            val body = """{"data":[{"id":"DeepSeek-V4-Pro-V3.5-9B-Uncensored-Heretic-GGUF","loaded_context_length":24576}]}"""
                            val bytes = body.toByteArray(Charsets.UTF_8)
                            out.write(
                                ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                                    .toByteArray(Charsets.UTF_8)
                            )
                            out.write(bytes)
                        } else {
                            val bytes = """{"error":"not_found"}""".toByteArray(Charsets.UTF_8)
                            out.write(
                                ("HTTP/1.1 404 Not Found\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                                    .toByteArray(Charsets.UTF_8)
                            )
                            out.write(bytes)
                        }
                        out.flush()
                    }
                }
            }

            val scanner = LanAiScanner()
            val found = scanner.probeSpecificHost("127.0.0.1:$port")
            srv.close()
            serverThread.join(1500)

            assertEquals(1, found.size)
            assertEquals("DeepSeek-V4-Pro-V3.5-9B-Uncensored-Heretic-GGUF", found.first().models.first())
            assertEquals(24576, found.first().modelContextLengths["DeepSeek-V4-Pro-V3.5-9B-Uncensored-Heretic-GGUF"])
        }
    }

    @Test
    fun `ContextWindowResolver and ProviderConfig auto-detect per model and preserve manual overrides`() {
        val hereticCtx = ContextWindowResolver.inferContextWindow(
            modelId = "DeepSeek-V4-Pro-V3.5-9B-Uncensored-Heretic-Q4_K_M.gguf",
            providerType = ProviderType.LM_STUDIO,
            category = ProviderCategory.LOCAL_LAN
        )
        assertEquals(16_384, hereticCtx)

        assertEquals(
            131_072,
            ContextWindowResolver.inferContextWindow(
                modelId = "qwen2.5-coder-14b-128k",
                providerType = ProviderType.OLLAMA,
                category = ProviderCategory.LOCAL_LAN
            )
        )
        assertEquals(
            1_000_000,
            ContextWindowResolver.inferContextWindow(
                modelId = "gemini-2.5-pro",
                providerType = ProviderType.GEMINI,
                category = ProviderCategory.CLOUD
            )
        )

        val baseCfg = ProviderConfig(
            providerId = ProviderType.LM_STUDIO.id,
            modelId = "DeepSeek-V4-Pro-V3.5-9B-Uncensored-Heretic",
            autoContextWindow = true,
            modelContextOverrides = mapOf("qwen3:8b" to 65_536)
        )
        // Server reported 24K -> auto mode picks 24K
        assertEquals(24_576, baseCfg.resolveContextWindowForModel("DeepSeek-V4-Pro-V3.5-9B-Uncensored-Heretic", 24_576))
        // Switching to a model with an explicit manual override uses 64K even over server's 32K
        assertEquals(65_536, baseCfg.resolveContextWindowForModel("qwen3:8b", 32_768))

        // Ollama /api/show parser
        val ollamaShowPayload = """
            {
              "parameters": "temperature 0.7\nnum_ctx 49152",
              "model_info": {
                "deepseek2.context_length": 131072
              }
            }
        """.trimIndent()
        assertEquals(49_152, ModelScanner.parseOllamaShowContextLength(ollamaShowPayload))

        val ollamaArchOnly = """{"model_info":{"qwen2.context_length":32768}}"""
        assertEquals(32_768, ModelScanner.parseOllamaShowContextLength(ollamaArchOnly))
    }

    @Test
    fun `extractInlineFallbackToolCalls parses XML DeepSeek DSML function Mistral JSON and Pythonic formats`() {
        // 1. `<tool_call>` with loose single-quoted JSON & trailing comma
        val xmlRes = AgentEngine.extractInlineFallbackToolCalls(
            "Сейчас проверю файлы.\n<tool_call>\n{'name': 'run_command', 'arguments': {'command': 'ls -la', 'target_env': 'internal',}}\n</tool_call>",
            sampleKnownTools
        )
        assertEquals("Сейчас проверю файлы.", xmlRes.cleanedText)
        assertEquals(1, xmlRes.toolCalls.size)
        assertEquals("run_command", xmlRes.toolCalls[0].name)
        assertTrue(xmlRes.toolCalls[0].viaTextFallback)
        assertEquals(
            "ls -la",
            LlmHttpFactory.json.parseToJsonElement(xmlRes.toolCalls[0].argumentsJson).jsonObject["command"]?.jsonPrimitive?.content
        )

        // 2. Native DeepSeek V3 / V3.5 / V4 `<｜tool▁calls▁begin｜>`
        val dsNative = """
            Проверяю ядро системы:
            <｜tool▁calls▁begin｜><｜tool▁call▁begin｜>function<｜tool▁sep｜>run_command
            ```json
            {"command": "uname -a", "target_env": "internal"}
            ```<｜tool▁call▁end｜><｜tool▁calls▁end｜>
        """.trimIndent()
        val dsRes = AgentEngine.extractInlineFallbackToolCalls(dsNative, sampleKnownTools)
        assertEquals("Проверяю ядро системы:", dsRes.cleanedText)
        assertEquals(1, dsRes.toolCalls.size)
        assertEquals("run_command", dsRes.toolCalls[0].name)
        assertEquals(
            "uname -a",
            LlmHttpFactory.json.parseToJsonElement(dsRes.toolCalls[0].argumentsJson).jsonObject["command"]?.jsonPrimitive?.content
        )

        // 3. `<function=name><parameter=k>v</parameter></function>`
        val fnTag = "<function=file_read><parameter=path>README.md</parameter></function>"
        val fnRes = AgentEngine.extractInlineFallbackToolCalls(fnTag, sampleKnownTools)
        assertEquals(1, fnRes.toolCalls.size)
        assertEquals("file_read", fnRes.toolCalls[0].name)
        assertEquals(
            "README.md",
            LlmHttpFactory.json.parseToJsonElement(fnRes.toolCalls[0].argumentsJson).jsonObject["path"]?.jsonPrimitive?.content
        )

        // 4. Mistral `[TOOL_CALLS]`
        val mistralRes = AgentEngine.extractInlineFallbackToolCalls(
            """[TOOL_CALLS] [{"name":"web_search","arguments":{"query":"ForgeMind Android"}}]""",
            sampleKnownTools
        )
        assertEquals(1, mistralRes.toolCalls.size)
        assertEquals("web_search", mistralRes.toolCalls[0].name)

        // 5. Fenced ```json tool call matching known tool name
        val fencedJsonRes = AgentEngine.extractInlineFallbackToolCalls(
            "```json\n{\"name\": \"list_files\", \"arguments\": {\"path\": \".\"}}\n```",
            sampleKnownTools
        )
        assertEquals(1, fencedJsonRes.toolCalls.size)
        assertEquals("list_files", fencedJsonRes.toolCalls[0].name)

        // 6. Pythonic invocation `run_command("df -h")`
        val pyRes = AgentEngine.extractInlineFallbackToolCalls(
            "```python\nrun_command(\"df -h\")\n```",
            sampleKnownTools
        )
        assertEquals(1, pyRes.toolCalls.size)
        assertEquals("run_command", pyRes.toolCalls[0].name)
        assertEquals(
            "df -h",
            LlmHttpFactory.json.parseToJsonElement(pyRes.toolCalls[0].argumentsJson).jsonObject["command"]?.jsonPrimitive?.content
        )
    }

    @Test
    fun `viaTextFallback wire history avoids role tool and shouldUseCompactModelMode detects 9B Heretic`() {
        val hereticCfg = ProviderConfig(
            providerId = "lan_lm_studio_192_168_1_50_1234",
            modelId = "DeepSeek-V4-Pro-V3.5-9B-Uncensored-Heretic",
            contextWindow = 16_384,
            categoryOverride = ProviderCategory.LOCAL_LAN
        )
        assertTrue(AgentToolRegistry.shouldUseCompactModelMode(hereticCfg))

        // Unexecuted bash block nudge fires for action prompts, not educational questions
        assertTrue(
            AgentEngine.shouldNudgeUnexecutedCommandOutput(
                userPrompt = "Покажи список файлов в текущей папке",
                assistantText = "Выполните команду:\n```bash\nls -la\n```",
                isCompactOrUncensored = true
            )
        )
        assertFalse(
            AgentEngine.shouldNudgeUnexecutedCommandOutput(
                userPrompt = "Объясни, что делает команда grep в Linux?",
                assistantText = "Пример поиска строки:\n```bash\ngrep -rn \"foo\" .\n```",
                isCompactOrUncensored = true
            )
        )

        // Verify `viaTextFallback = true` serializes as `<tool_call>` + USER result (zero LlmRole.TOOL rows!)
        val fallbackCall = ToolCallInfo(
            id = "tc_1",
            name = "run_command",
            argumentsJson = """{"command":"whoami"}""",
            viaTextFallback = true
        )
        val history = listOf(
            ChatMessageEntity(
                id = "m1",
                sessionId = "s1",
                orderIndex = 1,
                role = LlmRole.USER.name,
                content = "Кто текущий пользователь?"
            ),
            ChatMessageEntity(
                id = "m2",
                sessionId = "s1",
                orderIndex = 2,
                role = LlmRole.ASSISTANT.name,
                content = "Проверяю.",
                toolCallsJson = LlmHttpFactory.json.encodeToString(listOf(fallbackCall))
            ),
            ChatMessageEntity(
                id = "m3",
                sessionId = "s1",
                orderIndex = 3,
                role = LlmRole.TOOL.name,
                content = "u0_a412",
                toolCallId = "tc_1",
                toolName = "run_command"
            )
        )
        val wire = AgentEngine.buildWireHistory("SYS", "", history)
        assertTrue(wire.none { it.role == LlmRole.TOOL })
        val assistantMsg = wire.first { it.role == LlmRole.ASSISTANT }
        assertTrue(assistantMsg.toolCalls.isEmpty())
        assertTrue(assistantMsg.content.contains("<tool_call>"))
        val trailingUser = wire.last()
        assertEquals(LlmRole.USER, trailingUser.role)
        assertTrue(trailingUser.content.contains("[Результат инструмента run_command]"))
        assertTrue(trailingUser.content.contains("u0_a412"))
    }

    @Test
    fun `OpenAiCompatibleClient automatically downgrades to text tool protocol on HTTP 400 template error`() = runBlocking {
        val requestPayloads = CopyOnWriteArrayList<String>()
        ServerSocket(0).use { srv ->
            val port = srv.localPort
            val serverThread = thread(start = true) {
                var attempt = 0
                while (!srv.isClosed && attempt < 2) {
                    val sock = runCatching { srv.accept() }.getOrNull() ?: break
                    sock.use { s ->
                        s.soTimeout = 3000
                        val req = runCatching { readRawHttpRequest(s.getInputStream()) }.getOrNull() ?: return@use
                        val (_, bodyText) = req
                        requestPayloads += bodyText
                        val out = s.getOutputStream()
                        if (attempt == 0) {
                            val err = """{"error":{"message":"Chat template does not support tools (Jinja error)"}}""".toByteArray(Charsets.UTF_8)
                            out.write(
                                ("HTTP/1.1 400 Bad Request\r\nContent-Type: application/json\r\nContent-Length: ${err.size}\r\nConnection: close\r\n\r\n")
                                    .toByteArray(Charsets.UTF_8)
                            )
                            out.write(err)
                        } else {
                            val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"<tool_call>{\\\"name\\\":\\\"run_command\\\",\\\"arguments\\\":{\\\"command\\\":\\\"pwd\\\"}}</tool_call>\"}}]}\n\ndata: [DONE]\n\n"
                                .toByteArray(Charsets.UTF_8)
                            out.write(
                                ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${sse.size}\r\nConnection: close\r\n\r\n")
                                    .toByteArray(Charsets.UTF_8)
                            )
                            out.write(sse)
                        }
                        out.flush()
                        attempt++
                    }
                }
            }

            val client = OpenAiCompatibleClient(OkHttpClient())
            val cfg = ProviderConfig(
                providerId = ProviderType.LM_STUDIO.id,
                baseUrl = "http://127.0.0.1:$port/v1",
                modelId = "DeepSeek-V4-Pro-V3.5-9B-Uncensored-Heretic",
                contextWindow = 16_384
            )
            val events = client.streamChat(
                config = cfg,
                messages = listOf(
                    LlmWireMessage(role = LlmRole.SYSTEM, content = "System prompt"),
                    LlmWireMessage(role = LlmRole.USER, content = "Run pwd")
                ),
                tools = listOf(
                    LlmToolSpec(
                        name = "run_command",
                        description = "Execute shell command",
                        parametersSchema = buildJsonObject {
                            put("type", JsonPrimitive("object"))
                        }
                    )
                )
            ).toList()

            serverThread.join(3000)

            assertEquals(2, requestPayloads.size)
            val firstJson = LlmHttpFactory.json.parseToJsonElement(requestPayloads[0]).jsonObject
            val secondJson = LlmHttpFactory.json.parseToJsonElement(requestPayloads[1]).jsonObject
            assertTrue("tools" in firstJson)
            assertFalse("tools" in secondJson)
            assertTrue(secondJson["messages"].toString().contains("<tool_call>"))

            val fullText = events.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString("") { it.text }
            val extracted = AgentEngine.extractInlineFallbackToolCalls(fullText, setOf("run_command"))
            assertEquals(1, extracted.toolCalls.size)
            assertEquals("run_command", extracted.toolCalls[0].name)
        }
    }
}
