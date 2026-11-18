package com.forgemind.termuxbridge

import java.io.File

object BridgeEnvironment {
    private val ephemeral = setOf("TMUX", "TMUX_PANE", "SHLVL", "OLDPWD")
    fun clean(values: Map<String, String>) = values.filterKeys { it !in ephemeral }
    fun build(extra: Map<String, String> = emptyMap()): Map<String, String> {
        require(extra.size <= 128 && extra.entries.sumOf { it.key.length + it.value.length } <= 64_000)
        require(extra.all { (key,value) -> key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && '\u0000' !in value })
        val prefix = TermuxBridgeService.PREFIX
        return clean(System.getenv()).toMutableMap().apply {
            put("HOME", TermuxBridgeService.HOME); put("PREFIX", prefix)
            put("TMPDIR", "$prefix/tmp"); put("PATH", "$prefix/bin:/system/bin:/system/xbin")
            put("TERM", "xterm-256color"); put("COLORTERM", "truecolor"); put("LANG", "en_US.UTF-8")
            remove("LD_LIBRARY_PATH"); remove("LD_PRELOAD")
            if (File("$prefix/lib/libtermux-exec.so").isFile) put("LD_PRELOAD", "$prefix/lib/libtermux-exec.so")
            putAll(clean(extra))
        }
    }
}
