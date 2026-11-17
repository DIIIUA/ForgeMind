package com.forgemind.app

import com.forgemind.app.bridge.ManagedTermuxSessions
import org.junit.Assert.assertEquals
import org.junit.Test

class ManagedSessionNumberTests {
    @Test fun closingEverySessionResetsTheNextLabelToTwo() {
        assertEquals(2, ManagedTermuxSessions.nextSessionNumber(emptyList(), 48))
    }

    @Test fun aFreshInstallationStartsAtTwo() {
        assertEquals(2, ManagedTermuxSessions.nextSessionNumber(emptyList(), 2))
    }

    @Test fun aKeptSessionPreventsResetEvenWhenNewerSessionsHaveClosed() {
        assertEquals(8, ManagedTermuxSessions.nextSessionNumber(listOf(2), 8))
    }

    @Test fun sessionsRecoveredFromTermuxTakePrecedenceOverAnOldCounter() {
        assertEquals(10, ManagedTermuxSessions.nextSessionNumber(listOf(2, 9), 3))
    }
}
