package com.forgemind.app.llm

import com.forgemind.app.data.db.ChatMessageEntity
import com.forgemind.app.data.model.ProviderConfig

data class CompactionOutcome(
    val newSummary: String,
    val compactedUpToOrder: Int,
    val tokensBefore: Int,
    val tokensAfter: Int,
    val usedLlm: Boolean
)

class ContextCompactor(
    private val llmRouter: LlmRouter
) {
    /**
     * Conservative token estimation covering Cyrillic, Latin, code, and tool JSON payloads.
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        // Russian/code average ~2.8 chars per token safely avoids context overflow
        return ((text.length / 2.8) + 8).toInt()
    }

    fun estimateSessionTokens(
        systemPrompt: String,
        compactedSummary: String,
        activeMessages: List<ChatMessageEntity>
    ): Int {
        var total = estimateTokens(systemPrompt) + estimateTokens(compactedSummary)
        for (m in activeMessages) {
            total += estimateTokens(m.content)
            total += estimateTokens(m.toolCallsJson)
        }
        return total
    }

    fun shouldAutoCompact(
        estimatedTokens: Int,
        contextWindow: Int,
        thresholdPercent: Int,
        activeMessagesCount: Int
    ): Boolean {
        if (contextWindow <= 0 || activeMessagesCount < 8) return false
        val limit = (contextWindow * thresholdPercent.coerceIn(40, 95)) / 100
        return estimatedTokens >= limit
    }

    suspend fun compactMessages(
        config: ProviderConfig,
        existingSummary: String,
        messagesToCompact: List<ChatMessageEntity>
    ): CompactionOutcome {
        val tokensBefore = messagesToCompact.sumOf { estimateTokens(it.content) + estimateTokens(it.toolCallsJson) } +
            estimateTokens(existingSummary)

        if (messagesToCompact.isEmpty()) {
            return CompactionOutcome(
                newSummary = existingSummary,
                compactedUpToOrder = 0,
                tokensBefore = tokensBefore,
                tokensAfter = tokensBefore,
                usedLlm = false
            )
        }

        val lastOrder = messagesToCompact.maxOf { it.orderIndex } + 1
        val transcript = buildString {
            if (existingSummary.isNotBlank()) {
                appendLine("=== PREVIOUS CONVERSATION MEMORY ===")
                appendLine(existingSummary)
                appendLine()
            }
            appendLine("=== NEW MESSAGES TO COMPACT ===")
            for (m in messagesToCompact) {
                val head = if (!m.toolName.isNullOrBlank()) "${m.role}(${m.toolName})" else m.role
                val body = m.content.take(2500)
                appendLine("[$head]: $body")
            }
        }.take(45_000)

        val sys = """
            You are a precise development conversation memory compactor (Context Compactor).
            Preserve all critical long-term information so the agent can continue without losing context:
            1. Primary user goal and constraints.
            2. All touched file paths, shell/SSH commands, URLs, MCP servers, and configuration.
            3. What has already been done, which files changed, errors encountered, and remaining work.
            4. Language and key decisions.
            Return only a structured concise summary without fluff.
        """.trimIndent()

        val llmRes = llmRouter.completeOnce(config, sys, transcript)
        val summaryText = llmRes.getOrNull()?.takeIf { it.isNotBlank() }
            ?: fallbackCompact(existingSummary, messagesToCompact)

        return CompactionOutcome(
            newSummary = summaryText,
            compactedUpToOrder = lastOrder,
            tokensBefore = tokensBefore,
            tokensAfter = estimateTokens(summaryText),
            usedLlm = llmRes.isSuccess && !llmRes.getOrNull().isNullOrBlank()
        )
    }

    fun fallbackCompact(existingSummary: String, messages: List<ChatMessageEntity>): String = buildString {
        if (existingSummary.isNotBlank()) {
            appendLine(existingSummary.take(4000))
            appendLine()
        }
        appendLine("Compacted messages (${messages.size}):")
        for (m in messages) {
            val snippet = m.content.replace('\n', ' ').trim().take(240)
            if (snippet.isNotBlank()) {
                val label = if (!m.toolName.isNullOrBlank()) "${m.role}:${m.toolName}" else m.role
                appendLine("- [$label] $snippet")
            }
        }
    }.take(8000)
}
