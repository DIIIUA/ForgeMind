package com.forgemind.app

import com.forgemind.app.accessibility.awaitAccessibilityConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccessibilityConnectionTests {
    @Test fun `connected service is returned without waiting`() = runTest {
        val service = Any()
        assertSame(service, awaitAccessibilityConnection({ service }, { true }))
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun `disabled service is never enabled by a read request`() = runTest {
        var authorized = false
        assertNull(awaitAccessibilityConnection<Any>({ null }, { authorized }))
        assertFalse(authorized)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun `slow Android binding is allowed to finish without toggling permission`() = runTest {
        val service = Any()
        var connected: Any? = null
        val authorized = true
        launch { delay(1_500); connected = service }
        assertSame(service, awaitAccessibilityConnection({ connected }, { authorized }))
        assertTrue(authorized)
        assertEquals(1_500L, testScheduler.currentTime)
    }

    @Test fun `user disablement while waiting stops recovery`() = runTest {
        var authorized = true
        launch { delay(250); authorized = false }
        assertNull(awaitAccessibilityConnection<Any>({ null }, { authorized }))
        assertFalse(authorized)
        assertEquals(300L, testScheduler.currentTime)
    }

    @Test fun `unbound authorized service times out without removing authorization`() = runTest {
        val authorized = true
        assertNull(awaitAccessibilityConnection<Any>({ null }, { authorized }))
        assertTrue(authorized)
        assertEquals(3_000L, testScheduler.currentTime)
    }

    @Test fun `cancelling caller leaves authorization intact`() = runTest {
        val authorized = true
        val pending = async { awaitAccessibilityConnection<Any>({ null }, { authorized }) }
        runCurrent()
        advanceTimeBy(150)
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertTrue(authorized)
    }
}
