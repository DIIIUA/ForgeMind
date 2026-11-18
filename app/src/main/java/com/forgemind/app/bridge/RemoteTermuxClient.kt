package com.forgemind.app.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.forgemind.app.BuildConfig
import com.forgemind.termuxbridge.api.ITermuxBridge
import com.termux.terminal.RemotePty
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** ForgeMind keeps its own UID. Only explicit IPC crosses into Termux. */
class RemoteTermuxClient private constructor(private val context: Context) {
    companion object {
        const val PACKAGE = "com.forgemind.termuxbridge"
        const val TERMUX_CERT = "b6da01480eefd5fbf2cd3771b8d1021ec791304bdd6c4bf41d3faabad48ee5e1"
        const val HOME = "/data/data/com.termux/files/home"
        @Volatile private var instance: RemoteTermuxClient? = null
        fun get(context: Context): RemoteTermuxClient = instance ?: synchronized(this) {
            instance ?: RemoteTermuxClient(context.applicationContext).also { instance = it }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    @Volatile private var api: ITermuxBridge? = null
    private var binding: ServiceConnection? = null
    private val _status = MutableStateFlow("Мост не подключён")
    val status = _status.asStateFlow()

    fun installationStatus(): String = runCatching {
        if (TermuxDaemonApi.configured(context)) return "available"
        val pm = context.packageManager
        val termux = pm.getApplicationInfo("com.termux", 0)
        val cert = TERMUX_CERT.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        if (!pm.hasSigningCertificate("com.termux", cert, PackageManager.CERT_INPUT_SHA256))
            return "У этой сборки Termux другая подпись; доступны SSH и RUN_COMMAND"
        val bridge = runCatching { pm.getApplicationInfo(PACKAGE, 0) }.getOrNull()
            ?: return "Мост не установлен"
        if (bridge.uid != termux.uid || !pm.hasSigningCertificate(PACKAGE, cert, PackageManager.CERT_INPUT_SHA256))
            return "Подпись или UID моста не совпадает с GitHub Termux"
        if (bridge.uid == context.applicationInfo.uid) return "Основное приложение должно иметь отдельный UID"
        "available"
    }.getOrElse { "Termux не установлен" }
    fun installed(): Boolean = installationStatus() == "available"

    suspend fun connect(): ITermuxBridge = withContext(Dispatchers.IO) { mutex.withLock {
        if (TermuxDaemonApi.configured(context)) {
            val daemon = TermuxDaemonApi(TermuxDaemonApi.config(context))
            try {
                val info = daemon.info()
                check(info.getInt("protocol") == 1 && info.getBoolean("termuxReady"))
                _status.value = "Termux " + info.getString("version")
                return@withLock daemon
            } catch (failure: Exception) {
                _status.value = "Помощник Termux не подключён. Откройте мастер настройки."
                throw failure
            }
        }
        api?.takeIf { it.asBinder().isBinderAlive }?.let { return@withLock it }
        val state = installationStatus()
        check(state == "available") { state }
        binding?.let { runCatching { context.unbindService(it) } }
        val ready = CompletableDeferred<ITermuxBridge>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                ready.complete(ITermuxBridge.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName) { api = null; _status.value = "Связь с мостом потеряна" }
            override fun onBindingDied(name: ComponentName) { api = null; _status.value = "Мост обновлён; требуется подключение" }
            override fun onNullBinding(name: ComponentName) { ready.completeExceptionally(IllegalStateException("Bridge rejected binding")) }
        }
        binding = connection
        try {
            check(context.bindService(Intent().setClassName(PACKAGE, "$PACKAGE.TermuxBridgeService"), connection, Context.BIND_AUTO_CREATE))
            val service = withTimeout(5_000) { ready.await() }
            val info = service.info()
            check(info.getInt("protocol") == 1 && info.getBoolean("termuxReady")) { "Open Termux and complete shell setup" }
            check(BuildConfig.DEBUG || !info.getBoolean("debug")) { "Release ForgeMind requires a release bridge" }
            service.asBinder().linkToDeath({ api = null; _status.value = "Связь с мостом потеряна" }, 0)
            api = service
            _status.value = "Мост Termux " + info.getString("version") +
                if (info.getBoolean("tmuxReady")) "" else "; для команд и терминала: pkg install tmux"
            service
        } catch (failure: Exception) {
            runCatching { context.unbindService(connection) }; binding = null
            _status.value = failure.message ?: "Мост недоступен"
            throw failure
        }
    } }

    /** Safe fallback is allowed here: no user operation has been sent yet. */
    suspend fun available(forCommands: Boolean = false): Boolean = try {
        val connection = connect()
        !forCommands || withContext(Dispatchers.IO) { connection.info().getBoolean("tmuxReady") }
    }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }

    suspend fun start(command: String, cwd: String, env: Map<String, String> = emptyMap()): AsyncCommandStart {
        val service = connect()
        val id = UUID.randomUUID().toString()
        CommandTaskStore(java.io.File(context.filesDir, "command-tasks")).create(
            backend = CommandTaskBackend.REMOTE_BRIDGE, pid = "bridge:$id", logFile = "bridge:$id", workDir = cwd.ifBlank { HOME })
        val request = Bundle().apply {
            putString("action", "start"); putString("id", id); putString("command", command); putString("cwd", cwd)
            putBundle("env", Bundle().apply { env.forEach { (k,v) -> putString(k,v) } })
        }
        try { withContext(Dispatchers.IO) { service.tasks(request) } }
        catch (cancelled: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { service.tasks(Bundle().apply { putString("action","cancel"); putString("id",id) }) } }
            throw cancelled
        }
        catch (failure: android.os.RemoteException) {
            // Persist this handle in CommandTaskStore even if the start response was lost.
            _status.value = "Ответ о запуске потерян; проверьте задачу $id"
        }
        catch (failure: Exception) {
            throw AsyncCommandLaunchUncertain("Bridge task $id was submitted. Inspect its status before retrying. " + failure.message)
        }
        return AsyncCommandStart("bridge:$id", "bridge:$id", cwd.ifBlank { HOME }, backend = CommandTaskBackend.REMOTE_BRIDGE)
    }

    suspend fun task(id: String, action: String = "status"): Bundle = withContext(Dispatchers.IO) {
        connect().tasks(Bundle().apply { putString("action", action); putString("id", id.removePrefix("bridge:")) })
    }
    suspend fun execute(command: String, cwd: String, timeoutSeconds: Int): CommandExecResult {
        val started = start(command, cwd)
        try {
            val end = System.currentTimeMillis() + timeoutSeconds.coerceIn(1,600) * 1000L
            while (true) {
                val result = task(started.pid)
                when (result.getString("state")) {
                    "COMPLETED", "CANCELLED" -> return CommandExecResult(result.getString("stdout").orEmpty(), result.getString("stderr").orEmpty(), result.getInt("exitCode", -1),
                        outputTruncated = result.getBoolean("outputTruncated"))
                    "UNKNOWN", "LOST" -> return CommandExecResult(result.getString("stdout").orEmpty(), result.getString("stderr").orEmpty(), -1,
                        error = "Task " + started.pid + ": " + (result.getString("error") ?: result.getString("state")))
                }
                if (System.currentTimeMillis() >= end) {
                    val resultAfterStop = task(started.pid, "cancel")
                    return CommandExecResult(resultAfterStop.getString("stdout").orEmpty(), resultAfterStop.getString("stderr").orEmpty(), 124, timedOut = true)
                }
                delay(100)
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { runCatching { task(started.pid, "cancel") } }
            throw cancelled
        } catch (failure: Exception) {
            return CommandExecResult("", "", -1, error = "Bridge task " + started.pid +
                " outcome is unknown. Inspect it before retrying. " + failure.message)
        }
    }

    suspend fun sessions(action: String, session: ManagedTermuxSession? = null, keep: Boolean = false): Bundle =
        withContext(Dispatchers.IO) {
            if (action == "open" && TermuxDaemonApi.configured(context)) {
                val name = session?.name ?: error("Session required")
                require(name.matches(Regex("(forgemind-|fm-agent-)[a-f0-9-]{36}")))
                val socketName = connect().info().getString("socket") ?: error("Helper socket unavailable")
                require(socketName.matches(Regex("fm-[a-f0-9]{20}")))
                context.startForegroundService(Intent("com.termux.RUN_COMMAND").apply {
                    setClassName("com.termux", "com.termux.app.RunCommandService")
                    putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/tmux")
                    putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-L", socketName, "attach-session", "-t", "=$name"))
                    putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
                    putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
                    putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
                })
                return@withContext Bundle()
            }
            connect().sessions(Bundle().apply {
            putString("action", action); putString("id", session?.id); putString("name", session?.name); putBoolean("keep", keep)
        }) }
    fun session(bundle: Bundle) = ManagedTermuxSession(bundle.getString("id")!!, bundle.getString("name")!!, bundle.getInt("number"), bundle.getBoolean("keep"))
    @Suppress("DEPRECATION")
    suspend fun listSessions(): List<ManagedTermuxSession> =
        sessions("list").getParcelableArrayList<Bundle>("sessions").orEmpty().map(::session)

    suspend fun files(action: String, path: String, target: String? = null, confirmed: Boolean = false, offset: Int = 0): Bundle =
        withContext(Dispatchers.IO) { connect().files(Bundle().apply {
            putString("action", action); putString("path", path); putString("destination", target)
            putBoolean("userConfirmed", confirmed); putInt("offset", offset)
        }) }
    suspend fun file(path: String, mode: String): ParcelFileDescriptor =
        withContext(Dispatchers.IO) { connect().openFile(path, mode) }

    suspend fun writeFile(path: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val service = connect()
        if (service is TermuxDaemonApi) service.writeFile(path, bytes)
        else ParcelFileDescriptor.AutoCloseOutputStream(service.openFile(path, "wt")).use { it.write(bytes) }
    }

    suspend fun openPty(name: String, env: Map<String,String> = emptyMap()): RemotePty = withContext(Dispatchers.IO) {
        require(name.matches(Regex("(forgemind-|fm-agent-)[a-f0-9-]{36}")))
        val service = connect()
        val lease = Binder()
        val result = service.openPty(Bundle().apply {
            putStringArray("argv", arrayOf("/data/data/com.termux/files/usr/bin/tmux", "attach-session", "-t", "=$name"))
            putString("cwd", HOME); putInt("rows", 24); putInt("columns", 80); putBinder("lease", lease)
            putBundle("env", Bundle().apply { env.forEach { (k,v) -> putString(k,v) } })
        })
        val id = result.getString("id")!!
        @Suppress("DEPRECATION") val descriptor = result.getParcelable<ParcelFileDescriptor>("descriptor")!!
        object : RemotePty {
            private val closed = AtomicBoolean(false)
            private val lifetime = lease
            private val sizes = kotlinx.coroutines.channels.Channel<Pair<Int,Int>>(kotlinx.coroutines.channels.Channel.CONFLATED)
            private val resizeWorker = scope.launch {
                for ((columns,rows) in sizes) if (!closed.get()) runCatching { service.resizePty(id, rows, columns) }
            }
            override fun descriptor() = descriptor
            override fun pid() = result.getInt("pid")
            override fun resize(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
                if (!closed.get()) sizes.trySend(columns to rows)
            }
            override fun waitForExit(): Int {
                while (!closed.get()) {
                    val state = runCatching { service.waitForPty(id, 1000) }.getOrElse { close(); return -1 }
                    if (!state.getBoolean("running")) return state.getInt("exitCode", -1)
                }
                return -1
            }
            override fun close() {
                if (!closed.compareAndSet(false,true)) return
                sizes.close()
                resizeWorker.cancel()
                runCatching { descriptor.close() }
                scope.launch { runCatching { service.closePty(id) } }
            }
        }
    }
}
