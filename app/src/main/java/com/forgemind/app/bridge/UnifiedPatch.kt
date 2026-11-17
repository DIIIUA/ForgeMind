package com.forgemind.app.bridge

/**
 * Applies a unified diff (`@@ -a,b +c,d @@` hunks, optional ---/+++ headers) to text.
 * Each hunk is located near its stated line first, then anywhere in the file (the file may have shifted);
 * exact match first, then a whitespace-tolerant match. Line separator and trailing newline are preserved.
 * On failure the error names the hunk, the expected lines and what the file has around that place.
 */
object UnifiedPatch {
    data class Applied(val text: String, val hunks: Int, val notes: List<String>)

    private data class Hunk(val oldStart: Int, val header: String, val oldLines: List<String>, val newLines: List<String>)

    private val HUNK_RE = Regex("""^@@\s*-(\d+)(?:,(\d+))?\s+\+(\d+)(?:,(\d+))?\s*@@.*$""")

    fun apply(original: String, diff: String): Applied {
        val sep = if (original.contains("\r\n")) "\r\n" else "\n"
        val endsWithNewline = original.endsWith("\n")
        val body = if (endsWithNewline) original.removeSuffix("\n").removeSuffix("\r") else original
        val lines = if (original.isEmpty()) mutableListOf() else body.split(sep).toMutableList()

        val fileHeaders = diff.replace("\r\n", "\n").split("\n").count { it.startsWith("+++ ") }
        if (fileHeaders > 1) error("The diff touches $fileHeaders files. Call file_patch once per file with only that file's hunks.")
        val hunks = parse(diff)
        if (hunks.isEmpty()) error("No hunks found. The diff must contain at least one '@@ -a,b +c,d @@' header followed by ' ', '-' and '+' lines.")

        val notes = mutableListOf<String>()
        var offset = 0
        hunks.forEachIndexed { idx, h ->
            val expected = (h.oldStart - 1 + offset).coerceAtLeast(0)
            val (pos, fuzzy) = locate(lines, h.oldLines, expected)
                ?: error(buildString {
                    append("Hunk #${idx + 1} (${h.header}) does not match the file.")
                    append("\nExpected lines (context and '-'):\n")
                    append(h.oldLines.take(12).joinToString("\n") { "  $it" })
                    val from = expected.coerceIn(0, lines.size)
                    val to = (from + h.oldLines.size + 2).coerceAtMost(lines.size)
                    if (from < to) {
                        append("\nThe file around line ${from + 1} has:\n")
                        append((from until to).joinToString("\n") { "${it + 1}| ${lines[it]}" })
                    }
                    append("\nRe-read the file (file_read) and rebuild the hunk from the current text.")
                })
            if (pos != expected) notes += "hunk #${idx + 1} applied at line ${pos + 1} (diff said ${h.oldStart})"
            if (fuzzy) notes += "hunk #${idx + 1} matched ignoring indentation/trailing spaces"
            repeat(h.oldLines.size) { lines.removeAt(pos) }
            lines.addAll(pos, h.newLines)
            offset += h.newLines.size - h.oldLines.size
        }
        val joined = lines.joinToString(sep)
        val text = if (endsWithNewline || (original.isEmpty() && joined.isNotEmpty())) joined + sep else joined
        return Applied(text, hunks.size, notes)
    }

    private fun parse(diff: String): List<Hunk> {
        val out = mutableListOf<Hunk>()
        var header: String? = null
        var oldStart = 0
        val oldL = mutableListOf<String>()
        val newL = mutableListOf<String>()
        fun flush() {
            val h = header ?: return
            out += Hunk(oldStart, h, oldL.toList(), newL.toList())
            oldL.clear(); newL.clear(); header = null
        }
        for (raw in diff.replace("\r\n", "\n").split("\n")) {
            val m = HUNK_RE.find(raw)
            when {
                m != null -> { flush(); header = raw.trim(); oldStart = m.groupValues[1].toInt().coerceAtLeast(1) }
                header == null -> Unit // file headers, `diff --git`, `index`, prose before the first hunk
                raw.startsWith("---") || raw.startsWith("+++") -> Unit
                raw.startsWith("\\") -> Unit // "\ No newline at end of file"
                raw.startsWith("+") -> newL += raw.substring(1)
                raw.startsWith("-") -> oldL += raw.substring(1)
                raw.startsWith(" ") -> { oldL += raw.substring(1); newL += raw.substring(1) }
                raw.isEmpty() -> { oldL += ""; newL += "" } // some tools drop the leading space of empty context lines
                else -> { flush() }
            }
        }
        flush()
        // An empty trailing context line produced by split() on a final newline is not part of the hunk.
        return out.map { h ->
            var o = h.oldLines; var n = h.newLines
            while (o.isNotEmpty() && n.isNotEmpty() && o.last().isEmpty() && n.last().isEmpty()) { o = o.dropLast(1); n = n.dropLast(1) }
            h.copy(oldLines = o, newLines = n)
        }
    }

    /** Returns (index, fuzzy) of the block nearest to [expected]; null when absent. */
    private fun locate(lines: List<String>, block: List<String>, expected: Int): Pair<Int, Boolean>? {
        if (block.isEmpty()) return expected.coerceIn(0, lines.size) to false
        val last = lines.size - block.size
        if (last < 0) return null
        fun at(i: Int, fuzzy: Boolean) = block.indices.all { k ->
            if (fuzzy) lines[i + k].trim() == block[k].trim() else lines[i + k] == block[k]
        }
        for (fuzzy in listOf(false, true)) {
            if (expected in 0..last && at(expected, fuzzy)) return expected to fuzzy
            for (d in 1..lines.size) {
                val a = expected - d
                val b = expected + d
                if (a in 0..last && at(a, fuzzy)) return a to fuzzy
                if (b in 0..last && at(b, fuzzy)) return b to fuzzy
                if (a < 0 && b > last) break
            }
        }
        return null
    }
}
