package com.forgemind.app.data.memory

/** Cased BERT basic tokenization + greedy WordPiece, matching the pinned model's tokenizer.json. */
class WordPieceTokenizer(vocabulary: List<String>) {
    private val vocab = vocabulary.withIndex().associate { it.value to it.index.toLong() }
    private val unknown = vocab.getValue("[UNK]")
    private val cls = vocab.getValue("[CLS]")
    private val sep = vocab.getValue("[SEP]")
    private fun chinese(c: Int) = c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF || c in 0x20000..0x2A6DF ||
        c in 0x2A700..0x2B73F || c in 0x2B740..0x2B81F || c in 0x2B820..0x2CEAF || c in 0xF900..0xFAFF || c in 0x2F800..0x2FA1F
    private fun punctuation(c: Int) = c in 33..47 || c in 58..64 || c in 91..96 || c in 123..126 ||
        Character.getType(c) in setOf(20,21,22,23,24,29,30)
    fun pieces(text: String): LongArray {
        val special = Regex("\\[(UNK|SEP|PAD|CLS|MASK)\\]").findAll(text).toList()
        if (special.isNotEmpty()) {
            val result = mutableListOf<Long>()
            var start = 0
            special.forEach { match ->
                result.addAll(pieces(text.substring(start,match.range.first)).toList())
                result += vocab.getValue(match.value)
                start = match.range.last + 1
            }
            result.addAll(pieces(text.substring(start)).toList())
            return result.toLongArray()
        }
        val tokens = mutableListOf<String>()
        val word = StringBuilder()
        fun flush() { if (word.isNotEmpty()) { tokens += word.toString(); word.clear() } }
        text.codePoints().toArray().forEach { c ->
            when {
                c == 0 || c == 0xfffd -> Unit
                Character.isWhitespace(c) || Character.isSpaceChar(c) -> flush()
                Character.getType(c) in setOf(Character.CONTROL.toInt(), Character.FORMAT.toInt()) -> Unit
                chinese(c) || punctuation(c) -> { flush(); tokens += String(Character.toChars(c)) }
                else -> word.appendCodePoint(c)
            }
        }
        flush()
        return tokens.flatMap { token ->
            val chars = token.codePoints().toArray()
            if (chars.size > 100) listOf(unknown) else {
                val result = mutableListOf<Long>()
                var start = 0
                while (start < chars.size) {
                    var end = chars.size
                    var found: Long? = null
                    while (end > start) {
                        val part = (if (start == 0) "" else "##") + String(chars, start, end - start)
                        found = vocab[part]
                        if (found != null) break
                        end--
                    }
                    if (found == null) { result.clear(); result += unknown; break }
                    result += found; start = end
                }
                result
            }
        }.toLongArray()
    }
    fun chunks(text: String): List<LongArray> {
        val ids = pieces(text)
        if (ids.isEmpty()) return listOf(longArrayOf(cls, sep))
        // Every token is covered, including the tail of long memory notes; 16 tokens overlap.
        val chunks = mutableListOf<LongArray>()
        var offset = 0
        while (offset < ids.size) {
            val end = minOf(offset + 126, ids.size)
            chunks += longArrayOf(cls) + ids.copyOfRange(offset, end) + longArrayOf(sep)
            if (end == ids.size) break
            offset += 110
        }
        return chunks
    }
}
