package com.forgemind.app.voice

/**
 * Whisper-family models "hear" stock phrases in silence or noise (subtitle credits, "to be continued").
 * Such a transcript must not be sent to the agent as if the user said it.
 */
object SttHallucinationFilter {
    private val PHRASES = listOf(
        "продолжение следует",
        "субтитры сделал dimatorzok",
        "субтитры создавал dimatorzok",
        "субтитры подготовил",
        "редактор субтитров",
        "спасибо за просмотр",
        "подписывайтесь на канал",
        "thank you for watching",
        "thanks for watching",
        "subtitles by the amara.org community",
        "please subscribe"
    )

    private fun norm(s: String) = s.lowercase()
        .replace('ё', 'е')
        .replace(Regex("[\\p{Punct}…«»—–]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Returns the transcript without hallucinated stock phrases; empty when nothing real was said. */
    fun clean(transcript: String): String {
        val trimmed = transcript.trim()
        if (trimmed.isEmpty()) return ""
        val n = norm(trimmed)
        if (PHRASES.any { n == it || (n.startsWith(it) && n.length <= it.length + 12) }) return ""
        var out = trimmed
        for (p in PHRASES) {
            val tail = Regex("[\\s.,!?…]*" + Regex.escape(p) + "[\\s.!?…]*$", RegexOption.IGNORE_CASE)
            out = out.replace(tail, "")
        }
        return out.trim()
    }
}
