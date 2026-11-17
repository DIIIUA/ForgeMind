package com.forgemind.app

import com.forgemind.app.bridge.cancellableBlockingIo
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CancellableBlockingIoTests {
    @Test fun successfulIoReturnsItsValueWithoutCancellingTheResource() = runBlocking {
        var cleaned = false
        assertEquals(42, cancellableBlockingIo({ cleaned = true }) { 42 })
        assertFalse(cleaned)
    }

    @Test fun deadlineClosesAndInterruptsBlockingIo() = runBlocking {
        val cleaned = AtomicBoolean()
        val exited = CountDownLatch(1)
        val result = withTimeoutOrNull(100) {
            cancellableBlockingIo({ cleaned.set(true) }) {
                try { Thread.sleep(10_000); "unexpected" }
                finally { exited.countDown() }
            }
        }
        assertNull(result)
        assertTrue(cleaned.get())
        assertTrue("Blocking worker did not exit", exited.await(1, TimeUnit.SECONDS))
    }

    @Test fun ioFailureIsReturnedWithoutRetryingTheOperation() = runBlocking {
        var attempts = 0
        val error = runCatching { cancellableBlockingIo({}) { attempts++; error("connection lost") } }.exceptionOrNull()
        assertEquals("connection lost", error?.message)
        assertEquals(1, attempts)
    }
}
