package com.forgemind.app.bridge

object DiffUtils {
    fun generateUnifiedDiff(
        filePath: String,
        before: String?,
        after: String?
    ): String {
        val oldLines = before?.lines() ?: emptyList()
        val newLines = after?.lines() ?: emptyList()
        if (before == null && after != null) {
            return buildString {
                appendLine("--- /dev/null")
                appendLine("+++ b/$filePath")
                appendLine("@@ -0,0 +1,${newLines.size} @@")
                newLines.take(80).forEach { appendLine("+$it") }
                if (newLines.size > 80) appendLine("... (+${newLines.size - 80} lines)")
            }.trimEnd()
        }
        if (before != null && after == null) {
            return buildString {
                appendLine("--- a/$filePath")
                appendLine("+++ /dev/null")
                appendLine("@@ -1,${oldLines.size} +0,0 @@")
                oldLines.take(80).forEach { appendLine("-$it") }
            }.trimEnd()
        }
        // Compact LCS-lite line diff window
        var prefix = 0
        while (prefix < oldLines.size && prefix < newLines.size && oldLines[prefix] == newLines[prefix]) {
            prefix++
        }
        var suffix = 0
        while (
            suffix < (oldLines.size - prefix) &&
            suffix < (newLines.size - prefix) &&
            oldLines[oldLines.size - 1 - suffix] == newLines[newLines.size - 1 - suffix]
        ) {
            suffix++
        }

        val oldStart = (prefix - 2).coerceAtLeast(0)
        val oldEnd = (oldLines.size - suffix + 2).coerceAtMost(oldLines.size)
        val newEnd = (newLines.size - suffix + 2).coerceAtMost(newLines.size)

        return buildString {
            appendLine("--- a/$filePath")
            appendLine("+++ b/$filePath")
            appendLine("@@ -${oldStart + 1},${(oldEnd - oldStart).coerceAtLeast(0)} +${oldStart + 1},${(newEnd - oldStart).coerceAtLeast(0)} @@")
            for (i in oldStart until prefix) {
                appendLine(" ${oldLines[i]}")
            }
            for (i in prefix until (oldLines.size - suffix)) {
                appendLine("-${oldLines[i]}")
            }
            for (i in prefix until (newLines.size - suffix)) {
                appendLine("+${newLines[i]}")
            }
            val tailStartOld = oldLines.size - suffix
            for (i in tailStartOld until oldEnd) {
                if (i in oldLines.indices) appendLine(" ${oldLines[i]}")
            }
        }.trimEnd()
    }
}
