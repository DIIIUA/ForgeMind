package com.forgemind.app.bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
enum class CommandTaskBackend { SHARED_TERMUX, REMOTE_BRIDGE, EMBEDDED, SSH, TERMUX_SERVICE }

/** Routing is recorded at launch; changing the active environment must not redirect an old task. */
@Serializable
data class CommandTaskRecord(
    val id: String,
    val backend: CommandTaskBackend,
    val pid: String,
    val logFile: String,
    val workDir: String,
    val createdAt: Long,
    val statusFile: String? = null,
    val processIdentity: String? = null,
    val serverId: String? = null,
    val serverFingerprint: String? = null,
    val cancelled: Boolean = false
)

data class CommandTaskListing(
    val tasks: List<CommandTaskRecord>,
    val unreadableRecords: Int,
    val hasMore: Boolean
)

/** Private application metadata only: no shell command, credentials or captured output is copied. */
class CommandTaskStore(private val directory: File) {
    private val lock = locks.getOrPut(directory.canonicalPath) { Any() }

    fun create(
        backend: CommandTaskBackend, pid: String, logFile: String, workDir: String,
        statusFile: String? = null, processIdentity: String? = null,
        serverId: String? = null, serverFingerprint: String? = null
    ): CommandTaskRecord = synchronized(lock) {
        val taskId = if (backend == CommandTaskBackend.REMOTE_BRIDGE) "task-" + pid.removePrefix("bridge:") else "task-" + UUID.randomUUID()
        require(validId(taskId))
        if (File(directory, "$taskId.json").isFile) {
            return@synchronized get(taskId).also {
                require(it.backend == backend && it.pid == pid && it.logFile == logFile) { "Task identity mismatch" }
            }
        }
        val record = CommandTaskRecord(taskId, backend, pid, logFile,
            workDir, System.currentTimeMillis(), statusFile, processIdentity, serverId, serverFingerprint)
        write(record)
        record
    }

    fun get(id: String): CommandTaskRecord = synchronized(lock) {
        require(validId(id)) { "Invalid task_id. Use the ID returned by run_command_async or list_command_tasks." }
        val file = File(directory, "$id.json")
        require(file.isFile) { "Unknown task_id. Use list_command_tasks to recover saved tasks." }
        read(file).also { require(it.id == id) { "Task metadata does not match its ID" } }
    }

    fun list(limit: Int = 20, offset: Int = 0): CommandTaskListing = synchronized(lock) {
        var unreadable = 0
        val all = directory.listFiles().orEmpty().filter { it.extension == "json" && validId(it.nameWithoutExtension) }
            .mapNotNull { file ->
                runCatching { read(file).also { check(it.id == file.nameWithoutExtension) } }
                    .getOrElse { unreadable++; null }
            }.sortedWith(compareByDescending<CommandTaskRecord> { it.createdAt }.thenBy { it.id })
        val count = limit.coerceIn(1, 100)
        val start = offset.coerceAtLeast(0)
        CommandTaskListing(all.drop(start).take(count), unreadable, all.size.toLong() > start.toLong() + count)
    }

    fun markCancelled(id: String): CommandTaskRecord = synchronized(lock) {
        get(id).copy(cancelled = true).also(::write)
    }

    private fun read(file: File): CommandTaskRecord {
        check(file.length() <= 32 * 1024) { "Task metadata is too large" }
        return json.decodeFromString<CommandTaskRecord>(file.readText()).also(::validate)
    }

    private fun write(record: CommandTaskRecord) {
        validate(record)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create task metadata directory" }
        val temporary = File(directory, ".${record.id}-${UUID.randomUUID()}.tmp")
        try {
            temporary.writeText(json.encodeToString(record))
            val destination = File(directory, "${record.id}.json").toPath()
            try {
                Files.move(temporary.toPath(), destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temporary.delete() }
    }

    private fun validate(record: CommandTaskRecord) {
        require(validId(record.id) && record.pid.isNotBlank() && record.logFile.isNotBlank()) { "Invalid task metadata" }
        require(record.logFile.length <= 4096 && record.workDir.length <= 4096) { "Task path is too long" }
        if (record.backend == CommandTaskBackend.SSH) {
            require(!record.serverId.isNullOrBlank() && !record.serverFingerprint.isNullOrBlank()) { "Task server identity is missing" }
        }
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
        private val json = Json { ignoreUnknownKeys = true }
        fun validId(id: String) = id.matches(Regex("task-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
    }
}
