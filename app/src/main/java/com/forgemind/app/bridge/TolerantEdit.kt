package com.forgemind.app.bridge

/**
 * file_edit matching that does not punish the agent for invisible differences.
 * Order: exact → line-ending normalised (CRLF/LF) → per-line trimmed (indentation / trailing spaces).
 * When nothing matches, the error shows the closest fragment with line numbers so the agent can retry precisely.
 */
object TolerantEdit {
    data class Result(val updated: String, val note: String)

    fun apply(current: String, oldString: String, newString: String, path: String): Result {
        // 1. Exact.
        exactOnce(current, oldString, path)?.let { idx ->
            return Result(current.replaceRange(idx, idx + oldString.length, newString), "")
        }
        // 2. Line endings: the file uses CRLF, the agent sent LF (or the other way round).
        if (current.contains("\r\n") && !oldString.contains("\r\n")) {
            val o = oldString.replace("\n", "\r\n")
            val n = newString.replace("\r\n", "\n").replace("\n", "\r\n")
            exactOnce(current, o, path)?.let { idx ->
                return Result(current.replaceRange(idx, idx + o.length, n), " (matched after normalising line endings to CRLF)")
            }
        } else if (!current.contains("\r\n") && oldString.contains("\r\n")) {
            val o = oldString.replace("\r\n", "\n")
            exactOnce(current, o, path)?.let { idx ->
                return Result(current.replaceRange(idx, idx + o.length, newString.replace("\r\n", "\n")), " (matched after normalising line endings to LF)")
            }
        }
        // 3. Per-line trimmed comparison of a whole block of lines.
        val sep = if (current.contains("\r\n")) "\r\n" else "\n"
        val fileLines = current.split(sep)
        val oldLines = oldString.replace("\r\n", "\n").trim('\n').split("\n")
        val want = oldLines.map { it.trim() }
        if (want.any { it.isNotEmpty() }) {
            val hits = (0..fileLines.size - want.size).filter { start ->
                want.indices.all { k -> fileLines[start + k].trim() == want[k] }
            }
            if (hits.size > 1) {
                error("old_string matches ${hits.size} places in '$path' when indentation is ignored (lines ${hits.take(5).joinToString { (it + 1).toString() }}). Add more surrounding lines to make it unique.")
            }
            if (hits.size == 1) {
                val start = hits[0]
                val before = fileLines.subList(0, start)
                val after = fileLines.subList(start + want.size, fileLines.size)
                val replacement = newString.replace("\r\n", "\n").trim('\n').split("\n")
                val updated = (before + replacement + after).joinToString(sep)
                return Result(updated, " (matched ignoring indentation/trailing spaces at lines ${start + 1}–${start + want.size}; check the diff)")
            }
        }
        // 4. Nothing: show the closest fragment.
        error(buildString {
            append("old_string not found in '$path' (also tried CRLF/LF and ignoring indentation).")
            closestFragment(fileLines, want)?.let { (lineNo, frag) ->
                append(" Closest fragment near line $lineNo:\n").append(frag)
            }
            append("\nRead the file with file_read (offset_line/limit_lines) and copy the exact text.")
        })
    }

    private fun exactOnce(current: String, needle: String, path: String): Int? {
        if (needle.isEmpty()) error("old_string is empty. To create or overwrite a whole file use file_write.")
        val first = current.indexOf(needle)
        if (first < 0) return null
        val second = current.indexOf(needle, first + 1)
        if (second >= 0) {
            var count = 2
            var i = current.indexOf(needle, second + 1)
            while (i >= 0) { count++; i = current.indexOf(needle, i + 1) }
            error("old_string appears $count times in '$path'. Add more surrounding context lines.")
        }
        return first
    }

    private fun closestFragment(fileLines: List<String>, want: List<String>): Pair<Int, String>? {
        val anchor = want.firstOrNull { it.length >= 4 } ?: return null
        var bestIdx = -1
        var bestScore = 0
        fileLines.forEachIndexed { i, line ->
            val t = line.trim()
            val score = commonPrefix(t, anchor) * 2 + if (t.isNotEmpty() && (anchor.contains(t) || t.contains(anchor))) 20 else 0
            if (score > bestScore) { bestScore = score; bestIdx = i }
        }
        if (bestIdx < 0 || bestScore < 8) return null
        val from = (bestIdx - 2).coerceAtLeast(0)
        val to = (bestIdx + want.size + 2).coerceAtMost(fileLines.size)
        val frag = (from until to).joinToString("\n") { "${it + 1}| ${fileLines[it]}" }
        return (bestIdx + 1) to frag
    }

    private fun commonPrefix(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }
}
