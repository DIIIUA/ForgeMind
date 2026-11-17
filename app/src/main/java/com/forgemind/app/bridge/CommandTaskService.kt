package com.forgemind.app.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** The same task API for the shared Termux session, embedded shell, SSH and RUN_COMMAND. */
class CommandTaskService(private val bridge: TermuxBridge) {
    val store = CommandTaskStore(File(bridge.appContext.filesDir, "command-tasks"))
    val controller = CommandTaskController(store, ::inspect, ::stop)

    fun track(start: AsyncCommandStart): CommandTaskRecord = store.create(
        backend = if (start.pid.startsWith("tmux:")) CommandTaskBackend.SHARED_TERMUX
            else start.backend ?: error("Background task transport was not recorded"),
        pid = start.pid, logFile = start.logFile, workDir = start.workDir,
        statusFile = start.statusFile, processIdentity = start.processIdentity,
        serverId = start.serverId, serverFingerprint = start.serverFingerprint
    )

    fun track(start: LocalAsyncJob): CommandTaskRecord = store.create(
        backend = CommandTaskBackend.EMBEDDED, pid = start.pid.toString(),
        logFile = start.logFile.absolutePath, workDir = start.workDir,
        statusFile = start.statusFile?.absolutePath, processIdentity = start.processIdentity
    )

    private suspend fun inspect(task: CommandTaskRecord, lines: Int): CommandTaskSnapshot = withContext(Dispatchers.IO) {
        when (task.backend) {
            CommandTaskBackend.EMBEDDED -> CommandTaskProcess.localSnapshot(task, lines)
            CommandTaskBackend.SHARED_TERMUX -> {
                val raw = bridge.checkCommandStatus(task.pid, task.logFile, lines)
                val header = raw.lineSequence().firstOrNull().orEmpty()
                val state = when (header) {
                    "__RUNNING__" -> CommandTaskState.RUNNING
                    "__FINISHED__" -> if (task.cancelled) CommandTaskState.CANCELLED else CommandTaskState.COMPLETED
                    "__LOST__" -> if (task.cancelled) CommandTaskState.CANCELLED else CommandTaskState.LOST
                    else -> CommandTaskState.UNKNOWN
                }
                // Only the executor's appended final status is authoritative, not a line in stdout.
                val exit = if (header == "__FINISHED__") raw.lineSequence().lastOrNull()
                    ?.removePrefix("__FORGEMIND_EXIT__:")?.toIntOrNull() else null
                CommandTaskSnapshot(task.id, state, exit,
                    if (state == CommandTaskState.UNKNOWN) "" else raw.substringAfter('\n', "").takeLast(32_768),
                    if (state == CommandTaskState.UNKNOWN) raw.take(1000) else null,
                    task.workDir, task.backend, task.serverId)
            }
            CommandTaskBackend.SSH, CommandTaskBackend.TERMUX_SERVICE -> {
                val result = bridge.runTrackedTaskControl(task, CommandTaskProcess.probeScript(task, lines))
                check(result.error == null && !result.timedOut && result.exitCode == 0) {
                    result.error ?: result.stderr.ifBlank { "Task status query did not complete" }
                }
                val marker = "__FORGEMIND_TASK_STATE__:"
                val output = result.stdout.lineSequence().toList()
                val index = output.indexOfFirst { it.startsWith(marker) }
                check(index >= 0) { "Task status reply is missing its metadata" }
                val fields = output[index].removePrefix(marker).split(':', limit = 2)
                var state = CommandTaskState.valueOf(fields[0])
                if (task.cancelled && state in setOf(CommandTaskState.COMPLETED, CommandTaskState.LOST)) state = CommandTaskState.CANCELLED
                CommandTaskSnapshot(task.id, state, fields.getOrNull(1)?.toIntOrNull(),
                    output.drop(index + 1).joinToString("\n").takeLast(32_768),
                    when (state) {
                        CommandTaskState.LOST -> "Original process disappeared without a recorded exit code"
                        CommandTaskState.UNKNOWN -> "Process identity is unavailable; cancellation is disabled"
                        else -> null
                    }, task.workDir, task.backend, task.serverId)
            }
        }
    }

    private suspend fun stop(task: CommandTaskRecord): CommandTaskStopResult = withContext(Dispatchers.IO) {
        if (task.backend == CommandTaskBackend.SHARED_TERMUX) {
            val result = bridge.killCommand(task.pid)
            check(result.error == null && result.exitCode == 0) { result.error ?: result.stderr }
            return@withContext if (result.stdout == "Already finished") CommandTaskStopResult.ALREADY_FINISHED else CommandTaskStopResult.STOPPED
        }
        val script = CommandTaskProcess.stopScript(task)
        val result = if (task.backend == CommandTaskBackend.EMBEDDED) {
            val capture = File.createTempFile("task-stop-", ".log", bridge.appContext.cacheDir)
            var process: Process? = null
            try {
                process = ProcessBuilder("/system/bin/sh", "-c", script).redirectErrorStream(true).redirectOutput(capture).start()
                check(process.waitFor(8, TimeUnit.SECONDS)) { "Task stop helper timed out; inspect status before retrying" }
                CommandExecResult(capture.readText(), "", process.exitValue())
            } finally {
                process?.takeIf { it.isAlive }?.destroyForcibly()
                capture.delete()
            }
        } else bridge.runTrackedTaskControl(task, script)
        if (result.exitCode == 4) {
            val current = inspect(task, 1)
            if (current.finished) return@withContext CommandTaskStopResult.ALREADY_FINISHED
        }
        check(result.error == null && !result.timedOut && result.exitCode == 0) {
            result.error ?: result.stderr.ifBlank { result.stdout.ifBlank { "Task cancellation could not be confirmed" } }
        }
        CommandTaskStopResult.STOPPED
    }
}
