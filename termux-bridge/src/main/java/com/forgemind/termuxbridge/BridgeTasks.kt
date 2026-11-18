package com.forgemind.termuxbridge

import android.content.Context
import android.os.Bundle
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/** Persist before dispatch. Reusing a request ID inspects the original task, never launches again. */
class BridgeTasks(context: Context, private val tmux: BridgeTmux) {
    private val root = File(context.filesDir, "tasks").apply { mkdirs() }
    private fun directory(id: String): File {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "Invalid task ID" }
        return File(root, id)
    }
    private fun metadata(owner: Int, id: String): Pair<File, JSONObject> {
        val dir = directory(id)
        val json = JSONObject(File(dir, "request.json").readText())
        check(json.getInt("owner") == owner) { "Task belongs to another client" }
        return dir to json
    }
    private fun save(dir: File, json: JSONObject) {
        val file = AtomicFile(File(dir, "request.json"))
        val out = file.startWrite()
        try { out.write(json.toString().toByteArray()); file.finishWrite(out) }
        catch (failure: Exception) { file.failWrite(out); throw failure }
    }
    private fun exit(dir: File) = runCatching { File(dir, "status").readText().trim().toIntOrNull() }.getOrNull()
    private fun tail(file: File): String = if (!file.isFile) "" else RandomAccessFile(file, "r").use {
        val size = minOf(it.length(), 32_768L).toInt()
        it.seek(it.length() - size)
        val data = ByteArray(size); it.readFully(data); String(data, Charsets.UTF_8)
    }
    private fun ownedPane(dir: File, id: String, runningOnly: Boolean = false): String? {
        val pane = File(dir, "pane").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.matches(Regex("%[0-9]+")) } ?: return null
        val (code, text) = tmux.control("display-message", "-p", "-t", pane,
            "#{session_name}\t#{@forgemind-owner}\t#{@forgemind-task}\t#{pane_dead}")
        val fields = text.trim().split('\t')
        return pane.takeIf { code == 0 && fields.size == 4 && fields[0] == tmux.agentName &&
            fields[1] == "com.forgemind.termuxbridge" && fields[2] == id && (!runningOnly || fields[3] == "0") }
    }

    @Synchronized fun start(owner: Int, request: Bundle): Bundle {
        val id = request.getString("id") ?: error("A stable request ID is required")
        val dir = directory(id)
        val command = request.getString("command").orEmpty()
        require(command.isNotBlank() && command.length <= 64_000 && '\u0000' !in command)
        val cwd = BridgeWorkingDirectory.resolve(request.getString("cwd"))
        require(cwd.isDirectory)
        val extra = request.getBundle("env")?.let { b -> b.keySet().associateWith { b.getString(it) ?: error("Environment must contain strings") } }.orEmpty()
        val env = BridgeEnvironment.build(extra)
        val fingerprintInput = JSONObject().put("command", command).put("cwd", cwd.path)
            .put("env", JSONObject(extra.toSortedMap())).toString()
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(fingerprintInput.toByteArray()).joinToString("") { "%02x".format(it) }
        if (File(dir, "request.json").isFile) {
            val (_, existing) = metadata(owner, id)
            require(existing.getString("fingerprint") == fingerprint) { "Request ID was already used for another command" }
            return snapshot(owner, id)
        }
        check(root.listFiles().orEmpty().count { it.isDirectory && File(it, "request.json").isFile && exit(it) == null } < 16) {
            "Too many unfinished tasks; inspect or cancel existing tasks first"
        }
        tmux.ensureAgent() // No user command dispatched yet.
        root.listFiles().orEmpty().filter { it.isDirectory && exit(it) != null }.forEach { previous ->
            ownedPane(previous, previous.name)?.let { tmux.control("kill-pane", "-t", it) }
        }
        check(dir.mkdirs() || dir.isDirectory)
        fun q(name: String) = BridgeTmux.quote(File(dir, name).path)
        File(dir, "command.sh").writeText(command + "\n")
        val exports = env.entries.joinToString("\n") { "export " + it.key + "=" + BridgeTmux.quote(it.value) }
        val onExit = "rc=\$?; trap - EXIT; exec 1>&3 2>&4; wait; printf '%s' \"\$rc\" > " + q("status.tmp") +
            "; mv " + q("status.tmp") + " " + q("status")
        File(dir, "driver.sh").writeText(
            "exec 3>&1 4>&2\ntrap " + BridgeTmux.quote(onExit) + " EXIT\n" +
            "for ((i=0;i<300;i++)); do [[ -f " + q("cancelled") + " ]] && exit 130; [[ -f " + q("ready") + " ]] && break; sleep 0.1; done\n" +
            "[[ -f " + q("ready") + " && ! -f " + q("cancelled") + " ]] || exit 130\n" +
            exports + "\nunset TMUX TMUX_PANE SHLVL OLDPWD\n" +
            // Captures are bounded on disk while the live terminal continues draining output.
            "exec > >(tee /dev/fd/3 | { head -c 1048576 > " + q("stdout") + "; cat >/dev/null; }) " +
            "2> >(tee /dev/fd/4 | { head -c 1048576 > " + q("stderr") + "; cat >/dev/null; })\n" +
            "cd -- " + BridgeTmux.quote(cwd.path) + " || exit\n" +
            BridgeTmux.BASH + " " + q("command.sh") + "\n")
        val meta = JSONObject().put("owner", owner).put("fingerprint", fingerprint).put("cwd", cwd.path)
            .put("created", System.currentTimeMillis()).put("phase", "dispatching")
        save(dir, meta)
        try {
            val window = "task-$id"
            val (code, text) = tmux.control("new-window", "-d", "-P", "-F", "#{pane_id}", "-t", tmux.agentName + ":",
                "-n", window, BridgeTmux.BASH + " " + q("driver.sh"),
                ";", "set-option", "-p", "-t", tmux.agentName + ":$window", "@forgemind-task", id,
                ";", "set-option", "-w", "-t", tmux.agentName + ":$window", "remain-on-exit", "on")
            check(code == 0) { "Task dispatch is uncertain: $text" }
            val pane = text.lineSequence().first().trim()
            check(pane.matches(Regex("%[0-9]+")))
            File(dir, "pane").writeText(pane)
            check(ownedPane(dir, id) != null) { "Task pane ownership could not be confirmed" }
            save(dir, meta.put("phase", "submitted"))
            File(dir, "ready").writeText("start")
            tmux.control("select-window", "-t", pane)
        } catch (failure: Exception) {
            File(dir, "cancelled").writeText("cancel")
            save(dir, meta.put("phase", "uncertain").put("error", failure.message.orEmpty().take(1000)))
            // The ID still exists and can be inspected after a lost IPC response.
        }
        return snapshot(owner, id)
    }

    fun snapshot(owner: Int, id: String): Bundle {
        val (dir, meta) = metadata(owner, id)
        val sampled = BridgeTaskSnapshotState.sample({ exit(dir) }, { ownedPane(dir,id,runningOnly=true) })
        val code = sampled.exitCode
        val cancelled = File(dir, "cancelled").exists()
        val pane = sampled.runningPane
        val state = when {
            code != null -> if (cancelled) "CANCELLED" else "COMPLETED"
            pane != null -> "RUNNING"
            meta.optString("phase") == "submitted" -> if (cancelled) "CANCELLED" else "LOST"
            else -> "UNKNOWN"
        }
        return Bundle().apply {
            putString("id", id); putString("state", state); putString("cwd", meta.getString("cwd"))
            if (code != null) putInt("exitCode", code)
            putString("stdout", tail(File(dir, "stdout"))); putString("stderr", tail(File(dir, "stderr")))
            putBoolean("outputTruncated", listOf("stdout", "stderr").any { File(dir, it).length() >= 1_048_576 })
            if (state == "UNKNOWN") putString("error", meta.optString("error", "Dispatch outcome is unknown; do not retry automatically"))
        }
    }

    @Synchronized fun cancel(owner: Int, id: String): Bundle {
        val (dir, meta) = metadata(owner, id)
        if (exit(dir) != null) return snapshot(owner, id)
        File(dir, "cancelled").writeText("cancel")
        val owned = ownedPane(dir,id)
        check(owned != null || !File(dir,"ready").exists() || exit(dir) != null) {
            "Task cancellation is not confirmed: pane ownership is unavailable"
        }
        owned?.let { pane ->
            tmux.control("send-keys", "-t", pane, "C-c")
            repeat(10) { if (exit(dir) == null) Thread.sleep(50) }
            if (exit(dir) == null) {
                check(ownedPane(dir, id) == pane) { "Task cancellation is not confirmed: pane ownership changed" }
                check(tmux.control("kill-pane", "-t", pane).first == 0) { "Task cancellation is not confirmed" }
            }
        }
        if (exit(dir) == null) File(dir, "status").writeText("130")
        save(dir, meta.put("phase", "cancelled"))
        return snapshot(owner, id)
    }

    fun execute(owner: Int, request: Bundle): Bundle {
        val id = request.getString("id").orEmpty()
        return when (request.getString("action")) {
            "start" -> start(owner, request)
            "status" -> snapshot(owner, id)
            "cancel" -> cancel(owner, id)
            "list" -> Bundle().apply {
                val tasks = root.listFiles().orEmpty().filter { File(it, "request.json").isFile }
                    .sortedByDescending { it.lastModified() }.take(64).mapNotNull {
                        runCatching { val (_, meta) = metadata(owner, it.name)
                            Bundle().apply { putString("id", it.name); putString("cwd", meta.getString("cwd")); putLong("created", meta.getLong("created")) }
                        }.getOrNull()
                    }
                putParcelableArrayList("tasks", ArrayList(tasks))
            }
            else -> error("Unknown task operation")
        }
    }
}
