package com.forgemind.app.data.model

import com.forgemind.app.data.db.ChatMessageEntity

data class ChatSearchHit(val sessionId: String, val messageId: String?, val excerpt: String,
    val offset: Int = 0, val inThinking: Boolean = false)

/** Literal Unicode case-insensitive search. %, _ and quotes are ordinary characters. */
object ChatContentSearch {
    fun ranges(text: String, query: String, limit: Int = 1000): List<IntRange> {
        if (query.isBlank()) return emptyList()
        val result = mutableListOf<IntRange>()
        var from = 0
        while (from <= text.length - query.length && result.size < limit) {
            val at = text.indexOf(query, from, ignoreCase = true)
            if (at < 0) break
            result += at until at + query.length
            from = at + query.length
        }
        return result
    }

    fun excerpt(text: String, query: String, offset: Int? = null): String {
        val at = (offset ?: text.indexOf(query, ignoreCase = true)).coerceIn(0, text.length)
        val start = (at - 65).coerceAtLeast(0)
        val end = (at + query.length + 115).coerceAtMost(text.length)
        return (if (start > 0) "…" else "") + text.substring(start, end).replace('\n', ' ') +
            if (end < text.length) "…" else ""
    }

    fun matchingMessages(messages: List<ChatMessageEntity>, query: String): List<ChatSearchHit> {
        if (query.isBlank()) return emptyList()
        val result = mutableListOf<ChatSearchHit>()
        for (message in messages) {
            for ((body, thinking) in listOf(message.content to false, message.thinking to true)) {
                for (range in ranges(body, query, (1000 - result.size).coerceAtLeast(0))) {
                    result += ChatSearchHit(message.sessionId, message.id, excerpt(body, query, range.first), range.first, thinking)
                }
                if (result.size >= 1000) return result
            }
        }
        return result
    }

    fun quotation(text: String): String = text.lineSequence().joinToString("\n") { "> $it" } + "\n\n"
}
