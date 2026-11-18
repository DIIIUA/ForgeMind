package com.forgemind.app.setup

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Only this device's addresses are accepted. Pairing and shell ports are separate. */
@Suppress("DEPRECATION")
class LocalAdbDiscovery(context: Context, private val type: String, private val onPort: (Int) -> Unit) : AutoCloseable {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val closed = AtomicBoolean(false)
    private val resolving = AtomicBoolean(false)
    private val queue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = close()
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
        override fun onServiceFound(serviceInfo: NsdServiceInfo) { queue.add(serviceInfo); resolveNext() }
    }
    fun start() { try { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) } catch (_: Exception) { close() } }
    private fun resolveNext() {
        if (closed.get() || !resolving.compareAndSet(false, true)) return
        val item = queue.poll() ?: run { resolving.set(false); return }
        try { nsd.resolveService(item, object : NsdManager.ResolveListener {
            private fun next() { resolving.set(false); resolveNext() }
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = next()
            override fun onServiceResolved(info: NsdServiceInfo) {
                try {
                    val local = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                    if (!closed.get() && info.port in 1..65535 && local.any { it == info.host }) onPort(info.port)
                } catch (_: Exception) { /* Try the next service or let the UI offer manual ports. */ }
                finally { next() }
            }
        }) } catch (_: Exception) { resolving.set(false); resolveNext() }
    }
    override fun close() { if (closed.compareAndSet(false, true)) runCatching { nsd.stopServiceDiscovery(listener) } }
    companion object {
        const val PAIRING = "_adb-tls-pairing._tcp"
        const val CONNECT = "_adb-tls-connect._tcp"
    }
}
