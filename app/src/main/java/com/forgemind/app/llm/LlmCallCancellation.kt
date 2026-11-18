package com.forgemind.app.llm

import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.Call

/** A body-less child Job completes as soon as its parent is cancelled. A completion hook on
 * the reader Job itself would wait for the blocked execute()/read to finish before closing it. */
internal suspend fun cancelCallWithCoroutine(call: Call): DisposableHandle {
    val cancellationSignal=Job(currentCoroutineContext()[Job])
    val listener=cancellationSignal.invokeOnCompletion { cause -> if(cause!=null)call.cancel() }
    return object:DisposableHandle {
        override fun dispose() {
            listener.dispose()
            cancellationSignal.complete()
        }
    }
}
