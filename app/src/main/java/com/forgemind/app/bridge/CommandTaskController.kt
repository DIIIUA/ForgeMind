package com.forgemind.app.bridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
enum class CommandTaskState { RUNNING, COMPLETED, CANCELLED, LOST, UNKNOWN }

@Serializable
data class CommandTaskSnapshot(
    @SerialName("task_id") val taskId: String,
    val state: CommandTaskState,
    @SerialName("exit_code") val exitCode: Int? = null,
    val output: String = "",
    val detail: String? = null,
    @SerialName("workdir") val workDir: String = "",
    val backend: CommandTaskBackend,
    @SerialName("server_id") val serverId: String? = null,
    @SerialName("output_truncated") val outputTruncated: Boolean = false
) {
    val finished: Boolean get() = state in setOf(CommandTaskState.COMPLETED, CommandTaskState.CANCELLED, CommandTaskState.LOST)
}

@Serializable
data class CommandTaskWaitResult(
    val tasks: List<CommandTaskSnapshot>,
    @SerialName("timed_out") val timedOut: Boolean,
    @SerialName("all_finished") val allFinished: Boolean,
    val reason: String
)

enum class CommandTaskStopResult { STOPPED, ALREADY_FINISHED }

/** Waits and reads never cancel commands. The stop adapter must recheck process ownership itself. */
class CommandTaskController(
    val store: CommandTaskStore,
    private val inspect: suspend (CommandTaskRecord, Int) -> CommandTaskSnapshot,
    private val stop: suspend (CommandTaskRecord) -> CommandTaskStopResult
) {
    suspend fun status(id: String, lines: Int = 60): CommandTaskSnapshot = probe(store.get(id), lines)

    suspend fun cancel(id: String, lines: Int = 60): CommandTaskSnapshot {
        val task = store.get(id)
        val before = probe(task, lines)
        if (before.finished) return before
        check(before.state == CommandTaskState.RUNNING) { "Cannot verify this task's process; cancellation was not attempted. ${before.detail.orEmpty()}" }
        if (stop(task) == CommandTaskStopResult.STOPPED) store.markCancelled(id)
        return probe(store.get(id), lines)
    }

    suspend fun waitFor(ids: List<String>, timeoutMillis: Long, any: Boolean = false, lines: Int = 30): CommandTaskWaitResult {
        require(ids.isNotEmpty() && ids.size <= 8) { "Provide 1 to 8 task_ids" }
        val records = ids.distinct().map(store::get) // Validate every ID before inspecting anything.
        val budget = timeoutMillis.coerceIn(0, 30_000)
        val started = System.nanoTime()
        var latest = records.map { unknown(it, "Status has not been read within the wait budget") }
        fun result(timedOut: Boolean, reason: String) =
            CommandTaskWaitResult(latest, timedOut, latest.all { it.finished }, reason)
        while (true) {
            val remaining = budget - (System.nanoTime() - started) / 1_000_000
            if (budget > 0 && remaining <= 0) return result(true, "timeout")
            // timeout=0 means one status poll, with no extra waiting for command completion.
            val snapshots = withTimeoutOrNull(if (budget == 0L) 10_000 else remaining) {
                coroutineScope { records.map { task -> async { probe(task, lines) } }.awaitAll() }
            } ?: return result(true, "timeout")
            latest = snapshots
            if (if (any) latest.any { it.finished } else latest.all { it.finished }) return result(false, "finished")
            if (latest.any { it.state == CommandTaskState.UNKNOWN }) return result(false, "unavailable")
            if (budget == 0L) return result(false, "snapshot")
            delay(minOf(200L, (budget - (System.nanoTime() - started) / 1_000_000).coerceAtLeast(1)))
        }
    }

    private suspend fun probe(task: CommandTaskRecord, lines: Int): CommandTaskSnapshot = try {
        inspect(task, lines.coerceIn(1, 100)).let { result ->
            result.copy(output = result.output.takeLast(8000),
                outputTruncated = result.outputTruncated || result.output.length > 8000)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        unknown(task, failure.message ?: "Task status unavailable")
    }

    private fun unknown(task: CommandTaskRecord, detail: String) = CommandTaskSnapshot(
        task.id, CommandTaskState.UNKNOWN, detail = detail, workDir = task.workDir,
        backend = task.backend, serverId = task.serverId
    )
}
