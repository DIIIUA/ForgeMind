package com.forgemind.app.agent

import com.forgemind.app.data.db.ChatMessageEntity

data class ContextHistorySelection(
    val messages: List<ChatMessageEntity>,
    val omittedMessages: Int,
    val foldedToolResults: Int
)

/** A request-only view. Originals, branches and tool call IDs are never edited. */
object ContextHistoryPolicy {
    fun select(
        messages: List<ChatMessageEntity>,
        limit: Int,
        foldOldTools: Boolean,
        keepRecentTools: Int
    ): ContextHistorySelection {
        var start = if (limit <= 0) 0 else (messages.size - limit).coerceAtLeast(0)
        // Retain the complete current user turn, even when an agent has made many tool calls.
        val lastUser = messages.indexOfLast { it.role == "USER" }
        if (lastUser >= 0 && start > lastUser) start = lastUser
        // A tool reply must keep its assistant tool_call batch.
        while (start > 0 && messages[start].role == "TOOL") start--
        val selected = messages.drop(start)
        val retainedIds = selected.filter { it.role == "TOOL" }.takeLast(keepRecentTools.coerceIn(1, 100)).map { it.id }.toSet()
        var folded = 0
        val result = selected.map { message ->
            if (!foldOldTools || message.role != "TOOL" || message.id in retainedIds || message.content.length <= 2400) message
            else {
                folded++
                message.copy(content = buildString {
                    appendLine("[Earlier tool output shortened for this request; ${message.content.length} characters remain in saved history.]")
                    appendLine(message.content.take(700))
                    appendLine("\n[...]")
                    appendLine(message.content.takeLast(700))
                    append("Read original with chat_read_session(session_id=\"${message.sessionId}\", message_id=\"${message.id}\", offset_char=0, limit_chars=12000).")
                })
            }
        }
        return ContextHistorySelection(result, start, folded)
    }

    fun effectiveLimit(chatLimit: Int, providerLimit: Int): Int =
        (if (chatLimit < 0) providerLimit else chatLimit).coerceIn(0, 10_000)
}
