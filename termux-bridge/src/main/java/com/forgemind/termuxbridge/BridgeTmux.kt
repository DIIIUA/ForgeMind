package com.forgemind.termuxbridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Only this bridge's UUID names and ownership tags are accepted as management targets. */
class BridgeTmux(private val context: Context) {
    private val prefs = context.getSharedPreferences("sessions", Context.MODE_PRIVATE)
    private val owner = context.packageName
    val agentName: String = prefs.getString("agent-name", null) ?: ("fm-agent-" + UUID.randomUUID()).also {
        check(prefs.edit().putString("agent-name", it).commit())
    }
    companion object {
        const val HOME = TermuxBridgeService.HOME
        const val PREFIX = TermuxBridgeService.PREFIX
        const val BASH = "$PREFIX/bin/bash"
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        fun validName(name: String) = name.matches(Regex("forgemind-[a-f0-9-]{36}"))
        fun nextNumber(active: List<Int>, saved: Int): Int = active.maxOrNull()?.let { maxOf(2, saved, it + 1) } ?: 2
    }

    fun control(vararg args: String): Pair<Int, String> {
        check(File("$PREFIX/bin/tmux").canExecute()) { "Install tmux in Termux: pkg install tmux" }
        val capture = File.createTempFile("tmux-", ".txt", context.cacheDir)
        var process: Process? = null
        try {
            val builder = ProcessBuilder(listOf("$PREFIX/bin/tmux") + args).directory(File(HOME))
                .redirectErrorStream(true).redirectOutput(capture)
            builder.environment().apply { clear(); putAll(BridgeEnvironment.build()) }
            process = builder.start()
            check(process.waitFor(5, TimeUnit.SECONDS)) { "tmux control timed out; inspect state before retrying" }
            return process.exitValue() to capture.inputStream().use { input ->
                val bytes = ByteArray(64 * 1024)
                val size = input.read(bytes)
                if (size < 0) "" else String(bytes, 0, size, Charsets.UTF_8)
            }
        } finally { process?.takeIf { it.isAlive }?.destroyForcibly(); capture.delete() }
    }

    fun launchService(script: String, cwd: String = HOME, interactive: Boolean = false) {
        checkNotNull(context.startForegroundService(Intent("com.termux.service_execute").apply {
            setClassName("com.termux", "com.termux.app.TermuxService")
            data = Uri.Builder().scheme("com.termux.file").path(BASH).build()
            putExtra("com.termux.execute.arguments", arrayOf("-lc", script))
            putExtra("com.termux.execute.cwd", cwd)
            putExtra("com.termux.execute.background", !interactive)
            if (interactive) putExtra("com.termux.execute.session_action", "2")
        })) { "Termux service is unavailable" }
    }

    fun ownedAgent(): Boolean {
        val result = control("display-message", "-p", "-t", "=$agentName:", "#{session_name}\t#{@forgemind-owner}")
        if (result.first != 0) return false
        if (result.second.trimEnd('\r', '\n') == "$agentName\t") return false
        check(result.second.trim() == "$agentName\t$owner") { "Agent session ownership changed" }
        return true
    }

    @Synchronized fun ensureAgent() {
        if (ownedAgent()) return
        // tmux allocates the unique persisted name atomically. No filesystem lock can go stale,
        // and a duplicate/foreign name fails before its ownership tag could be changed.
        launchService(
            "tmux new-session -d -s $agentName -n shell -x 120 -y 32 -c $HOME $BASH -l || exit; " +
            "tmux set-option -t =$agentName: @forgemind-owner " + quote(owner) + " || exit; " +
            "while tmux has-session -t =$agentName 2>/dev/null; do sleep 5; done")
        repeat(60) { Thread.sleep(100); if (ownedAgent()) return }
        error("Termux startup is not confirmed. Reopen the terminal to inspect it.")
    }

    fun list(): List<Bundle> {
        val (code, text) = control("list-sessions", "-F",
            "#{session_id}\t#{session_name}\t#{@forgemind-owner}\t#{@forgemind-number}\t#{@forgemind-keep}")
        if (code != 0) {
            check(text.contains("no server running") || text.contains("no sessions") || text.contains("No such file")) { text }
            return emptyList()
        }
        return text.lineSequence().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size != 5 || p[2] != owner || !p[0].matches(Regex("\\$[0-9]+")) || !validName(p[1])) return@mapNotNull null
            val n = p[3].toIntOrNull()?.takeIf { it >= 2 } ?: return@mapNotNull null
            Bundle().apply { putString("id", p[0]); putString("name", p[1]); putInt("number", n); putBoolean("keep", p[4] == "1") }
        }.sortedBy { it.getInt("number") }.toList()
    }

    private fun requireOwned(request: Bundle): Bundle = list().singleOrNull {
        it.getString("id") == request.getString("id") && it.getString("name") == request.getString("name")
    } ?: error("Session identity or ownership changed; reopen the terminal")

    @Synchronized fun create(): Bundle {
        ensureAgent()
        val number = nextNumber(list().map { it.getInt("number") }, prefs.getInt("next-number", 2))
        check(prefs.edit().putInt("next-number", number + 1).commit())
        val name = "forgemind-" + UUID.randomUUID()
        launchService("tmux new-session -d -s $name -n shell -x 120 -y 32 -c $HOME $BASH -l || exit; " +
            "tmux set-option -t =$name: @forgemind-owner " + quote(owner) +
            " \\; set-option -t =$name: @forgemind-number $number \\; set-option -t =$name: @forgemind-keep 0 " +
            "\\; set-option -t =$name: detach-on-destroy on \\; set-option -t =$name: status-left '[$number] ' " +
            "|| { tmux kill-session -t =$name; exit; }; " +
            "while tmux has-session -t =$name 2>/dev/null; do sleep 5; done")
        repeat(60) { Thread.sleep(100); list().firstOrNull { it.getString("name") == name }?.let { return it } }
        error("Session creation is not confirmed. Reopen to inspect it; do not create it again automatically.")
    }

    @Synchronized fun execute(request: Bundle): Bundle = when (request.getString("action")) {
        "list" -> Bundle().apply { putParcelableArrayList("sessions", ArrayList(list())) }
        "create" -> create()
        "agent" -> { ensureAgent(); Bundle().apply { putString("name", agentName) } }
        "keep" -> requireOwned(request).apply {
            val keep = request.getBoolean("keep")
            check(control("set-option", "-t", getString("id") + ":", "@forgemind-keep", if (keep) "1" else "0").first == 0)
            putBoolean("keep", keep)
        }
        "close" -> { val owned = requireOwned(request); check(control("kill-session", "-t", owned.getString("id")!!).first == 0); Bundle() }
        "replace" -> {
            val old = requireOwned(request)
            val fresh = create()
            if (!old.getBoolean("keep")) execute(Bundle(request).apply { putString("action", "close") })
            fresh
        }
        "open" -> {
            val name = if (request.getString("name") == agentName) { check(ownedAgent()); agentName }
                else requireOwned(request).getString("name")!!
            launchService("exec tmux attach-session -t =" + quote(name), interactive = true)
            Bundle()
        }
        "select-shell" -> {
            val name = if (request.getString("name") == agentName) { check(ownedAgent()); agentName }
                else requireOwned(request).getString("name")!!
            check(control("select-window", "-t", "=$name:^").first == 0)
            Bundle()
        }
        else -> error("Unknown session operation")
    }
}
