package com.forgemind.app.setup

import java.io.IOException
import java.io.InputStream

internal data class AdbShellReply(val text: String, val exitCode: Int) {
    companion object {
        fun command(command: String, marker: String): String {
            require(marker.matches(Regex("FM_[a-f0-9]{32}")))
            return "($command); fm_exit=\$?; printf '\\n$marker:%s\\n' \"\$fm_exit\""
        }
        fun read(input: InputStream, marker: String): AdbShellReply {
            val completion = Regex("(?:^|\\r?\\n)" + Regex.escape(marker) + ":([0-9]{1,3})\\r?\\n")
            val output = java.io.ByteArrayOutputStream()
            val bytes = ByteArray(4096)
            while (output.size() < 64 * 1024) {
                val count = input.read(bytes, 0, minOf(bytes.size, 64 * 1024 - output.size()))
                if (count < 0) break
                output.write(bytes, 0, count)
                val text = output.toString("UTF-8")
                val end = completion.find(text)
                if (end != null) return AdbShellReply(text.substring(0, end.range.first), end.groupValues[1].toInt())
            }
            throw IOException("ADB command completion was not received")
        }
    }
}
