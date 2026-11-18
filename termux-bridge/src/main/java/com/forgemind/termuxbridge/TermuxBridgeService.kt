package com.forgemind.termuxbridge

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.Looper
import com.forgemind.termuxbridge.api.ITermuxBridge
import java.io.File
import java.util.concurrent.TimeUnit

class TermuxBridgeService : Service() {
    private val fileAccess by lazy { BridgeFiles(this) }
    private val tmux by lazy { BridgeTmux(this) }
    private val tasks by lazy { BridgeTasks(this, tmux) }
    private val services by lazy { BridgeServices(this, tmux) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var destroying = false
    private val ptys = BridgePtys { mainHandler.post { updateForeground() } }
    companion object {
        const val HOME = "/data/data/com.termux/files/home"
        const val PREFIX = "/data/data/com.termux/files/usr"
        const val TERMUX_CERT = "b6da01480eefd5fbf2cd3771b8d1021ec791304bdd6c4bf41d3faabad48ee5e1"
    }

    private fun signed(uid: Int, hex: String): Boolean = hex.length == 64 &&
        packageManager.hasSigningCertificate(uid, hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), PackageManager.CERT_INPUT_SHA256)

    private fun <T> authorized(body: (Int) -> T): T {
        val caller = Binder.getCallingUid()
        val packages = packageManager.getPackagesForUid(caller)?.toSet().orEmpty()
        if (!CallerPolicy.allows(packages, signed(caller, BuildConfig.FORGEMIND_CERTIFICATE),
                BuildConfig.DEBUG, BuildConfig.DEBUG && signed(caller, BuildConfig.DEBUG_CLIENT_CERTIFICATE))) {
            throw SecurityException("Only the signed ForgeMind application may use this bridge")
        }
        // Capture and verify caller BEFORE clearing identity or leaving the Binder thread.
        val identity = Binder.clearCallingIdentity()
        try { return body(caller) }
        catch (failure: java.io.IOException) {
            // AIDL can marshal runtime exceptions, but not arbitrary checked IO exceptions.
            throw IllegalStateException(failure.message ?: "Bridge IO failed", failure)
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    private fun requireTermux() {
        val termux = packageManager.getApplicationInfo("com.termux", 0)
        check(termux.uid == applicationInfo.uid && signed(termux.uid, TERMUX_CERT)) { "Compatible GitHub Termux is required" }
        check(File("$PREFIX/bin/bash").canExecute()) { "Open Termux once to install its shell" }
    }

    private fun command(request: Bundle): String = request.getString("command").orEmpty().also {
        require(it.isNotBlank() && it.length <= 64_000 && '\u0000' !in it) { "Invalid command" }
    }

    private fun boundedText(file: File): String = file.inputStream().use { input ->
        val buffer = ByteArray(32_768)
        var size = 0
        while (size < buffer.size) {
            val read = input.read(buffer, size, buffer.size - size)
            if (read < 0) break
            size += read
        }
        String(buffer, 0, size, Charsets.UTF_8)
    }

    private val binder = object : ITermuxBridge.Stub() {
        override fun ensureServices(request: Bundle): Bundle = authorized { requireTermux(); services.ensure(request) }
        override fun tasks(request: Bundle): Bundle = authorized { owner -> requireTermux(); tasks.execute(owner, request) }
        override fun sessions(request: Bundle): Bundle = authorized { requireTermux(); tmux.execute(request) }
        override fun openPty(request: Bundle): Bundle = authorized { owner -> requireTermux(); ptys.open(owner, request) }
        override fun resizePty(id: String, rows: Int, columns: Int) = authorized { owner -> ptys.resize(owner, id, rows, columns) }
        override fun signalPty(id: String, signal: Int) = authorized { owner -> ptys.signal(owner, id, signal) }
        override fun waitForPty(id: String, timeoutMs: Long): Bundle = authorized { owner -> ptys.await(owner, id, timeoutMs) }
        override fun closePty(id: String) = authorized { owner -> ptys.close(owner, id) }
        override fun openFile(path: String, mode: String): ParcelFileDescriptor = authorized {
            requireTermux(); fileAccess.open(path, mode)
        }

        override fun files(request: Bundle): Bundle = authorized {
            requireTermux(); fileAccess.execute(request)
        }
        override fun info(): Bundle = authorized {
            Bundle().apply {
                putInt("protocol", 1)
                putString("version", BuildConfig.VERSION_NAME)
                putBoolean("termuxReady", runCatching { requireTermux() }.isSuccess)
                putBoolean("tmuxReady", File("$PREFIX/bin/tmux").canExecute())
                putStringArray("capabilities", arrayOf("exec", "tasks", "pty", "sessions", "files", "service_start", "ensure_services"))
                putInt("uid", applicationInfo.uid)
                putBoolean("debug", BuildConfig.DEBUG)
            }
        }

        override fun exec(request: Bundle): Bundle = authorized { owner ->
            requireTermux()
            val id = request.getString("id") ?: java.util.UUID.randomUUID().toString()
            val timeout = request.getLong("timeoutMs", 10_000).coerceIn(1, 600_000)
            val dispatch = Bundle(request).apply { putString("id", id) }
            tasks.start(owner, dispatch)
            val deadline = System.currentTimeMillis() + timeout
            var result = tasks.snapshot(owner, id)
            while (result.getString("state") == "RUNNING" && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
                result = tasks.snapshot(owner, id)
            }
            val timedOut = result.getString("state") == "RUNNING"
            if (timedOut) result = tasks.cancel(owner, id)
            result.apply {
                putBoolean("timedOut", timedOut)
                if (timedOut) putInt("exitCode", 124)
                else if (!containsKey("exitCode")) putInt("exitCode", -1)
            }
        }
        override fun startService(request: Bundle): Bundle = authorized {
            requireTermux()
            val script = command(request)
            val cwd = BridgeWorkingDirectory.resolve(request.getString("cwd"))
            require(cwd.isDirectory)
            val intent = Intent("com.termux.service_execute").apply {
                setClassName("com.termux", "com.termux.app.TermuxService")
                data = Uri.Builder().scheme("com.termux.file").path("$PREFIX/bin/bash").build()
                putExtra("com.termux.execute.arguments", arrayOf("-lc", script))
                putExtra("com.termux.execute.cwd", cwd.path)
                putExtra("com.termux.execute.background", true)
            }
            checkNotNull(startForegroundService(intent)) { "Termux service is unavailable" }
            Bundle().apply { putBoolean("submitted", true) }
        }
    }

    // onBind is not the remote transaction: checking getCallingUid here would check the wrong identity.
    override fun onBind(intent: Intent) = binder

    private fun updateForeground() {
        if (destroying) return
        if (ptys.activeCount == 0) { stopForeground(STOP_FOREGROUND_REMOVE); return }
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("terminal", "Termux terminal", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, Notification.Builder(this, "terminal")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("ForgeMind ↔ Termux")
            .setContentText("Terminal sessions: ${ptys.activeCount}").setOngoing(true).build())
    }
    override fun onDestroy() {
        destroying = true
        ptys.closeAll()
        super.onDestroy()
    }
}
