package com.forgemind.app.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class TerminalViewMode {
    WATCH_AGENT,
    MANUAL
}

data class AiTerminalState(
    val isAlive: Boolean = false,
    val isRunningCommand: Boolean = false,
    val pid: Long? = null,
    val workDir: String = "",
    val currentCommand: String? = null,
    val lastExitCode: Int? = null,
    val lastSource: String = "Embedded Shell",
    val usedSshFallback: Boolean = false,
    val commandCount: Int = 0,
    val outputLog: String = ""
)

data class EmbeddedExecResult(
    val stdout: String,
    val exitCode: Int,
    val timedOut: Boolean = false,
    val workDir: String = "",
    val pid: Long? = null
)

data class LocalAsyncJob(
    val id: String,
    val pid: Long,
    val logFile: File,
    val workDir: String,
    val command: String
)

/**
 * Persistent physical AI shell running inside the app (/system/bin/sh).
 * Preserves the working directory (cd), environment variables (export), and a live output feed
 * for the "Agent (Live)" terminal view even if SSH is unavailable or drops.
 */
class EmbeddedAiShell(
    private val defaultRootDirProvider: () -> File,
    private val shellBinary: String = "/system/bin/sh",
    private val dynamicEnvProvider: () -> Map<String, String> = { emptyMap() }
) {
    private val execMutex = Mutex()
    private val _state = MutableStateFlow(AiTerminalState())
    val state: StateFlow<AiTerminalState> = _state.asStateFlow()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var stdinWriter: BufferedWriter? = null

    @Volatile
    private var readerThread: Thread? = null

    @Volatile
    private var currentPid: Long? = null

    @Volatile
    private var activeWorkDir: File? = null

    private val waiters = ConcurrentHashMap<String, MarkerWaiter>()
    private val localAsyncJobs = ConcurrentHashMap<String, LocalAsyncJob>()
    private val logLock = Any()
    private val logBuffer = StringBuilder()

    private class MarkerWaiter(val token: String) {
        val output = StringBuilder()
        val deferred = CompletableDeferred<Pair<Int, String>>()
    }

    fun ensureStarted(workDir: File? = null): AiTerminalState {
        synchronized(this) {
            val proc = process
            if (proc != null && proc.isAlive) {
                return _state.value
            }
            val dir = (workDir ?: activeWorkDir ?: defaultRootDirProvider()).apply { mkdirs() }
            activeWorkDir = dir
            startProcessLocked(dir)
            return _state.value
        }
    }

    suspend fun execute(
        command: String,
        workDir: File? = null,
        timeoutSeconds: Int = 35,
        sourceTag: String = "Embedded Shell",
        usedSshFallback: Boolean = false
    ): EmbeddedExecResult = withContext(Dispatchers.IO) {
        execMutex.withLock {
            val baseDir = defaultRootDirProvider().apply { mkdirs() }
            val requestedDir = workDir?.also { it.mkdirs() }
            val startDir = requestedDir ?: activeWorkDir ?: baseDir

            synchronized(this@EmbeddedAiShell) {
                if (process?.isAlive != true) {
                    activeWorkDir = startDir
                    startProcessLocked(startDir)
                }
            }

            val proc = process ?: return@withContext EmbeddedExecResult(
                stdout = "Failed to start embedded shell ($shellBinary)",
                exitCode = -1,
                workDir = startDir.absolutePath
            )

            val token = UUID.randomUUID().toString().replace("-", "").take(12)
            val waiter = MarkerWaiter(token)
            waiters[token] = waiter

            val tmpDir = File(baseDir, ".tmp").apply { mkdirs() }
            ensureHelperBinInstalled(baseDir)
            val dynEnv = runCatching { dynamicEnvProvider() }.getOrDefault(emptyMap())
            val scriptFile = File(tmpDir, "fg_cmd_$token.sh")
            val scriptBody = buildString {
                dynEnv.forEach { (k, v) ->
                    if (k.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                        append("export ").append(k).append('=').append(shellQuote(v)).append('\n')
                    }
                }
                if (requestedDir != null && requestedDir.absolutePath != activeWorkDir?.absolutePath) {
                    append("cd ").append(shellQuote(requestedDir.absolutePath)).append('\n')
                }
                append(command)
                if (!command.endsWith('\n')) append('\n')
            }
            scriptFile.writeText(scriptBody, Charsets.UTF_8)

            val dirLabel = (requestedDir ?: activeWorkDir ?: baseDir).absolutePath
            appendLogLine("\n[${timestamp()}] [$sourceTag] $dirLabel $ $command")
            _state.value = _state.value.copy(
                isAlive = true,
                isRunningCommand = true,
                currentCommand = command,
                lastSource = sourceTag,
                usedSshFallback = usedSshFallback,
                workDir = dirLabel,
                commandCount = _state.value.commandCount + 1
            )

            val dispatchLine = buildString {
                append(". ").append(shellQuote(scriptFile.absolutePath)).append(" </dev/null; ")
                append("__fg_ec=\$?; ")
                append("rm -f ").append(shellQuote(scriptFile.absolutePath)).append(" 2>/dev/null; ")
                append("printf '\\n__FG_END_%s__:%d:%s\\n' '").append(token).append("' \"\$__fg_ec\" \"\$PWD\"\n")
            }

            val writeOk = runCatching {
                stdinWriter?.apply {
                    write(dispatchLine)
                    flush()
                } ?: error("stdin closed")
            }.isSuccess

            if (!writeOk) {
                waiters.remove(token)
                runCatching { scriptFile.delete() }
                destroyProcessLocked()
                val errText = "Embedded shell input stream closed, restarting..."
                appendLogLine("[shell] $errText")
                _state.value = _state.value.copy(isRunningCommand = false, isAlive = false, lastExitCode = -1)
                return@withContext EmbeddedExecResult(errText, -1, workDir = dirLabel, pid = currentPid)
            }

            val markerResult = withTimeoutOrNull(timeoutSeconds.coerceIn(1, 300) * 1000L) {
                waiter.deferred.await()
            }

            waiters.remove(token)
            runCatching { scriptFile.delete() }

            if (markerResult == null) {
                val partial = waiter.output.toString().trimEnd()
                val msg = buildString {
                    if (partial.isNotBlank()) appendLine(partial)
                    append("Command timed out after ${timeoutSeconds}s — embedded shell restarted.")
                }
                appendLogLine("[timeout ${timeoutSeconds}s -> restart]")
                restartLocked(activeWorkDir ?: startDir)
                _state.value = _state.value.copy(
                    isRunningCommand = false,
                    currentCommand = null,
                    lastExitCode = -1
                )
                return@withContext EmbeddedExecResult(
                    stdout = msg,
                    exitCode = -1,
                    timedOut = true,
                    workDir = activeWorkDir?.absolutePath ?: dirLabel,
                    pid = currentPid
                )
            }

            val (exitCode, pwdAfter) = markerResult
            if (pwdAfter.isNotBlank()) {
                val dirAfter = File(pwdAfter)
                if (dirAfter.isDirectory) {
                    activeWorkDir = dirAfter
                }
            }
            val rawOut = waiter.output.toString().trimEnd()
            appendLogLine("[exit $exitCode${if (pwdAfter.isNotBlank()) " • pwd=$pwdAfter" else ""}]")
            _state.value = _state.value.copy(
                isAlive = process?.isAlive == true,
                isRunningCommand = false,
                currentCommand = null,
                workDir = pwdAfter.ifBlank { activeWorkDir?.absolutePath ?: dirLabel },
                lastExitCode = exitCode
            )
            EmbeddedExecResult(
                stdout = rawOut,
                exitCode = exitCode,
                timedOut = false,
                workDir = _state.value.workDir,
                pid = currentPid
            )
        }
    }

    /**
     * Starts a background command inside the embedded physical shell (does not require SSH/Termux).
     */
    suspend fun startAsync(
        command: String,
        workDir: File? = null
    ): Result<LocalAsyncJob> = withContext(Dispatchers.IO) {
        runCatching {
            val baseDir = defaultRootDirProvider().apply { mkdirs() }
            val dir = (workDir ?: activeWorkDir ?: baseDir).apply { mkdirs() }
            val logsDir = File(baseDir, ".async_logs").apply { mkdirs() }
            val jobId = "local_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(4)}"
            val logFile = File(logsDir, "$jobId.log")
            val runnerScript = File(logsDir, "$jobId.run.sh")
            val exitTag = "__FORGEMIND_EXIT__"
            runnerScript.writeText(
                buildString {
                    append("cd ").append(shellQuote(dir.absolutePath)).append(" || exit 1\n")
                    append("{\n").append(command).append("\n} >> ").append(shellQuote(logFile.absolutePath)).append(" 2>&1\n")
                    append("ec=\$?\n")
                    append("printf '\\n").append(exitTag).append(":%s\\n' \"\$ec\" >> ").append(shellQuote(logFile.absolutePath)).append('\n')
                    append("rm -f ").append(shellQuote(runnerScript.absolutePath)).append(" 2>/dev/null\n")
                },
                Charsets.UTF_8
            )
            val launchCmd = "nohup $shellBinary ${shellQuote(runnerScript.absolutePath)} </dev/null >/dev/null 2>&1 & echo \$!"
            val startRes = execute(launchCmd, dir, timeoutSeconds = 10, sourceTag = "Embedded Shell Async")
            val pid = startRes.stdout.lineSequence().map { it.trim() }.lastOrNull { it.all(Char::isDigit) && it.isNotEmpty() }
                ?.toLongOrNull()
                ?: error("Failed to start background process: ${startRes.stdout}")

            val job = LocalAsyncJob(
                id = jobId,
                pid = pid,
                logFile = logFile,
                workDir = dir.absolutePath,
                command = command
            )
            localAsyncJobs[jobId] = job
            localAsyncJobs["$pid"] = job
            appendLogLine("[async started] id=$jobId pid=$pid log=${logFile.name}")
            job
        }
    }

    suspend fun checkAsyncStatus(idOrPid: String, logPath: String?, tailLines: Int = 60): String =
        withContext(Dispatchers.IO) {
            val job = localAsyncJobs[idOrPid]
            val pidNum = job?.pid ?: idOrPid.removePrefix("local_").substringBefore('_').toLongOrNull()
            val file = when {
                !logPath.isNullOrBlank() && File(logPath).exists() -> File(logPath)
                job != null && job.logFile.exists() -> job.logFile
                else -> null
            }
            val alive = pidNum != null && File("/proc/$pidNum").exists()
            val lines = file?.takeIf { it.exists() }?.readLines()?.takeLast(tailLines.coerceIn(10, 300)).orEmpty()
            buildString {
                appendLine("Process PID=${pidNum ?: idOrPid}: ${if (alive) "RUNNING" else "FINISHED"}")
                if (file != null) appendLine("Log: ${file.absolutePath}")
                if (lines.isNotEmpty()) {
                    appendLine("--- tail ---")
                    lines.forEach { appendLine(it) }
                }
            }.trimEnd()
        }

    suspend fun killAsync(idOrPid: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val job = localAsyncJobs[idOrPid]
            val pidNum = job?.pid ?: idOrPid.removePrefix("local_").substringBefore('_').toLongOrNull()
                ?: error("Invalid process ID/PID: $idOrPid")
            val res = execute("kill -TERM $pidNum 2>/dev/null; sleep 1; kill -9 $pidNum 2>/dev/null; echo Killed:$pidNum", timeoutSeconds = 8)
            appendLogLine("[async killed] pid=$pidNum")
            res.stdout.ifBlank { "Terminated process $pidNum" }
        }
    }

    /**
     * Appends commands executed over SSH/Termux into the shared Agent (Live) feed.
     */
    fun recordExternalExecution(
        sourceLabel: String,
        workDir: String?,
        command: String,
        output: String,
        exitCode: Int,
        usedSshFallback: Boolean = false
    ) {
        val dir = workDir?.ifBlank { "~" } ?: "~"
        appendLogLine("\n[${timestamp()}] [$sourceLabel] $dir $ $command")
        if (output.isNotBlank()) {
            output.trimEnd().lineSequence().forEach { appendLogLine(it) }
        }
        appendLogLine("[exit $exitCode]")
        _state.value = _state.value.copy(
            lastSource = sourceLabel,
            usedSshFallback = usedSshFallback,
            lastExitCode = exitCode,
            commandCount = _state.value.commandCount + 1
        )
    }

    fun restart() {
        synchronized(this) {
            val dir = (activeWorkDir ?: defaultRootDirProvider()).apply { mkdirs() }
            appendLogLine("\n[${timestamp()}] [Manual AI shell restart]")
            restartLocked(dir)
        }
    }

    fun clearLog() {
        synchronized(logLock) {
            logBuffer.clear()
            _state.value = _state.value.copy(outputLog = "")
        }
    }

    fun shutdown() {
        synchronized(this) {
            waiters.values.forEach { it.deferred.complete(-1 to "") }
            waiters.clear()
            destroyProcessLocked()
            _state.value = _state.value.copy(isAlive = false, isRunningCommand = false, pid = null, currentCommand = null)
        }
    }

    private fun restartLocked(dir: File) {
        waiters.values.forEach { it.deferred.complete(-1 to dir.absolutePath) }
        waiters.clear()
        destroyProcessLocked()
        startProcessLocked(dir)
    }

    private fun startProcessLocked(dir: File) {
        dir.mkdirs()
        val tmpDir = File(dir, ".tmp").apply { mkdirs() }
        val binDir = ensureHelperBinInstalled(dir)
        val pb = ProcessBuilder(shellBinary).directory(dir).redirectErrorStream(true)
        pb.environment().apply {
            put("HOME", dir.absolutePath)
            put("TMPDIR", tmpDir.absolutePath)
            put("TERM", "xterm-256color")
            val curPath = this["PATH"].orEmpty()
            val baseSystemPath = curPath.ifBlank { "/system/bin:/system/xbin:/vendor/bin:/apex/com.android.runtime/bin" }
            put("PATH", "${binDir.absolutePath}:/data/data/com.termux/files/usr/bin:$baseSystemPath")
            runCatching { dynamicEnvProvider() }.getOrDefault(emptyMap()).forEach { (k, v) ->
                put(k, v)
            }
        }
        val proc = pb.start()
        process = proc
        stdinWriter = BufferedWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8))
        activeWorkDir = dir

        val bootToken = "BOOT_${UUID.randomUUID().toString().take(8)}"
        runCatching {
            stdinWriter?.apply {
                write("echo '__FG_PID__:'\$\$\n")
                write("printf '__FG_BOOT_%s__:%s\\n' '$bootToken' \"\$PWD\"\n")
                flush()
            }
        }

        val reader = Thread({
            try {
                BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { br ->
                    var line: String?
                    while (br.readLine().also { line = it } != null) {
                        val rawLine = line ?: continue
                        if (rawLine.startsWith("__FG_PID__:")) {
                            val pidVal = rawLine.removePrefix("__FG_PID__:").trim().toLongOrNull()
                            if (pidVal != null) {
                                currentPid = pidVal
                                _state.value = _state.value.copy(pid = pidVal, isAlive = true)
                            }
                            continue
                        }
                        if (rawLine.startsWith("__FG_BOOT_${bootToken}__:")) {
                            val pwdVal = rawLine.removePrefix("__FG_BOOT_${bootToken}__:").trim()
                            _state.value = _state.value.copy(
                                isAlive = true,
                                workDir = pwdVal.ifBlank { dir.absolutePath }
                            )
                            continue
                        }

                        var matchedEnd = false
                        val iterator = waiters.entries.iterator()
                        while (iterator.hasNext()) {
                            val entry = iterator.next()
                            val prefix = "__FG_END_${entry.key}__:"
                            if (rawLine.startsWith(prefix)) {
                                val rest = rawLine.removePrefix(prefix)
                                val colonIdx = rest.indexOf(':')
                                val ec = if (colonIdx >= 0) rest.substring(0, colonIdx).toIntOrNull() ?: 0 else rest.toIntOrNull() ?: 0
                                val pwd = if (colonIdx >= 0) rest.substring(colonIdx + 1) else ""
                                entry.value.deferred.complete(ec to pwd)
                                matchedEnd = true
                                break
                            }
                        }
                        if (matchedEnd) continue

                        waiters.values.forEach { w ->
                            if (w.output.length < 256_000) {
                                w.output.appendLine(rawLine)
                            }
                        }
                        appendLogLine(rawLine)
                    }
                }
            } catch (_: Throwable) {
                // Stream closed
            } finally {
                waiters.values.forEach { w ->
                    if (!w.deferred.isCompleted) {
                        w.deferred.complete(-1 to (activeWorkDir?.absolutePath ?: ""))
                    }
                }
                _state.value = _state.value.copy(isAlive = false, isRunningCommand = false, currentCommand = null)
            }
        }, "ForgeMind-EmbeddedAiShell").apply {
            isDaemon = true
            start()
        }
        readerThread = reader

        if (logBuffer.isEmpty()) {
            appendLogLine("[${timestamp()}] Embedded physical AI shell started ($shellBinary @ ${dir.absolutePath})")
        }
        _state.value = _state.value.copy(
            isAlive = true,
            workDir = dir.absolutePath
        )
    }

    private fun destroyProcessLocked() {
        val pidToKill = currentPid
        runCatching { stdinWriter?.close() }
        stdinWriter = null
        val p = process
        process = null
        currentPid = null
        if (p != null) {
            if (pidToKill != null) {
                runCatching { Runtime.getRuntime().exec(arrayOf(shellBinary, "-c", "pkill -9 -P $pidToKill 2>/dev/null; kill -9 $pidToKill 2>/dev/null")) }
            }
            runCatching { p.destroy() }
            runCatching { p.waitFor(250, TimeUnit.MILLISECONDS) }
            runCatching { if (p.isAlive) p.destroyForcibly() }
        }
        runCatching { readerThread?.interrupt() }
        readerThread = null
    }

    private fun appendLogLine(line: String) {
        synchronized(logLock) {
            logBuffer.append(line).append('\n')
            val maxChars = 100_000
            if (logBuffer.length > maxChars) {
                val drop = logBuffer.length - 75_000
                logBuffer.delete(0, drop)
            }
            _state.value = _state.value.copy(outputLog = logBuffer.toString())
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    companion object {
        fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

        fun ensureHelperBinInstalled(rootDir: File): File {
            val binDir = File(rootDir, ".forgemind/bin").apply { mkdirs() }
            val fmMcp = File(binDir, "fm-mcp")
            if (!fmMcp.exists() || fmMcp.length() != FM_MCP_SCRIPT.toByteArray(Charsets.UTF_8).size.toLong()) {
                runCatching {
                    fmMcp.writeText(FM_MCP_SCRIPT, Charsets.UTF_8)
                    fmMcp.setExecutable(true, false)
                }
            }
            return binDir
        }

        private val FM_MCP_SCRIPT = """
            #!/system/bin/sh
            # ForgeMind Built-in Loopback MCP CLI (works in bare /system/bin/sh via nc or curl)
            set -u
            PORT="${'$'}{FORGEMIND_MCP_PORT:-8765}"
            URL="${'$'}{FORGEMIND_MCP_URL:-http://127.0.0.1:${'$'}{PORT}/mcp}"
            HEALTH_URL="http://127.0.0.1:${'$'}{PORT}/health"
            TOKEN="${'$'}{FORGEMIND_MCP_TOKEN:-}"

            http_get_health() {
              if command -v curl >/dev/null 2>&1; then
                curl -fsS "${'$'}HEALTH_URL"
              else
                printf 'GET /health HTTP/1.1\r\nHost: 127.0.0.1:%s\r\nConnection: close\r\n\r\n' "${'$'}PORT" \
                  | /system/bin/nc -w 10 127.0.0.1 "${'$'}PORT" | sed '1,/^\r\{0,1\}${'$'}/d'
              fi
            }

            http_post_rpc() {
              body="${'$'}1"
              if command -v curl >/dev/null 2>&1; then
                if [ -n "${'$'}TOKEN" ]; then
                  curl -fsS -X POST "${'$'}URL" -H "Content-Type: application/json" -H "Authorization: Bearer ${'$'}TOKEN" --data-raw "${'$'}body"
                else
                  curl -fsS -X POST "${'$'}URL" -H "Content-Type: application/json" --data-raw "${'$'}body"
                fi
              else
                len=${'$'}(printf '%s' "${'$'}body" | wc -c | tr -d ' \t\r\n')
                if [ -n "${'$'}TOKEN" ]; then
                  {
                    printf 'POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:%s\r\nContent-Type: application/json\r\nContent-Length: %s\r\nAuthorization: Bearer %s\r\nConnection: close\r\n\r\n' "${'$'}PORT" "${'$'}len" "${'$'}TOKEN"
                    printf '%s' "${'$'}body"
                  } | /system/bin/nc -w 30 127.0.0.1 "${'$'}PORT" | sed '1,/^\r\{0,1\}${'$'}/d'
                else
                  {
                    printf 'POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:%s\r\nContent-Type: application/json\r\nContent-Length: %s\r\nConnection: close\r\n\r\n' "${'$'}PORT" "${'$'}len"
                    printf '%s' "${'$'}body"
                  } | /system/bin/nc -w 30 127.0.0.1 "${'$'}PORT" | sed '1,/^\r\{0,1\}${'$'}/d'
                fi
              fi
            }

            cmd="${'$'}{1:-help}"
            case "${'$'}cmd" in
              health)
                http_get_health
                echo ""
                ;;
              tools)
                http_post_rpc '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
                echo ""
                ;;
              dom)
                args="${'$'}{2:-{}}"
                http_post_rpc "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"screen_read_windows\",\"arguments\":${'$'}args}}"
                echo ""
                ;;
              act)
                args="${'$'}{2:-{}}"
                http_post_rpc "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"screen_act_tag\",\"arguments\":${'$'}args}}"
                echo ""
                ;;
              batch)
                steps="${'$'}{2:-[]}"
                http_post_rpc "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"screen_act_batch\",\"arguments\":{\"steps\":${'$'}steps}}}"
                echo ""
                ;;
              shot)
                args="${'$'}{2:-{}}"
                http_post_rpc "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"screen_capture\",\"arguments\":${'$'}args}}"
                echo ""
                ;;
              call)
                tool="${'$'}{2:?Usage: fm-mcp call <tool_name> [args_json]}"
                args="${'$'}{3:-{}}"
                http_post_rpc "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"${'$'}tool\",\"arguments\":${'$'}args}}"
                echo ""
                ;;
              *)
                echo "Usage: fm-mcp <health|tools|dom [json]|act <json>|batch <steps_array_json>|shot [json]|call <tool> [json]>"
                ;;
            esac
        """.trimIndent() + "\n"
    }
}
