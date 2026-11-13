package com.forgemind.app

import com.forgemind.app.bridge.DiffUtils
import com.forgemind.app.bridge.EmbeddedAiShell
import com.forgemind.app.bridge.InAppDocumentParser
import com.forgemind.app.bridge.TerminalViewMode
import com.forgemind.app.bridge.TermuxBridge
import com.forgemind.app.bridge.WorkspaceManager
import com.forgemind.app.data.db.ChatMessageEntity
import com.forgemind.app.data.model.LlmRole
import com.forgemind.app.data.model.ProviderConfig
import com.forgemind.app.data.model.ProviderType
import com.forgemind.app.data.model.TtsProviderType
import com.forgemind.app.data.model.VoiceSettings
import com.forgemind.app.data.model.WorkspaceEnvType
import com.forgemind.app.floating.FloatingChatService
import com.forgemind.app.i18n.AppLanguage
import com.forgemind.app.llm.ContextCompactor
import com.forgemind.app.llm.LlmHttpFactory
import com.forgemind.app.llm.LlmRouter
import com.forgemind.app.llm.LlmStreamEvent
import com.forgemind.app.llm.LlmToolSpec
import com.forgemind.app.llm.LlmWireMessage
import com.forgemind.app.llm.ModelScanner
import com.forgemind.app.llm.OpenAiCompatibleClient
import com.forgemind.app.mcp.McpClientManager
import com.forgemind.app.ui.AppSection
import com.forgemind.app.voice.TtsManager
import com.forgemind.app.web.AgentBrowserController
import com.forgemind.app.web.WebScraperEngine
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
            messages = listOf(LlmWireMessage(LlmRole.USER, "Hello")),
            tools = emptyList(),
            includeStreamOptions = true
        )
        assertNull(oaiReasoning["temperature"])
        assertEquals(4096, oaiReasoning["max_completion_tokens"]?.jsonPrimitive?.int)

        val openRouterStd = OpenAiCompatibleClient.buildRequestBody(
            config = ProviderConfig("openrouter", modelId = "anthropic/claude-sonnet-4", maxTokens = 2048, temperature = 0.5f),
            provider = ProviderType.OPENROUTER,
            messages = listOf(LlmWireMessage(LlmRole.USER, "Hello")),
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
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Hello, \"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"world!\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":4}}\n\n" +
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
                messages = listOf(LlmWireMessage(LlmRole.USER, "test")),
                tools = emptyList()
            ).toList()

            val fullText = events.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString("") { it.text }
            assertEquals("Hello, world!", fullText)
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
            text = "Hello!",
            language = "en",
            speakerEmbedding = emb,
            gptCondLatent = lat,
            pitch = 3.0f,
            keepTempo = true
        )
        assertEquals("Hello!", payload["text"]?.jsonPrimitive?.content)
        assertFalse(payload["add_wav_header"]?.jsonPrimitive?.boolean ?: true)
        assertEquals(20, payload["stream_chunk_size"]?.jsonPrimitive?.int)
        assertEquals(3.0f, payload["pitch"]?.jsonPrimitive?.float)
        assertTrue(payload["keep_tempo"]?.jsonPrimitive?.boolean ?: false)
        assertEquals(2, payload["speaker_embedding"]?.jsonArray?.size)

        val cleaned = TtsManager.sanitizeForSpeech(
            "## Heading\nSee [link](https://example.com) and code:\n```kotlin\nval x = 1\n```\nDone!",
            skipCodeBlocks = true
        )
        assertFalse(cleaned.contains("val x = 1"))
        assertFalse(cleaned.contains("https://example.com"))
        assertTrue(cleaned.contains("Heading"))
        assertTrue(cleaned.contains("[code block in message]"))
    }

    @Test
    fun `contextCompactor thresholds and fallback compaction behave properly`() {
        val compactor = ContextCompactor(LlmRouter())
        assertFalse(compactor.shouldAutoCompact(estimatedTokens = 90_000, contextWindow = 100_000, thresholdPercent = 80, activeMessagesCount = 4))
        assertTrue(compactor.shouldAutoCompact(estimatedTokens = 82_000, contextWindow = 100_000, thresholdPercent = 80, activeMessagesCount = 10))

        val msgs = listOf(
            ChatMessageEntity("1", "s1", 1, "USER", "Build terminal"),
            ChatMessageEntity("2", "s1", 2, "ASSISTANT", "Terminal implemented in TerminalSessionManager.kt")
        )
        val summary = compactor.fallbackCompact("Previous goal", msgs)
        assertTrue(summary.contains("Previous goal"))
        assertTrue(summary.contains("TerminalSessionManager.kt"))
    }

    @Test
    fun `webScraper detects empty SPA shell and converts html to markdown`() {
        val spaHtml = """<html><body><div id="root"></div><noscript>Please enable JavaScript to run this app.</noscript></body></html>"""
        assertTrue(WebScraperEngine.looksLikeEmptySpa(spaHtml))

        val normalHtml = """<html><body><article><h1>Heading</h1><p>Regular long article body text in English sufficient for a standard HTML page without an SPA shell ${"abcde ".repeat(50)}</p></article></body></html>"""
        assertFalse(WebScraperEngine.looksLikeEmptySpa(normalHtml))

        val doc = Jsoup.parse("<div><h2>Section</h2><ul><li>First</li><li>Second</li></ul></div>")
        val md = WebScraperEngine.elementToMarkdown(doc.body().child(0))
        assertTrue(md.contains("## Section"))
        assertTrue(md.contains("- First"))
    }

    @Test
    fun `default ssh profile uses port 2222 and saf tree label decodes android folders`() {
        val def = TermuxBridge.defaultTermuxProfile()
        assertEquals(2222, def.port)
        assertEquals("", def.defaultWorkDir)
        assertTrue(def.isDefault)

        assertEquals(
            "Phone Storage / Documents/Workspace",
            WorkspaceManager.formatSafTreeLabel("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FWorkspace")
        )
        assertEquals(
            "Phone Storage / Download/My+App",
            WorkspaceManager.formatSafTreeLabel("content://com.android.externalstorage.documents/tree/primary%3ADownload%2FMy%2BApp/document/primary%3ADownload%2FMy%2BApp")
        )
        assertEquals(
            "/storage/emulated/0/Documents/My Project",
            WorkspaceManager.safUriToDisplayPath("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FMy%20Project")
        )
        assertEquals("Not selected", WorkspaceManager.formatSafTreeLabel(null))

        assertEquals(listOf("CHAT", "FILES", "TERMINAL", "PREVIEW", "SETTINGS"), AppSection.entries.map { it.name })
    }

    @Test
    fun `ttsManager streams studio_speakers O1 extracts single speaker and skips reserved meta key`() {
        val sampleJson = """
            {
              "__default__": {"speaker_embedding": [9.9, 8.8], "gpt_cond_latent": [[7.7]]},
              "Andrew Chipper": {"speaker_embedding": [0.5], "gpt_cond_latent": [[0.6]]},
              "Claribel Dervla": {"speaker_embedding": [0.1, 0.2], "gpt_cond_latent": [[0.3, 0.4]]}
            }
        """.trimIndent()

        val allKeys = TtsManager.extractSpeakerNamesFromStream(StringReader(sampleJson))
        assertEquals(listOf("__default__", "Andrew Chipper", "Claribel Dervla"), allKeys)

        val dir = TtsManager.buildSpeakerDirectory(allKeys, preferredDefault = "Claribel Dervla")
        assertFalse(dir.voices.any { it.equals("__default__", ignoreCase = true) })
        assertEquals("Claribel Dervla", dir.defaultVoice)
        assertEquals(listOf("Claribel Dervla", "Andrew Chipper"), dir.voices)
        assertNull(TtsManager.sanitizeVoiceName("__default__"))
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
    fun `agentBrowserController withSerializedAgentOp prevents overlapping evalJs and screenshot`() = runTest {
        val mutex = Mutex()
        val trace = mutableListOf<String>()
        val jobEval = async {
            AgentBrowserController.withSerializedAgentOp(mutex) {
                trace += "eval_start"
                delay(80)
                trace += "eval_end"
            }
        }
        val jobShot = async {
            delay(10)
            AgentBrowserController.withSerializedAgentOp(mutex) {
                trace += "shot_start"
                delay(20)
                trace += "shot_end"
            }
        }
        jobEval.await()
        jobShot.await()
        assertEquals(listOf("eval_start", "eval_end", "shot_start", "shot_end"), trace)
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
                    "data: {\"choices\":[{\"delta\":{\"reasoning\":{\"text\":\"ignored\"},\"content\":\"Reply ok\"}}]}\n\n" +
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
                messages = listOf(LlmWireMessage(LlmRole.USER, "Hello")),
                tools = listOf(tool)
            ).toList()

            val fullText = events.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString("") { it.text }
            assertEquals("Reply ok", fullText)
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
        assertEquals("External SSH", extSsh.name)

        assertEquals("Browser", AppSection.PREVIEW.label)

        val voice = VoiceSettings(
            ttsProvider = TtsProviderType.XTTS_V2,
            xttsBaseUrl = "http://192.168.1.100:8020",
            xttsVoice = "Claribel Dervla",
            xttsExternalUrl = "https://xtts.example.org",
            xttsExternalVoice = "Andrew Chipper",
            xttsExternalBearerToken = "ext-tok"
        )
        assertEquals("http://192.168.1.100:8020", voice.activeXttsBaseUrl())
        assertEquals("Claribel Dervla", voice.activeXttsVoice())
        assertEquals("", voice.activeXttsBearerToken())

        val extVoice = voice.copy(ttsProvider = TtsProviderType.XTTS_EXTERNAL)
        assertEquals("https://xtts.example.org", extVoice.activeXttsBaseUrl())
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
        assertEquals("AI Home", WorkspaceEnvType.INTERNAL.label)
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
                .setBody("""{"choices":[{"message":{"role":"assistant","content":"Done without SSE"},"finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":4}}""")
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
            val ev1 = client.streamChat(cfg, listOf(LlmWireMessage(LlmRole.USER, "test")), emptyList()).toList()
            assertEquals("Done without SSE", ev1.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString("") { it.text })
            assertTrue(ev1.any { it is LlmStreamEvent.Usage && it.promptTokens == 12 && it.completionTokens == 4 })

            val ev2 = client.streamChat(cfg, listOf(LlmWireMessage(LlmRole.USER, "files")), emptyList()).toList()
            val ready = ev2.filterIsInstance<LlmStreamEvent.ToolCallReady>().single()
            assertEquals("list_files", ready.toolCall.name)
            assertEquals("""{"path":""}""", ready.toolCall.argumentsJson)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `floatingChatService calculateBottomLeftResize anchors top right edge and clamps bounds`() {
        val expanded = FloatingChatService.calculateBottomLeftResize(
            startW = 300,
            startH = 400,
            startCenterX = 100,
            startCenterY = -50,
            dx = -60,
            dy = 80,
            minW = 150,
            maxW = 600,
            minH = 180,
            maxH = 700
        )
        assertEquals(360, expanded.width)
        assertEquals(480, expanded.height)
        assertEquals(70, expanded.centerX)
        assertEquals(-10, expanded.centerY)
        assertEquals(250, expanded.centerX + expanded.width / 2)
        assertEquals(-250, expanded.centerY - expanded.height / 2)

        val clamped = FloatingChatService.calculateBottomLeftResize(
            startW = 300,
            startH = 400,
            startCenterX = 100,
            startCenterY = -50,
            dx = 500,
            dy = -500,
            minW = 150,
            maxW = 600,
            minH = 180,
            maxH = 700
        )
        assertEquals(150, clamped.width)
        assertEquals(180, clamped.height)
        assertEquals(175, clamped.centerX)
        assertEquals(-160, clamped.centerY)
        assertEquals(250, clamped.centerX + clamped.width / 2)
        assertEquals(-250, clamped.centerY - clamped.height / 2)
    }

    @Test
    fun `modelScanner builds venice type=all query url and parses full model_spec catalog`() {
        assertEquals(
            "https://api.venice.ai/api/v1/models?type=all",
            ModelScanner.buildOpenAiModelsQueryUrl("https://api.venice.ai/api/v1/models", ProviderType.VENICE)
        )
        assertEquals(
            "https://api.venice.ai/api/v1/models?type=text",
            ModelScanner.buildOpenAiModelsQueryUrl("https://api.venice.ai/api/v1/models?type=text", ProviderType.VENICE)
        )
        assertEquals(
            "https://api.openai.com/v1/models",
            ModelScanner.buildOpenAiModelsQueryUrl("https://api.openai.com/v1/models", ProviderType.OPENAI)
        )

        val venicePayload = """
            {
              "data": [
                {
                  "id": "flux-dev",
                  "type": "image",
                  "model_spec": { "name": "FLUX Dev", "betaModel": true }
                },
                {
                  "id": "venice-uncensored",
                  "type": "text",
                  "model_spec": { "name": "Venice Uncensored 1.1", "availableContextTokens": 32768 }
                },
                {
                  "id": "qwen3-coder:free",
                  "type": "code",
                  "context_length": 131072
                },
                {
                  "id": "kokoro-tts",
                  "type": "tts",
                  "model_spec": { "name": "Kokoro TTS", "offline": false }
                }
              ]
            }
        """.trimIndent()
        val parsed = ModelScanner.parseOpenAiModelsJson(venicePayload, ProviderType.VENICE, "venice")
        assertEquals(4, parsed.size)
        assertEquals("qwen3-coder:free", parsed.first().id)
        assertTrue(parsed.first().pricingHint?.contains("FREE") == true)
        val uncensored = parsed.first { it.id == "venice-uncensored" }
        assertEquals("Venice Uncensored 1.1", uncensored.name)
        assertEquals(32768, uncensored.contextLength)
        val imageModel = parsed.first { it.id == "flux-dev" }
        assertTrue(imageModel.pricingHint?.contains("image") == true)
        assertTrue(imageModel.pricingHint?.contains("beta") == true)
    }

    @Test
    fun `appLanguage resolves supported codes and falls back cleanly`() {
        assertEquals(14, AppLanguage.entries.size)
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromCode("en"))
        assertEquals(AppLanguage.RUSSIAN, AppLanguage.fromCode("RU"))
        assertEquals(AppLanguage.CHINESE, AppLanguage.fromCode("zh-cn"))
        assertEquals(AppLanguage.TURKISH, AppLanguage.fromCode("tr"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromCode(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromCode("unknown-lang"))
    }

    @Test
    fun `inAppDocumentParser extracts docx xlsx pptx rtf and zip entries`() {
        assertTrue(InAppDocumentParser.isImageExtension("photo.HEIC"))
        assertTrue(InAppDocumentParser.isPdfExtension("report.PDF"))
        assertTrue(InAppDocumentParser.isOfficeDocumentExtension("sheet.xlsx"))
        assertTrue(InAppDocumentParser.isMarkdownExtension("README.md"))
        assertTrue(InAppDocumentParser.isArchiveExtension("bundle.apk"))

        val docxBytes = zipEntriesBytes(
            "word/document.xml" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <w:document><w:body>
                    <w:p><w:r><w:t>First Paragraph</w:t></w:r></w:p>
                    <w:p><w:r><w:t>Second</w:t></w:r><w:r><w:tab/></w:r><w:r><w:t>Column</w:t></w:r></w:p>
                </w:body></w:document>
            """.trimIndent()
        )
        val docxParsed = InAppDocumentParser.parseOfficeOrStructuredDocument("notes.docx", docxBytes)
        assertTrue(docxParsed.text.contains("First Paragraph"))
        assertTrue(docxParsed.text.contains("Second\tColumn"))

        val xlsxBytes = zipEntriesBytes(
            "xl/sharedStrings.xml" to """<?xml version="1.0"?><sst><si><t>Model</t></si><si><t>Status</t></si></sst>""",
            "xl/worksheets/sheet1.xml" to """
                <?xml version="1.0"?>
                <worksheet><sheetData>
                    <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
                    <row r="2"><c r="A2"><v>42</v></c><c r="B2" t="inlineStr"><is><t>Ready</t></is></c></row>
                </sheetData></worksheet>
            """.trimIndent()
        )
        val xlsxParsed = InAppDocumentParser.parseOfficeOrStructuredDocument("table.xlsx", xlsxBytes)
        assertTrue(xlsxParsed.text.contains("Model | Status"))
        assertTrue(xlsxParsed.text.contains("42 | Ready"))

        val pptxBytes = zipEntriesBytes(
            "ppt/slides/slide1.xml" to """<?xml version="1.0"?><p:sld><p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>Slide Title</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>"""
        )
        val pptxParsed = InAppDocumentParser.parseOfficeOrStructuredDocument("deck.pptx", pptxBytes)
        assertTrue(pptxParsed.text.contains("--- Slide 1 ---"))
        assertTrue(pptxParsed.text.contains("Slide Title"))

        val rtfParsed = InAppDocumentParser.parseOfficeOrStructuredDocument(
            "memo.rtf",
            """{\rtf1\ansi {\fonttbl{\f0 Arial;}}Hello \b ForgeMind\b0 \par Next line}""".toByteArray()
        )
        assertTrue(rtfParsed.text.contains("Hello"))
        assertTrue(rtfParsed.text.contains("Next line"))

        val zipList = InAppDocumentParser.listArchiveEntries(xlsxBytes)
        assertEquals(2, zipList.entryCount)
        assertTrue(zipList.text.contains("xl/sharedStrings.xml"))
    }

    @Test
    fun `embeddedAiShell records external ssh feed and quotes shell arguments`() {
        val tmpRoot = Files.createTempDirectory("forgemind_shell_test").toFile()
        try {
            val shell = EmbeddedAiShell({ tmpRoot })
            assertEquals(listOf(TerminalViewMode.WATCH_AGENT, TerminalViewMode.MANUAL), TerminalViewMode.entries)
            shell.recordExternalExecution(
                sourceLabel = "Termux SSH",
                workDir = "/data/data/com.termux/files/home",
                command = "echo ok",
                output = "ok",
                exitCode = 0
            )
            assertTrue(shell.state.value.outputLog.contains("[Termux SSH]"))
            assertTrue(shell.state.value.outputLog.contains("$ echo ok"))
            assertTrue(shell.state.value.outputLog.contains("[exit 0]"))
            assertEquals("'/tmp/a'\"'\"'b'", EmbeddedAiShell.shellQuote("/tmp/a'b"))
            shell.clearLog()
            assertEquals("", shell.state.value.outputLog)
        } finally {
            tmpRoot.deleteRecursively()
        }
    }

    private fun zipEntriesBytes(vararg entries: Pair<String, String>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }
}
