package com.forgemind.app.bridge

import android.content.Context
import kotlinx.coroutines.delay
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ManagedTermuxSession(val id: String, val name: String, val number: Int, val keep: Boolean)

/** Only explicitly tagged ForgeMind sessions are managed here. Native Termux tabs are never targets. */
class ManagedTermuxSessions(private val context: Context) {
    private val prefs = context.getSharedPreferences("terminal-sessions", Context.MODE_PRIVATE)

    fun list(): List<ManagedTermuxSession> {
        val (code, output) = control("list-sessions", "-F",
            "#{session_id}\t#{session_name}\t#{@forgemind-owner}\t#{@forgemind-number}\t#{@forgemind-keep}")
        if (code != 0) {
            check(output.contains("no server running") || output.contains("no sessions") ||
                output.contains("No such file or directory")) { "Cannot list terminal sessions: $output" }
            return emptyList()
        }
        return output.lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 5 || parts[2] != context.packageName ||
                !parts[0].matches(Regex("\\$[0-9]+")) || !validName(parts[1])) return@mapNotNull null
            val number = parts[3].toIntOrNull()?.takeIf { it >= 2 } ?: return@mapNotNull null
            ManagedTermuxSession(parts[0], parts[1], number, parts[4] == "1")
        }.sortedBy { it.number }.toList()
    }

    suspend fun create(): ManagedTermuxSession {
        SharedTermuxRuntime.requireReady(context)
        // Starting the agent session also installs tmux when needed, via Termux's own service.
        check(SharedTermuxRuntime.ensureSession(context)) { "Termux session startup timed out" }
        val number = nextSessionNumber(list().map { it.number }, prefs.getInt("next-number", 2))
        check(prefs.edit().putInt("next-number", number + 1).commit()) { "Cannot save session number" }
        val name = "forgemind-${UUID.randomUUID()}"
        SharedTermuxRuntime.requestManagedSession(context, name, number)
        repeat(100) {
            list().firstOrNull { it.name == name }?.let { return it }
            delay(200)
        }
        // A late service launch is recovered by list() on the next entry, not blindly retried.
        error("Termux session startup timed out. Reopen the terminal to check it.")
    }

    fun setKeep(id: String, keep: Boolean, expectedName: String? = null): ManagedTermuxSession {
        val owned = requireOwned(id, expectedName)
        check(control("set-option", "-t", "${owned.id}:", "@forgemind-keep", if (keep) "1" else "0").first == 0)
        return owned.copy(keep = keep)
    }

    /** New session must be usable before the old one is closed. Retained sessions keep running. */
    suspend fun replace(id: String, expectedName: String? = null): ManagedTermuxSession {
        val old = requireOwned(id, expectedName)
        val fresh = create()
        if (!old.keep) close(old.id, old.name)
        return fresh
    }

    fun close(id: String, expectedName: String? = null) {
        val owned = requireOwned(id, expectedName)
        // An exact stable tmux ID AND our ownership tag are required, never a tab number/prefix.
        check(control("kill-session", "-t", owned.id).first == 0) { "Cannot close this session" }
    }

    private fun requireOwned(id: String, expectedName: String? = null): ManagedTermuxSession =
        list().singleOrNull { it.id == id && (expectedName == null || it.name == expectedName) }
            ?: error("This session is no longer managed by ForgeMind; reopen the terminal")

    private fun control(vararg args: String): Pair<Int, String> {
        val capture = File.createTempFile("sessions-", ".txt", context.cacheDir)
        var process: Process? = null
        try {
            val builder = ProcessBuilder(listOf("${SharedTermuxRuntime.PREFIX}/bin/tmux") + args)
                .directory(File(SharedTermuxRuntime.HOME)).redirectErrorStream(true).redirectOutput(capture)
            builder.environment().apply { clear(); putAll(SharedTermuxRuntime.environment()) }
            process = builder.start()
            check(process.waitFor(5, TimeUnit.SECONDS)) { "Termux session query timed out" }
            return process.exitValue() to capture.readText()
        } finally {
            process?.takeIf { it.isAlive }?.destroyForcibly()
            capture.delete()
        }
    }

    companion object {
        fun validName(name: String) = name.matches(Regex("forgemind-[a-f0-9-]{36}"))

        internal fun nextSessionNumber(activeNumbers: List<Int>, savedNext: Int): Int {
            // Labels restart at 2 only after all owned sessions have gone, including kept ones.
            // Ownership and close/restart targets use the UUID and tmux ID, never this label.
            val highestActive = activeNumbers.maxOrNull() ?: return 2
            return maxOf(2, savedNext, highestActive + 1)
        }
    }
}
