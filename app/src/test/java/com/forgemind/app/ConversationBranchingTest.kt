package com.forgemind.app

import com.forgemind.app.data.db.ChatMessageEntity
import com.forgemind.app.data.model.ConversationBranching
import com.forgemind.app.data.model.LlmRole
import com.forgemind.app.data.model.ProviderConfig
import com.forgemind.app.data.model.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationBranchingTest {

    private fun msg(
        id: String,
        order: Int,
        role: LlmRole,
        content: String = id,
        parentId: String? = null
    ) = ChatMessageEntity(
        id = id,
        sessionId = "s1",
        orderIndex = order,
        role = role.name,
        content = content,
        parentMessageId = parentId,
        createdAt = 1_000L + order
    )

    @Test
    fun legacyLinearMessagesResolveCleanlyWithoutFalseBranches() {
        val list = listOf(
            msg("u1", 1, LlmRole.USER, "Hello"),
            msg("a1", 2, LlmRole.ASSISTANT, "Hi!"),
            msg("u2", 3, LlmRole.USER, "How are you?"),
            msg("a2", 4, LlmRole.ASSISTANT, "Ready to work.")
        )

        val resolved = ConversationBranching.resolve(list, "{}")
        assertEquals(listOf("u1", "a1", "u2", "a2"), resolved.activeMessages.map { it.id })
        assertTrue(resolved.switchInfoByMessageId.isEmpty())
    }

    @Test
    fun editingUserMessagePreservesOriginalBranchAndSwitchesNonDestructively() {
        val list = listOf(
            msg("u1", 1, LlmRole.USER, "Original question", parentId = null),
            msg("a1", 2, LlmRole.ASSISTANT, "Original answer", parentId = null),
            // User edits u1 -> creates u1_v2 with same parent (__root__) and new reply a1_v2
            msg("u1_v2", 3, LlmRole.USER, "Edited question", parentId = ConversationBranching.ROOT_KEY),
            msg("a1_v2", 4, LlmRole.ASSISTANT, "Edited answer", parentId = "u1_v2")
        )

        // Default selection picks latest sibling (2/2)
        val latest = ConversationBranching.resolve(list, "{}")
        assertEquals(listOf("u1_v2", "a1_v2"), latest.activeMessages.map { it.id })
        val swLatest = latest.switchInfoByMessageId["u1_v2"]!!
        assertEquals(1, swLatest.currentIndex)
        assertEquals(2, swLatest.totalCount)
        assertEquals("u1", swLatest.prevChildId)
        assertNull(swLatest.nextChildId)

        // Switch back to branch 1/2 -> restores original u1 + a1 intact!
        val selBranch1 = ConversationBranching.withSelection("{}", ConversationBranching.ROOT_KEY, "u1")
        val branch1 = ConversationBranching.resolve(list, selBranch1)
        assertEquals(listOf("u1", "a1"), branch1.activeMessages.map { it.id })
        val swFirst = branch1.switchInfoByMessageId["u1"]!!
        assertEquals(0, swFirst.currentIndex)
        assertEquals(2, swFirst.totalCount)
        assertNull(swFirst.prevChildId)
        assertEquals("u1_v2", swFirst.nextChildId)
    }

    @Test
    fun multiStepToolTurnPropagatesBranchSwitchToFinalAssistantBubble() {
        val list = listOf(
            msg("u1", 1, LlmRole.USER, "Run ls", parentId = ConversationBranching.ROOT_KEY),
            // Branch 1: tool call -> tool result -> final answer
            msg("a1_call", 2, LlmRole.ASSISTANT, "", parentId = "u1"),
            msg("t1_res", 3, LlmRole.TOOL, "file1.txt", parentId = "a1_call"),
            msg("a1_final", 4, LlmRole.ASSISTANT, "Found file1.txt", parentId = "t1_res"),
            // Branch 2: regenerated from a1_final -> forks from u1!
            msg("a2_call", 5, LlmRole.ASSISTANT, "", parentId = "u1"),
            msg("t2_res", 6, LlmRole.TOOL, "file1.txt\nfile2.txt", parentId = "a2_call"),
            msg("a2_final", 7, LlmRole.ASSISTANT, "Found 2 files", parentId = "t2_res")
        )

        val resolved = ConversationBranching.resolve(list, "{}")
        assertEquals(listOf("u1", "a2_call", "t2_res", "a2_final"), resolved.activeMessages.map { it.id })

        // Switch pill must be attached to a2_final (the visible final answer bubble)
        val sw = resolved.switchInfoByMessageId["a2_final"]!!
        assertEquals("u1", sw.parentKey)
        assertEquals(1, sw.currentIndex)
        assertEquals(2, sw.totalCount)
        assertEquals("a1_call", sw.prevChildId)

        // Retry parent lookup on final bubble must point back to u1 and keep [u1] as context
        val (retryParent, contextBeforeTurn) = ConversationBranching.findRetryParentKey(resolved, list.last())
        assertEquals("u1", retryParent)
        assertEquals(listOf("u1"), contextBeforeTurn.map { it.id })
    }

    @Test
    fun reasoningEffortRoundTripsCleanlyInProviderConfig() {
        val base = ProviderConfig(providerId = "openrouter")
        assertEquals(ReasoningEffort.MEDIUM, base.reasoningEffort())

        val high = base.withReasoningEffort(ReasoningEffort.HIGH)
        assertEquals(ReasoningEffort.HIGH, high.reasoningEffort())
        assertEquals(true, high.enableThinking)

        val offAgain = high.withReasoningEffort(ReasoningEffort.OFF)
        assertEquals(ReasoningEffort.OFF, offAgain.reasoningEffort())
        assertEquals(false, offAgain.enableThinking)
    }
}
