package com.forgemind.app.agent

import com.forgemind.app.data.db.ChatMessageEntity
import kotlinx.serialization.json.*

/** Exclusion changes request history only. Resolve branch parents before applying this view. */
object ContextExclusionPolicy {
    private val json = Json { ignoreUnknownKeys = true }
    private fun calls(message: ChatMessageEntity): Set<String> = runCatching {
        json.parseToJsonElement(message.toolCallsJson).jsonArray.mapNotNull {
            it.jsonObject["id"]?.jsonPrimitive?.contentOrNull
        }.toSet()
    }.getOrDefault(emptySet())

    fun eligible(messages: List<ChatMessageEntity>): List<ChatMessageEntity> {
        val omitted = messages.filter { it.excludedFromContext }.map { it.id }.toMutableSet()
        val repliesByCall = messages.filter { it.role == "TOOL" }.groupBy { it.toolCallId }
        // A tool-call batch and its results are indivisible even for imported/malformed flags.
        messages.filter { it.role == "ASSISTANT" }.forEach { assistant ->
            val ids = calls(assistant)
            if (ids.isNotEmpty()) {
                val replies = ids.flatMap { repliesByCall[it].orEmpty() }
                if (assistant.id in omitted || replies.any { it.id in omitted }) {
                    omitted += assistant.id
                    omitted += replies.map { it.id }
                }
            }
        }
        return messages.filterNot { it.id in omitted }
    }

    fun affected(messages: List<ChatMessageEntity>, messageId: String, wholeTurn: Boolean): Set<String> {
        val index = messages.indexOfFirst { it.id == messageId }
        require(index >= 0) { "Сообщение больше не находится в активной ветке" }
        if (!wholeTurn) return setOf(messageId)
        val start = (index downTo 0).firstOrNull { messages[it].role == "USER" } ?: index
        val end = (index + 1 until messages.size).firstOrNull { messages[it].role == "USER" } ?: messages.size
        return messages.subList(start, end).map { it.id }.toSet()
    }
}
