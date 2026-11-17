package com.forgemind.app.ui

/** A command requires an explicit prefix or a mode selected by the human. */
object ChatTerminalCommand {
    fun extract(text: String, commandMode: Boolean = false): String? {
        val trimmed = text.trim()
        if (commandMode) return trimmed.takeIf { it.isNotBlank() }
        if (!text.startsWith("/term") || (trimmed.length > 5 && !trimmed[5].isWhitespace())) return null
        return trimmed.drop(5).trim()
    }
}

data class PendingChatCommand(
    val sessionId: String,
    val command: String,
    val sourceText: String,
    val targetTabId: String?,
    val targetLabel: String,
    val createTermux: Boolean,
    val workspacePath: String
)
