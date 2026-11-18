package com.forgemind.app.data.model

import com.forgemind.app.data.db.*
import kotlinx.coroutines.*

class ChatSearchRepository(private val db: ForgeMindDatabase) {
    suspend fun search(query: String): List<Pair<ChatSessionEntity, ChatSearchHit>> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val sessions = db.sessionDao().getAll().associateBy { it.id }
        val hits = linkedMapOf<String, ChatSearchHit>()
        sessions.values.filter { it.title.contains(query, ignoreCase = true) }.forEach {
            hits[it.id] = ChatSearchHit(it.id, null, ChatContentSearch.excerpt(it.title, query))
        }
        var after = ""
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = db.messageDao().searchPage(after)
            if (page.isEmpty()) break
            for (message in page) {
                if (message.sessionId !in sessions || hits[message.sessionId]?.messageId != null) continue
                val body = when {
                    message.content.contains(query, ignoreCase = true) -> message.content
                    message.thinking.contains(query, ignoreCase = true) -> message.thinking
                    else -> continue
                }
                hits[message.sessionId] = ChatSearchHit(message.sessionId, message.id, ChatContentSearch.excerpt(body, query),
                    body.indexOf(query, ignoreCase = true), !message.content.contains(query, ignoreCase = true))
            }
            after = page.last().id
        }
        sessions.values.sortedByDescending { it.updatedAt }.mapNotNull { s -> hits[s.id]?.let { s to it } }
    }
}
