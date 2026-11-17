package com.forgemind.app.agent

/**
 * Agent-computer interface contract for tool results (what the model reads back):
 * - a result is never empty: silent success says so explicitly;
 * - truncation is never silent: the model is told how much was cut and how to narrow the request;
 * - an error always ends with a "how to fix" block, including the tool's parameters when known;
 * - a denied call tells the model not to retry it blindly.
 */
object ToolResultFormatter {
    const val MAX_TOOL_RESULT_CHARS = 14_000
    const val FIX_MARKER = "[КАК ИСПРАВИТЬ]"

    fun forModel(
        toolName: String,
        out: ToolRunOutput,
        paramsHint: String? = null,
        loopNote: String = ""
    ): String {
        val raw = out.text.trimEnd()
        val body = when {
            raw.isBlank() && !out.isError ->
                "Выполнено: $toolName завершился успешно, вывода нет."
            raw.isBlank() ->
                "Ошибка: $toolName завершился с ошибкой без описания."
            toolName != ToolCatalog.TOOL_NAME && raw.length > MAX_TOOL_RESULT_CHARS ->
                raw.take(MAX_TOOL_RESULT_CHARS) +
                    "\n\n[ОБРЕЗАНО: показано $MAX_TOOL_RESULT_CHARS из ${raw.length} символов. " +
                    "Сузь запрос (offset/limit, selector, grep, max_chars) или сохрани вывод в файл и читай его частями.]"
            else -> raw
        }
        val fix = if (out.isError && !body.contains(FIX_MARKER)) {
            buildString {
                append("\n\n").append(FIX_MARKER).append(' ')
                append("Прочитай текст ошибки и поправь аргументы или выбери другой инструмент. ")
                append("Не повторяй тот же вызов без изменений.")
                if (!paramsHint.isNullOrBlank()) append("\nПараметры $toolName: ").append(paramsHint)
            }
        } else ""
        return body + fix + loopNote
    }

    fun denied(toolName: String): String =
        "Пользователь отклонил вызов $toolName.\n\n$FIX_MARKER Не повторяй этот вызов. " +
            "Объясни пользователю, зачем он нужен, и спроси, как поступить, или предложи другой путь."

    /** Closest tool names for a misspelled / invented tool (shared prefix, substring, small edit distance). */
    fun closestNames(unknown: String, known: Collection<String>, limit: Int = 5): List<String> {
        val u = unknown.lowercase()
        val uHead = u.substringBefore('_')
        return known.asSequence()
            .map { name ->
                val n = name.lowercase()
                val score = when {
                    n == u -> 0
                    n.contains(u) || u.contains(n) -> 1
                    n.substringBefore('_') == uHead -> 2 + editDistance(n, u) / 4
                    else -> 6 + editDistance(n, u)
                }
                name to score
            }
            .filter { it.second <= 9 }
            .sortedBy { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }
}
