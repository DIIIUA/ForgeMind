package com.forgemind.app.agent

import com.forgemind.app.data.model.ChatAttachment
import java.util.UUID

/** When a message typed while the agent is busy reaches the model. */
enum class QueuedInputMode {
    /** Sent as a new turn once the current run finishes («Дослать»). */
    AFTER_RUN,

    /** Handed to the running agent at its next step, without stopping it («Сейчас»). */
    NEXT_STEP
}

/** A message the user sent while the agent was working. Kept in memory until it is delivered. */
data class QueuedUserInput(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val text: String,
    val attachments: List<ChatAttachment> = emptyList(),
    val mode: QueuedInputMode = QueuedInputMode.AFTER_RUN,
    val createdAt: Long = System.currentTimeMillis()
)
