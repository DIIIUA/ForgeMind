package com.forgemind.termuxbridge

import android.content.Context
import android.os.Bundle
import java.net.InetSocketAddress
import java.net.Socket

/** Explicit requests only. The main app owns any human-configured startup list. */
class BridgeServices(context: Context, private val tmux: BridgeTmux) {
    private val attempts = context.getSharedPreferences("service-attempts", Context.MODE_PRIVATE)
    private fun healthy(port: Int) = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1",port),250); true }
    }.getOrDefault(false)

    @Synchronized fun ensure(request: Bundle): Bundle {
        @Suppress("DEPRECATION") val services = request.getParcelableArrayList<Bundle>("services").orEmpty()
        require(services.size <= 4)
        val result = services.map { service ->
            val port = service.getInt("port")
            require(port in 1..65535)
            val command = service.getString("command").orEmpty()
            require(command.isNotBlank() && command.length <= 64_000 && '\u0000' !in command)
            var started = false
            if (!healthy(port) && System.currentTimeMillis() - attempts.getLong("port-$port",0) >= 60_000) {
                // Save before sending. A lost Binder response must not immediately start it twice.
                check(attempts.edit().putLong("port-$port",System.currentTimeMillis()).commit())
                tmux.launchService(command)
                started = true
            }
            val deadline = System.currentTimeMillis() + if (started) 10_000 else 0
            var ready = healthy(port)
            while (!ready && System.currentTimeMillis() < deadline) { Thread.sleep(100); ready = healthy(port) }
            Bundle().apply {
                putInt("port",port); putBoolean("started",started); putBoolean("ready",ready)
                putString("state",if (ready) "working" else if (started) "not_ready" else "retry_later")
            }
        }
        return Bundle().apply { putParcelableArrayList("services",ArrayList(result)) }
    }
}
