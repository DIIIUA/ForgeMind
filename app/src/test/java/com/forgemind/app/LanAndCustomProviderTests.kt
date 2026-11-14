package com.forgemind.app

import com.forgemind.app.data.model.LlmRole
import com.forgemind.app.data.model.ProviderAuthMode
import com.forgemind.app.data.model.ProviderCategory
import com.forgemind.app.data.model.ProviderConfig
import com.forgemind.app.data.model.ProviderType
import com.forgemind.app.data.model.WireProtocol
import com.forgemind.app.data.prefs.SettingsRepository
import com.forgemind.app.llm.LlmHttpFactory
import com.forgemind.app.llm.LlmStreamEvent
import com.forgemind.app.llm.LlmToolSpec
import com.forgemind.app.llm.LlmWireMessage
import com.forgemind.app.llm.OllamaNativeClient
import com.forgemind.app.llm.OpenAiResponsesClient
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class LanAndCustomProviderTests {

    @Test
    fun `isLocalOrPrivateHost and normalizeBaseUrl detect LAN Tailscale mDNS and public hosts`() {
        assertTrue(LlmHttpFactory.isLocalOrPrivateHost("127.0.0.1:11434"))
        assertTrue(LlmHttpFactory.isLocalOrPrivateHost("192.168.1.55:1234/v1"))
        assertTrue(LlmHttpFactory.isLocalOrPrivateHost("10.0.0.8:8080"))
        assertTrue(LlmHttpFactory.isLocalOrPrivateHost("172.20.10.2:8000"))
        assertTrue(LlmHttpFactory.isLocalOrPrivateHost("100.88.14.9:11434")) // Tailscale CGNAT
        assertTrue(LlmHttpFactory.isLocalOrPrivateHost("mac-studio.local:1234"))
        assertTrue(LlmHttpFactory.isLocalOrPrivateHost("workstation:11434"))

        assertFalse(LlmHttpFactory.isLocalOrPrivateHost("api.openai.com"))
        assertFalse(LlmHttpFactory.isLocalOrPrivateHost("172.32.0.1"))
        assertFalse(LlmHttpFactory.isLocalOrPrivateHost("100.10.1.1")) // Public 100.x outside 100.64/10

        assertEquals(
            "http://192.168.1.55:1234/v1",
            LlmHttpFactory.normalizeBaseUrl("192.168.1.55:1234/v1/", "http://127.0.0.1:1234/v1")
        )
        assertEquals(
            "http://100.90.1.2:11434",
            LlmHttpFactory.normalizeBaseUrl("100.90.1.2:11434", "http://127.0.0.1:11434")
        )
        assertEquals(
            "https://gateway.example.com/v1",
            LlmHttpFactory.normalizeBaseUrl("gateway.example.com//v1/", "https://api.openai.com/v1")
        )
    }

    @Test
    fun `resolveEndpointUrl honors rawEndpointUrl toggle and trailing hash`() {
        val azureRawCfg = ProviderConfig(
            providerId = "custom_azure",
            baseUrl = "https://my-res.openai.azure.com/openai/deployments/gpt4o/chat/completions?api-version=2024-10-21",
            rawEndpointUrl = true
        )
        assertEquals(
            "https://my-res.openai.azure.com/openai/deployments/gpt4o/chat/completions?api-version=2024-10-21",
            LlmHttpFactory.resolveEndpointUrl(azureRawCfg, "https://api.openai.com/v1") { "$it/chat/completions" }
        )

        val hashCfg = ProviderConfig(
            providerId = "custom_hash",
            baseUrl = "192.168.0.42:9000/custom/infer#"
        )
        assertEquals(
            "http://192.168.0.42:9000/custom/infer",
            LlmHttpFactory.resolveEndpointUrl(hashCfg, "https://api.example.com") { "$it/v1/chat/completions" }
        )
    }

    @Test
    fun `applyProviderAuth supports NONE BEARER X_API_KEY CUSTOM_HEADER and QUERY_KEY`() {
        val baseUrl = "https://api.example.com/v1/chat/completions"

        // NONE
        val bNone = Request.Builder().url(baseUrl)
        val uNone = LlmHttpFactory.applyProviderAuth(
            bNone, baseUrl,
            ProviderConfig("custom", authMode = ProviderAuthMode.NONE),
            "secret-123", ProviderAuthMode.BEARER
        )
        assertEquals(baseUrl, uNone)
        assertEquals(null, bNone.build().header("Authorization"))

        // CUSTOM_HEADER + prefix
        val bCustom = Request.Builder().url(baseUrl)
        val uCustom = LlmHttpFactory.applyProviderAuth(
            bCustom, baseUrl,
            ProviderConfig(
                "custom",
                authMode = ProviderAuthMode.CUSTOM_HEADER,
                customAuthHeader = "api-key",
                customAuthPrefix = "Token "
            ),
            "abc-999", ProviderAuthMode.BEARER
        )
        assertEquals(baseUrl, uCustom)
        assertEquals("Token abc-999", bCustom.build().header("api-key"))

        // QUERY_KEY
        val bQuery = Request.Builder().url("$baseUrl?foo=1")
        val uQuery = LlmHttpFactory.applyProviderAuth(
            bQuery, "$baseUrl?foo=1",
            ProviderConfig(
                "custom",
                authMode = ProviderAuthMode.QUERY_KEY,
                customQueryParam = "api_key"
            ),
            "myKey", ProviderAuthMode.BEARER
        )
        assertEquals("$baseUrl?foo=1&api_key=myKey", uQuery)
    }

    @Test
    fun `mergeExtraBody deeply merges nested JSON objects without losing base fields`() {
        val base = buildJsonObject {
            put("model", JsonPrimitive("qwen3:8b"))
            put("options", buildJsonObject {
                put("num_ctx", JsonPrimitive(65536))
                put("temperature", JsonPrimitive(0.6))
            })
        }
        val merged = LlmHttpFactory.mergeExtraBody(
            base,
            """{"options":{"top_k":40},"keep_alive":"30m"}"""
        )
        assertEquals("qwen3:8b", merged["model"]?.jsonPrimitive?.content)
        assertEquals("30m", merged["keep_alive"]?.jsonPrimitive?.content)
        assertEquals(65536, merged["options"]?.jsonObject?.get("num_ctx")?.jsonPrimitive?.intOrNull)
        assertEquals(40, merged["options"]?.jsonObject?.get("top_k")?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun `OllamaNativeClient sends num_ctx and parses NDJSON thinking text and tool_calls`() = runBlocking {
        val capturedRequestBody = AtomicReference("")
        val capturedPath = AtomicReference("")

        ServerSocket(0).use { server ->
            val port = server.localPort
            val serverThread = thread(start = true) {
                server.accept().use { sock ->
                    val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                    val reqLine = reader.readLine().orEmpty()
                    capturedPath.set(reqLine.split(" ").getOrNull(1).orEmpty())
                    var contentLength = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                        }
                    }
                    val buf = CharArray(contentLength)
                    var readTotal = 0
                    while (readTotal < contentLength) {
                        val n = reader.read(buf, readTotal, contentLength - readTotal)
                        if (n <= 0) break
                        readTotal += n
                    }
                    capturedRequestBody.set(String(buf, 0, readTotal))

                    val ndjson = listOf(
                        """{"message":{"role":"assistant","thinking":"Checking LAN...","content":""},"done":false}""",
                        """{"message":{"role":"assistant","content":"Hello from Ollama!","tool_calls":[{"function":{"name":"env_info","arguments":{"detail":"full"}}}]},"done":false}""",
                        """{"message":{"role":"assistant","content":""},"done":true,"prompt_eval_count":42,"eval_count":15}"""
                    ).joinToString("\n") + "\n"
                    val bytes = ndjson.toByteArray(Charsets.UTF_8)
                    val out = sock.getOutputStream()
                    out.write(
                        ("HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/x-ndjson\r\n" +
                            "Content-Length: ${bytes.size}\r\n" +
                            "Connection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
                    )
                    out.write(bytes)
                    out.flush()
                }
            }

            val client = OllamaNativeClient(OkHttpClient())
            val cfg = ProviderConfig(
                providerId = ProviderType.OLLAMA.id,
                baseUrl = "http://127.0.0.1:$port",
                modelId = "qwen3:8b",
                contextWindow = 49152,
                wireProtocolOverride = WireProtocol.OLLAMA_NATIVE
            )
            val events = client.streamChat(
                config = cfg,
                messages = listOf(LlmWireMessage(role = LlmRole.USER, content = "Hi")),
                tools = listOf(LlmToolSpec("env_info", "Env info", buildJsonObject {}))
            ).toList()

            serverThread.join(3000)

            assertEquals("/api/chat", capturedPath.get())
            val sentJson = LlmHttpFactory.json.parseToJsonElement(capturedRequestBody.get()).jsonObject
            assertEquals(49152, sentJson["options"]?.jsonObject?.get("num_ctx")?.jsonPrimitive?.intOrNull)

            assertTrue(events.any { it is LlmStreamEvent.ThinkingDelta && it.text == "Checking LAN..." })
            assertTrue(events.any { it is LlmStreamEvent.TextDelta && it.text == "Hello from Ollama!" })
            assertTrue(events.any { it is LlmStreamEvent.ToolCallReady && it.toolCall.name == "env_info" })
        }
    }

    @Test
    fun `OpenAiResponsesClient streams v1 responses SSE text and function_call`() = runBlocking {
        val capturedPath = AtomicReference("")
        ServerSocket(0).use { server ->
            val port = server.localPort
            val serverThread = thread(start = true) {
                server.accept().use { sock ->
                    val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                    val reqLine = reader.readLine().orEmpty()
                    capturedPath.set(reqLine.split(" ").getOrNull(1).orEmpty())
                    var contentLength = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                        }
                    }
                    val skip = CharArray(contentLength)
                    var r = 0
                    while (r < contentLength) {
                        val n = reader.read(skip, r, contentLength - r)
                        if (n <= 0) break
                        r += n
                    }

                    val sse = buildString {
                        append("data: {\"type\":\"response.output_text.delta\",\"delta\":\"Responses OK\"}\n\n")
                        append("data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\",\"call_id\":\"call_7\",\"name\":\"git_status\",\"arguments\":\"{}\"}}\n\n")
                        append("data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}}\n\n")
                    }
                    val bytes = sse.toByteArray(Charsets.UTF_8)
                    val out = sock.getOutputStream()
                    out.write(
                        ("HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/event-stream\r\n" +
                            "Content-Length: ${bytes.size}\r\n" +
                            "Connection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
                    )
                    out.write(bytes)
                    out.flush()
                }
            }

            val client = OpenAiResponsesClient(OkHttpClient())
            val cfg = ProviderConfig(
                providerId = "custom_resp",
                baseUrl = "http://127.0.0.1:$port/v1",
                modelId = "gpt-5.4",
                isUserCreated = true,
                categoryOverride = ProviderCategory.CUSTOM,
                wireProtocolOverride = WireProtocol.OPENAI_RESPONSES,
                authMode = ProviderAuthMode.NONE
            )
            val events = client.streamChat(
                config = cfg,
                messages = listOf(LlmWireMessage(role = LlmRole.USER, content = "Ping")),
                tools = emptyList()
            ).toList()

            serverThread.join(3000)

            assertEquals("/v1/responses", capturedPath.get())
            assertTrue(events.any { it is LlmStreamEvent.TextDelta && it.text == "Responses OK" })
            assertTrue(events.any { it is LlmStreamEvent.ToolCallReady && it.toolCall.name == "git_status" })
        }
    }

    @Test
    fun `orderedProviderConfigs includes all built-in and user-created LAN and Custom providers`() {
        val customLan = ProviderConfig(
            providerId = "lan_ollama_192_168_1_50_11434",
            customDisplayName = "My Rig Ollama",
            isUserCreated = true,
            categoryOverride = ProviderCategory.LOCAL_LAN
        )
        val customExt = ProviderConfig(
            providerId = "custom_gateway_1",
            customDisplayName = "Corp Gateway",
            isUserCreated = true,
            categoryOverride = ProviderCategory.CUSTOM
        )
        val ordered = SettingsRepository.orderedProviderConfigs(
            mapOf(
                customExt.providerId to customExt,
                customLan.providerId to customLan
            )
        )
        assertTrue(ordered.size >= ProviderType.entries.size + 2)
        assertTrue(ordered.any { it.providerId == customLan.providerId })
        assertTrue(ordered.any { it.providerId == customExt.providerId })
    }
}
