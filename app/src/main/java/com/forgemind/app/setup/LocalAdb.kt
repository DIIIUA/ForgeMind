package com.forgemind.app.setup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.forgemind.app.R
import com.forgemind.app.accessibility.ForgeAccessibilityService
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LocalAdb private constructor(private val context: Context) {
    data class State(val busy: Boolean = false, val paired: Boolean = false, val connected: Boolean = false,
        val message: String = "", val results: Map<SetupGrant, Boolean> = emptyMap())
    private val directory = File(context.noBackupFilesDir, "local-adb").apply { mkdirs() }
    private val _state = MutableStateFlow(State(paired = File(directory, "paired").exists()))
    val state = _state.asStateFlow()
    private val mutex = Mutex()
    private var manager: Manager? = null
    @Volatile private var phase = "idle"
    private class Manager(dir: File) : AbsAdbConnectionManager() {
        private val identityKey: java.security.PrivateKey
        private val cert: Certificate
        init {
            val keyFile = File(dir, "identity.pk8")
            val certFile = File(dir, "identity.crt")
            if (!keyFile.exists() || !certFile.exists()) {
                val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                val dn = X500Name("CN=ForgeMind Local ADB")
                val now = System.currentTimeMillis()
                val holder = JcaX509v3CertificateBuilder(dn, BigInteger(128, SecureRandom()),
                    Date(now - 86_400_000), Date(now + 10L * 365 * 86_400_000), dn, pair.public)
                    .build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
                val generated = JcaX509CertificateConverter().getCertificate(holder)
                keyFile.writeBytes(pair.private.encoded); certFile.writeBytes(generated.encoded)
            }
            identityKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            cert = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            setApi(Build.VERSION.SDK_INT); setHostAddress("127.0.0.1"); setTimeout(12, TimeUnit.SECONDS)
            setThrowOnUnauthorised(true)
        }
        override fun getPrivateKey() = identityKey
        override fun getCertificate() = cert
        override fun getDeviceName() = "ForgeMind"
    }
    // The network library uses blocking sockets. Bound UI lifetime independently and close
    // the connection on timeout; late callbacks cannot set a successful state.
    private suspend fun <T> bounded(block: () -> T): T = withTimeout(30_000) {
        suspendCancellableCoroutine { continuation ->
            val thread = Thread({
                runCatching(block).onSuccess { if (continuation.isActive) continuation.resume(it) }
                    .onFailure { if (continuation.isActive) continuation.resumeWithException(it) }
            }, "ForgeMind-ADB").apply { isDaemon = true }
            continuation.invokeOnCancellation { thread.interrupt(); runCatching { manager?.close() }; manager = null }
            thread.start()
        }
    }
    suspend fun pair(port: Int, code: String) = operation {
        require(Build.VERSION.SDK_INT >= 30)
        val checked = LocalAdbPolicy.pairingCode(code)
        require(port in 1..65535)
        val client = manager ?: withContext(Dispatchers.IO) { Manager(directory) }.also { manager = it }
        PairingRelay(port).use { relay ->
            check(bounded { client.pair("127.0.0.1", relay.port, checked) }) { context.getString(R.string.adb_pair_failed) }
        }
        File(directory, "paired").writeText("paired")
        _state.value = _state.value.copy(paired = true, message = context.getString(R.string.adb_paired))
    }
    suspend fun connect(port: Int? = null) = operation {
        if (port != null) connectPort(port)
        else withTimeout(20_000) {
            phase = "discovery"
            val candidates = Channel<Int>(Channel.UNLIMITED)
            LocalAdbDiscovery(context, LocalAdbDiscovery.CONNECT) { candidates.trySend(it) }.use {
                try {
                    it.start()
                    AdbPortSelection.connect(candidates, ::connectPort)
                } finally { candidates.close() }
            }
        }
    }
    private suspend fun connectPort(resolved: Int) {
        require(resolved in 1..65535)
        withContext(Dispatchers.IO) { manager?.let { runCatching { it.disconnect() } } }
        val client = withContext(Dispatchers.IO) { Manager(directory) }.also { manager = it }
        phase = "connect"
        check(bounded { client.connect("127.0.0.1", resolved) })
        phase = "shell"
        val identity = shell("id -u")
        check(identity.exitCode == 0 && identity.text.trim() == "2000") { "ADB shell UID is not 2000" }
        _state.value = _state.value.copy(connected = true, message = context.getString(R.string.adb_connected))
    }
    private suspend fun shell(command: String): AdbShellReply {
        val client = checkNotNull(manager)
        return bounded {
            val marker = "FM_" + java.util.UUID.randomUUID().toString().replace("-", "")
            val stream = client.openStream("shell:" + AdbShellReply.command(command, marker))
            try { AdbShellReply.read(stream.openInputStream(), marker) }
            finally { runCatching { stream.close() } }
        }
    }
    suspend fun grant(selected: Set<SetupGrant>) = operation {
        check(_state.value.connected)
        val pkg = LocalAdbPolicy.quote(context.packageName)
        val component = context.packageName + "/com.forgemind.app.accessibility.ForgeAccessibilityService"
        val results = _state.value.results.toMutableMap()
        for (grant in selected) {
            val command = when (grant) {
                SetupGrant.OVERLAY -> "cmd appops set $pkg SYSTEM_ALERT_WINDOW allow"
                SetupGrant.SECURE_SETTINGS -> "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS"
                SetupGrant.TERMUX_COMMAND -> "pm grant $pkg com.termux.permission.RUN_COMMAND"
                SetupGrant.MICROPHONE -> "pm grant $pkg android.permission.RECORD_AUDIO"
                SetupGrant.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) "pm grant $pkg android.permission.POST_NOTIFICATIONS" else "true"
                SetupGrant.STORAGE -> "cmd appops set $pkg MANAGE_EXTERNAL_STORAGE allow"
                SetupGrant.BATTERY -> "dumpsys deviceidle whitelist +${context.packageName}"
                SetupGrant.ACCESSIBILITY -> {
                    val before = shell("settings get secure enabled_accessibility_services")
                    check(before.exitCode == 0)
                    "settings put secure enabled_accessibility_services " + LocalAdbPolicy.quote(LocalAdbPolicy.accessibilityServices(before.text, component)) +
                        " && settings put secure accessibility_enabled 1"
                }
            }
            val reply = shell(command)
            if (grant == SetupGrant.ACCESSIBILITY) repeat(20) { if (!ForgeAccessibilityService.isConnected.value) delay(250) }
            results[grant] = reply.exitCode == 0 && granted(grant)
            _state.value = _state.value.copy(results = results.toMap())
        }
        _state.value = _state.value.copy(message = context.getString(R.string.adb_grants_checked))
    }
    fun granted(grant: SetupGrant): Boolean = when (grant) {
        SetupGrant.OVERLAY -> Settings.canDrawOverlays(context)
        SetupGrant.ACCESSIBILITY -> ForgeAccessibilityService.isConnected.value
        SetupGrant.SECURE_SETTINGS -> context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        SetupGrant.TERMUX_COMMAND -> context.checkSelfPermission("com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED
        SetupGrant.MICROPHONE -> context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        SetupGrant.NOTIFICATIONS -> NotificationManagerCompat.from(context).areNotificationsEnabled()
        SetupGrant.STORAGE -> Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
        SetupGrant.BATTERY -> context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    private suspend fun operation(action: suspend () -> Unit) = mutex.withLock {
        _state.value = _state.value.copy(busy = true, message = context.getString(R.string.adb_working))
        try { action(); true }
        catch (timeout: TimeoutCancellationException) {
            _state.value = _state.value.copy(connected = false, message = context.getString(R.string.adb_retry))
            false
        } catch (cancelled: CancellationException) {
            _state.value = _state.value.copy(connected = false, message = context.getString(R.string.adb_retry))
            throw cancelled
        } catch (failure: Exception) {
            // Record only exception types and code locations: never pairing codes,
            // keys, shell commands, remote output, or arbitrary exception messages.
            val trace = generateSequence<Throwable>(failure) { it.cause }.take(5).joinToString("\n") {
                it.javaClass.name + "\n" + it.stackTrace.take(8).joinToString("\n")
            }
            android.util.Log.w("ForgeMindAdb", "$phase: $trace")
            _state.value = _state.value.copy(connected = false, message = context.getString(R.string.adb_retry) + "\n" + failure.javaClass.simpleName)
            false
        } finally { _state.value = _state.value.copy(busy = false) }
    }
    fun disconnect() {
        runCatching { manager?.close() }; manager = null
        _state.value = _state.value.copy(connected = false)
    }
    companion object {
        @Volatile private var instance: LocalAdb? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: LocalAdb(context.applicationContext).also { instance = it }
        }
    }
}
