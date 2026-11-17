package com.forgemind.app.bridge

import kotlinx.coroutines.suspendCancellableCoroutine

/** Adapts blocking network clients that do not observe coroutine deadlines themselves. */
internal suspend fun <T> cancellableBlockingIo(cleanup: () -> Unit, operation: () -> T): T =
    suspendCancellableCoroutine { continuation ->
        val worker = Thread({
            try {
                if (continuation.isActive) {
                    val result = runCatching(operation)
                    if (result.isFailure) runCatching(cleanup)
                    continuation.resumeWith(result)
                }
            } finally {
                // Cancellation can happen before a blocking connect has installed its socket.
                if (!continuation.isActive && continuation.isCancelled) runCatching(cleanup)
            }
        }, "ForgeMind-SSH-IO").apply { isDaemon = true }
        continuation.invokeOnCancellation {
            runCatching(cleanup)
            worker.interrupt()
        }
        worker.start()
    }
