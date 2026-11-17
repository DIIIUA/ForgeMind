package com.forgemind.app.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PendingModelFallback(
    val id: String,
    val sessionId: String,
    val step: FallbackStep
)

/** A single cancellable question. Old buttons/finalizers cannot resolve a newer run. */
class ModelFallbackApproval {
    private val lock = Any()
    private val state = MutableStateFlow<PendingModelFallback?>(null)
    val pending = state.asStateFlow()
    private var waiter: CompletableDeferred<Boolean>? = null

    suspend fun await(request: PendingModelFallback): Boolean {
        val context = currentCoroutineContext()
        val deferred = CompletableDeferred<Boolean>(context[Job])
        synchronized(lock) {
            context.ensureActive()
            check(waiter == null)
            waiter = deferred
            state.value = request
        }
        try {
            return deferred.await()
        } finally {
            synchronized(lock) {
                if (waiter === deferred) {
                    waiter = null
                    state.value = null
                }
            }
            deferred.cancel()
        }
    }

    fun resolve(id: String, allow: Boolean) = synchronized(lock) {
        if (state.value?.id != id) return@synchronized
        val old = waiter
        waiter = null
        state.value = null
        old?.complete(allow)
    }

    fun cancel() = synchronized(lock) {
        val old = waiter
        waiter = null
        state.value = null
        old?.cancel()
    }
}
