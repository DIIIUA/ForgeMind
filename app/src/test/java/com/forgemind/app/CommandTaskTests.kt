package com.forgemind.app

import com.forgemind.app.bridge.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CommandTaskTests {
    @get:Rule val temporary = TemporaryFolder()
    private val boot = "00000000-0000-0000-0000-000000000001"
    private fun store() = CommandTaskStore(temporary.newFolder())
    private fun task(store: CommandTaskStore) = store.create(CommandTaskBackend.EMBEDDED, "1234", "/private/log", "/work",
        statusFile = "/private/status", processIdentity = "$boot:98765")
    private fun snapshot(task: CommandTaskRecord, state: CommandTaskState, exit: Int? = null) =
        CommandTaskSnapshot(task.id, state, exit, backend = task.backend, workDir = task.workDir, serverId = task.serverId)

    @Test fun taskRouteSurvivesStoreRecreation() {
        val directory = temporary.newFolder()
        val original = CommandTaskStore(directory).create(CommandTaskBackend.SSH, "42", "/original/log", "/original/work",
            statusFile = "/original/status", processIdentity = "$boot:9", serverId = "original-server", serverFingerprint = "route-hash")
        assertEquals(original, CommandTaskStore(directory).get(original.id))
        assertEquals(original, CommandTaskStore(directory).list().tasks.single())
    }

    @Test fun cancellationFlagSurvivesStoreRecreation() {
        val directory = temporary.newFolder()
        val original = task(CommandTaskStore(directory))
        CommandTaskStore(directory).markCancelled(original.id)
        assertTrue(CommandTaskStore(directory).get(original.id).cancelled)
    }

    @Test fun taskIdCannotBeAFilePathOrAnUnknownPid() {
        val store = store()
        for (bad in listOf("../secret", "1234", "task-../../secret", "task-00000000-0000-0000-0000-000000000000")) {
            assertTrue(runCatching { store.get(bad) }.isFailure)
        }
    }

    @Test fun corruptRecordDoesNotHideOtherTasksAndIsReported() {
        val directory = temporary.newFolder()
        val store = CommandTaskStore(directory)
        val good = task(store)
        val broken = task(store)
        File(directory, "${broken.id}.json").writeText("{broken")
        assertEquals(listOf(good), store.list().tasks)
        assertEquals(1, store.list().unreadableRecords)
        assertTrue(runCatching { store.get(broken.id) }.isFailure)
    }

    @Test fun metadataWithAnotherIdCannotRedirectLookup() {
        val directory = temporary.newFolder()
        val store = CommandTaskStore(directory)
        val first = task(store)
        val second = task(store)
        File(directory, "${first.id}.json").writeText(File(directory, "${second.id}.json").readText())
        assertTrue(runCatching { store.get(first.id) }.isFailure)
    }

    @Test fun listIsBoundedAndDoesNotDeleteOlderRecords() {
        val store = store()
        val tasks = (1..4).map { task(store) }
        assertEquals(2, store.list(2).tasks.size)
        assertTrue(store.list(2).hasMore)
        tasks.forEach { assertEquals(it, store.get(it.id)) }
    }

    @Test fun paginationCanRecoverEverySavedTask() {
        val store = store()
        val expected = (1..5).map { task(store).id }.toSet()
        val pages = listOf(store.list(2, 0), store.list(2, 2), store.list(2, 4))
        assertEquals(expected, pages.flatMap { it.tasks }.map { it.id }.toSet())
        assertEquals(listOf(true, true, false), pages.map { it.hasMore })
    }

    @Test fun largeOutputIsBoundedAndMarkedAsTruncated() = runBlocking {
        val store = store()
        val task = task(store)
        val controller = CommandTaskController(store, { t, _ ->
            snapshot(t, CommandTaskState.COMPLETED, 0).copy(output = "x".repeat(20_000) + "END")
        }, { error("No stop expected") })
        val result = controller.status(task.id)
        assertEquals(8000, result.output.length)
        assertTrue(result.output.endsWith("END"))
        assertTrue(result.outputTruncated)
    }

    @Test fun cancellingOneTaskDoesNotStopAnother() = runBlocking {
        val store = store()
        val first = task(store)
        val second = task(store)
        val stopped = mutableListOf<String>()
        val controller = CommandTaskController(store, { t, _ ->
            snapshot(t, if (t.cancelled) CommandTaskState.CANCELLED else CommandTaskState.RUNNING)
        }, { t -> stopped += t.id; CommandTaskStopResult.STOPPED })
        assertEquals(CommandTaskState.CANCELLED, controller.cancel(first.id).state)
        assertEquals(listOf(first.id), stopped)
        assertEquals(CommandTaskState.RUNNING, controller.status(second.id).state)
        assertFalse(store.get(second.id).cancelled)
    }

    @Test fun completedTaskNeverSignalsAReusedPid() = runBlocking {
        val store = store()
        val task = task(store)
        val controller = CommandTaskController(store, { t, _ -> snapshot(t, CommandTaskState.COMPLETED, 7) },
            { error("Must not signal a completed task") })
        assertEquals(7, controller.cancel(task.id).exitCode)
        assertFalse(store.get(task.id).cancelled)
    }

    @Test fun unknownProcessIsNotCancelled() = runBlocking {
        val store = store()
        val task = task(store)
        val controller = CommandTaskController(store, { t, _ -> snapshot(t, CommandTaskState.UNKNOWN) },
            { error("Must not signal an unknown process") })
        assertTrue(runCatching { controller.cancel(task.id) }.isFailure)
    }

    @Test fun groupWaitReturnsIndependentExitCodes() = runBlocking {
        val store = store()
        val first = task(store)
        val second = task(store)
        var secondPolls = 0
        val controller = CommandTaskController(store, { t, _ ->
            when {
                t.id == first.id -> snapshot(t, CommandTaskState.COMPLETED, 7)
                ++secondPolls >= 2 -> snapshot(t, CommandTaskState.COMPLETED, 0)
                else -> snapshot(t, CommandTaskState.RUNNING)
            }
        }, { error("Waiting must not stop tasks") })
        val result = controller.waitFor(listOf(first.id, second.id), 2000)
        assertTrue(result.allFinished)
        assertFalse(result.timedOut)
        assertEquals(listOf(7, 0), result.tasks.map { it.exitCode })
    }

    @Test fun waitAnyLeavesOtherTaskRunning() = runBlocking {
        val store = store()
        val first = task(store)
        val second = task(store)
        val controller = CommandTaskController(store, { t, _ ->
            snapshot(t, if (t.id == first.id) CommandTaskState.COMPLETED else CommandTaskState.RUNNING)
        }, { error("Waiting must not stop tasks") })
        val result = controller.waitFor(listOf(first.id, second.id), 2000, any = true)
        assertFalse(result.allFinished)
        assertFalse(result.timedOut)
        assertEquals(CommandTaskState.RUNNING, result.tasks[1].state)
    }

    @Test fun waitTimeoutDoesNotCancelTheCommand() = runBlocking {
        val store = store()
        val task = task(store)
        val controller = CommandTaskController(store, { t, _ -> delay(5000); snapshot(t, CommandTaskState.RUNNING) },
            { error("Timeout must not stop the command") })
        val result = withTimeout(1000) { controller.waitFor(listOf(task.id), 40) }
        assertTrue(result.timedOut)
        assertFalse(result.allFinished)
        assertFalse(store.get(task.id).cancelled)
    }

    @Test fun invalidGroupIsRejectedBeforeAnyStatusRequest() = runBlocking {
        val store = store()
        val task = task(store)
        var calls = 0
        val controller = CommandTaskController(store, { t, _ -> calls++; snapshot(t, CommandTaskState.RUNNING) },
            { error("No stop expected") })
        assertTrue(runCatching { controller.waitFor(listOf(task.id, "../other"), 100) }.isFailure)
        assertEquals(0, calls)
    }

    @Test fun unavailableStatusIsNotSuccessfulCompletion() = runBlocking {
        val store = store()
        val task = task(store)
        val controller = CommandTaskController(store, { _, _ -> error("Original SSH server is disabled") },
            { error("No stop expected") })
        val result = controller.waitFor(listOf(task.id), 1000)
        assertFalse(result.allFinished)
        assertEquals("unavailable", result.reason)
        assertEquals(CommandTaskState.UNKNOWN, result.tasks.single().state)
    }

    @Test fun procIdentityHandlesSpacesAndParenthesesInProcessName() {
        val fields = listOf("S") + List(18) { "0" } + "12345"
        assertEquals("$boot:12345", CommandTaskProcess.identityFromProc("77 (name ) with spaces) ${fields.joinToString(" ")}", boot))
        assertNull(CommandTaskProcess.identityFromProc("77 (zombie) Z ${List(19) { "0" }.joinToString(" ")}", boot))
    }

    @Test fun shellExitTextInTheLogIsNotAnAuthoritativeExitCode() {
        val directory = temporary.newFolder()
        val log = File(directory, "output").apply { writeText("__FORGEMIND_EXIT__:0\n") }
        val record = CommandTaskRecord("task-00000000-0000-0000-0000-000000000000", CommandTaskBackend.EMBEDDED,
            "999999999", log.absolutePath, "/work", 1, statusFile = File(directory, "missing-status").absolutePath)
        val result = CommandTaskProcess.localSnapshot(record, 10)
        assertNull(result.exitCode)
        assertEquals(CommandTaskState.UNKNOWN, result.state)
    }

    @Test fun stopScriptRefusesUnverifiedOrProcessGroupPids() {
        val record = task(store())
        assertTrue(runCatching { CommandTaskProcess.stopScript(record.copy(pid = "0")) }.isFailure)
        assertTrue(runCatching { CommandTaskProcess.stopScript(record.copy(pid = "-1")) }.isFailure)
        assertTrue(runCatching { CommandTaskProcess.stopScript(record.copy(processIdentity = null)) }.isFailure)
    }
}
