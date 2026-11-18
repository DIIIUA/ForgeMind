package com.forgemind.app.llm

import kotlinx.serialization.json.*

/** Only an explicitly rejected, non-streaming request may be retried with a smaller output cap. */
object ContextBudgetRecovery {
    fun outputLimit(error: String, requested: Int): Int? {
        if (!error.contains("context", true)) return null
        val prompt = Regex("(\\d+)\\s*(?:prompt|input)\\b", RegexOption.IGNORE_CASE)
            .find(error)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val patterns = listOf(
            "(?:context(?:[ _-]*(?:window|length|size|limit|budget))?)[\\s:=()]*?(?:of\\s+|is\\s+)?(\\d+)",
            "(\\d+)\\s*(?:context|total|available)\\b",
            ">\\s*(\\d+)"
        )
        val window = patterns.firstNotNullOfOrNull { pattern ->
            Regex(pattern, RegexOption.IGNORE_CASE).find(error)?.groupValues?.get(1)?.toLongOrNull()
        } ?: return null
        val remaining = window - prompt - 32
        return remaining.takeIf { it in 1 until requested.toLong() }?.toInt()
    }

    fun capped(body: JsonObject, limit: Int?, responses: Boolean): JsonObject {
        if (limit == null) return body
        val field = if (responses) "max_output_tokens" else if ("max_completion_tokens" in body) "max_completion_tokens" else "max_tokens"
        return JsonObject(body.toMutableMap().apply {
            remove("max_tokens"); remove("max_completion_tokens"); remove("max_output_tokens")
            put(field, JsonPrimitive(limit))
        })
    }
}
