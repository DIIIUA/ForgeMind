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
import java.io.File
import java.io.OutputStream
import java.util.UUID

enum class TerminalMode {
    LOCAL_SHELL,
    SSH_PTY
}

data class TerminalTabState(
    val id: String,
    val title: String,
    val mode: TerminalMode,
    val output: String = "",
    val isAlive: Boolean = true,
    val workDir: String = ""
)

class TerminalSessionManager(
    private val termuxBridge: TermuxBridge,
    defaultAiWorkDirProvider: () -> File = { File("/data/local/tmp") }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val embeddedAiShell: EmbeddedAiShell = EmbeddedAiShell(defaultAiWorkDirProvider)
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

    fun setViewMode(mode: TerminalViewMode) {
        _viewMode.value = mode
    }

    fun ensureAiShellStarted() {
        embeddedAiShell.ensureStarted()
    }

    fun restartAiShell() {
        scope.launch {
            embeddedAiShell.restart()
        }
    }

    fun clearAiTerminalLog() {
        embeddedAiShell.clearLog()
    }

    fun selectTab(id: String) {
        _activeTabId.value = id
    }

    fun openLocalShell(workDir: File, title: String = "Local Shell"): String {
        val id = UUID.randomUUID().toString()
        workDir.mkdirs()
        val tab = TerminalTabState(
            id = id,
            title = title,
            mode = TerminalMode.LOCAL_SHELL,
            output = "ForgeMind Local Shell (${workDir.absolutePath})\n$ ",
            isAlive = true,
            workDir = workDir.absolutePath
        )
        _tabs.value = _tabs.value + tab
        _activeTabId.value = id
        buffers[id] = StringBuilder(tab.output)

        scope.launch {
            runCatching {
                val pb = ProcessBuilder("/system/bin/sh")
                    .directory(workDir)
                    .redirectErrorStream(true)
                val tmpDir = File(workDir, ".tmp").apply { mkdirs() }
                pb.environment()["HOME"] = workDir.absolutePath
                pb.environment()["TMPDIR"] = tmpDir.absolutePath
                pb.environment()["TERM"] = "xterm-256color"
                val proc = pb.start()
                writers[id] = proc.outputStream
                cleanups[id] = { runCatching { proc.destroyForcibly() } }

                val reader = proc.inputStream
                val chunk = ByteArray(2048)
                while (true) {
                    val n = reader.read(chunk)
                    if (n < 0) break
                    val text = String(chunk, 0, n, Charsets.UTF_8)
                    appendOutput(id, stripAnsiForPlainView(text))
                }
                val code = proc.waitFor()
                appendOutput(id, "\n[Process exited: exit $code]\n")
                markDead(id)
            }.onFailure { err ->
                appendOutput(id, "\n[Failed to start shell: ${err.message}]\n")
                markDead(id)
            }
        }
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
        sendRaw(tabId, (line + "\n").toByteArray(Charsets.UTF_8))
        val curTab = _tabs.value.firstOrNull { it.id == tabId }
        if (curTab?.mode == TerminalMode.LOCAL_SHELL) {
            appendOutput(tabId, "$line\n")
        }
    }

    fun sendRaw(tabId: String, bytes: ByteArray) {
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
    fun sendUpArrow(tabId: String) = sendRaw(tabId, byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte()))
    fun sendDownArrow(tabId: String) = sendRaw(tabId, byteArrayOf(0x1B, '['.code.toByte(), 'B'.code.toByte()))

    fun clearOutput(tabId: String) {
        buffers[tabId] = StringBuilder()
        _tabs.value = _tabs.value.map { if (it.id == tabId) it.copy(output = "") else it }
    }

    fun closeTab(tabId: String) {
        readerJobs.remove(tabId)?.cancel()
        cleanups.remove(tabId)?.invoke()
        writers.remove(tabId)
        buffers.remove(tabId)
        val remaining = _tabs.value.filterNot { it.id == tabId }
        _tabs.value = remaining
        if (_activeTabId.value == tabId) {
            _activeTabId.value = remaining.lastOrNull()?.id
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
