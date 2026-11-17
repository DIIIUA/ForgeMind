package com.forgemind.app

import android.content.ContextWrapper
import com.forgemind.app.bridge.*
import com.forgemind.app.data.db.SshProfileEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CommandTaskRouteTests {
    private val profile = SshProfileEntity("original", "Original", host = "first.example", username = "worker")
    private fun task(bridge: TermuxBridge) = CommandTaskRecord(
        "task-00000000-0000-0000-0000-000000000000", CommandTaskBackend.SSH,
        "42", "/task/log", "/work", 1, serverId = profile.id,
        serverFingerprint = bridge.taskServerFingerprint(profile)
    )

    @Test fun changedServerCannotReceiveAnOldTaskControlCommand() = runBlocking {
        val bridge = TermuxBridge(ContextWrapper(null))
        val saved = task(bridge)
        bridge.updateProfiles(listOf(profile.copy(host = "second.example")))
        val failure = runCatching { bridge.runTrackedTaskControl(saved, "echo MUST_NOT_RUN") }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("changed"))
    }

    @Test fun aMissingProfileDoesNotFallBackToTheDefaultServer() = runBlocking {
        val bridge = TermuxBridge(ContextWrapper(null))
        val saved = task(bridge)
        bridge.updateProfiles(listOf(profile.copy(id = "different", isDefault = true)))
        val failure = runCatching { bridge.runTrackedTaskControl(saved, "echo MUST_NOT_RUN") }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("missing or disabled"))
    }

    @Test fun credentialRotationKeepsTheRouteButChangingUserDoesNot() {
        val bridge = TermuxBridge(ContextWrapper(null))
        assertEquals(bridge.taskServerFingerprint(profile), bridge.taskServerFingerprint(profile.copy(password = "test-only")))
        assertNotEquals(bridge.taskServerFingerprint(profile), bridge.taskServerFingerprint(profile.copy(username = "another")))
    }
}
