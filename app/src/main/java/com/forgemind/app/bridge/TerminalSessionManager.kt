package com.forgemind.app.bridge

import com.forgemind.app.data.db.SshProfileEntity
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.OutputStream
import java.util.UUID
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.flow.update

enum class TerminalMode {
    LOCAL_SHELL,
    TERMUX_PTY,
    SSH_PTY
}

data class TerminalTabState(
    val id: String,
    val title: String,
    val mode: TerminalMode,
    val output: String = "",
    val isAlive: Boolean = true,
    val workDir: String = "",
    val nativeSession: TerminalSession? = null,
    val revision: Long = 0,
    val sharedSession: String? = null,
    val managedSession: ManagedTermuxSession? = null
)

class TerminalSessionManager(
    private val termuxBridge: TermuxBridge,
    private val dynamicEnvProvider: () -> Map<String, String> = { emptyMap() },
    defaultAiWorkDirProvider: () -> File = { File("/data/local/tmp") }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val managedSessions = ManagedTermuxSessions(termuxBridge.appContext)
    private val sessionMutex = Mutex()

    val embeddedAiShell: EmbeddedAiShell = EmbeddedAiShell(
        defaultRootDirProvider = defaultAiWorkDirProvider,
        dynamicEnvProvider = dynamicEnvProvider
    )
    val aiTerminalState: StateFlow<AiTerminalState> = embeddedAiShell.state

    private val _viewMode = MutableStateFlow(TerminalViewMode.WATCH_AGENT)
    val viewMode: StateFlow<TerminalViewMode> = _viewMode.asStateFlow()

    private val _tabs = MutableStateFlow<List<TerminalTabState>>(emptyList())
    val tabs: StateFlow<List<TerminalTabState>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    private val writers = mutableMapOf<String, OutputStream>()
    private val cleanups = mutableMapOf<String, () -> Unit>()
    private val buffers = mutableMapOf<String, StringBuilder>()
    private val readerJobs = mutableMapOf<String, Job>()

    private val _watchSharedTermux = MutableStateFlow(false)
    val watchSharedTermux: StateFlow<Boolean> = _watchSharedTermux.asStateFlow()
    fun watchSharedTermux(enabled: Boolean) { _watchSharedTermux.value = enabled }

    fun setViewMode(mode: TerminalViewMode) {
        _viewMode.value = mode
    }

    fun ensureAiShellStarted() {
        if (!termuxBridge.hasUnifiedTermux()) embeddedAiShell.ensureStarted()
    }

    fun sharedCommandEnvironment(workspace: File): Map<String, String> {
        val bin = EmbeddedAiShell.ensureHelperBinInstalled(workspace)
        return dynamicEnvProvider() + ("PATH" to "${SharedTermuxRuntime.PREFIX}/bin:${bin.absolutePath}:/system/bin:/system/xbin")
    }

    fun restartAiShell() {
        scope.launch {
            embeddedAiShell.restart()
        }
    }

    fun clearAiTerminalLog() {
        embeddedAiShell.clearLog()
    }

    fun restoreAiTerminalLog() {
        embeddedAiShell.restoreLog()
    }

    fun selectTab(id: String) {
        _activeTabId.value = id
    }

    fun openLocalShell(workDir: File, title: String = "Local PTY"): String {
        workDir.mkdirs()
        val tmpDir = File(workDir, ".tmp").apply { mkdirs() }
        val binDir = EmbeddedAiShell.ensureHelperBinInstalled(workDir)
        val env = System.getenv().toMutableMap().apply {
            put("HOME", workDir.absolutePath)
            put("TMPDIR", tmpDir.absolutePath)
            put("TERM", "xterm-256color")
            put("COLORTERM", "truecolor")
            put("PATH", "${binDir.absolutePath}:/system/bin:/system/xbin:/vendor/bin")
            putAll(dynamicEnvProvider())
        }
        return openPty("/system/bin/sh", workDir.absolutePath,
            arrayOf("/system/bin/sh", "-i"), env, title, TerminalMode.LOCAL_SHELL)
    }

    fun openTermuxShell(): String {
        watchSharedTermux(true)
        _tabs.value.firstOrNull { it.sharedSession == SharedTermuxRuntime.SESSION && it.isAlive }?.let {
            _activeTabId.value = it.id
            return it.id
        }
        SharedTermuxRuntime.requireReady(termuxBridge.appContext)
        if (!SharedTermuxRuntime.hasSession()) {
            SharedTermuxRuntime.requestSessionViaTermux(termuxBridge.appContext)
        }
        return openPty(SharedTermuxRuntime.BASH, SharedTermuxRuntime.HOME,
            SharedTermuxRuntime.startupArgs(), SharedTermuxRuntime.environment(),
            termuxBridge.appContext.getString(com.forgemind.app.R.string.term_agent_session),
            TerminalMode.TERMUX_PTY, sharedSession = SharedTermuxRuntime.SESSION)
    }

    suspend fun openManagedTermux(newSession: Boolean = false) = withContext(Dispatchers.IO) {
        sessionMutex.withLock {
            SharedTermuxRuntime.requireReady(termuxBridge.appContext)
            check(SharedTermuxRuntime.ensureSession(termuxBridge.appContext)) { "Termux startup timed out" }
            var sessions = managedSessions.list()
            val fresh = if (newSession || sessions.isEmpty()) managedSessions.create() else null
            if (fresh != null) sessions = managedSessions.list()
            withContext(Dispatchers.Main) {
                val previous = _activeTabId.value
                _tabs.value.filter { tab -> tab.managedSession != null &&
                    sessions.none { it.name == tab.sharedSession } }.forEach { closeTab(it.id) }
                sessions.forEach { attachManaged(it) }
                _activeTabId.value = fresh?.let { added -> _tabs.value.first { it.sharedSession == added.name }.id }
                    ?: previous?.takeIf { id -> _tabs.value.any { it.id == id && it.isAlive && it.managedSession != null } }
                    ?: _tabs.value.lastOrNull { it.managedSession != null }?.id
                _activeTabId.value ?: error("Managed terminal was not attached")
            }
        }
    }

    private fun attachManaged(session: ManagedTermuxSession): String {
        _tabs.value.firstOrNull { it.sharedSession == session.name }?.let { tab ->
            if (tab.isAlive) {
                _tabs.update { tabs -> tabs.map { if (it.id == tab.id) it.copy(managedSession = session) else it } }
                return tab.id
            }
            closeTab(tab.id) // Only the dead UI client, not the detached shell.
        }
        return openPty(SharedTermuxRuntime.BASH, SharedTermuxRuntime.HOME,
            SharedTermuxRuntime.startupArgs(session.name), SharedTermuxRuntime.environment(),
            termuxBridge.appContext.getString(com.forgemind.app.R.string.term_session_number, session.number),
            TerminalMode.TERMUX_PTY, sharedSession = session.name, managedSession = session)
    }

    suspend fun restartManagedTermux(tabId: String) = withContext(Dispatchers.IO) {
        sessionMutex.withLock {
            val old = _tabs.value.firstOrNull { it.id == tabId }?.managedSession ?: return@withLock
            // tmux IDs may be reused after its server restarts; the UUID name must also match.
            val stillExists = managedSessions.list().any { it.id == old.id && it.name == old.name }
            val fresh = if (stillExists) managedSessions.replace(old.id, old.name) else managedSessions.create()
            withContext(Dispatchers.Main) {
                if (!old.keep || !stillExists) closeTab(tabId)
                _activeTabId.value = attachManaged(fresh)
            }
        }
    }

    suspend fun setSessionKeep(tabId: String, keep: Boolean) = withContext(Dispatchers.IO) {
        sessionMutex.withLock {
            val old = _tabs.value.firstOrNull { it.id == tabId }?.managedSession ?: return@withLock
            val updated = managedSessions.setKeep(old.id, keep, old.name)
            _tabs.update { tabs -> tabs.map { if (it.id == tabId) it.copy(managedSession = updated) else it } }
        }
    }

    suspend fun closeManagedTermux(tabId: String) = withContext(Dispatchers.IO) {
        sessionMutex.withLock {
            val old = _tabs.value.firstOrNull { it.id == tabId }?.managedSession
            if (old != null && managedSessions.list().any { it.id == old.id && it.name == old.name }) {
                managedSessions.close(old.id, old.name)
            }
            withContext(Dispatchers.Main) { closeTab(tabId) }
        }
    }

    fun openSessionInTermux() = SharedTermuxRuntime.openInTermux(termuxBridge.appContext,
        _tabs.value.firstOrNull { it.id == _activeTabId.value }?.sharedSession ?: SharedTermuxRuntime.SESSION)

    fun selectTermuxShellWindow() {
        val selected = _tabs.value.firstOrNull { it.id == _activeTabId.value }?.sharedSession ?: return
        scope.launch {
            val builder = ProcessBuilder("${SharedTermuxRuntime.PREFIX}/bin/tmux", "select-window", "-t", "=$selected:^")
                .redirectErrorStream(true).redirectOutput(File("/dev/null"))
            builder.environment().apply { clear(); putAll(SharedTermuxRuntime.environment()) }
            runCatching {
                val process = builder.start()
                try { process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) }
                finally { if (process.isAlive) process.destroyForcibly() }
            }
        }
    }

    private val transcriptHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val transcriptPending = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun scheduleTranscript(id: String, session: TerminalSession, immediate: Boolean) {
        if (!immediate && !transcriptPending.add(id)) return
        val task = Runnable {
            transcriptPending.remove(id)
            val transcript = session.emulator?.screen?.transcriptText.orEmpty().takeLast(100_000)
            _tabs.update { tabs -> tabs.map { tab -> if (tab.id == id) tab.copy(output = transcript) else tab } }
        }
        if (immediate) task.run() else transcriptHandler.postDelayed(task, 300)
    }

    private fun openPty(
        shell: String, workDir: String, args: Array<String>, env: Map<String, String>,
        title: String, mode: TerminalMode, sharedSession: String? = null,
        managedSession: ManagedTermuxSession? = null
    ): String {
        val id = UUID.randomUUID().toString()
        val client = PtySessionClient(termuxBridge.appContext) { changed ->
            // Redraw on every change; the full transcript copy (up to 1000 rows) is rebuilt
            // at most every 300 ms, so heavy output does not stall the main thread.
            _tabs.update { tabs -> tabs.map { tab ->
                if (tab.id == id) tab.copy(isAlive = changed.isRunning, revision = tab.revision + 1) else tab
            } }
            scheduleTranscript(id, changed, immediate = !changed.isRunning)
        }
        val session = TerminalSession(shell, workDir, args,
            env.map { (key, value) -> "$key=$value" }.toTypedArray(), 1000, client)
        _tabs.update { it + TerminalTabState(id, title, mode, workDir = workDir, nativeSession = session,
            sharedSession = sharedSession, managedSession = managedSession) }
        _activeTabId.value = id
        cleanups[id] = { session.finishIfRunning() }
        session.updateSize(80, 24, 8, 16)
        return id
    }

    fun openSshTerminal(profile: SshProfileEntity = termuxBridge.activeProfile, title: String = "Termux SSH"): String {
        val id = UUID.randomUUID().toString()
        val tab = TerminalTabState(
            id = id,
            title = title,
            mode = TerminalMode.SSH_PTY,
            output = "Connecting SSH to ${profile.host}:${profile.port}...\n",
            isAlive = true,
            workDir = profile.defaultWorkDir
        )
        _tabs.value = _tabs.value + tab
        _activeTabId.value = id
        buffers[id] = StringBuilder(tab.output)

        val job = scope.launch {
            var session: Session? = null
            var channel: ChannelShell? = null
            runCatching {
                val pair = termuxBridge.openSshPtySession(profile)
                session = pair.first
                channel = pair.second
                val input = channel!!.inputStream
                val output = channel!!.outputStream
                writers[id] = output
                cleanups[id] = {
                    runCatching { channel?.disconnect() }
                    runCatching { session?.disconnect() }
                }

                // Move into configured working directory cleanly
                val wd = profile.defaultWorkDir.ifBlank { "/data/data/com.termux/files/home" }
                output.write("cd ${TermuxBridge.shellSingleQuote(wd)} 2>/dev/null; clear\n".toByteArray(Charsets.UTF_8))
                output.flush()

                val buf = ByteArray(4096)
                while (channel!!.isConnected) {
                    val n = input.read(buf)
                    if (n < 0) break
                    appendOutput(id, stripAnsiForPlainView(String(buf, 0, n, Charsets.UTF_8)))
                }
                appendOutput(id, "\n[SSH session closed]\n")
                markDead(id)
            }.onFailure { err ->
                appendOutput(
                    id,
                    "\n[SSH error: ${err.message}]\nHint: in Termux run 'pkg install openssh && passwd && sshd'\n"
                )
                markDead(id)
            }
        }
        readerJobs[id] = job
        return id
    }

    fun sendLine(tabId: String, line: String) {
        val shared = _tabs.value.firstOrNull { it.id == tabId }?.sharedSession
        if (shared == SharedTermuxRuntime.SESSION) {
            scope.launch {
                // An exited agent pane keeps the last output visible. Typing the next command
                // returns to the interactive shell; live panes still receive interactive input.
                SharedTermuxRuntime.tmuxQuery("if-shell", "-t", "${SharedTermuxRuntime.SESSION}:",
                    "-F", "#{pane_dead}", "select-window -t ${SharedTermuxRuntime.SESSION}:^")
                sendRaw(tabId, (line + "\n").toByteArray(Charsets.UTF_8))
            }
            return
        }
        sendRaw(tabId, (line + "\n").toByteArray(Charsets.UTF_8))
        val curTab = _tabs.value.firstOrNull { it.id == tabId }
        if (curTab?.mode == TerminalMode.LOCAL_SHELL && curTab.nativeSession == null) {
            appendOutput(tabId, "$line\n")
        }
    }

    fun sendRaw(tabId: String, bytes: ByteArray) {
        _tabs.value.firstOrNull { it.id == tabId }?.nativeSession?.let { session ->
            session.write(bytes, 0, bytes.size)
            return
        }
        val out = writers[tabId] ?: return
        scope.launch {
            runCatching {
                out.write(bytes)
                out.flush()
            }
        }
    }

    fun sendCtrlC(tabId: String) = sendRaw(tabId, byteArrayOf(0x03))
    fun sendCtrlD(tabId: String) = sendRaw(tabId, byteArrayOf(0x04))
    fun sendTab(tabId: String) = sendRaw(tabId, byteArrayOf(0x09))
    fun sendEsc(tabId: String) = sendRaw(tabId, byteArrayOf(0x1B))
    fun sendArrow(tabId: String, direction: Char) {
        require(direction in "ABCD")
        val emulator = _tabs.value.firstOrNull { it.id == tabId }?.nativeSession?.emulator
        val prefix = if (emulator?.isCursorKeysApplicationMode == true) "\u001BO" else "\u001B["
        sendRaw(tabId, "$prefix$direction".toByteArray(Charsets.UTF_8))
    }
    fun sendUpArrow(tabId: String) = sendArrow(tabId, 'A')
    fun sendDownArrow(tabId: String) = sendArrow(tabId, 'B')
    fun sendRightArrow(tabId: String) = sendArrow(tabId, 'C')
    fun sendLeftArrow(tabId: String) = sendArrow(tabId, 'D')

    fun clearOutput(tabId: String) {
        _tabs.value.firstOrNull { it.id == tabId }?.nativeSession?.let { session ->
            session.reset()
            return
        }
        buffers[tabId] = StringBuilder()
        _tabs.value = _tabs.value.map { if (it.id == tabId) it.copy(output = "") else it }
    }

    fun closeTab(tabId: String) {
        val closingManagedSession = _tabs.value.firstOrNull { it.id == tabId }?.managedSession != null
        readerJobs.remove(tabId)?.cancel()
        cleanups.remove(tabId)?.invoke()
        writers.remove(tabId)
        buffers.remove(tabId)
        val remaining = _tabs.value.filterNot { it.id == tabId }
        _tabs.value = remaining
        if (_activeTabId.value == tabId) {
            // Closing a manual Termux tab must never select the agent or an old SSH shell.
            // With no other manual session, show an empty terminal until the user opens one.
            _activeTabId.value = if (closingManagedSession)
                remaining.lastOrNull { it.managedSession != null && it.isAlive }?.id
            else remaining.lastOrNull()?.id
        }
    }

    private fun appendOutput(tabId: String, text: String) {
        val sb = buffers.getOrPut(tabId) { StringBuilder() }
        synchronized(sb) {
            sb.append(text)
            if (sb.length > 140_000) {
                sb.delete(0, sb.length - 120_000)
            }
            val snap = sb.toString()
            _tabs.value = _tabs.value.map { if (it.id == tabId) it.copy(output = snap) else it }
        }
    }

    private fun markDead(tabId: String) {
        _tabs.value = _tabs.value.map { if (it.id == tabId) it.copy(isAlive = false) else it }
    }

    companion object {
        private val ANSI_CSI_REGEX = Regex("""\u001B\[[0-?]*[ -/]*[@-~]|\u001B\][^\u0007]*\u0007|\r""")
        fun stripAnsiForPlainView(raw: String): String =
            raw.replace("\r\n", "\n").replace(ANSI_CSI_REGEX, "")
    }
}
