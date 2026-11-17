package com.forgemind.app.bridge

import java.io.File
import java.io.RandomAccessFile

/** A PID alone is not an identity: Android/Linux can reuse it after a command exits. */
object CommandTaskProcess {
    fun identity(pid: String): String? {
        if (!validPid(pid)) return null
        return runCatching {
            identityFromProc(File("/proc/$pid/stat").readText(),
                File("/proc/sys/kernel/random/boot_id").readText().trim())
        }.getOrNull()
    }

    internal fun identityFromProc(stat: String, boot: String): String? {
        val fields = stat.substringAfterLast(") ", "").trim().split(Regex("\\s+"))
        if (fields.size < 20 || fields[0] in setOf("Z", "X") || !fields[19].all(Char::isDigit) ||
            !boot.matches(Regex("[0-9a-f-]{36}"))) return null
        return "$boot:${fields[19]}"
    }

    fun localSnapshot(task: CommandTaskRecord, lines: Int): CommandTaskSnapshot {
        val exit = task.statusFile?.let { path -> runCatching { File(path).readText().trim().toIntOrNull() }.getOrNull() }
        val current = identity(task.pid)
        val state = when {
            exit != null -> if (task.cancelled) CommandTaskState.CANCELLED else CommandTaskState.COMPLETED
            task.cancelled && current != task.processIdentity -> CommandTaskState.CANCELLED
            task.processIdentity == null -> CommandTaskState.UNKNOWN
            current == null && File("/proc/${task.pid}").exists() -> CommandTaskState.UNKNOWN
            current != task.processIdentity -> CommandTaskState.LOST
            else -> CommandTaskState.RUNNING
        }
        return CommandTaskSnapshot(task.id, state, exit, tail(File(task.logFile), lines),
            detail = when (state) {
                CommandTaskState.LOST -> "The original process disappeared without a recorded exit code"
                CommandTaskState.UNKNOWN -> "Process identity was unavailable at launch; it cannot be safely controlled"
                else -> null
            }, workDir = task.workDir, backend = task.backend, serverId = task.serverId)
    }

    internal fun tail(file: File, lines: Int): String = runCatching {
        RandomAccessFile(file, "r").use { input ->
            val size = input.length().coerceAtMost(32_768).toInt()
            input.seek(input.length() - size)
            val bytes = ByteArray(size)
            input.readFully(bytes)
            String(bytes, Charsets.UTF_8).lineSequence().toList().takeLast(lines.coerceIn(1, 100)).joinToString("\n")
        }
    }.getOrDefault("")

    fun validPid(pid: String) = pid.matches(Regex("[0-9]+")) && (pid.toLongOrNull() ?: 0) > 1

    /** POSIX sh; Linux process start ticks plus boot ID. A zombie is no longer running. */
    val identityFunction: String = """
        fm_identity() {
          fm_stat=${'$'}(cat "/proc/${'$'}1/stat" 2>/dev/null) || return 1
          fm_rest=${'$'}{fm_stat##*) }
          set -- ${'$'}fm_rest
          [ "${'$'}1" != Z ] && [ "${'$'}1" != X ] || return 1
          [ "${'$'}#" -ge 20 ] || return 1
          shift 19
          fm_ticks=${'$'}1
          case "${'$'}fm_ticks" in ''|*[!0-9]*) return 1;; esac
          fm_boot=${'$'}(cat /proc/sys/kernel/random/boot_id 2>/dev/null) || return 1
          printf '%s:%s' "${'$'}fm_boot" "${'$'}fm_ticks"
        }
    """.trimIndent()

    fun probeScript(task: CommandTaskRecord, lines: Int): String {
        require(validPid(task.pid)) { "Invalid recorded process ID" }
        val status = task.statusFile ?: error("Task has no completion record")
        val expected = TermuxBridge.shellSingleQuote(task.processIdentity.orEmpty())
        return identityFunction + "\n" + """
            fm_exit=${'$'}(cat ${TermuxBridge.shellSingleQuote(status)} 2>/dev/null)
            case "${'$'}fm_exit" in ''|*[!0-9-]*) fm_exit=;; esac
            fm_now=${'$'}(fm_identity ${task.pid})
            if [ -n "${'$'}fm_exit" ]; then fm_state=COMPLETED
            elif [ ! -d /proc/${task.pid} ]; then fm_state=LOST
            elif [ -z "${'$'}fm_now" ]; then fm_state=UNKNOWN
            elif [ -z $expected ]; then fm_state=UNKNOWN
            elif [ "${'$'}fm_now" = $expected ]; then fm_state=RUNNING
            else fm_state=LOST; fi
            printf '__FORGEMIND_TASK_STATE__:%s:%s\n' "${'$'}fm_state" "${'$'}fm_exit"
            tail -c 32768 ${TermuxBridge.shellSingleQuote(task.logFile)} 2>/dev/null | tail -n ${lines.coerceIn(1, 100)}
        """.trimIndent()
    }

    /** Every signal is preceded by an identity check. Missing identity means no signal is sent. */
    fun stopScript(task: CommandTaskRecord): String {
        require(validPid(task.pid)) { "Invalid recorded process ID" }
        val identity = task.processIdentity?.takeIf { it.matches(Regex("[0-9a-f-]{36}:[0-9]+")) }
            ?: error("Process identity was not recorded; refusing to stop an unverified PID")
        val expected = TermuxBridge.shellSingleQuote(identity)
        return identityFunction + "\n" + """
            [ "${'$'}(fm_identity ${task.pid})" = $expected ] || { echo 'Original task process is no longer present'; exit 4; }
            pkill -TERM -P ${task.pid} 2>/dev/null || true
            if [ "${'$'}(fm_identity ${task.pid})" = $expected ]; then kill -TERM ${task.pid} 2>/dev/null || true; fi
            fm_i=0
            while [ "${'$'}(fm_identity ${task.pid})" = $expected ] && [ "${'$'}fm_i" -lt 10 ]; do
              sleep 0.1; fm_i=${'$'}((fm_i+1))
            done
            if [ "${'$'}(fm_identity ${task.pid})" = $expected ]; then kill -KILL ${task.pid} 2>/dev/null || true; fi
            fm_i=0
            while [ "${'$'}(fm_identity ${task.pid})" = $expected ] && [ "${'$'}fm_i" -lt 10 ]; do
              sleep 0.1; fm_i=${'$'}((fm_i+1))
            done
            [ "${'$'}(fm_identity ${task.pid})" != $expected ] || { echo 'Task process did not stop'; exit 5; }
            echo 'Stopped the recorded task process'
        """.trimIndent()
    }
}
