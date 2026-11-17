package com.forgemind.app

import com.forgemind.app.agent.AgentEngine
import com.forgemind.app.agent.SystemPromptComposer
import com.forgemind.app.data.model.LlmRole
import com.forgemind.app.data.model.ProviderConfig
import com.forgemind.app.data.model.ProviderType
import com.forgemind.app.llm.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SystemPromptComposerTests {
    private val markers = listOf("APP_BASE_MARKER", "MODEL_SETTING_MARKER", "GLOBAL_SETTING_MARKER", "CHAT_SETTING_MARKER")
    private val config = ProviderConfig(providerId = "custom", modelId = "test-model")
    private fun system(global: String = markers[2], chat: String = markers[3]) =
        SystemPromptComposer.compose(markers[0], markers[1], global, chat, "ENV_REFERENCE_MARKER")
    private fun wire() = listOf(LlmWireMessage(LlmRole.SYSTEM, system()), LlmWireMessage(LlmRole.USER, "HELLO_MARKER"))
    private fun strings(element: JsonElement): List<String> = when (element) {
        is JsonObject -> element.values.flatMap(::strings)
        is JsonArray -> element.flatMap(::strings)
        is JsonPrimitive -> listOf(element.content)
    }
    private fun assertInstructions(text: String) {
        markers.forEach { marker -> assertEquals("Lost or duplicated instruction section $marker", 1, text.split(marker).size - 1) }
    }

    @Test fun customInstructionsPreserveTheApplicationBaseAndTheirOrder() {
        val value = system()
        assertInstructions(value)
        assertEquals(markers, markers.sortedBy { value.indexOf(it) })
        assertTrue(value.contains("with equal authority"))
    }

    @Test fun blankCustomInstructionsLeaveTheBaseIntact() {
        assertEquals("APP_BASE_MARKER", SystemPromptComposer.compose(" APP_BASE_MARKER ", "", "\n", "  "))
    }

    @Test fun editedAndClearedInstructionsDoNotLeaveOldTextBehind() {
        assertTrue(system(global = "NEW_GLOBAL").contains("NEW_GLOBAL"))
        assertFalse(system(global = "NEW_GLOBAL").contains(markers[2]))
        val cleared = system(global = "", chat = "")
        assertTrue(cleared.contains(markers[0]) && cleared.contains(markers[1]))
        assertFalse(cleared.contains(markers[2]) || cleared.contains(markers[3]))
    }

    @Test fun ordinaryHistoryKeepsEveryInstructionInSystemRole() {
        val history = AgentEngine.buildWireHistory(system(), "SAVED_MEMORY", emptyList())
        val systems = history.filter { it.role == LlmRole.SYSTEM }
        assertEquals(1, systems.size)
        assertInstructions(systems.single().content)
        assertTrue(systems.single().content.contains("SAVED_MEMORY"))
    }

    @Test fun textToolModePreservesTheSameSystemInstructions() {
        val downgraded = LlmHttpFactory.downgradeWireMessagesForTextTools(wire(), emptyList())
        assertInstructions(downgraded.single { it.role == LlmRole.SYSTEM }.content)
        assertFalse(downgraded.filter { it.role != LlmRole.SYSTEM }.any { it.content.contains(markers[2]) })
    }

    @Test fun openAiChatSendsEditableInstructionsAsSystemContent() {
        val body = OpenAiCompatibleClient.buildRequestBody(config, ProviderType.OPENAI, wire(), emptyList(), false)
        val messages = body.getValue("messages").jsonArray
        val system = messages.map { it.jsonObject }.single { it["role"]?.jsonPrimitive?.content == "system" }
        assertInstructions(system.getValue("content").jsonPrimitive.content)
    }

    @Test fun responsesSendsEditableInstructionsInInstructionsField() {
        val body = OpenAiResponsesClient.buildRequestBody(config, wire(), emptyList(), false)
        assertInstructions(body.getValue("instructions").jsonPrimitive.content)
        assertFalse(strings(body.getValue("input")).any { it.contains(markers[2]) })
    }

    @Test fun ollamaSendsEditableInstructionsAsSystemContent() {
        val body = OllamaNativeClient.buildRequestBody(config, wire(), emptyList(), false)
        val system = body.getValue("messages").jsonArray.map { it.jsonObject }
            .single { it["role"]?.jsonPrimitive?.content == "system" }
        assertInstructions(system.getValue("content").jsonPrimitive.content)
    }

    private fun privatePayload(client: Any): JsonObject {
        val method = client.javaClass.getDeclaredMethod("buildRequestBody", ProviderConfig::class.java, List::class.java, List::class.java)
        method.isAccessible = true
        return method.invoke(client, config, wire(), emptyList<LlmToolSpec>()) as JsonObject
    }

    @Test fun anthropicSendsEditableInstructionsInSystemField() {
        val body = privatePayload(AnthropicClient())
        assertInstructions(strings(body.getValue("system")).joinToString("\n"))
        assertFalse(strings(body.getValue("messages")).any { it.contains(markers[2]) })
    }

    @Test fun geminiSendsEditableInstructionsInSystemInstructionField() {
        val body = privatePayload(GeminiClient())
        assertInstructions(strings(body.getValue("systemInstruction")).joinToString("\n"))
        assertFalse(strings(body.getValue("contents")).any { it.contains(markers[2]) })
    }
}
