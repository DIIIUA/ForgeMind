package com.forgemind.app.voice

private val TOOL_CLOSE_REGEX = Regex("(?i)</tool_?calls?\\s*>")
private val BACKTICK_FENCE_CLOSE_REGEX = Regex("(?m)^\\s*```[^\\n]*(?:\\n|$)")
private val TILDE_FENCE_CLOSE_REGEX = Regex("(?m)^\\s*~~~[^\\n]*(?:\\n|$)")
private val STREAM_MARKER_REGEX = Regex("(?im)^\\s*(```|~~~)|<tool_?calls?\\b[^>]*>")
private val SENTENCE_BOUNDARY_REGEX = Regex("[.!?。！？](?=\\s|$)|\\n")

private val CLEAN_INVISIBLE_REGEX = Regex("[\\u200B-\\u200F\\u2060\\uFEFF]")
private val CLEAN_NBSP_REGEX = Regex("[\\u00A0\\u202F\\u2000-\\u200A\\u3000]")
private val CLEAN_TOOL_BLOCK_REGEX = Regex("(?is)<tool_?calls?\\b[^>]*>.*?(?:</tool_?calls?>|$)")
private val CLEAN_BACKTICK_FENCE_REGEX = Regex("(?s)```.*?(?:```|$)")
private val CLEAN_TILDE_FENCE_REGEX = Regex("(?s)~~~.*?(?:~~~|$)")
private val CLEAN_MD_IMAGE_REGEX = Regex("!\\[[^]]*]\\([^)]*\\)")
private val CLEAN_MD_LINK_REGEX = Regex("\\[([^]]+)]\\([^)]*\\)")
private val CLEAN_MD_PREFIX_REGEX = Regex("(?m)^\\s*(?:#{1,6}\\s+|>\\s*|[-*+]\\s+|\\d+[.)]\\s+)")
private val CLEAN_HTML_TAG_REGEX = Regex("<[^>]*>")
private val CLEAN_URL_REGEX = Regex("https?://[^\\s)\\]]+")
private val CLEAN_MD_CHARS_REGEX = Regex("[`*~]")
private val CLEAN_PIPE_REGEX = Regex("[|]")
private val CLEAN_UNDERSCORE_REGEX = Regex("(?<!\\p{L})_|_(?!\\p{L})")
private val CLEAN_EMPTY_PARENS_REGEX = Regex("\\(\\s*\\)|\\[\\s*]")
private val CLEAN_ELLIPSIS_REGEX = Regex("…|\\.{2,}")
private val CLEAN_MULTI_BANG_REGEX = Regex("!{2,}")
private val CLEAN_MULTI_Q_REGEX = Regex("\\?{2,}")
private val CLEAN_MULTI_COMMA_REGEX = Regex(",{2,}")
private val CLEAN_WHITESPACE_REGEX = Regex("\\s+")
private val CLEAN_SPACE_BEFORE_PUNCT_REGEX = Regex("\\s+([,.!?;:»”\\)\\]])")
private val CLEAN_SPACE_AFTER_OPEN_REGEX = Regex("([«“\\(\\[])\\s+")
private val CLEAN_MULTI_PUNCT_REGEX = Regex("([.!?])(?:\\s*[.!?])+")

/** Hidden code/protocol blocks stay hidden across arbitrary stream deltas. */
class StreamingSpeechText {
    private val pending = StringBuilder()
    private var fence: String? = null
    private var toolBlock = false
    fun append(delta: String, finish: Boolean = false): List<String> {
        if (delta.isNotEmpty()) pending.append(delta)
        val out = mutableListOf<String>()
        fun emit(raw: String) { cleanSpeechMarkdown(raw).takeIf { it.isNotBlank() }?.let(out::add) }
        while (pending.isNotEmpty()) {
            if (fence != null || toolBlock) {
                val close = when {
                    toolBlock -> TOOL_CLOSE_REGEX.find(pending)
                    fence == "```" -> BACKTICK_FENCE_CLOSE_REGEX.find(pending)
                    fence == "~~~" -> TILDE_FENCE_CLOSE_REGEX.find(pending)
                    else -> Regex("(?m)^\\s*${Regex.escape(fence!!)}[^\\n]*(?:\\n|$)").find(pending)
                }
                if (close != null) {
                    pending.delete(0, close.range.last + 1); fence = null; toolBlock = false; continue
                }
                if (finish) pending.clear()
                else if (pending.length > 8192) pending.delete(0, pending.length - 64)
                break
            }
            val marker = STREAM_MARKER_REGEX.find(pending)
            val boundary = SENTENCE_BOUNDARY_REGEX.find(pending)?.range?.first ?: -1
            if (marker != null && (boundary < 0 || marker.range.first <= boundary)) {
                emit(pending.substring(0, marker.range.first))
                val token = marker.value.trimStart()
                pending.delete(0, marker.range.last + 1)
                if (token.startsWith("<")) toolBlock = true else fence = token.take(3)
                continue
            }
            val uncertain = pending.indexOf("<").takeIf { it >= 0 && pending.indexOf(">", it) < 0 }
            val end = when {
                boundary >= 0 && (uncertain == null || boundary < uncertain) -> boundary + 1
                finish -> pending.length
                pending.length > 8192 && uncertain == null -> pending.lastIndexOf(" ", 4096).takeIf { it > 0 } ?: 4096
                else -> -1
            }
            if (end < 0) {
                if (pending.length > 8192 && uncertain != null && uncertain < pending.length - 64) pending.delete(uncertain, pending.length - 64)
                break
            }
            emit(pending.substring(0, end)); pending.delete(0, end)
        }
        return out
    }
}

fun cleanSpeechMarkdown(raw: String): String = raw
    .replace(CLEAN_INVISIBLE_REGEX, "")
    .replace(CLEAN_NBSP_REGEX, " ")
    .replace(CLEAN_TOOL_BLOCK_REGEX, " ")
    .replace(CLEAN_BACKTICK_FENCE_REGEX, " ")
    .replace(CLEAN_TILDE_FENCE_REGEX, " ")
    .replace(CLEAN_MD_IMAGE_REGEX, " ")
    .replace(CLEAN_MD_LINK_REGEX, "$1")
    .replace(CLEAN_MD_PREFIX_REGEX, "")
    .replace(CLEAN_HTML_TAG_REGEX, " ")
    .replace(CLEAN_URL_REGEX, " ")
    .replace(CLEAN_MD_CHARS_REGEX, "")
    .replace(CLEAN_PIPE_REGEX, " ")
    .replace(CLEAN_UNDERSCORE_REGEX, " ")
    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    .replace(CLEAN_EMPTY_PARENS_REGEX, " ")
    .replace(CLEAN_ELLIPSIS_REGEX, ".")
    .replace(CLEAN_MULTI_BANG_REGEX, "!")
    .replace(CLEAN_MULTI_Q_REGEX, "?")
    .replace(CLEAN_MULTI_COMMA_REGEX, ",")
    .replace(CLEAN_WHITESPACE_REGEX, " ")
    .replace(CLEAN_SPACE_BEFORE_PUNCT_REGEX, "$1")
    .replace(CLEAN_SPACE_AFTER_OPEN_REGEX, "$1")
    .replace(CLEAN_MULTI_PUNCT_REGEX, "$1")
    .trim()
