package com.forgemind.app

import com.forgemind.app.agent.ContextHistoryPolicy
import com.forgemind.app.data.db.ChatMessageEntity
import com.forgemind.app.data.model.ProviderConfig
import com.forgemind.app.llm.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ContextControlsTests {
    private fun msg(index: Int, role: String, content: String = "message $index") = ChatMessageEntity("id$index", "test", index, role, content)
    @Test fun limitRetainsCurrentUserTurnAndToolBatch() {
        val messages = listOf(msg(0,"USER"),msg(1,"ASSISTANT"),msg(2,"USER"),msg(3,"ASSISTANT"),msg(4,"TOOL"),msg(5,"TOOL"))
        assertEquals(messages.drop(2), ContextHistoryPolicy.select(messages, 1, false, 8).messages)
        assertEquals(2, ContextHistoryPolicy.select(messages, 1, false, 8).omittedMessages)
        assertEquals(messages, ContextHistoryPolicy.select(messages, 0, false, 8).messages)
    }
    @Test fun foldedOutputsKeepOriginalAndRecentResult() {
        val messages = listOf(msg(0,"USER"), msg(1,"TOOL","a".repeat(9000)),msg(2,"TOOL","b".repeat(9000)))
        val result = ContextHistoryPolicy.select(messages,0,true,1)
        assertEquals(1,result.foldedToolResults)
        assertTrue(result.messages[1].content.contains("message_id=\"id1\""))
        assertEquals(9000,messages[1].content.length)
        assertEquals(messages[2],result.messages[2])
        assertEquals(9,ContextHistoryPolicy.effectiveLimit(-1,9)); assertEquals(0,ContextHistoryPolicy.effectiveLimit(0,9))
    }
    @Test fun compactionReadsEveryFragmentIncludingLongMessageTail() = runBlocking {
        val inputs = mutableListOf<String>()
        val compactor = ContextCompactor(LlmRouter()) { _, system, input ->
            assertTrue(system.contains("KEEP_SPECIAL")); inputs += input; Result.success("small memory")
        }
        val content = "A".repeat(150000) + "FINAL_TAIL_MARKER"
        val result = compactor.compactMessages(ProviderConfig("custom_test",contextWindow=8192),"old",listOf(msg(0,"USER",content)),"KEEP_SPECIAL")
        assertTrue(inputs.size > 2)
        val transcript = inputs.joinToString("") { it.substringAfter("=== NEXT TRANSCRIPT FRAGMENT ===\n") }
        assertTrue(transcript.contains(content)); assertTrue(result.usedLlm)
        assertEquals(1,result.compactedUpToOrder)
    }
    @Test fun failedLaterFragmentDoesNotYieldPartialMemory() = runBlocking {
        var calls=0
        val compactor=ContextCompactor(LlmRouter()) { _,_,_ -> if (++calls==1) Result.success("first memory") else Result.failure(IllegalStateException("offline")) }
        val result=runCatching { compactor.compactMessages(ProviderConfig("custom_test",contextWindow=8192),"",listOf(msg(0,"USER","X".repeat(40000)))) }
        assertTrue(result.isFailure); assertEquals(2,calls)
    }
    @Test fun cancellationPropagatesAndEmptySummaryFails() = runBlocking {
        val cfg=ProviderConfig("custom_test")
        val messages=listOf(msg(0,"USER","a".repeat(9000)))
        val cancelled=ContextCompactor(LlmRouter()) { _,_,_ -> throw CancellationException("stop") }
        assertTrue(runCatching { cancelled.compactMessages(cfg,"",messages) }.exceptionOrNull() is CancellationException)
        val empty=ContextCompactor(LlmRouter()) { _,_,_ -> Result.success("") }
        assertTrue(runCatching { empty.compactMessages(cfg,"",messages) }.isFailure)
    }
    @Test fun omittedHistoryNoticeAndPinnedUserTurnsAppearInWireHistory() {
        val active = listOf(
            msg(0, "USER", "First original goal"),
            msg(1, "ASSISTANT", "Acknowledged first"),
            msg(2, "USER", "Second constraint"),
            msg(3, "ASSISTANT", "Acknowledged second"),
            msg(4, "USER", "Latest follow-up")
        )
        val sel = ContextHistoryPolicy.select(active, limit = 2, foldOldTools = false, keepRecentTools = 8)
        assertEquals(3, sel.omittedMessages)
        val pinned = com.forgemind.app.agent.AgentEngine.recentOmittedUserTurns(active, sel.omittedMessages)
        assertEquals(listOf("First original goal", "Second constraint"), pinned)
        val wire = com.forgemind.app.agent.AgentEngine.buildWireHistory(
            systemPrompt = "SYS",
            compactedSummary = "",
            messages = sel.messages,
            pinnedUserTurns = pinned,
            omittedMessages = sel.omittedMessages
        )
        val sys = wire.first().content
        assertTrue(sys.contains("скрыто более ранних сообщений: 3"))
        assertTrue(sys.contains("First original goal"))
        assertTrue(sys.contains("Second constraint"))
    }
}
