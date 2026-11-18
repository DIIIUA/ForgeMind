package com.forgemind.app.setup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import java.io.IOException

/** NSD may return stale advertisements before the currently listening local port. */
internal object AdbPortSelection {
    suspend fun connect(candidates: ReceiveChannel<Int>, attempt: suspend (Int) -> Unit) {
        val tried = mutableSetOf<Int>()
        var lastFailure: Exception? = null
        for (port in candidates) {
            if (port !in 1..65535 || !tried.add(port)) continue
            try { attempt(port); return }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { lastFailure = failure }
            if (tried.size >= 16) break
        }
        throw lastFailure ?: IOException("No reachable local ADB service")
    }
}
