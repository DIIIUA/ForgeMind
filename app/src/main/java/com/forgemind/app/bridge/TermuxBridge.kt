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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class CommandExecResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val timedOut: Boolean = false,
    val error: String? = null,
    val fallbackAllowed: Boolean = false,
    val route: String? = null,
    val outputTruncated: Boolean = false
)

data class AsyncCommandStart(
    val pid: String,
    val logFile: String,
    val workDir: String,
    val statusFile: String? = null,
    val processIdentity: String? = null,
    val serverId: String? = null,
    val serverFingerprint: String? = null,
    val backend: CommandTaskBackend? = null
)

/** A transport error after dispatch is not permission to execute the same command elsewhere. */
class AsyncCommandLaunchUncertain(message: String) : IllegalStateException(message)

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return
        val bundle = intent.getBundleExtra("result")
        val stdout = bundle?.getString("stdout") ?: ""
        val stderr = bundle?.getString("stderr") ?: ""
        val exitCode = bundle?.getInt("exitCode", -1) ?: -1
        val errCode = bundle?.getInt("err", 0) ?: 0
        val errmsg = bundle?.getString("errmsg")
        val err = if (TermuxCallbackStatus.failed(errCode, errmsg)) {
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
    internal val appContext: Context get() = context.applicationContext
    private val sharedTmux by lazy { SharedTmuxExecutor(appContext) }
    val remote by lazy { RemoteTermuxClient.get(appContext) }
    fun hasDirectTerminal(): Boolean = hasUnifiedTermux() || remote.installed()
    fun usesRemoteBridge(serverHint: String? = null): Boolean {
        val profile = runCatching { resolveProfile(serverHint) }.getOrNull() ?: return false
        return isLocalDeviceProfile(profile) && remote.installed()
    }
    fun remotePath(path: String, serverHint: String? = null): String {
        if (path.startsWith("/") || path.startsWith("~")) return path
        val base = resolveProfile(serverHint).defaultWorkDir.trim().ifBlank { RemoteTermuxClient.HOME }
        return if (path.isBlank() || path == ".") base else base.trimEnd('/') + "/" + path
    }

    private val profileMutexes = ConcurrentHashMap<String, Mutex>()
    private val cachedSessions = ConcurrentHashMap<String, Pair<Int, Session>>()
    private val _connectedProfileIds = MutableStateFlow<Set<String>>(emptySet())
    val connectedProfileIds: StateFlow<Set<String>> = _connectedProfileIds.asStateFlow()

    private val _sshReachability = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val sshReachability: StateFlow<Map<String, Boolean>> = _sshReachability.asStateFlow()

    fun refreshConnectionStates() {
        val activeIds = cachedSessions
            .filterValues { (_, sess) -> sess.isConnected }
            .keys
            .toSet()
        _connectedProfileIds.value = activeIds
        if (activeIds.isNotEmpty()) {
            val next = _sshReachability.value.toMutableMap()
            activeIds.forEach { next[it] = true }
            _sshReachability.value = next
        }
    }

    suspend fun probeEnabledProfilesReachability(): Map<String, Boolean> = withContext(Dispatchers.IO) {
        val enabled = allProfiles.filter {
            it.enabled && !(com.forgemind.app.BuildConfig.TERMUX_PLUGIN && it.id == "default_termux")
        }
        if (enabled.isEmpty()) {
            _sshReachability.value = emptyMap()
            return@withContext emptyMap()
        }
        val probed = coroutineScope {
            enabled.map { profile ->
                async {
                    val ok = probeSingleSshProfile(profile)
                    profile.id to ok
                }
            }.awaitAll().toMap()
        }
        _sshReachability.value = probed
        probed
    }

    private fun probeSingleSshProfile(profile: SshProfileEntity): Boolean {
        if (cachedSessions[profile.id]?.second?.isConnected == true) return true
        val effective = CloudflareAccessSshSupport.resolveEffectiveProfile(profile, cachedMcpServers.get())
        val host = CloudflareAccessSshSupport.normalizeHost(effective.host)
        if (host.isBlank()) {
            return isLocalDeviceProfile(profile) && isTermuxInstalled() && hasTermuxPermission()
        }
        if (effective.username.isBlank() && !(isLocalDeviceProfile(profile) && isTermuxInstalled() && hasTermuxPermission())) {
            return false
        }
        val dialPort = if (effective.useCloudflareAccess) {
            CloudflareAccessSshSupport.resolvePort(effective.host, effective.port)
        } else {
            effective.port
        }
        if (dialPort !in 1..65535) return false
        val tcpOk = runCatching {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(host, dialPort), 1600)
                true
            }
        }.getOrDefault(false)
        if (tcpOk) return true
        return isLocalDeviceProfile(profile) && isTermuxInstalled() && hasTermuxPermission()
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
            val res = runCatching {
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
            val next = _sshReachability.value.toMutableMap()
            next[profile.id] = res.isSuccess
            _sshReachability.value = next
            res
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

    fun hasUnifiedTermux(): Boolean =
        com.forgemind.app.BuildConfig.TERMUX_PLUGIN && SharedTermuxRuntime.hasSharedUid(context)

    fun usesSharedTerminal(serverHint: String? = null): Boolean = hasUnifiedTermux() &&
        (serverHint.isNullOrBlank() || serverHint.equals("default_termux", true) ||
            runCatching { resolveProfile(serverHint).id == "default_termux" }.getOrDefault(false))

    suspend fun runWorkspaceCommand(command: String, workspace: java.io.File, workDir: String?,
                                    timeoutSeconds: Int, dynamicEnv: Map<String, String> = emptyMap()): CommandExecResult {
        check(hasUnifiedTermux())
        val script = SharedWorkspaceShell.script(java.io.File(context.filesDir, "termux-workspaces"),
            workspace, workDir, command, dynamicEnv)
        return sharedTmux.execute(script, workspace.absolutePath, timeoutSeconds)
    }

    suspend fun runWorkspaceCommandAsync(command: String, workspace: java.io.File, workDir: String?,
                                         dynamicEnv: Map<String, String> = emptyMap()): Result<AsyncCommandStart> =
        withContext(Dispatchers.IO) { runCatching {
            check(hasUnifiedTermux())
            val script = SharedWorkspaceShell.script(java.io.File(context.filesDir, "termux-workspaces"),
                workspace, workDir, command, dynamicEnv, persistChanges = false)
            sharedTmux.startAsync(script, workspace.absolutePath)
        } }

    suspend fun runCommand(
        command: String,
        workDir: String? = null,
        timeoutSeconds: Int = 35,
        serverHint: String? = null
    ): CommandExecResult = withContext(Dispatchers.IO) {
        if (usesSharedTerminal(serverHint)) {
            return@withContext sharedTmux.execute(command, workDir?.trim().orEmpty(), timeoutSeconds)
        }
        val profile = runCatching { resolveProfile(serverHint) }.getOrElse { err ->
            return@withContext CommandExecResult("", "", -1, error = err.message)
        }
        val dir = workDir?.trim()?.takeIf { it.isNotBlank() }
            ?: profile.defaultWorkDir.trim()

        if (usesRemoteBridge(serverHint) && remote.available(forCommands = true)) {
            return@withContext remote.execute(command, dir, timeoutSeconds).copy(route = "Termux bridge")
        }
        if (usesSharedTerminal(serverHint)) {
            return@withContext sharedTmux.execute(command, dir, timeoutSeconds)
        }

        var dispatched = false
        val sshAttempt = if (profile.enabled) {
            runCatching {
                mutexFor(profile.id).withLock {
                    val sess = openOrReuseSessionLocked(profile)
                    dispatched = true
                    execViaSession(sess, command, dir, timeoutSeconds)
                }
            }
        } else {
            Result.failure(IllegalStateException("SSH '${profile.name}' is disabled"))
        }
        if (sshAttempt.isSuccess) {
            return@withContext sshAttempt.getOrThrow().copy(route = "SSH: " + profile.name)
        }
        sshAttempt.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        if (!TermuxRoutePolicy.allowFallback(dispatched)) {
            return@withContext CommandExecResult("", "", -1,
                error = "SSH command outcome is unknown on '" + profile.name + "'. Inspect it before retrying. " + sshAttempt.exceptionOrNull()?.message)
        }

        val sshError = sshAttempt.exceptionOrNull()?.message
        if (isLocalDeviceProfile(profile) && (serverHint.isNullOrBlank() || isLocalHost(profile.host)) &&
            isTermuxInstalled() && hasTermuxPermission()
        ) {
            val termuxDir = dir.ifBlank { "/data/data/com.termux/files/home" }
            val intentRes = runViaTermuxIntent(command, termuxDir, timeoutSeconds).copy(route = "RUN_COMMAND")
            if (intentRes.error == null || intentRes.stdout.isNotBlank()) {
                return@withContext intentRes
            }
            return@withContext intentRes.copy(
                error = "Both SSH '${profile.name}' (${sshError ?: "disabled"}) and RUN_COMMAND (${intentRes.error}) failed"
            )
        }

        CommandExecResult(
            fallbackAllowed = true,
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
            if (usesSharedTerminal(serverHint)) {
                return@runCatching sharedTmux.startAsync(command, workDir?.trim().orEmpty())
            }
            val profile = resolveProfile(serverHint)
            val dir = workDir?.trim()?.takeIf { it.isNotBlank() }
                ?: profile.defaultWorkDir.trim()
            if (usesRemoteBridge(serverHint) && remote.available(forCommands = true)) {
                return@runCatching remote.start(command, dir)
            }
            if (usesSharedTerminal(profile.id)) {
                // Its own window of the shared session; status and output are files, no nohup/setsid.
                return@runCatching sharedTmux.startAsync(command, dir)
            }
            val logName = ".forgemind_cmd_${UUID.randomUUID()}.log"
            val escapedLog = shellSingleQuote(logName)
            val statusName = "$logName.status"
            val routeFingerprint = taskServerFingerprint(profile)
            val runner = "($command) >> $escapedLog 2>&1; ec=\$?; " +
                "printf '%s\\n' \"\$ec\" > ${shellSingleQuote(statusName)}; " +
                "printf '\\n__FORGEMIND_EXIT__:%s\\n' \"\$ec\" >> $escapedLog"
            val script = CommandTaskProcess.identityFunction + "\n" +
                (if (dir.isBlank()) "" else "cd ${shellSingleQuote(dir)} || exit 1\n") + """
                : > $escapedLog
                SHELL_BIN=$(command -v bash || echo sh)
                nohup "${'$'}SHELL_BIN" -c ${shellSingleQuote(runner)} >/dev/null 2>&1 </dev/null &
                fm_pid=${'$'}!
                fm_cwd=${'$'}(pwd -P)
                printf '__FM_PID__:%s\n__FM_LOG__:%s/%s\n__FM_CWD__:%s\n__FM_IDENTITY__:%s\n' \
                    "${'$'}fm_pid" "${'$'}fm_cwd" $escapedLog "${'$'}fm_cwd" "${'$'}(fm_identity "${'$'}fm_pid")"
            """.trimIndent()
            var backend = CommandTaskBackend.SSH
            val res = try {
                mutexFor(profile.id).withLock {
                    // Connection failure is still before dispatch; an exec failure is uncertain.
                    check(profile.enabled) { "SSH '${profile.name}' is disabled" }
                    val connection = openOrReuseSessionLocked(profile)
                    try { execViaSession(connection, script, dir, 15) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        throw AsyncCommandLaunchUncertain("Background launch outcome is unknown on '${profile.name}'. Do not retry automatically. Log name: $logName. ${failure.message}")
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (uncertain: AsyncCommandLaunchUncertain) { throw uncertain }
            catch (failure: Exception) {
                if (!isLocalDeviceProfile(profile) || !isLocalHost(profile.host) || !isTermuxInstalled() || !hasTermuxPermission()) throw failure
                backend = CommandTaskBackend.TERMUX_SERVICE
                runViaTermuxIntent(script, dir.ifBlank { "/data/data/com.termux/files/home" }, 15)
            }
            fun field(prefix: String) = res.stdout.lineSequence().firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix).orEmpty()
            val pid = field("__FM_PID__:")
            val logFile = field("__FM_LOG__:")
            if (res.timedOut || res.error != null || !CommandTaskProcess.validPid(pid) || !logFile.startsWith('/')) {
                throw AsyncCommandLaunchUncertain("Background launch outcome is unknown. Do not retry automatically. Log name: $logName. ${res.stderr.ifBlank { res.error.orEmpty() }}")
            }
            AsyncCommandStart(pid, logFile, field("__FM_CWD__:"), "$logFile.status",
                field("__FM_IDENTITY__:").takeIf { it.isNotBlank() },
                profile.id.takeIf { backend == CommandTaskBackend.SSH },
                routeFingerprint.takeIf { backend == CommandTaskBackend.SSH }, backend)
        }
    }

    /** Never resolve a saved task through the currently active server or an execution fallback. */
    internal suspend fun runTrackedTaskControl(task: CommandTaskRecord, script: String): CommandExecResult = withContext(Dispatchers.IO) {
        if (task.backend == CommandTaskBackend.TERMUX_SERVICE) {
            check(isTermuxInstalled() && hasTermuxPermission()) { "Termux RUN_COMMAND is unavailable for this saved task" }
            return@withContext runViaTermuxIntent(script, task.workDir, 8)
        }
        require(task.backend == CommandTaskBackend.SSH)
        val profile = allProfiles.singleOrNull { it.id == task.serverId && it.enabled }
            ?: error("The original task server is missing or disabled; no other server was used")
        check(taskServerFingerprint(profile) == task.serverFingerprint) { "The task server address or user has changed; refusing to redirect the task" }
        mutexFor(profile.id).withLock {
            execViaSession(openOrReuseSessionLocked(profile), script, "", 8)
        }
    }

    internal fun taskServerFingerprint(profile: SshProfileEntity): String {
        val effective = CloudflareAccessSshSupport.resolveEffectiveProfile(profile, cachedMcpServers.get())
        val route = listOf(profile.id, effective.host, effective.port.toString(), effective.username,
            effective.useCloudflareAccess.toString()).joinToString("\u0000")
        return java.security.MessageDigest.getInstance("SHA-256").digest(route.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    suspend fun checkCommandStatus(
        pid: String,
        logFile: String,
        tailLines: Int = 60,
        serverHint: String? = null
    ): String =
        withContext(Dispatchers.IO) {
            if (pid.startsWith("tmux:")) return@withContext sharedTmux.asyncStatus(pid.removePrefix("tmux:"), logFile, tailLines)
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
        if (pid.startsWith("tmux:")) return sharedTmux.killAsync(pid.removePrefix("tmux:"))
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
        TermuxRoutePolicy.localBridgeTarget(profile.host, profile.useCloudflareAccess,
            profile.enabled, defaultLocal = profile.id == "default_termux")

    fun isLocalSshTarget(serverHint: String? = null): Boolean {
        val profile = runCatching { resolveProfile(serverHint) }.getOrNull() ?: return false
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

    private suspend fun openOrReuseSessionLocked(profile: SshProfileEntity): Session {
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
        cancellableBlockingIo({ session.disconnect() }) { session.connect(12_000) }
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

    private suspend fun execViaSession(
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
        return cancellableBlockingIo({ ch.disconnect() }) {
            try {
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
                CommandExecResult(
                    stdout = outBuf.toString(Charsets.UTF_8.name()),
                    stderr = errBuf.toString(Charsets.UTF_8.name()),
                    exitCode = if (ch.isClosed) ch.exitStatus else -1,
                    timedOut = timedOut
                )
            } finally {
                runCatching { ch.disconnect() }
            }
        }
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
