package com.forgemind.app

import com.forgemind.app.agent.AgentEngine
import com.forgemind.app.data.db.ChatMessageEntity
import com.forgemind.app.data.model.AgentSettings
import com.forgemind.app.data.model.LlmRole
import com.forgemind.app.data.model.ToolCallInfo
import com.forgemind.app.data.prefs.SettingsRepository
import com.forgemind.app.llm.LlmHttpFactory
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentReflectionLoopTests {

    @Test
    fun `agentSettings defaults and legacy maxTurns migration work properly`() {
        val defaults = AgentSettings()
        assertEquals(100, defaults.maxTurns)
        assertEquals(25, defaults.thinkEveryToolCalls)

        val legacyJson = """{"enableTools":true,"maxTurns":25,"globalSystemPrompt":"test"}"""
        val migrated = SettingsRepository.decodeAgentSettings(legacyJson)
        assertEquals(100, migrated.maxTurns)
        assertEquals(25, migrated.thinkEveryToolCalls)
        assertEquals("test", migrated.globalSystemPrompt)

        val explicitJson = """{"enableTools":true,"maxTurns":25,"thinkEveryToolCalls":10}"""
        val kept = SettingsRepository.decodeAgentSettings(explicitJson)
        assertEquals(25, kept.maxTurns)
        assertEquals(10, kept.thinkEveryToolCalls)
    }

    @Test
    fun `forced reflection trigger handles threshold and disabled state`() {
        assertFalse(AgentEngine.shouldTriggerForcedReflection(toolCallsSinceThink = 24, thinkEveryToolCalls = 25))
        assertTrue(AgentEngine.shouldTriggerForcedReflection(toolCallsSinceThink = 25, thinkEveryToolCalls = 25))
        assertTrue(AgentEngine.shouldTriggerForcedReflection(toolCallsSinceThink = 28, thinkEveryToolCalls = 25))
        assertFalse(AgentEngine.shouldTriggerForcedReflection(toolCallsSinceThink = 100, thinkEveryToolCalls = 0))
    }

    @Test
    fun `stripTaskDoneMarker detects and strips completion marker cleanly`() {
        val (plain, done1) = AgentEngine.stripTaskDoneMarker("Продолжаю анализ кода.")
        assertEquals("Продолжаю анализ кода.", plain)
        assertFalse(done1)

        val (cleaned, done2) = AgentEngine.stripTaskDoneMarker("Задача полностью выполнена.\n[[TASK_DONE]]")
        assertEquals("Задача полностью выполнена.", cleaned)
        assertTrue(done2)
    }

    @Test
    fun `buildReflectionWireHistory flattens tool messages and injects reflection directive`() {
        val calls = listOf(
            ToolCallInfo(id = "c1", name = "run_command", argumentsJson = """{"command":"ls"}"""),
            ToolCallInfo(id = "c2", name = "read_file", argumentsJson = """{"path":"a.txt"}""")
        )
        val history = listOf(
            ChatMessageEntity("1", "s1", 1, LlmRole.USER.name, "Проверь проект"),
            ChatMessageEntity(
                id = "2",
                sessionId = "s1",
                orderIndex = 2,
                role = LlmRole.ASSISTANT.name,
                content = "Проверяю",
                toolCallsJson = LlmHttpFactory.json.encodeToString(calls)
            ),
            ChatMessageEntity("3", "s1", 3, LlmRole.TOOL.name, "file1\nfile2", toolCallId = "c1", toolName = "run_command"),
            ChatMessageEntity("4", "s1", 4, LlmRole.TOOL.name, "содержимое", toolCallId = "c2", toolName = "read_file")
        )

        val wire = AgentEngine.buildReflectionWireHistory(
            systemPrompt = "SYS",
            compactedSummary = "",
            messages = history,
            callsInStreak = 25,
            threshold = 25
        )

        assertEquals(LlmRole.SYSTEM, wire[0].role)
        assertTrue(wire[0].content.contains("ПРИНУДИТЕЛЬНАЯ ПАУЗА НА ОСМЫСЛЕНИЕ"))
        assertTrue(wire[0].content.contains("25"))

        // Tool roles and toolCalls must all be flattened to standard text turns for 100% API compatibility
        assertTrue(wire.none { it.role == LlmRole.TOOL })
        assertTrue(wire.none { it.toolCalls.isNotEmpty() })
        assertTrue(wire.all { it.toolCallId == null })

        // Consecutive TOOL outputs are merged cleanly into one USER message
        assertEquals(4, wire.size)
        assertEquals(LlmRole.USER, wire[1].role)
        assertEquals(LlmRole.ASSISTANT, wire[2].role)
        assertTrue(wire[2].content.contains("run_command"))
        assertEquals(LlmRole.USER, wire[3].role)
        assertTrue(wire[3].content.contains("[Результат run_command]"))
        assertTrue(wire[3].content.contains("[Результат read_file]"))
        assertNull(wire[3].toolCallId)
    }
}
