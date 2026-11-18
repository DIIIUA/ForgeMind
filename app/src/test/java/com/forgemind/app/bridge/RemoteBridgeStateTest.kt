package com.forgemind.app.bridge
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class RemoteBridgeStateTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun remoteAgentTabIsNotAManualChatCommandTarget() {
        val tab=TerminalTabState("id","Agent",TerminalMode.TERMUX_PTY,sharedSession="fm-agent-"+UUID.randomUUID())
        assertFalse(tab.acceptsManualChatCommand)
    }
    @Test fun managedManualTabCanReceiveChatCommands() {
        val session=ManagedTermuxSession("$2","forgemind-"+UUID.randomUUID(),2,false)
        assertTrue(TerminalTabState("id","Manual",TerminalMode.TERMUX_PTY,sharedSession=session.name,managedSession=session).acceptsManualChatCommand)
    }
    @Test fun closedTabCannotReceiveCommands() { assertFalse(TerminalTabState("id","Local",TerminalMode.LOCAL_SHELL,isAlive=false).acceptsManualChatCommand) }
    @Test fun bridgeRequestKeepsOnePersistedTaskAcrossTrackingAndRestart() {
        val pid="bridge:"+UUID.randomUUID()
        val store=CommandTaskStore(temporary.root)
        val beforeDispatch=store.create(CommandTaskBackend.REMOTE_BRIDGE,pid,pid,"/home")
        val afterDispatch=CommandTaskStore(temporary.root).create(CommandTaskBackend.REMOTE_BRIDGE,pid,pid,"/home")
        assertEquals(beforeDispatch,afterDispatch)
        assertEquals(1,store.list().tasks.size)
    }
    @Test fun unknownExecutionErrorDoesNotEnableAnotherExecutionRoute() {
        assertFalse(CommandExecResult("","",-1,error="connection lost").fallbackAllowed)
    }
}
