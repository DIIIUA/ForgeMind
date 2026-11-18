package com.forgemind.app.data.model

import com.forgemind.app.data.db.ChatMessageEntity

object ConversationActions {
    fun copyConversation(messages: List<ChatMessageEntity>): String = messages
        .filter { it.role == "USER" || it.role == "ASSISTANT" }
        .joinToString("\n\n") { message ->
            val name = if (message.role == "USER") "Пользователь" else message.modelId ?: "Ассистент"
            "$name:\n${message.content}"
        }

    fun nextBranchTitle(title: String, existingTitles: Collection<String>): String {
        val root = title.replace(Regex(" \\(\\d+\\)$"), "").take(100)
        var number = 1
        while ("$root ($number)" in existingTitles) number++
        return "$root ($number)"
    }
}
