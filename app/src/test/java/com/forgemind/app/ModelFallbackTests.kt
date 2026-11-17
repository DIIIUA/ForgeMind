package com.forgemind.app

import com.forgemind.app.agent.*
import com.forgemind.app.data.db.ChatMessageEntity
import com.forgemind.app.data.model.*
import com.forgemind.app.llm.LlmHttpFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelFallbackTests {
    private val a = FallbackModel("a", "model-a")
    private val b = FallbackModel("b", "model-b")
    private val c = FallbackModel("c", "model-c")
    private val d = FallbackModel("d", "model-d")
    private val e = FallbackModel("e", "model-e")

    @Test fun `favorites are ordered deduplicated and unknown providers are ignored`() {
        assertEquals(listOf(b, a, FallbackModel("a", "ns::model")),
            FallbackModel.fromFavorites(listOf("b::model-b", "a::model-a", "b::model-b",
                "gone::x", "broken", "a::", "a::ns::model"), setOf("a", "b")))
    }

    @Test fun `no alternative means no retry loop`() {
        assertNull(ModelFallbackSequence(a, emptyList()).afterFailure(false))
        assertNull(ModelFallbackSequence(a, listOf(a, a)).afterFailure(true))
    }

    @Test fun `same provider different model is an alternative`() {
        val other = FallbackModel(a.providerId, "second-model")
        assertEquals(other, ModelFallbackSequence(a, listOf(other)).afterFailure(false)!!.to)
    }

    @Test fun `confirmation is enabled by default and persists`() {
        val old = LlmHttpFactory.json.decodeFromString<AgentSettings>("{}")
        assertTrue(old.askBeforeModelFallback)
        val saved = LlmHttpFactory.json.encodeToString(old.copy(askBeforeModelFallback = false))
        assertFalse(LlmHttpFactory.json.decodeFromString<AgentSettings>(saved).askBeforeModelFallback)
        assertTrue(ModelFallbackSequence(a, listOf(b)).afterFailure(true)!!.requiresConfirmation)
    }

    @Test fun `three failed switches always stop automatic mode`() {
        val seq = ModelFallbackSequence(a, listOf(a, b, c, d, e))
        for ((i, model) in listOf(b, c, d).withIndex()) {
            val step = seq.afterFailure(false)!!
            assertFalse(step.requiresConfirmation)
            assertEquals(model, step.to)
            assertEquals(i + 1, step.attempt)
            seq.accept(step)
        }
        val guard = seq.afterFailure(false)!!
        assertTrue(guard.mandatoryGuard)
        assertTrue(guard.requiresConfirmation)
        assertEquals(3, guard.failedSwitches)
        assertEquals(e, guard.to)
    }

    @Test fun `short list stops before repeating a failed model`() {
        val seq = ModelFallbackSequence(a, listOf(b))
        seq.accept(seq.afterFailure(false)!!)
        val repeat = seq.afterFailure(false)!!
        assertEquals(a, repeat.to)
        assertTrue(repeat.mandatoryGuard)
    }

    @Test fun `continue starts another bounded group`() {
        val seq = ModelFallbackSequence(a, listOf(b, c, d, e))
        repeat(3) { seq.accept(seq.afterFailure(false)!!) }
        val guard = seq.afterFailure(false)!!
        seq.accept(guard)
        val next = seq.afterFailure(false)!!
        assertFalse(next.requiresConfirmation)
        assertEquals(2, next.attempt)
        seq.accept(next)
        seq.accept(seq.afterFailure(false)!!)
        assertTrue(seq.afterFailure(false)!!.mandatoryGuard)
    }

    @Test fun `successful model resets consecutive failure budget`() {
        val seq = ModelFallbackSequence(a, listOf(b, c, d, e))
        repeat(3) { seq.accept(seq.afterFailure(false)!!) }
        seq.succeeded()
        val next = seq.afterFailure(false)!!
        assertFalse(next.mandatoryGuard)
        assertEquals(1, next.attempt)
        assertEquals(e, next.to)
    }

    private fun request(id: String) = PendingModelFallback(id, "session", ModelFallbackSequence(a, listOf(b)).afterFailure(true)!!)

    @Test fun `approval suspends without starting next request`() = runTest {
        val gate = ModelFallbackApproval()
        var nextStarted = false
        val task = async { if (gate.await(request("one"))) nextStarted = true }
        runCurrent()
        assertFalse(task.isCompleted)
        assertFalse(nextStarted)
        assertEquals("session", gate.pending.value!!.sessionId)
        gate.resolve("one", true)
        task.await()
        assertTrue(nextStarted)
        assertNull(gate.pending.value)
    }

    @Test fun `denial does not switch`() = runTest {
        val gate = ModelFallbackApproval()
        val task = async { gate.await(request("one")) }
        runCurrent()
        gate.resolve("one", false)
        assertFalse(task.await())
        assertNull(gate.pending.value)
    }

    @Test fun `stop clears the prompt and cancels its waiter`() = runTest {
        val gate = ModelFallbackApproval()
        val task = async { gate.await(request("old")) }
        runCurrent()
        gate.cancel()
        task.join()
        assertTrue(task.isCancelled)
        assertNull(gate.pending.value)
    }

    @Test fun `parent cancellation clears prompt and stale buttons cannot resolve new run`() = runTest {
        val gate = ModelFallbackApproval()
        val old = async { gate.await(request("old")) }
        runCurrent()
        old.cancelAndJoin()
        assertNull(gate.pending.value)
        val fresh = async { gate.await(request("new")) }
        runCurrent()
        gate.resolve("old", true)
        runCurrent()
        assertFalse(fresh.isCompleted)
        gate.resolve("new", true)
        assertTrue(fresh.await())
    }

    @Test fun `old finalizer cannot clear a new prompt`() = runTest {
        val gate = ModelFallbackApproval()
        val old = async { gate.await(request("old")) }
        runCurrent()
        gate.cancel()
        val fresh = async(start = CoroutineStart.UNDISPATCHED) { gate.await(request("new")) }
        runCurrent()
        assertTrue(old.isCancelled)
        assertEquals("new", gate.pending.value!!.id)
        gate.resolve("new", true)
        assertTrue(fresh.await())
    }

    @Test fun `fallback history keeps tool result and partial answer but omits status and errors`() {
        fun msg(i: Int, role: LlmRole, text: String) = ChatMessageEntity(
            id = "m$i", sessionId = "s", orderIndex = i, role = role.name, content = text)
        val call = ToolCallInfo(id = "t", name = "file_read", argumentsJson = "{}")
        val rows = listOf(
            msg(0, LlmRole.USER, "Continue the task"),
            msg(1, LlmRole.ASSISTANT, "").copy(toolCallsJson = LlmHttpFactory.json.encodeToString(listOf(call))),
            msg(2, LlmRole.TOOL, "already read").copy(toolCallId = "t", toolName = "file_read"),
            msg(3, LlmRole.ASSISTANT, "partial answer"),
            msg(4, LlmRole.ASSISTANT, "provider failed").copy(isError = true),
            msg(5, LlmRole.SYSTEM, "trying another model").copy(toolName = ModelFallbackSequence.EVENT_TOOL_NAME)
        )
        val wire = AgentEngine.buildWireHistory("You are model-b", "saved summary", rows)
        assertEquals(1, wire.count { it.role == LlmRole.SYSTEM })
        assertTrue(wire.first().content.contains("saved summary"))
        assertTrue(wire.any { it.role == LlmRole.TOOL && it.content == "already read" })
        assertTrue(wire.any { it.content == "partial answer" })
        assertFalse(wire.any { it.content.contains("provider failed") || it.content.contains("trying another") })
    }
}
