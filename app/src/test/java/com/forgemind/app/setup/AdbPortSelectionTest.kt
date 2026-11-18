package com.forgemind.app.setup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.net.ConnectException

class AdbPortSelectionTest {
    @Test fun staleAdvertisementDoesNotHideWorkingService() = runTest {
        val ports = Channel<Int>(Channel.UNLIMITED)
        listOf(0, 32001, 32001, 32002).forEach { ports.send(it) }; ports.close()
        val tried = mutableListOf<Int>()
        AdbPortSelection.connect(ports) { port ->
            tried += port
            if (port == 32001) throw ConnectException("stale")
        }
        assertEquals(listOf(32001, 32002), tried)
    }
    @Test fun exhaustedCandidatesCannotReportSuccess() = runTest {
        val ports = Channel<Int>(1); ports.send(32001); ports.close()
        try {
            AdbPortSelection.connect(ports) { throw ConnectException("closed") }
            fail("Must preserve the connection failure")
        } catch (expected: ConnectException) { assertEquals("closed", expected.message) }
    }
    @Test fun cancellationStopsBeforeAnotherConnection() = runTest {
        val ports = Channel<Int>(2); ports.send(32001); ports.send(32002); ports.close()
        var attempts = 0
        try {
            AdbPortSelection.connect(ports) { attempts++; throw CancellationException("cancelled") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { assertEquals(1, attempts) }
    }
}
