package com.forgemind.app.accessibility

import kotlinx.coroutines.delay

/** Waits for Android to bind an authorized service. Never changes authorization. */
internal suspend fun <T : Any> awaitAccessibilityConnection(
    current: () -> T?,
    isAuthorized: () -> Boolean,
    timeoutMillis: Long = 3_000L,
): T? {
    current()?.let { return it }
    var waited = 0L
    while (isAuthorized() && waited < timeoutMillis) {
        val interval = minOf(100L, timeoutMillis - waited)
        delay(interval)
        waited += interval
        current()?.let { return it }
    }
    return null
}
