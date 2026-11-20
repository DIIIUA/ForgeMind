package com.forgemind.app.agent

import com.forgemind.app.data.model.ToolCallInfo
import kotlinx.serialization.json.*
import java.util.UUID

/** Markdown examples are inert, including unfinished fences during streaming. */
object TextToolProtocol {
    private val strictJson = Json { isLenient = false }
    private val xml = Regex("""(?ms)^[ \t]{0,3}<tool_call>[ \t\r\n]*(.*?)</tool_call>[ \t]*(?=\r?$)""")
    private val fence = Regex("""^ {0,3}(`{3,}|~{3,})(.*)$""")
    private val openToolCallLine = Regex("""^ {0,3}<tool_call>""")
    private val inlineBackticks = Regex("""(`+)([\s\S]*?)\1(?!`)""")
    private val legacyMarkupRegex = Regex("""(?m)^\s*(?:<[^\n]*(?:DSML|tool▁|tool_|function=|invoke)|\[TOOL_CALLS\])""")

    fun outsideCode(text: String, transform: (String) -> String): String {
        // Replace protected spans before parsing, then restore them byte for byte.
        val saved = mutableListOf<String>()
        val prefix = "FM_LITERAL_${UUID.randomUUID()}_"
        fun protect(value: String): String {
            val newline = if (value.endsWith("\n")) "\n" else ""
            saved += value.removeSuffix(newline.takeIf { it.isNotEmpty() } ?: "\u0000")
            return "$prefix${saved.lastIndex}_END$newline"
        }
        var open: Char? = null
        var width = 0
        var inXml = false
        val masked = buildString(text.length) {
            var offset = 0
            while (offset < text.length) {
                val end = text.indexOf('\n', offset).let { if (it < 0) text.length else it + 1 }
                val line = text.substring(offset, end)
                val bare = line.trimEnd('\r', '\n')
                val match = fence.matchEntire(bare)
                if (open != null) {
                    append(protect(line))
                    if (match != null && match.groupValues[1][0] == open &&
                        match.groupValues[1].length >= width && match.groupValues[2].isBlank()) open = null
                } else if (inXml) {
                    append(line)
                    if (line.contains("</tool_call>")) inXml = false
                } else if (match != null) {
                    open = match.groupValues[1][0]; width = match.groupValues[1].length
                    append(protect(line))
                } else if (bare.trimStart().startsWith(">") || bare.startsWith("    ") || bare.startsWith("\t")) {
                    append(protect(line))
                } else {
                    append(line)
                    if (openToolCallLine.containsMatchIn(line) && !line.contains("</tool_call>")) inXml = true
                }
                offset = end
            }
        }
        // Inline backtick spans, including multi-backtick delimiters.
        val inline = if (masked.contains('`')) {
            val xmlRanges = if (masked.contains("<tool_call>")) xml.findAll(masked).map { it.range }.toList() else emptyList()
            inlineBackticks.replace(masked) {
                if (xmlRanges.any { range -> it.range.first in range }) it.value else protect(it.value)
            }
        } else masked
        var result = transform(inline)
        if (saved.isNotEmpty()) {
            val restoreRegex = Regex("${Regex.escape(prefix)}(\\d+)_END")
            // Up to 2 passes handles placeholders nested inside protected spans (none expected, safe fallback).
            repeat(2) {
                if (!result.contains(prefix)) return@repeat
                result = restoreRegex.replace(result) { m ->
                    val idx = m.groupValues[1].toIntOrNull()
                    if (idx != null && idx in saved.indices) saved[idx] else m.value
                }
            }
        }
        return result
    }

    fun parse(text: String, knownNames: Set<String>, allowLegacy: Boolean = true): AgentEngine.Companion.FallbackToolParseResult {
        if (knownNames.isEmpty()) return AgentEngine.Companion.FallbackToolParseResult(text, emptyList())
        val calls = mutableListOf<ToolCallInfo>()
        val cleaned = outsideCode(text) { visible ->
            var remaining = xml.replace(visible) { match ->
                val obj = runCatching { strictJson.parseToJsonElement(match.groupValues[1]) as? JsonObject }.getOrNull()
                val name = (obj?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content
                val args = obj?.get("arguments") as? JsonObject
                if (name in knownNames && args != null && obj!!.keys.all { it in setOf("name", "arguments", "id") } && !args.toString().contains("FM_LITERAL_")) {
                    calls += ToolCallInfo(id = "xml_${UUID.randomUUID()}", name = name!!,
                        argumentsJson = args.toString(), viaTextFallback = true)
                    ""
                } else match.value
            }
            // Malformed/unclosed XML is text, never repaired into a command.
            // Existing provider-specific DSML/DeepSeek protocols remain supported outside examples.
            val legacyMarkup = legacyMarkupRegex.containsMatchIn(remaining)
            if (allowLegacy && legacyMarkup && !remaining.contains("<tool_call", ignoreCase = true) &&
                !remaining.contains("<toolcall", ignoreCase = true)) {
                val legacy = AgentEngine.extractInlineFallbackToolCalls(remaining, knownNames)
                if (legacy.toolCalls.isNotEmpty() && legacy.toolCalls.all { it.name in knownNames && !it.argumentsJson.contains("FM_LITERAL_") }) {
                    calls += legacy.toolCalls
                    remaining = legacy.cleanedText
                }
            }
            remaining
        }
        return AgentEngine.Companion.FallbackToolParseResult(if (calls.isEmpty()) text else cleaned.trim(), calls)
    }
}
