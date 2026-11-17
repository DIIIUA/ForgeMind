package com.forgemind.app

import com.forgemind.app.agent.AgentEngine
import com.forgemind.app.data.db.ChatMessageEntity
import com.forgemind.app.data.model.AttachmentKind
import com.forgemind.app.data.model.ChatAttachment
import com.forgemind.app.data.model.LlmRole
import com.forgemind.app.llm.LlmHttpFactory
import com.forgemind.app.llm.LlmWireMessage
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the model actually receives as history: media budget, compaction, stopped turns. */
class AgentHistoryTests {

    private fun img(n: Int) = ChatAttachment(
        id = "a$n", name = "shot$n.png", mimeType = "image/png",
        localPath = "/x/shot$n.png", sizeBytes = 1000L, kind = AttachmentKind.IMAGE
    )

    private fun msg(i: Int, role: LlmRole, content: String) =
        ChatMessageEntity(id = "m$i", sessionId = "s", orderIndex = i, role = role.name, content = content)

    @Test
    fun `only the newest images are re-sent, older ones stay as file lines`() {
        val wire = (1..6).map { LlmWireMessage(role = LlmRole.USER, content = "u$it", attachments = listOf(img(it))) }
        val out = AgentEngine.limitResentMedia(wire, maxMedia = 4)
        val kinds = out.map { it.attachments.single().kind }
        assertEquals(
            listOf(AttachmentKind.BINARY, AttachmentKind.BINARY, AttachmentKind.IMAGE, AttachmentKind.IMAGE, AttachmentKind.IMAGE, AttachmentKind.IMAGE),
            kinds
        )
        // Older images keep their name and path, so the model can still open them with tools.
        assertEquals("/x/shot1.png", out[0].attachments.single().localPath)
        assertEquals(wire.map { it.content }, out.map { it.content })
    }

    @Test
    fun `history built for the model applies the media budget`() {
        val msgs = (0 until 6).map { i ->
            ChatMessageEntity(
                id = "m$i", sessionId = "s", orderIndex = i,
                role = (if (i % 2 == 0) LlmRole.USER else LlmRole.ASSISTANT).name,
                content = "t$i",
                attachmentsJson = if (i % 2 == 0) LlmHttpFactory.json.encodeToString(listOf(img(i), img(i + 100))) else "[]"
            )
        }
        val wire = AgentEngine.buildWireHistory("sys", "", msgs)
        val images = wire.flatMap { it.attachments }.count { it.kind == AttachmentKind.IMAGE }
        assertEquals(AgentEngine.MAX_RESENT_MEDIA, images)
    }

    @Test
    fun `turn stopped before an answer keeps the two user messages apart`() {
        val msgs = listOf(
            msg(0, LlmRole.USER, "Собери отчёт по сайтам"),
            msg(1, LlmRole.ASSISTANT, AgentEngine.STOPPED_BEFORE_ANSWER),
            msg(2, LlmRole.USER, "И добавь картинки")
        )
        val wire = AgentEngine.buildWireHistory("sys", "", msgs)
        assertEquals(listOf(LlmRole.SYSTEM, LlmRole.USER, LlmRole.ASSISTANT, LlmRole.USER), wire.map { it.role })
        assertEquals("И добавь картинки", wire.last().content)
    }

    @Test
    fun `compacted user messages are repeated word for word`() {
        val branch = listOf(
            msg(0, LlmRole.USER, "первое"),
            msg(1, LlmRole.ASSISTANT, "ок"),
            msg(2, LlmRole.USER, "второе"),
            msg(3, LlmRole.USER, "третье"),
            msg(4, LlmRole.USER, "четвёртое"),
            msg(5, LlmRole.ASSISTANT, "работаю"),
            msg(6, LlmRole.USER, "уже не сжато")
        )
        val pinned = AgentEngine.recentCompactedUserTurns(branch, compactedUpToOrder = 6)
        assertEquals(listOf("второе", "третье", "четвёртое"), pinned)
        assertTrue(AgentEngine.recentCompactedUserTurns(branch, compactedUpToOrder = 0).isEmpty())

        val wire = AgentEngine.buildWireHistory("sys", "сводка", branch.drop(6), pinned)
        val sys = wire.first().content
        assertTrue(sys.contains("[СЖАТАЯ ПАМЯТЬ ПРЕДЫДУЩЕГО ДИАЛОГА]"))
        assertTrue(sys.contains("3) четвёртое"))
        assertTrue(sys.indexOf("второе") < sys.indexOf("четвёртое"))
    }
}
