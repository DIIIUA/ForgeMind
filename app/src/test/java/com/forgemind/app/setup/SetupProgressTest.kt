package com.forgemind.app.setup

import org.junit.Assert.*
import org.junit.Test

class SetupProgressTest {
    @Test fun freshInstallOpensWelcome() {
        val state = SetupProgress.restore(null, null)
        assertTrue(state.opensAutomatically)
        assertEquals(SetupStep.WELCOME, state.step)
    }
    @Test fun interruptedGuideResumesItsStep() {
        val state = SetupProgress.restore("MCP", "ACTIVE")
        assertTrue(state.opensAutomatically)
        assertEquals(SetupStep.MCP, state.step)
    }
    @Test fun laterDoesNotNagOnNextLaunch() {
        val deferred = SetupProgress().visit(SetupStep.VOICE).defer()
        val restored = SetupProgress.restore(deferred.step.name, deferred.disposition.name)
        assertFalse(restored.opensAutomatically)
        assertEquals(SetupStep.VOICE, restored.reopen().step)
        assertTrue(restored.reopen().opensAutomatically)
    }
    @Test fun completedGuideOnlyReopensExplicitlyFromBeginning() {
        val state = SetupProgress().complete()
        val restored = SetupProgress.restore(state.step.name, state.disposition.name)
        assertFalse(restored.opensAutomatically)
        assertEquals(SetupStep.WELCOME, restored.reopen().step)
        assertTrue(restored.reopen().opensAutomatically)
    }
    @Test fun unknownStepAfterUpgradeDoesNotReopenCompletedGuide() {
        val state = SetupProgress.restore("OLD_STEP", "COMPLETED")
        assertFalse(state.opensAutomatically)
        assertEquals(SetupStep.WELCOME, state.step)
    }
    @Test fun unknownStoredStateHasRecoverableWelcome() {
        assertEquals(SetupProgress(), SetupProgress.restore("bad", "bad"))
    }
    @Test fun automaticChoiceSurvivesFinishAndReplay() {
        val state = SetupProgress(mode = SetupMode.AUTOMATIC).complete().reopen()
        assertEquals(SetupMode.AUTOMATIC, state.mode)
        assertEquals(SetupStep.WELCOME, state.step)
        assertEquals(SetupMode.AUTOMATIC, SetupProgress.restore("ENVIRONMENT", "ACTIVE", "AUTOMATIC").mode)
    }
    @Test fun termuxAndPermissionsComeBeforeModels() {
        assertEquals(listOf(SetupStep.WELCOME, SetupStep.ENVIRONMENT, SetupStep.PERMISSIONS, SetupStep.MODELS),
            SetupStep.entries.take(4))
    }
}
