package com.forgemind.app

import com.forgemind.app.bridge.DiffUtils
import com.forgemind.app.bridge.TermuxBridge
import com.forgemind.app.bridge.WorkspaceManager
import com.forgemind.app.data.db.ChatMessageEntity
import com.forgemind.app.data.model.LlmRole
import com.forgemind.app.data.model.ProviderConfig
import com.forgemind.app.data.model.ProviderType
import com.forgemind.app.data.model.TtsProviderType
import com.forgemind.app.data.model.VoiceSettings
import com.forgemind.app.data.model.WorkspaceEnvType
import com.forgemind.app.ui.AppSection
import com.forgemind.app.llm.ContextCompactor
import com.forgemind.app.llm.LlmHttpFactory
import com.forgemind.app.llm.LlmRouter
import com.forgemind.app.llm.LlmStreamEvent
import com.forgemind.app.llm.LlmToolSpec
import com.forgemind.app.llm.LlmWireMessage
import com.forgemind.app.llm.OpenAiCompatibleClient
import com.forgemind.app.mcp.McpClientManager
import com.forgemind.app.voice.TtsManager
import com.forgemind.app.web.WebScraperEngine
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.StringReader
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForgeMindCoreTests {

    @Test
    fun `openAi endpoint normalization handles variants cleanly`() {
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            OpenAiCompatibleClient.normalizeChatEndpoint("https://openrouter.ai/api/v1")
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            OpenAiCompatibleClient.normalizeChatEndpoint("https://api.openai.com/v1/")
        )
        assertEquals(
            "http://localhost:4000/v1/chat/completions",
            OpenAiCompatibleClient.normalizeChatEndpoint("http://localhost:4000")
        )
        assertEquals(
            "https://custom.example.com/v1/chat/completions",
            OpenAiCompatibleClient.normalizeChatEndpoint("https://custom.example.com/v1/chat/completions")
        )
    }

    @Test
    fun `openAi buildRequestBody switches reasoning params for gpt-5 and o-series`() {
        val oaiReasoning = OpenAiCompatibleClient.buildRequestBody(
            config = ProviderConfig("openai", modelId = "gpt-5.4", maxTokens = 4096, temperature = 0.7f),
            provider = ProviderType.OPENAI,
            messages = listOf(LlmWireMessage(LlmRole.USER, "Привет")),
            tools = emptyList(),
            includeStreamOptions = true
        )
        assertNull(oaiReasoning["temperature"])
        assertEquals(4096, oaiReasoning["max_completion_tokens"]?.jsonPrimitive?.int)

        val openRouterStd = OpenAiCompatibleClient.buildRequestBody(
            config = ProviderConfig("openrouter", modelId = "anthropic/claude-sonnet-4", maxTokens = 2048, temperature = 0.5f),
            provider = ProviderType.OPENROUTER,
            messages = listOf(LlmWireMessage(LlmRole.USER, "Привет")),
            tools = emptyList(),
            includeStreamOptions = true
        )
        assertNotNull(openRouterStd["temperature"])
        assertEquals(2048, openRouterStd["max_tokens"]?.jsonPrimitive?.int)
    }

    @Test
    fun `openAi client rotates keys on 429 rate limit and streams delta`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"rate limit"}}"""))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Привет, \"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"ForgeMind!\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":4}}\n\n" +
                        "data: [DONE]\n\n"
                )
        )
        server.start()
        try {
            val client = OpenAiCompatibleClient(OkHttpClient())
            val cfg = ProviderConfig(
                providerId = "custom",
                apiKeysRaw = "bad_key_1\ngood_key_2",
                baseUrl = server.url("/v1").toString(),
                modelId = "test-model",
                autoRotateKeys = true
            )
            val events = client.streamChat(
                config = cfg,
                messages = listOf(LlmWireMessage(LlmRole.USER, "тест")),
                tools = emptyList()
            ).toList()

            val fullText = events.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString("") { it.text }
            assertEquals("Привет, ForgeMind!", fullText)
            assertTrue(events.any { it is LlmStreamEvent.Done })

            val req1 = server.takeRequest()
            val req2 = server.takeRequest()
            assertEquals("Bearer bad_key_1", req1.getHeader("Authorization"))
            assertEquals("Bearer good_key_2", req2.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `mcp parseJsonOrSseResponse handles raw json and streamable sse`() {
        val direct = McpClientManager.parseJsonOrSseResponse("""{"jsonrpc":"2.0","id":1,"result":{"tools":[]}}""")
        assertNotNull(direct["result"])

        val sseBody = """
            event: message
            data: {"jsonrpc":"2.0","method":"notifications/progress","params":{"progress":50}}

            event: message
            data: {"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"ok"}]}}

        """.trimIndent()
        val parsed = McpClientManager.parseJsonOrSseResponse(sseBody, "text/event-stream")
        assertEquals(2, parsed["id"]?.jsonPrimitive?.int)
        assertEquals(
            "mcp__windows_srv__take_screenshot",
            McpClientManager.buildExposedToolName("Windows Srv!", "take.screenshot")
        )
    }

    @Test
    fun `diffUtils builds readable unified diff for create modify delete`() {
        val created = DiffUtils.generateUnifiedDiff("app.py", null, "print(1)\nprint(2)")
        assertTrue(created.contains("+++ b/app.py"))
        assertTrue(created.contains("+print(1)"))

        val edited = DiffUtils.generateUnifiedDiff(
            "main.kt",
            "fun main() {\n    println(1)\n}",
            "fun main() {\n    println(2)\n}"
        )
        assertTrue(edited.contains("-    println(1)"))
        assertTrue(edited.contains("+    println(2)"))

        val deleted = DiffUtils.generateUnifiedDiff("old.txt", "line1", null)
        assertTrue(deleted.contains("+++ /dev/null"))
        assertTrue(deleted.contains("-line1"))
    }

    @Test
    fun `ttsManager builds xtts payload CF headers and sanitizes markdown`() {
        val settings = VoiceSettings(
            xttsBaseUrl = "https://tts.example.com",
            xttsVoice = "Claribel Dervla",
            xttsPitch = 3.0f,
            xttsKeepTempo = true,
            xttsCfClientId = "cf-id-123",
            xttsCfClientSecret = "cf-secret-456",
            xttsBearerToken = "tok-789"
        )
        val req = TtsManager.buildXttsRequestBuilder("https://tts.example.com/tts_stream", settings).build()
        assertEquals("cf-id-123", req.header("CF-Access-Client-Id"))
        assertEquals("cf-secret-456", req.header("CF-Access-Client-Secret"))
        assertEquals("Bearer tok-789", req.header("Authorization"))

        val emb = JsonArray(listOf(JsonPrimitive(0.1f), JsonPrimitive(-0.2f)))
        val lat = JsonArray(listOf(JsonArray(listOf(JsonPrimitive(0.3f)))))
        val payload = TtsManager.buildXttsStreamPayload(
            text = "Привет!",
            language = "ru",
            speakerEmbedding = emb,
            gptCondLatent = lat,
            pitch = 3.0f,
            keepTempo = true
        )
        assertEquals("Привет!", payload["text"]?.jsonPrimitive?.content)
        assertFalse(payload["add_wav_header"]?.jsonPrimitive?.boolean ?: true)
        assertEquals(20, payload["stream_chunk_size"]?.jsonPrimitive?.int)
        assertEquals(3.0f, payload["pitch"]?.jsonPrimitive?.float)
        assertTrue(payload["keep_tempo"]?.jsonPrimitive?.boolean ?: false)
        assertEquals(2, payload["speaker_embedding"]?.jsonArray?.size)

        val cleaned = TtsManager.sanitizeForSpeech(
            "## Заголовок\nСмотри [ссылку](https://example.com) и код:\n```kotlin\nval x = 1\n```\nГотово!",
            skipCodeBlocks = true
        )
        assertFalse(cleaned.contains("val x = 1"))
        assertFalse(cleaned.contains("https://example.com"))
        assertTrue(cleaned.contains("Заголовок"))
        assertTrue(cleaned.contains("[код приведён в сообщении]"))
    }

    @Test
    fun `contextCompactor thresholds and fallback compaction behave properly`() {
        val compactor = ContextCompactor(LlmRouter())
        assertFalse(compactor.shouldAutoCompact(estimatedTokens = 90_000, contextWindow = 100_000, thresholdPercent = 80, activeMessagesCount = 4))
        assertTrue(compactor.shouldAutoCompact(estimatedTokens = 82_000, contextWindow = 100_000, thresholdPercent = 80, activeMessagesCount = 10))

        val msgs = listOf(
            ChatMessageEntity("1", "s1", 1, "USER", "Сделай терминал"),
            ChatMessageEntity("2", "s1", 2, "ASSISTANT", "Терминал реализован в TerminalSessionManager.kt")
        )
        val summary = compactor.fallbackCompact("Старая цель", msgs)
        assertTrue(summary.contains("Старая цель"))
        assertTrue(summary.contains("TerminalSessionManager.kt"))
    }

    @Test
    fun `webScraper detects empty SPA shell and converts html to markdown`() {
        val spaHtml = """<html><body><div id="root"></div><noscript>Please enable JavaScript to run this app.</noscript></body></html>"""
        assertTrue(WebScraperEngine.looksLikeEmptySpa(spaHtml))

        val normalHtml = """<html><body><article><h1>Заголовок</h1><p>Обычный длинный текст статьи на русском языке, достаточный для обычной HTML страницы без SPA заглушки ${"абвгд ".repeat(50)}</p></article></body></html>"""
        assertFalse(WebScraperEngine.looksLikeEmptySpa(normalHtml))

        val doc = Jsoup.parse("<div><h2>Секция</h2><ul><li>Первый</li><li>Второй</li></ul></div>")
        val md = WebScraperEngine.elementToMarkdown(doc.body().child(0))
        assertTrue(md.contains("## Секция"))
        assertTrue(md.contains("- Первый"))
    }

    @Test
    fun `default ssh profile uses port 2222 and saf tree label decodes android folders`() {
        val def = TermuxBridge.defaultTermuxProfile()
        assertEquals(2222, def.port)
        assertEquals("", def.defaultWorkDir)
        assertTrue(def.isDefault)

        assertEquals(
            "Память телефона / Movies/AIApp",
            WorkspaceManager.formatSafTreeLabel("content://com.android.externalstorage.documents/tree/primary%3AMovies%2FAIApp")
        )
        assertEquals(
            "Память телефона / Download/My+App",
            WorkspaceManager.formatSafTreeLabel("content://com.android.externalstorage.documents/tree/primary%3ADownload%2FMy%2BApp/document/primary%3ADownload%2FMy%2BApp")
        )
        assertEquals("Не выбрана", WorkspaceManager.formatSafTreeLabel(null))

        // Terminal tab removed from user navigation while 4 primary screens remain
        assertEquals(listOf("CHAT", "FILES", "PREVIEW", "SETTINGS"), AppSection.entries.map { it.name })
    }

    @Test
    fun `ttsManager streams studio_speakers O1 extracts single speaker and removes Venice_selected`() {
        val sampleJson = """
            {
              "Venice_selected": {"speaker_embedding": [9.9, 8.8], "gpt_cond_latent": [[7.7]]},
              "Andrew Chipper": {"speaker_embedding": [0.5], "gpt_cond_latent": [[0.6]]},
              "Claribel Dervla": {"speaker_embedding": [0.1, 0.2], "gpt_cond_latent": [[0.3, 0.4]]}
            }
        """.trimIndent()

        val allKeys = TtsManager.extractSpeakerNamesFromStream(StringReader(sampleJson))
        assertEquals(listOf("Venice_selected", "Andrew Chipper", "Claribel Dervla"), allKeys)

        val dir = TtsManager.buildSpeakerDirectory(allKeys, preferredDefault = "Claribel Dervla")
        assertFalse(dir.voices.any { it.equals("Venice_selected", ignoreCase = true) })
        assertEquals("Claribel Dervla", dir.defaultVoice)
        assertEquals(listOf("Claribel Dervla", "Andrew Chipper"), dir.voices)
        assertNull(TtsManager.sanitizeVoiceName("Venice_selected"))
        assertEquals("", VoiceSettings().xttsVoice)

        val picked = TtsManager.extractSingleSpeakerJsonFromStream(StringReader(sampleJson), "Claribel Dervla")
        assertNotNull(picked)
        assertEquals("Claribel Dervla", picked!!.first)
        assertTrue(picked.second.contains("0.1"))

        val fallback = TtsManager.extractSingleSpeakerJsonFromStream(StringReader(sampleJson), null)
        assertNotNull(fallback)
        assertEquals("Andrew Chipper", fallback!!.first)
    }

    @Test
    fun `openAi client retries without tools on 400 tool error and ignores non-primitive reasoning`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error":{"message":"This model does not support tool calling"}}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"reasoning\":{\"text\":\"ignored\"},\"content\":\"Ответ ок\"}}]}\n\n" +
                        "data: [DONE]\n\n"
                )
        )
        server.start()
        try {
            val client = OpenAiCompatibleClient(OkHttpClient())
            val cfg = ProviderConfig(
                providerId = "venice",
                apiKeysRaw = "test_key",
                baseUrl = server.url("/v1").toString(),
                modelId = "deepseek-test"
            )
            val tool = LlmToolSpec("test_tool", "desc", JsonObject(emptyMap()))
            val events = client.streamChat(
                config = cfg,
                messages = listOf(LlmWireMessage(LlmRole.USER, "Привет")),
                tools = listOf(tool)
            ).toList()

            val fullText = events.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString("") { it.text }
            assertEquals("Ответ ок", fullText)
            assertFalse(events.any { it is LlmStreamEvent.Error })
            assertTrue(events.any { it is LlmStreamEvent.Done })

            val body1 = server.takeRequest().body.readUtf8()
            val body2 = server.takeRequest().body.readUtf8()
            assertTrue(body1.contains("\"tools\""))
            assertFalse(body2.contains("\"tools\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `doh parser extracts A and AAAA addresses and applyCustomHeaders skips underscore metadata`() {
        val dohJson = """
            {
              "Status": 0,
              "Answer": [
                {"name": "api.venice.ai.", "type": 5, "TTL": 300, "data": "alias.example.net."},
                {"name": "alias.example.net.", "type": 1, "TTL": 300, "data": "104.18.28.226"},
                {"name": "alias.example.net.", "type": 28, "TTL": 300, "data": "2606:4700::6812:1ce2"}
              ]
            }
        """.trimIndent()
        val addrs = LlmHttpFactory.parseDohAnswerIps(dohJson)
        assertEquals(2, addrs.size)
        assertEquals("104.18.28.226", addrs[0].hostAddress)
        assertTrue(addrs[1].hostAddress!!.contains("2606:4700"))

        val req = Request.Builder().url("https://example.com").apply {
            LlmHttpFactory.applyCustomHeaders(
                this,
                """{"_ssh_server":"external_ssh","Authorization":"Bearer tok123","CF-Access-Client-Id":"cid"}"""
            )
        }.build()
        assertNull(req.header("_ssh_server"))
        assertEquals("Bearer tok123", req.header("Authorization"))
        assertEquals("cid", req.header("CF-Access-Client-Id"))
    }

    @Test
    fun `dual ssh defaults external xtts mode browser label and mcp sse endpoint resolution work`() {
        val localTermux = TermuxBridge.defaultTermuxProfile()
        val extSsh = TermuxBridge.defaultExternalSshProfile()
        assertEquals(2222, localTermux.port)
        assertEquals(2222, extSsh.port)
        assertTrue(localTermux.enabled)
        assertTrue(extSsh.enabled)
        assertTrue(localTermux.id != extSsh.id)
        assertEquals("SSH внешний", extSsh.name)

        assertEquals("Браузер", AppSection.PREVIEW.label)

        val voice = VoiceSettings(
            ttsProvider = TtsProviderType.XTTS_V2,
            xttsBaseUrl = "http://192.168.1.10:8020",
            xttsVoice = "Claribel Dervla",
            xttsExternalUrl = "https://xtts.example.trycloudflare.com",
            xttsExternalVoice = "Andrew Chipper",
            xttsExternalBearerToken = "ext-tok"
        )
        assertEquals("http://192.168.1.10:8020", voice.activeXttsBaseUrl())
        assertEquals("Claribel Dervla", voice.activeXttsVoice())
        assertEquals("", voice.activeXttsBearerToken())

        val extVoice = voice.copy(ttsProvider = TtsProviderType.XTTS_EXTERNAL)
        assertEquals("https://xtts.example.trycloudflare.com", extVoice.activeXttsBaseUrl())
        assertEquals("Andrew Chipper", extVoice.activeXttsVoice())
        assertEquals("ext-tok", extVoice.activeXttsBearerToken())

        assertEquals(
            "https://mcp.example.com/messages?session_id=abc",
            McpClientManager.resolveEndpointUrl("https://mcp.example.com/sse", "/messages?session_id=abc")
        )
        assertEquals(
            "https://other.example.com/msg",
            McpClientManager.resolveEndpointUrl("https://mcp.example.com/sse", "https://other.example.com/msg")
        )
    }

    @Test
    fun `url normalization workspace label and openAi single json fallback plus tool name dedup work`() = runTest {
        assertEquals("Домашняя ИИ", WorkspaceEnvType.INTERNAL.label)
        assertEquals(
            "https://api.venice.ai/api/v1",
            LlmHttpFactory.normalizeBaseUrl("  api.venice.ai//api/v1/  ", "https://api.openai.com/v1")
        )
        assertEquals(
            "http://192.168.1.100:8020",
            LlmHttpFactory.normalizeBaseUrl("192.168.1.100:8020/", "http://127.0.0.1:8020")
        )

        val server = MockWebServer()
        // 1) Non-SSE single JSON 200 fallback
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"role":"assistant","content":"Готово без SSE"},"finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":4}}""")
        )
        // 2) Streaming tool_call where provider resends full function.name on subsequent chunks
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"list_files\",\"arguments\":\"{\"}}]}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"list_files\",\"arguments\":\"\\\"path\\\":\\\"\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n" +
                        "data: [DONE]\n\n"
                )
        )
        server.start()
        try {
            val client = OpenAiCompatibleClient(OkHttpClient())
            val cfg = ProviderConfig(
                providerId = "custom",
                apiKeysRaw = "k",
                baseUrl = server.url("/v1").toString(),
                modelId = "deepseek-chat"
            )
            val ev1 = client.streamChat(cfg, listOf(LlmWireMessage(LlmRole.USER, "тест")), emptyList()).toList()
            assertEquals("Готово без SSE", ev1.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString("") { it.text })
            assertTrue(ev1.any { it is LlmStreamEvent.Usage && it.promptTokens == 12 && it.completionTokens == 4 })

            val ev2 = client.streamChat(cfg, listOf(LlmWireMessage(LlmRole.USER, "файлы")), emptyList()).toList()
            val ready = ev2.filterIsInstance<LlmStreamEvent.ToolCallReady>().single()
            assertEquals("list_files", ready.toolCall.name)
            assertEquals("""{"path":""}""", ready.toolCall.argumentsJson)
        } finally {
            server.shutdown()
        }
    }
}
