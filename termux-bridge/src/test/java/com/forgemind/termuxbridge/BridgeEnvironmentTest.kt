package com.forgemind.termuxbridge
import org.junit.Assert.*
import org.junit.Test

class BridgeEnvironmentTest {
    @Test fun inheritedTmuxStateIsRemoved() {
        assertEquals(mapOf("EDITOR" to "vim"), BridgeEnvironment.clean(mapOf("TMUX" to "socket", "TMUX_PANE" to "%1", "SHLVL" to "3", "OLDPWD" to "/old", "EDITOR" to "vim")))
    }
    @Test fun numberingRestartsAtTwoWhenAllManagedSessionsAreGone() { assertEquals(2,BridgeTmux.nextNumber(emptyList(),99)) }
    @Test fun retainedSessionsKeepLabelsUnique() { assertEquals(8,BridgeTmux.nextNumber(listOf(2,7),3)) }
    @Test fun foreignSessionNamesAreNotAccepted() { assertFalse(BridgeTmux.validName("server")); assertFalse(BridgeTmux.validName("forgemind-../server")) }
}
