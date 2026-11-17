package com.forgemind.app

import com.forgemind.app.agent.AgentEngine
import com.forgemind.app.agent.ModelFallbackSequence
import com.forgemind.app.data.db.ChatMessageEntity
import com.forgemind.app.data.model.LlmRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chat-only notices (fallback, failed auto-compaction) are shown to the user, never sent to the model. */
class ContextNoticeTests {
    @Test
    fun `context notices stay out of the model history`() {
        val msgs = listOf(
            ChatMessageEntity("u", "s", 0, LlmRole.USER.name, "Продолжи отчёт"),
            ChatMessageEntity("n", "s", 1, LlmRole.SYSTEM.name, "⚠️ Автосжатие контекста не удалось",
                toolName = ModelFallbackSequence.CONTEXT_NOTICE_TOOL_NAME),
            ChatMessageEntity("f", "s", 2, LlmRole.SYSTEM.name, "Переход на запасную модель",
                toolName = ModelFallbackSequence.EVENT_TOOL_NAME),
            ChatMessageEntity("a", "s", 3, LlmRole.ASSISTANT.name, "Готово")
        )
        val wire = AgentEngine.buildWireHistory("sys", "", msgs)
        assertEquals(listOf(LlmRole.SYSTEM, LlmRole.USER, LlmRole.ASSISTANT, LlmRole.USER), wire.map { it.role })
        assertTrue(wire.none { it.content.contains("Автосжатие") || it.content.contains("запасную") })
    }

    @Test
    fun `both notice kinds are chat events`() {
        assertTrue(ModelFallbackSequence.CONTEXT_NOTICE_TOOL_NAME in ModelFallbackSequence.UI_EVENT_TOOL_NAMES)
        assertTrue(ModelFallbackSequence.EVENT_TOOL_NAME in ModelFallbackSequence.UI_EVENT_TOOL_NAMES)
    }
}
