package com.forgemind.app.llm

import kotlinx.serialization.json.*

/** Only an explicitly rejected, non-streaming request may be retried with a smaller output cap. */
object ContextBudgetRecovery {
    private val CONTEXT_KEYWORDS = listOf(
        "context",
        "maximum context",
        "prompt is too long",
        "token count",
        "tokens limit",
        "max_tokens",
        "max_completion_tokens",
        "input tokens",
        "prompt tokens"
    )

    private val PROMPT_PATTERNS = listOf(
        // "7287 prompt tokens", "31000 input tokens", "120000 of text input"
        Regex("(\\d+)\\s*(?:prompt|input|of\\s+text\\s+input)\\b", RegexOption.IGNORE_CASE),
        // OpenAI / vLLM: "(7500 in your prompt; 1500 for the completion)" or "(30000 in the messages, 5000 in the completion)"
        Regex("\\((\\d+)\\s+(?:in\\s+(?:your\\s+|the\\s+)?(?:prompt|messages|input))", RegexOption.IGNORE_CASE),
        // OpenAI: "your messages resulted in 127500 tokens"
        Regex("messages\\s+resulted\\s+in\\s+(\\d+)\\s*tokens?", RegexOption.IGNORE_CASE),
        // OpenRouter: "Prompt tokens limit exceeded: 125000 + 8192 > 131072"
        Regex("prompt\\s+tokens?[^\\d]*(\\d+)\\s*\\+\\s*\\d+\\s*>", RegexOption.IGNORE_CASE),
        // Anthropic: "prompt is too long: 205000 tokens > 200000 maximum"
        Regex("prompt\\s+is\\s+too\\s+long[:\\s]+(\\d+)\\s*tokens?", RegexOption.IGNORE_CASE),
        // Gemini: "input token count (1050000) exceeds"
        Regex("input\\s+token\\s+count\\s*\\(?(\\d+)\\)?", RegexOption.IGNORE_CASE),
        // Generic: "request has 31000 input tokens" or "your prompt has 31000 tokens"
        Regex("(?:request|prompt)\\s+has\\s+(\\d+)\\s*(?:input\\s+)?tokens?", RegexOption.IGNORE_CASE)
    )

    private val WINDOW_PATTERNS = listOf(
        // "maximum context length is 128000 tokens", "context window of 8192", "context size: 32768"
        Regex("(?:maximum\\s+)?(?:context(?:[ _-]*(?:window|length|size|limit|budget))?)[\\s:=()]*?(?:of\\s+|is\\s+)?(\\d+)", RegexOption.IGNORE_CASE),
        // "8192 context", "131072 total", "32768 available"
        Regex("(\\d+)\\s*(?:context|total|available)\\b", RegexOption.IGNORE_CASE),
        // Gemini: "maximum number of tokens allowed (1048576)"
        Regex("maximum\\s+(?:number\\s+of\\s+)?tokens?(?:\\s+allowed)?[\\s:=()]*?(?:is\\s+|of\\s+)?(\\d+)", RegexOption.IGNORE_CASE),
        // Anthropic / OpenRouter: "> 200000 maximum", "> 131072"
        Regex(">\\s*(\\d+)", RegexOption.IGNORE_CASE)
    )

    fun outputLimit(error: String, requested: Int): Int? {
        if (CONTEXT_KEYWORDS.none { error.contains(it, ignoreCase = true) }) return null
        val prompt = PROMPT_PATTERNS.firstNotNullOfOrNull { regex ->
            regex.find(error)?.groupValues?.get(1)?.toLongOrNull()
        } ?: return null
        val window = WINDOW_PATTERNS.firstNotNullOfOrNull { regex ->
            regex.find(error)?.groupValues?.get(1)?.toLongOrNull()
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
