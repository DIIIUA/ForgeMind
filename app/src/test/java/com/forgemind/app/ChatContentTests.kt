package com.forgemind.app

import com.forgemind.app.agent.ContextExclusionPolicy
import com.forgemind.app.data.db.ChatMessageEntity
import com.forgemind.app.data.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ChatContentTests {
    private fun message(id: String, role: String = "USER", excluded: Boolean = false) =
        ChatMessageEntity(id, "s", id.last().code, role, content = id, excludedFromContext = excluded)

    @Test fun literalUnicodeSearchDoesNotInterpretSqlWildcards() {
        assertEquals(listOf(2..7, 11..16), ChatContentSearch.ranges("x ПРИВЕТ / привет", "привет"))
        assertTrue(ChatContentSearch.ranges("100 percent", "%_").isEmpty())
        assertEquals(listOf(3..4), ChatContentSearch.ranges("100%_ready", "%_"))
        assertEquals(1, ChatContentSearch.ranges("ёжик", "ЁЖИК").size)
    }

    @Test fun excerptsContainTheMatchAndAreBounded() {
        val excerpt = ChatContentSearch.excerpt("a".repeat(2000) + "TARGET" + "b".repeat(2000), "target")
        assertTrue(excerpt.contains("TARGET"))
        assertTrue(excerpt.length <= 190)
        assertTrue(ChatContentSearch.ranges("abc", " ").isEmpty())
    }

    @Test fun quotationsAreDraftTextAndNeverTerminalCommands() {
        val quote = ChatContentSearch.quotation("/term rm -rf test\nsecond line")
        assertEquals("> /term rm -rf test\n> second line\n\n", quote)
        assertNull(com.forgemind.app.ui.ChatTerminalCommand.extract(quote, false))
    }

    @Test fun searchAddressesEveryOccurrenceIncludingReasoningInOneMessage() {
        val item=message("a1","ASSISTANT").copy(content="MATCH then match",thinking="A third MATCH")
        val hits=ChatContentSearch.matchingMessages(listOf(item),"match")
        assertEquals(listOf(0,11,8),hits.map {it.offset})
        assertEquals(listOf(false,false,true),hits.map {it.inThinking})
    }

    @Test fun exclusionsKeepToolCallsAndRepliesAtomic() {
        val assistant = message("a1", "ASSISTANT").copy(toolCallsJson = """[{"id":"call1","name":"file_read","argumentsJson":"{}"}]""")
        val reply = message("t1", "TOOL", true).copy(toolCallId = "call1")
        val kept = message("u2")
        assertEquals(listOf(kept), ContextExclusionPolicy.eligible(listOf(assistant, reply, kept)))
        assertEquals(listOf(kept), ContextExclusionPolicy.eligible(listOf(assistant.copy(excludedFromContext=true), reply.copy(excludedFromContext=false), kept)))
    }

    @Test fun wholeTurnDoesNotDeleteTheFollowingTurnOrChangeBranchParents() {
        val messages = listOf(message("u1"), message("a1","ASSISTANT"), message("t1","TOOL"), message("a2","ASSISTANT"), message("u2"), message("a3","ASSISTANT"))
        assertEquals(setOf("u1","a1","t1","a2"), ContextExclusionPolicy.affected(messages, "a2", true))
        assertEquals(setOf("a2"), ContextExclusionPolicy.affected(messages, "a2", false))
    }

    @Test fun archiveDefaultsAndExclusionRoundTripAreBackwardCompatible() {
        val old = """{"id":"old","sessionId":"s","orderIndex":0,"role":"USER"}"""
        assertFalse(Json.decodeFromString<ChatMessageEntity>(old).excludedFromContext)
        val excluded = message("u1", excluded = true)
        assertTrue(Json.decodeFromString<ChatMessageEntity>(Json.encodeToString(excluded)).excludedFromContext)
        assertEquals(5, com.forgemind.app.data.archive.ChatArchiveBundle().formatVersion)
    }

    @Test fun switchingBranchesInvalidatesOnlyChangedCompactedPrefixes() {
        val user=message("u1").copy(orderIndex=0,parentMessageId=ConversationBranching.ROOT_KEY)
        val first=message("a1","ASSISTANT").copy(orderIndex=1,parentMessageId=user.id)
        val other=message("a2","ASSISTANT").copy(orderIndex=2,parentMessageId=user.id)
        val all=listOf(user,first,other)
        val before=ConversationBranching.resolve(all,ConversationBranching.withSelection("{}",user.id,first.id))
        val after=ConversationBranching.resolve(all,ConversationBranching.withSelection("{}",user.id,other.id))
        assertTrue(ConversationBranching.changesCompactedPrefix(before,after,3))
        assertFalse(ConversationBranching.changesCompactedPrefix(before,after,1))
    }
}
