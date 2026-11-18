package com.forgemind.app.bridge

object TermuxRoutePolicy {
    fun localBridgeTarget(host: String, cloudflare: Boolean, enabled: Boolean, defaultLocal: Boolean = false): Boolean {
        val address = host.trim().lowercase()
        return enabled && !cloudflare &&
            (address in setOf("127.0.0.1", "localhost", "::1") || (address.isEmpty() && defaultLocal))
    }
    fun allowFallback(commandDispatched: Boolean): Boolean = !commandDispatched
}
