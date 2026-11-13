package com.forgemind.app.bridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.forgemind.app.data.db.SshProfileEntity
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class CommandExecResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val timedOut: Boolean = false,
    val error: String? = null
)

data class AsyncCommandStart(
    val pid: String,
    val logFile: String,
    val workDir: String
)

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return
        val bundle = intent.getBundleExtra("result")
        val stdout = bundle?.getString("stdout") ?: ""
        val stderr = bundle?.getString("stderr") ?: ""
        val exitCode = bundle?.getInt("exitCode", -1) ?: -1
        val errCode = bundle?.getInt("err", 0) ?: 0
        val errmsg = bundle?.getString("errmsg")
        val err = if (errCode != 0 || !errmsg.isNullOrBlank()) {
            errmsg ?: "Termux err=$errCode"
        } else null

        pendingResults.remove(token)?.complete(
            CommandExecResult(
                stdout = stdout,
                stderr = stderr,
                exitCode = exitCode,
                error = err
            )
        )
    }

    companion object {
        const val EXTRA_TOKEN = "forgemind_termux_token"
        val pendingResults = ConcurrentHashMap<String, CompletableDeferred<CommandExecResult>>()
    }
}

class TermuxBridge(private val context: Context) {

    private val profileMutexes = ConcurrentHashMap<String, Mutex>()
    private val cachedSessions = ConcurrentHashMap<String, Pair<Int, Session>>()
    private val _connectedProfileIds = MutableStateFlow<Set<String>>(emptySet())
    val connectedProfileIds: StateFlow<Set<String>> = _connectedProfileIds.asStateFlow()

    fun refreshConnectionStates() {
        _connectedProfileIds.value = cachedSessions
            .filterValues { (_, sess) -> sess.isConnected }
            .keys
            .toSet()
    }
    @Volatile
    private var allProfiles: List<SshProfileEntity> = listOf(defaultTermuxProfile())
    @Volatile
    var activeProfile: SshProfileEntity = defaultTermuxProfile()
        private set

    private fun mutexFor(profileId: String): Mutex =
        profileMutexes.getOrPut(profileId) { Mutex() }

    fun getAllProfiles(): List<SshProfileEntity> = allProfiles

    fun getEnabledProfiles(): List<SshProfileEntity> = allProfiles.filter { it.enabled }

    fun updateProfiles(profiles: List<SshProfileEntity>) {
        val list = if (profiles.isEmpty()) listOf(defaultTermuxProfile()) else profiles
        allProfiles = list
        val enabled = list.filter { it.enabled }
        activeProfile = enabled.firstOrNull { it.isDefault }
            ?: enabled.firstOrNull()
            ?: list.firstOrNull { it.isDefault }
            ?: list.first()

        val validHashes = enabled.associate { it.id to it.hashCode() }
        val toClose = cachedSessions.keys.filter { id ->
            val expectedHash = validHashes[id]
            expectedHash == null || cachedSessions[id]?.first != expectedHash
        }
        toClose.forEach { disconnectSsh(it) }
    }

    fun updateProfile(profile: SshProfileEntity) {
        val current = allProfiles.toMutableList()
        val idx = current.indexOfFirst { it.id == profile.id }
        val updated = if (profile.isDefault) {
            current.map { it.copy(isDefault = false) }.toMutableList()
        } else current
        if (idx >= 0) {
            updated[idx] = profile
        } else {
            updated += profile
        }
        updateProfiles(updated)
    }

    fun resolveProfile(serverHint: String? = null): SshProfileEntity {
        val enabled = allProfiles.filter { it.enabled }
        val q = serverHint?.trim().orEmpty()
        if (q.isEmpty()) {
            return enabled.firstOrNull { it.isDefault }
                ?: enabled.firstOrNull()
                ?: activeProfile
        }
        val exact = enabled.firstOrNull { it.id.equals(q, ignoreCase = true) }
            ?: enabled.firstOrNull { it.name.equals(q, ignoreCase = true) }
            ?: enabled.firstOrNull { it.host.equals(q, ignoreCase = true) }
            ?: enabled.firstOrNull { "${it.host}:${it.port}".equals(q, ignoreCase = true) }
            ?: enabled.firstOrNull { it.name.contains(q, ignoreCase = true) }
            ?: enabled.firstOrNull { it.host.isNotBlank() && it.host.contains(q, ignoreCase = true) }
        if (exact != null) return exact

        val disabledMatch = allProfiles.firstOrNull {
            !it.enabled && (it.id.equals(q, true) || it.name.equals(q, true) || it.name.contains(q, true))
        }
        if (disabledMatch != null) {
            error("SSH server '${disabledMatch.name}' is disabled in Settings -> AI Envs")
        }
        val available = enabled.joinToString { "'${it.name}' (${it.host}:${it.port})" }.ifBlank { "no active SSH profiles" }
        error("SSH server '$q' not found. Available: $available")
    }

    fun isTermuxInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo("com.termux", 0)
        true
    }.getOrDefault(false)

    fun hasTermuxPermission(): Boolean =
        context.checkSelfPermission("com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED

    fun isSshConnected(serverHint: String? = null): Boolean {
        if (serverHint.isNullOrBlank()) {
            return cachedSessions.values.any { it.second.isConnected }
        }
        val p = runCatching { resolveProfile(serverHint) }.getOrNull() ?: return false
        return cachedSessions[p.id]?.second?.isConnected == true
    }

    fun disconnectSsh(profileId: String? = null) {
        if (profileId == null) {
            cachedSessions.values.forEach { (_, sess) -> runCatching { sess.disconnect() } }
            cachedSessions.clear()
        } else {
            cachedSessions.remove(profileId)?.let { (_, sess) -> runCatching { sess.disconnect() } }
        }
        refreshConnectionStates()
    }

    suspend fun testSshConnection(profile: SshProfileEntity = activeProfile): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                disconnectSsh(profile.id)
                val sess = mutexFor(profile.id).withLock {
                    openOrReuseSessionLocked(profile)
                }
                val who = execViaSession(sess, "whoami && pwd && uname -m", profile.defaultWorkDir.trim(), 10)
                if (who.exitCode != 0 && who.stdout.isBlank()) {
                    refreshConnectionStates()
                    error(who.stderr.ifBlank { who.error ?: "Exit ${who.exitCode}" })
                }
                refreshConnectionStates()
                "Connected to '${profile.name}' (${who.stdout.trim().replace("\n", " | ")})"
            }
        }

    suspend fun <T> withSftp(
        serverHint: String? = null,
        block: (ChannelSftp, String) -> T
    ): T = withContext(Dispatchers.IO) {
        val profile = resolveProfile(serverHint)
        if (!profile.enabled) error("SSH server '${profile.name}' is disabled in Settings")
        mutexFor(profile.id).withLock {
            val session = openOrReuseSessionLocked(profile)
            val sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(10_000)
            try {
                val baseDir = profile.defaultWorkDir.trim().ifBlank { sftp.home }
                block(sftp, baseDir)
            } finally {
                runCatching { sftp.disconnect() }
            }
        }
    }

    suspend fun runCommand(
        command: String,
        workDir: String? = null,
        timeoutSeconds: Int = 35,
        serverHint: String? = null
    ): CommandExecResult = withContext(Dispatchers.IO) {
        val profile = runCatching { resolveProfile(serverHint) }.getOrElse { err ->
            return@withContext CommandExecResult("", "", -1, error = err.message)
        }
        val dir = workDir?.trim()?.takeIf { it.isNotBlank() }
            ?: profile.defaultWorkDir.trim()

        val sshAttempt = if (profile.enabled) {
            runCatching {
                mutexFor(profile.id).withLock {
                    val sess = openOrReuseSessionLocked(profile)
                    execViaSession(sess, command, dir, timeoutSeconds)
                }
            }
        } else {
            Result.failure(IllegalStateException("SSH '${profile.name}' is disabled"))
        }
        if (sshAttempt.isSuccess) {
            return@withContext sshAttempt.getOrThrow()
        }

        val sshError = sshAttempt.exceptionOrNull()?.message
        if (isLocalDeviceProfile(profile) && (serverHint.isNullOrBlank() || isLocalHost(profile.host)) &&
            isTermuxInstalled() && hasTermuxPermission()
        ) {
            val termuxDir = dir.ifBlank { "/data/data/com.termux/files/home" }
            val intentRes = runViaTermuxIntent(command, termuxDir, timeoutSeconds)
            if (intentRes.error == null || intentRes.stdout.isNotBlank()) {
                return@withContext intentRes
            }
            return@withContext intentRes.copy(
                error = "Both SSH '${profile.name}' (${sshError ?: "disabled"}) and RUN_COMMAND (${intentRes.error}) failed"
            )
        }

        CommandExecResult(
            stdout = "",
            stderr = "",
            exitCode = -1,
            error = "No SSH connection to '${profile.name}' (${profile.host.ifBlank { "?" }}:${profile.port}): ${sshError ?: "not configured"}."
        )
    }

    suspend fun runCommandAsync(
        command: String,
        workDir: String? = null,
        serverHint: String? = null
    ): Result<AsyncCommandStart> = withContext(Dispatchers.IO) {
        runCatching {
            val profile = resolveProfile(serverHint)
            val dir = workDir?.trim()?.takeIf { it.isNotBlank() }
                ?: profile.defaultWorkDir.trim()
            val logName = ".forgemind_cmd_${System.currentTimeMillis()}.log"
            val logFile = if (dir.isNotBlank()) "$dir/$logName" else logName
            val escapedLog = shellSingleQuote(logFile)
            val script = """
                : > $escapedLog
                SHELL_BIN=$(command -v bash || echo sh)
                nohup "${'$'}SHELL_BIN" -c ${shellSingleQuote("($command) >> $escapedLog 2>&1; printf '\\n__FORGEMIND_EXIT__:%s\\n' \"\$?\" >> $escapedLog")} >/dev/null 2>&1 </dev/null &
                echo $!
            """.trimIndent()
            val res = runCommand(script, dir.ifBlank { null }, timeoutSeconds = 15, serverHint = profile.id)
            val pid = res.stdout.trim().lines().lastOrNull()?.trim().orEmpty()
            if (pid.toLongOrNull() == null) {
                error("Failed to start background command (${profile.name}): ${res.stderr.ifBlank { res.error ?: res.stdout }}")
            }
            AsyncCommandStart(pid = pid, logFile = logFile, workDir = dir.ifBlank { "~" })
        }
    }

    suspend fun checkCommandStatus(
        pid: String,
        logFile: String,
        tailLines: Int = 60,
        serverHint: String? = null
    ): String =
        withContext(Dispatchers.IO) {
            val safeLog = shellSingleQuote(logFile)
            val safePid = pid.filter { it.isDigit() }
            val cmd = """
                if kill -0 $safePid 2>/dev/null; then
                  echo "__RUNNING__"
                else
                  echo "__FINISHED__"
                fi
                tail -n ${tailLines.coerceIn(10, 300)} $safeLog 2>/dev/null || true
            """.trimIndent()
            val res = runCommand(cmd, timeoutSeconds = 15, serverHint = serverHint)
            if (res.error != null) "Status check failed: ${res.error}" else res.stdout
        }

    suspend fun killCommand(pid: String, serverHint: String? = null): CommandExecResult {
        val safePid = pid.filter { it.isDigit() }
        if (safePid.isBlank()) return CommandExecResult("", "Invalid PID", -1)
        return runCommand(
            "pkill -TERM -P $safePid 2>/dev/null; kill -9 $safePid 2>/dev/null; echo 'Killed PID $safePid'",
            timeoutSeconds = 10,
            serverHint = serverHint
        )
    }

    private fun isLocalHost(host: String): Boolean =
        host.trim().lowercase() in setOf("", "127.0.0.1", "localhost", "::1")

    fun isLocalDeviceProfile(profile: SshProfileEntity): Boolean =
        isLocalHost(profile.host) ||
            profile.id == "default_termux" ||
            profile.name.contains("termux", ignoreCase = true)

    fun isLocalSshTarget(serverHint: String? = null): Boolean {
        val profile = runCatching { resolveProfile(serverHint) }.getOrNull() ?: return serverHint.isNullOrBlank()
        return isLocalDeviceProfile(profile)
    }

    private val cachedMcpServers = java.util.concurrent.atomic.AtomicReference<List<com.forgemind.app.data.db.McpServerEntity>>(emptyList())
    private val cfWsClient: okhttp3.OkHttpClient by lazy { CloudflareAccessSshSupport.defaultWebSocketClient() }

    fun updateMcpServers(servers: List<com.forgemind.app.data.db.McpServerEntity>) {
        val prev = cachedMcpServers.getAndSet(servers)
        val prevSig = prev.map { listOf(it.id, it.endpoint, it.headersJson, it.enabled.toString()) }
        val nextSig = servers.map { listOf(it.id, it.endpoint, it.headersJson, it.enabled.toString()) }
        if (prevSig != nextSig) {
            val dropIds = cachedSessions.entries
                .filter { (_, pair) -> pair.second.port == 443 }
                .map { it.key }
            dropIds.forEach { id -> cachedSessions.remove(id)?.let { (_, sess) -> runCatching { sess.disconnect() } } }
        }
    }

    fun openSshPtySession(profile: SshProfileEntity = activeProfile): Pair<Session, com.jcraft.jsch.ChannelShell> {
        val effective = CloudflareAccessSshSupport.resolveEffectiveProfile(profile, cachedMcpServers.get())
        val jsch = buildJsch(effective)
        val user = effective.username.ifBlank { "u0_a0" }
        val normalizedHost = CloudflareAccessSshSupport.normalizeHost(effective.host)
        val host = if (effective.useCloudflareAccess) {
            normalizedHost.ifBlank { error("Specify the Cloudflare Access SSH hostname for '${profile.name}' in Settings") }
        } else {
            normalizedHost.ifBlank { effective.host.trim() }
        }
        val dialPort = if (effective.useCloudflareAccess) {
            CloudflareAccessSshSupport.resolvePort(effective.host, effective.port)
        } else {
            effective.port
        }
        val session = jsch.getSession(user, host, dialPort)
        configureSession(session, effective)
        session.connect(12_000)
        val shell = session.openChannel("shell") as com.jcraft.jsch.ChannelShell
        shell.setPty(true)
        shell.setPtyType("xterm-256color")
        shell.connect(8_000)
        return session to shell
    }

    private fun openOrReuseSessionLocked(profile: SshProfileEntity): Session {
        val hash = profile.hashCode()
        val existing = cachedSessions[profile.id]
        if (existing != null && existing.first == hash && existing.second.isConnected) {
            return existing.second
        }
        val effective = CloudflareAccessSshSupport.resolveEffectiveProfile(profile, cachedMcpServers.get())
        val host = CloudflareAccessSshSupport.normalizeHost(effective.host)
        if (host.isBlank()) error("Specify the host/IP for SSH '${profile.name}' in Settings")
        val user = effective.username.trim()
        if (user.isBlank()) error("Specify the username for SSH '${profile.name}' in Settings")
        if (!effective.useCloudflareAccess && effective.port !in 1..65535) {
            error("Invalid SSH port: ${effective.port}")
        }

        existing?.let { (_, oldSess) -> runCatching { oldSess.disconnect() } }
        val jsch = buildJsch(effective)
        val dialPort = if (effective.useCloudflareAccess) {
            CloudflareAccessSshSupport.resolvePort(effective.host, effective.port)
        } else {
            effective.port
        }
        val session = jsch.getSession(user, host, dialPort)
        configureSession(session, effective)
        session.connect(12_000)
        cachedSessions[profile.id] = hash to session
        refreshConnectionStates()
        return session
    }

    private fun buildJsch(profile: SshProfileEntity): JSch {
        val jsch = JSch()
        if (profile.privateKeyPem.isNotBlank()) {
            val passBytes = profile.keyPassphrase.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
            jsch.addIdentity(
                "forgemind_key",
                profile.privateKeyPem.toByteArray(Charsets.UTF_8),
                null,
                passBytes
            )
        }
        return jsch
    }

    private fun configureSession(session: Session, profile: SshProfileEntity) {
        if (profile.password.isNotBlank()) {
            session.setPassword(profile.password)
        }
        if (profile.useCloudflareAccess) {
            val effective = CloudflareAccessSshSupport.resolveEffectiveProfile(profile, cachedMcpServers.get())
            val wsUrl = CloudflareAccessSshSupport.buildWebSocketUrl(effective.host, effective.port)
            session.setSocketFactory(
                CloudflareAccessSshSocketFactory(
                    wsUrl = wsUrl,
                    clientId = effective.cfAccessClientId.trim(),
                    clientSecret = effective.cfAccessClientSecret.trim(),
                    connectTimeoutMs = 12_000,
                    httpClient = cfWsClient
                )
            )
        }
        val props = Properties().apply {
            put("StrictHostKeyChecking", "no")
            put("PreferredAuthentications", "publickey,password,keyboard-interactive")
            put("ServerAliveInterval", "15")
            put("ConnectTimeout", "12000")
        }
        session.setConfig(props)
    }

    private fun execViaSession(
        session: Session,
        command: String,
        workDir: String,
        timeoutSeconds: Int
    ): CommandExecResult {
        val ch = session.openChannel("exec") as ChannelExec
        val cdPart = if (workDir.isNotBlank()) "cd ${shellSingleQuote(workDir)} 2>/dev/null || true; " else ""
        val wrapped = "export TERM=xterm-256color LANG=\${LANG:-C.UTF-8}; $cdPart$command"
        ch.setCommand(wrapped)
        val outBuf = ByteArrayOutputStream()
        val errBuf = ByteArrayOutputStream()
        ch.outputStream = outBuf
        ch.setErrStream(errBuf)
        ch.connect(10_000)

        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        var timedOut = false
        while (!ch.isClosed) {
            if (System.currentTimeMillis() > deadline) {
                timedOut = true
                break
            }
            Thread.sleep(60)
        }
        val exitCode = if (ch.isClosed) ch.exitStatus else -1
        runCatching { ch.disconnect() }

        return CommandExecResult(
            stdout = outBuf.toString(Charsets.UTF_8.name()),
            stderr = errBuf.toString(Charsets.UTF_8.name()),
            exitCode = exitCode,
            timedOut = timedOut
        )
    }

    private suspend fun runViaTermuxIntent(
        command: String,
        workDir: String,
        timeoutSeconds: Int
    ): CommandExecResult {
        val token = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<CommandExecResult>()
        TermuxResultReceiver.pendingResults[token] = deferred

        val resultIntent = Intent(context, TermuxResultReceiver::class.java).apply {
            putExtra(TermuxResultReceiver.EXTRA_TOKEN, token)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pendingIntent = PendingIntent.getBroadcast(context, token.hashCode(), resultIntent, flags)

        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", workDir)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pendingIntent)
        }

        val started = runCatching {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }
        if (started.isFailure) {
            TermuxResultReceiver.pendingResults.remove(token)
            return CommandExecResult("", "", -1, error = "Failed to start Termux RunCommandService: ${started.exceptionOrNull()?.message}")
        }

        val awaited = withTimeoutOrNull(timeoutSeconds * 1000L) { deferred.await() }
        TermuxResultReceiver.pendingResults.remove(token)
        return awaited ?: CommandExecResult("", "", -1, timedOut = true, error = "Termux RUN_COMMAND timeout (${timeoutSeconds}s)")
    }

    companion object {
        fun shellSingleQuote(s: String): String = "'" + s.replace("'", "'\"'\"'") + "'"

        fun defaultTermuxProfile(): SshProfileEntity = SshProfileEntity(
            id = "default_termux",
            name = "Termux (local)",
            host = "127.0.0.1",
            port = 2222,
            username = "",
            password = "",
            defaultWorkDir = "",
            isDefault = true,
            enabled = true
        )

        fun defaultExternalSshProfile(): SshProfileEntity = SshProfileEntity(
            id = "external_ssh",
            name = "External SSH",
            host = "",
            port = 2222,
            username = "",
            password = "",
            defaultWorkDir = "",
            isDefault = false,
            enabled = true
        )
    }
}
