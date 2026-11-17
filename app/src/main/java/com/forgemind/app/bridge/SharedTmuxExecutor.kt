package com.forgemind.app.bridge

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

/** Each invocation owns one window in the session shared with the live UI and Termux. */
class SharedTmuxExecutor(private val context: Context) {
    private val mutex = Mutex()

    suspend fun execute(command: String, workDir: String, timeoutSeconds: Int): CommandExecResult =
        withContext(Dispatchers.IO) { mutex.withLock {
            SharedTermuxRuntime.requireReady(context)
            val session = SharedTermuxRuntime.SESSION
            // Created by Termux's service, never forked from ForgeMind (see SharedTermuxRuntime).
            if (!SharedTermuxRuntime.ensureSession(context)) {
                return@withLock CommandExecResult("", "", -1,
                    error = "Termux did not start the shared session '$session'. Open Termux once (it may still be installing tmux) and retry.")
            }
            // Retain the last result on screen until another command starts, without accumulating windows.
            control("list-panes", "-s", "-t", session, "-F", "#{pane_id} #{pane_dead} #{@forgemind-run}")
                .second.lineSequence().forEach { line ->
                    val parts = line.trim().split(' ')
                    if (parts.size == 3 && parts[1] == "1" && parts[2].startsWith("forge-")) {
                        control("kill-pane", "-t", parts[0])
                    }
                }
            val token = "forge-${UUID.randomUUID()}"
            val dir = File(context.cacheDir, "tmux-runs/$token").apply { mkdirs() }
            val script = File(dir, "command.sh").apply { writeText(command + "\n") }
            val driver = File(dir, "driver.sh")
            val output = File(dir, "stdout")
            val errors = File(dir, "stderr")
            val status = File(dir, "status")
            val ready = File(dir, "ready")
            val cancelled = File(dir, "cancelled")
            driver.writeText(buildDriver(script, output, errors, status, ready, cancelled,
                workDir.ifBlank { SharedTermuxRuntime.HOME }))
            var pane: String? = null
            var completed = false
            try {
                // Finish allocation even if cancelled, so cleanup always knows the exact owned pane.
                withContext(NonCancellable) {
                    val made = control("new-window", "-d", "-P", "-F", "#{pane_id}",
                        "-t", "$session:", "-n", "forgemind-agent",
                        "${SharedTermuxRuntime.BASH} ${TermuxBridge.shellSingleQuote(driver.absolutePath)}")
                    check(made.first == 0) { "Could not create agent terminal: ${made.second}" }
                    pane = made.second.trim().also { check(it.matches(Regex("%[0-9]+"))) }
                    check(control("set-option", "-p", "-t", pane!!, "@forgemind-run", token).first == 0)
                    check(control("set-option", "-w", "-t", pane!!, "remain-on-exit", "on").first == 0)
                }
                check(control("select-window", "-t", pane!!).first == 0)
                ready.writeText("start")
                val code = withTimeoutOrNull(timeoutSeconds.coerceIn(1, 600) * 1000L) {
                    var exit: Int?
                    do {
                        exit = readExit(status)
                        if (exit == null) delay(50)
                    } while (exit == null)
                    exit
                }
                if (code == null) stopOwnedPane(pane!!, token, status, cancelled)
                completed = code != null
                CommandExecResult(readBounded(output), readBounded(errors), code ?: -1,
                    timedOut = code == null,
                    error = if (code == null) "Shared terminal command timed out (${timeoutSeconds}s)" else null)
            } finally {
                withContext(NonCancellable) {
                    if (!completed) {
                        cancelled.writeText("cancel")
                        pane?.let { stopOwnedPane(it, token, status, cancelled) }
                    }
                    // The exited pane keeps its screen; scripts and captured output are invocation-local.
                    if (pane == null || !isOwnedAndAlive(pane!!, token)) {
                        listOf(script, driver, output, errors, status, ready, cancelled).forEach { it.delete() }
                        dir.delete()
                    }
                }
            }
        } }

    /**
     * Start [command] in its own window of the shared session and return at once. The window shows
     * the output live; the same output and the exit code go to a log file read directly by ForgeMind.
     */
    suspend fun startAsync(command: String, workDir: String): AsyncCommandStart = withContext(Dispatchers.IO) {
        mutex.withLock {
            SharedTermuxRuntime.requireReady(context)
            check(SharedTermuxRuntime.ensureSession(context)) {
                "Termux did not start the shared session '${SharedTermuxRuntime.SESSION}'"
            }
            val token = "forge-bg-${UUID.randomUUID()}"
            val dir = File(context.filesDir, "tmux-async/$token").apply { mkdirs() }
            val script = File(dir, "command.sh").apply { writeText(command + "\n") }
            val log = File(dir, "output.log").apply { writeText("") }
            val status = File(dir, "status")
            val ready = File(dir, "ready")
            val cancelled = File(dir, "cancelled")
            val cwd = workDir.ifBlank { SharedTermuxRuntime.HOME }
            fun q(file: File) = TermuxBridge.shellSingleQuote(file.absolutePath)
            val driver = File(dir, "driver.sh").apply {
                writeText(buildDriver(script, log, File(dir, "stderr"), status, ready, cancelled, cwd))
            }
            var pane: String? = null
            var started = false
            try {
                withContext(NonCancellable) {
                    val made = control("new-window", "-d", "-P", "-F", "#{pane_id}", "-t", "${SharedTermuxRuntime.SESSION}:",
                        "-n", "agent-bg", "${SharedTermuxRuntime.BASH} ${q(driver)}")
                    check(made.first == 0) { "Could not create a background window: ${made.second}" }
                    pane = made.second.trim().also { check(it.matches(Regex("%[0-9]+"))) }
                    check(control("set-option", "-p", "-t", pane!!, "@forgemind-run", token).first == 0)
                    check(control("set-option", "-w", "-t", pane!!, "remain-on-exit", "on").first == 0)
                }
                check(control("select-window", "-t", pane!!).first == 0)
                ready.writeText("start")
                started = true
            } finally {
                if (!started) withContext(NonCancellable) {
                    cancelled.writeText("cancel")
                    pane?.let { stopOwnedPane(it, token, status, cancelled) }
                }
            }
            AsyncCommandStart(pid = "tmux:$token", logFile = log.absolutePath, workDir = cwd)
        }
    }

    /** A missing pane is reported as lost, never as an indefinitely running command. */
    suspend fun asyncStatus(token: String, logFile: String, tailLines: Int): String {
        val dir = asyncDirectory(token) ?: return "Status check failed: invalid command ID"
        val log = File(dir, "output.log")
        if (File(logFile).canonicalFile != log.canonicalFile) return "Status check failed: command/log mismatch"
        if (!log.isFile) return "Status check failed: log file not found ($logFile)"
        fun tail(file: File): String = runCatching {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val start = (raf.length() - 512 * 1024).coerceAtLeast(0)
                raf.seek(start)
                val bytes = ByteArray((raf.length() - start).toInt())
                raf.readFully(bytes)
                String(bytes, Charsets.UTF_8)
            }
        }.getOrDefault("")
        var code = readExit(File(dir, "status"))
        var lost = false
        if (code == null) {
            val panes = control("list-panes", "-s", "-t", SharedTermuxRuntime.SESSION,
                "-F", "#{pane_dead} #{@forgemind-run}")
            code = readExit(File(dir, "status")) // Completion can race the pane listing.
            if (code == null && panes.first != 0) {
                return "Status check failed: the shared terminal is unavailable; command state is unknown"
            }
            lost = code == null && !panes.second.lineSequence().any { it.trim() == "0 $token" }
        }
        val lines = (tail(log) + tail(File(dir, "stderr"))).lines()
        return (if (lost) "__LOST__\nTerminal window disappeared before an exit code was recorded."
                else if (code != null) "__FINISHED__" else "__RUNNING__") + "\n" +
            lines.takeLast(tailLines.coerceIn(10, 300)).joinToString("\n") +
            if (code != null) "\n__FORGEMIND_EXIT__:$code" else ""
    }

    /** Ctrl+C, then close the window that runs this background command. */
    suspend fun killAsync(token: String): CommandExecResult = withContext(Dispatchers.IO) {
        val dir = asyncDirectory(token)?.takeIf { it.isDirectory }
            ?: return@withContext CommandExecResult("", "Invalid background command", 1)
        val status = File(dir, "status")
        if (readExit(status) != null) return@withContext CommandExecResult("Already finished", "", 0)
        val listing = control("list-panes", "-s", "-t", SharedTermuxRuntime.SESSION,
            "-F", "#{pane_id} #{pane_dead} #{@forgemind-run}").second
        val pane = listing.lineSequence().map { it.trim().split(' ') }
            .firstOrNull { it.size == 3 && it[2] == token }?.get(0)
            ?: return@withContext CommandExecResult("", "No window for $token", 1)
        withContext(NonCancellable) {
            stopOwnedPane(pane, token, status, File(dir, "cancelled"))
            if (readExit(status) == null) status.writeText("130")
        }
        CommandExecResult("Stopped background window $token", "", 0)
    }

    private fun asyncDirectory(token: String): File? =
        token.takeIf { it.matches(Regex("forge-bg-[a-f0-9-]{36}")) }
            ?.let { File(context.filesDir, "tmux-async/$it") }

    private suspend fun control(vararg args: String): Pair<Int, String> {
        val capture = File.createTempFile("tmux-control-", ".log", context.cacheDir)
        var process: Process? = null
        try {
            val builder = ProcessBuilder(listOf("${SharedTermuxRuntime.PREFIX}/bin/tmux") + args)
                .directory(File(SharedTermuxRuntime.HOME)).redirectErrorStream(true).redirectOutput(capture)
            builder.environment().apply { clear(); putAll(SharedTermuxRuntime.environment()) }
            process = builder.start()
            val running = process
            val code = withTimeoutOrNull(10_000) {
                while (running.isAlive) delay(20)
                running.exitValue()
            } ?: -1
            return code to readBounded(capture)
        } finally {
            process?.takeIf { it.isAlive }?.destroyForcibly()
            capture.delete()
        }
    }

    private suspend fun isOwnedAndAlive(pane: String, token: String): Boolean {
        val state = control("display-message", "-p", "-t", pane, "#{pane_dead} #{@forgemind-run}")
        return state.first == 0 && state.second.trim() == "0 $token"
    }

    private suspend fun stopOwnedPane(pane: String, token: String, status: File, cancelled: File) {
        cancelled.writeText("cancel")
        if (!isOwnedAndAlive(pane, token)) return
        control("send-keys", "-t", pane, "C-c")
        repeat(20) {
            if (readExit(status) != null || !isOwnedAndAlive(pane, token)) return
            delay(50)
        }
        if (isOwnedAndAlive(pane, token)) control("kill-pane", "-t", pane)
    }

    companion object {
        internal fun buildDriver(script: File, output: File, errors: File, status: File,
                                 ready: File, cancelled: File, cwd: String): String {
            fun q(file: File) = TermuxBridge.shellSingleQuote(file.absolutePath)
            val onExit = "rc=${'$'}?; trap - EXIT; exec 1>&3 2>&4; wait; " +
                "printf '%s' \"${'$'}rc\" > ${q(status)}"
            return """
                exec 3>&1 4>&2
                trap ${TermuxBridge.shellSingleQuote(onExit)} EXIT
                for ((attempt=0; attempt<300; attempt++)); do
                    [[ -f ${q(cancelled)} ]] && exit 130
                    [[ -f ${q(ready)} ]] && break
                    sleep 0.1
                done
                [[ -f ${q(ready)} && ! -f ${q(cancelled)} ]] || exit 130
                exec > >(tee ${q(output)}) 2> >(tee ${q(errors)} >&2)
                cd -- ${TermuxBridge.shellSingleQuote(cwd)} || exit
                ${SharedTermuxRuntime.BASH} ${q(script)}
            """.trimIndent() + "\n"
        }

        private fun readExit(file: File): Int? = runCatching { file.readText().trim().toIntOrNull() }.getOrNull()

        private fun readBounded(file: File): String = runCatching {
            file.bufferedReader().use { reader ->
                val result = StringBuilder()
                val chunk = CharArray(4096)
                while (result.length < 120_000) {
                    val n = reader.read(chunk, 0, minOf(chunk.size, 120_000 - result.length))
                    if (n < 0) break
                    result.append(chunk, 0, n)
                }
                if (reader.read() >= 0) result.append("\n[output truncated]")
                result.toString()
            }
        }.getOrDefault("")
    }
}
