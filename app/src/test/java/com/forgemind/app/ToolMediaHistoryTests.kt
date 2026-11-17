package com.forgemind.app

import com.forgemind.app.agent.AgentEngine
import com.forgemind.app.data.db.ChatMessageEntity
import com.forgemind.app.data.model.*
import com.forgemind.app.llm.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64

/** Regression: tool images must reach the provider, after all corresponding tool results. */
class ToolMediaHistoryTests {
    private val shot = ChatAttachment(
        id = "shot", name = "screen.png", mimeType = "image/png",
        localPath = "/unused/screen.png", sizeBytes = 1, kind = AttachmentKind.IMAGE
    )
    private fun calls(textFallback: Boolean = false) = listOf(
        ToolCallInfo("screen", "screen_capture", "{}", viaTextFallback = textFallback),
        ToolCallInfo("browser", "browser_agent_screenshot", "{}", viaTextFallback = textFallback)
    )
    private fun history(
        attachments: List<ChatAttachment> = listOf(shot),
        toolCalls: List<ToolCallInfo> = calls(),
        savedResults: Int = toolCalls.size
    ): List<ChatMessageEntity> = listOf(
        ChatMessageEntity(id = "u", sessionId = "s", orderIndex = 0, role = "USER", content = "Inspect"),
        ChatMessageEntity(id = "a", sessionId = "s", orderIndex = 1, role = "ASSISTANT",
            toolCallsJson = LlmHttpFactory.json.encodeToString(toolCalls),
            attachmentsJson = LlmHttpFactory.json.encodeToString(attachments))
    ) + toolCalls.take(savedResults).mapIndexed { i, tc ->
        ChatMessageEntity(id = "t$i", sessionId = "s", orderIndex = i + 2, role = "TOOL",
            content = "result $i", toolCallId = tc.id, toolName = tc.name)
    }

    @Test fun `native tool batch completes before media and persisted preview is unchanged`() {
        val source = history()
        val wire = AgentEngine.buildWireHistory("sys", "", source)
        assertEquals(listOf("SYSTEM", "USER", "ASSISTANT", "TOOL", "TOOL", "USER"), wire.map { it.role.name })
        assertTrue(wire[2].attachments.isEmpty())
        assertEquals(listOf(shot), wire.last().attachments)
        assertTrue(wire.last().content.contains("данные инструментов"))
        assertEquals(LlmHttpFactory.json.encodeToString(listOf(shot)), source[1].attachmentsJson)
    }

    @Test fun `stopped batch synthesizes missing results before media and next user turn`() {
        val source = history(savedResults = 1) + ChatMessageEntity(
            id = "u2", sessionId = "s", orderIndex = 4, role = "USER", content = "Continue here"
        )
        val wire = AgentEngine.buildWireHistory("sys", "", source)
        assertEquals("browser", wire[4].toolCallId)
        assertTrue(wire[4].content.contains("Прервано"))
        assertEquals(listOf(shot), wire.last().attachments)
        assertTrue(wire.last().content.endsWith("Continue here"))
    }

    @Test fun `reflection history retains tool images in the user observation`() {
        val wire = AgentEngine.buildReflectionWireHistory("sys", "", history(), 5, 5)
        assertTrue(wire.none { it.role == LlmRole.TOOL })
        assertEquals(LlmRole.USER, wire.last().role)
        assertTrue(wire.last().content.contains("result 1"))
        assertEquals(listOf(shot), wire.last().attachments)
    }

    @Test fun `text tool calls and protocol downgrade retain images`() {
        val textWire = AgentEngine.buildWireHistory("sys", "", history(toolCalls = calls(true)))
        val nativeWire = AgentEngine.buildWireHistory("sys", "", history())
        for (wire in listOf(textWire, LlmHttpFactory.downgradeWireMessagesForTextTools(nativeWire, emptyList()))) {
            assertTrue(wire.none { it.role == LlmRole.TOOL })
            assertEquals(listOf(shot), wire.last().attachments)
            assertTrue(wire.last().content.contains("result 1"))
        }
    }

    @Test fun `mixed text and native results do not insert a user between required tool responses`() {
        val mixed = calls().let { listOf(it[0].copy(viaTextFallback = true), it[1]) }
        val wire = AgentEngine.buildWireHistory("sys", "", history(toolCalls = mixed))
        val toolIndex = wire.indexOfFirst { it.role == LlmRole.TOOL }
        val observationIndex = wire.indexOfLast { it.role == LlmRole.USER }
        assertTrue(toolIndex < observationIndex)
        assertEquals(listOf(shot), wire[observationIndex].attachments)
    }

    @Test fun `media budget counts each tool image only once and keeps older file references`() {
        val images = (1..6).map { shot.copy(id = "s$it", localPath = "/s$it.png") }
        val wire = AgentEngine.buildWireHistory("sys", "", history(images))
        val atts = wire.flatMap { it.attachments }
        assertEquals(6, atts.size)
        assertEquals(4, atts.count { it.kind == AttachmentKind.IMAGE })
        assertEquals(images.takeLast(4), atts.filter { it.kind == AttachmentKind.IMAGE })
        assertEquals(images.take(2).map { it.localPath }, atts.take(2).map { it.localPath })
    }

    @Test fun `successive batches retain their own observations and no-attachment batch adds nothing`() {
        val first = history()
        val second = history(listOf(shot.copy(id = "second"))).drop(1).mapIndexed { i, m ->
            m.copy(id = "second-${m.id}", orderIndex = first.size + i)
        }
        val wire = AgentEngine.buildWireHistory("sys", "", first + second)
        assertEquals(listOf("shot", "second"), wire.flatMap { it.attachments }.map { it.id })
        assertEquals(2, wire.count { it.attachments.isNotEmpty() && it.role == LlmRole.USER })
        val noMedia = AgentEngine.buildWireHistory("sys", "", history(emptyList()))
        assertEquals(LlmRole.TOOL, noMedia.last().role)
    }

    private fun leaves(e: JsonElement): List<String> = when (e) {
        is JsonObject -> e.values.flatMap(::leaves)
        is JsonArray -> e.flatMap(::leaves)
        is JsonPrimitive -> listOf(e.content)
    }

    // Exercise real serializers with a file; no paid API calls or model credentials.
    private fun checkPayload(build: (List<LlmWireMessage>) -> JsonObject) {
        val encoded = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aF4sAAAAASUVORK5CYII="
        val file = File.createTempFile("forgemind-media-", ".png")
        try {
            file.writeBytes(Base64.getDecoder().decode(encoded))
            val att = shot.copy(localPath = file.absolutePath, sizeBytes = file.length())
            assertEquals(encoded, LlmHttpFactory.readAttachmentAsBase64(att))
            val wire = AgentEngine.buildWireHistory("sys", "", history(listOf(att)))
            val payload = build(wire)
            assertEquals(1, leaves(payload).count { it == encoded || it == "data:image/png;base64,$encoded" })
            assertTrue(leaves(payload).contains("result 0"))
            assertTrue(leaves(payload).contains("result 1"))
        } finally { file.delete() }
    }

    private val config = ProviderConfig(providerId = "custom", modelId = "test-vision")

    @Test fun `OpenAI chat payload includes screenshot bytes`() = checkPayload {
        OpenAiCompatibleClient.buildRequestBody(config, ProviderType.OPENAI, it, emptyList(), false)
    }

    @Test fun `Responses payload includes screenshot bytes`() = checkPayload {
        OpenAiResponsesClient.buildRequestBody(config, it, emptyList(), false)
    }

    @Test fun `Ollama payload includes screenshot bytes`() = checkPayload {
        OllamaNativeClient.buildRequestBody(config, it, emptyList(), false)
    }

    private fun privatePayload(client: Any, wire: List<LlmWireMessage>): JsonObject {
        val method = client.javaClass.getDeclaredMethod("buildRequestBody", ProviderConfig::class.java, List::class.java, List::class.java)
        method.isAccessible = true
        return method.invoke(client, config, wire, emptyList<LlmToolSpec>()) as JsonObject
    }

    @Test fun `Anthropic payload includes screenshot bytes`() = checkPayload { privatePayload(AnthropicClient(), it) }
    @Test fun `Gemini payload includes screenshot bytes`() = checkPayload { privatePayload(GeminiClient(), it) }
}
