package com.forgemind.termuxbridge

import org.junit.Assert.*
import org.junit.Test

class BridgeTaskSnapshotStateTest {
    @Test fun completionDuringTmuxQueryIsNotReportedAsLost() {
        var exit: Int? = null
        val result=BridgeTaskSnapshotState.sample({exit}) { exit=0; null }
        assertEquals(0,result.exitCode)
        assertNull(result.runningPane)
    }
    @Test fun completedTaskDoesNotDependOnTmuxAvailability() {
        val result=BridgeTaskSnapshotState.sample({7}) { error("Tmux must not be queried") }
        assertEquals(7,result.exitCode)
    }
    @Test fun runningTaskRetainsItsOwnedPane() {
        val result=BridgeTaskSnapshotState.sample({null}) { "%17" }
        assertNull(result.exitCode)
        assertEquals("%17",result.runningPane)
    }
}
