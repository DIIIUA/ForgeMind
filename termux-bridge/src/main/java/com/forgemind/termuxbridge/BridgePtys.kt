package com.forgemind.termuxbridge

import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.Os
import com.termux.terminal.PtyNative
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Handles are random and owner-bound. Never accept a pid supplied by an IPC caller. */
class BridgePtys(private val activityChanged: () -> Unit) {
    private class Pty(val id: String, val owner: Int, val pid: Int, val start: String,
                      val master: ParcelFileDescriptor, val lease: IBinder) {
        val completed = CountDownLatch(1)
        @Volatile var exit: Int? = null
        lateinit var death: IBinder.DeathRecipient
    }
    private val sessions = ConcurrentHashMap<String, Pty>()
    val activeCount: Int get() = sessions.values.count { it.exit == null }

    private fun process(pid: Int) = PtyProcessIdentity.parse(File("/proc/$pid/stat").readText())
    private fun identity(pid: Int): String = process(pid).start
    private fun owned(owner: Int, id: String) = sessions[id]?.takeIf { it.owner == owner }
        ?: throw IllegalArgumentException("PTY handle is unavailable")

    @Synchronized fun open(owner: Int, request: Bundle): Bundle {
        require(sessions.values.count { it.owner == owner } < 16) { "Close an existing terminal first" }
        val argv = request.getStringArray("argv") ?: arrayOf("${TermuxBridgeService.PREFIX}/bin/bash", "-l")
        require(argv.isNotEmpty() && argv.size <= 128 && argv.sumOf { it.length } <= 64_000 && argv.none { '\u0000' in it })
        val cwd = BridgeWorkingDirectory.resolve(request.getString("cwd"))
        require(cwd.isDirectory) { "Working directory is unavailable" }
        val rows = request.getInt("rows", 24).coerceIn(2, 300)
        val cols = request.getInt("columns", 80).coerceIn(2, 500)
        val lease = request.getBinder("lease") ?: error("A client lifetime token is required")
        val extra = request.getBundle("env")?.let { b -> b.keySet().associateWith { b.getString(it) ?: error("Environment values must be strings") } }.orEmpty()
        val pid = intArrayOf(0)
        val raw = PtyNative.create(argv[0], cwd.path, argv, BridgeEnvironment.build(extra).map { "${it.key}=${it.value}" }.toTypedArray(), pid, rows, cols)
        check(raw >= 0 && pid[0] > 0) { "Cannot create terminal" }
        val master = ParcelFileDescriptor.adoptFd(raw)
        val handle = UUID.randomUUID().toString()
        val pty = try { Pty(handle, owner, pid[0], identity(pid[0]), master, lease) }
        catch (failure: Exception) {
            master.close()
            // The fresh child has not been reaped, so its pid cannot yet be reused.
            runCatching { Os.kill(pid[0], 9) }
            Thread({ PtyNative.waitFor(pid[0]) }, "bridge-pty-reap").start()
            throw failure
        }
        pty.death = IBinder.DeathRecipient { close(owner, handle) }
        sessions[handle] = pty
        Thread({
            try { pty.exit = PtyNative.waitFor(pty.pid) }
            finally { pty.completed.countDown(); activityChanged() }
        }, "bridge-pty-waiter").apply { isDaemon = true }.start()
        try {
            lease.linkToDeath(pty.death, 0)
            activityChanged()
            return Bundle().apply {
                putString("id", handle); putInt("pid", pty.pid)
                putParcelable("descriptor", ParcelFileDescriptor.dup(master.fileDescriptor))
                putString("cwd", cwd.path)
            }
        } catch (failure: Exception) { close(owner, handle); throw failure }
    }

    fun resize(owner: Int, id: String, rows: Int, columns: Int) {
        val pty = owned(owner, id)
        synchronized(pty) {
            if (pty.exit == null && sessions[id] === pty) PtyNative.resize(pty.master.fd, rows.coerceIn(2,300), columns.coerceIn(2,500))
        }
    }
    fun signal(owner: Int, id: String, signal: Int) {
        require(signal in setOf(1, 2, 9, 15, 28)) { "Unsupported signal" }
        val pty = owned(owner, id)
        if (pty.exit == null) {
            // Signals address only the foreground group of this owned PTY session.
            // A sleeping child, rather than its waiting shell, must receive SIGINT.
            runCatching { process(pty.pid).signalTarget(pty.pid, pty.start) }.getOrNull()?.let { Os.kill(it, signal) }
        }
    }
    fun await(owner: Int, id: String, timeout: Long): Bundle {
        val pty = owned(owner, id)
        pty.completed.await(timeout.coerceIn(0, 10_000), TimeUnit.MILLISECONDS)
        return Bundle().apply { putBoolean("running", pty.exit == null); pty.exit?.let { putInt("exitCode", it) } }
    }
    fun close(owner: Int, id: String) {
        val pty = sessions[id] ?: return
        require(pty.owner == owner) { "PTY belongs to another client" }
        if (!sessions.remove(id, pty)) return
        runCatching { pty.lease.unlinkToDeath(pty.death, 0) }
        if (pty.exit == null && runCatching { identity(pty.pid) == pty.start }.getOrDefault(false)) {
            runCatching { Os.kill(pty.pid, 1) }
        }
        synchronized(pty) { runCatching { pty.master.close() } }
        Thread({
            if (!pty.completed.await(1500, TimeUnit.MILLISECONDS) &&
                runCatching { identity(pty.pid) == pty.start }.getOrDefault(false)) {
                runCatching { Os.kill(pty.pid, 9) }
            }
        }, "bridge-pty-close").apply { isDaemon = true }.start()
        activityChanged()
    }
    fun closeAll() { sessions.values.toList().forEach { close(it.owner, it.id) } }
}
