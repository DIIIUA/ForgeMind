package com.forgemind.app.data.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ToolGrant(val sessionId: String, val toolName: String, val fingerprint: String,
    val createdAt: Long = System.currentTimeMillis())

@Serializable
data class CompactionEvent(val sessionId: String, val before: Int, val after: Int, val automatic: Boolean,
    val restored: Boolean = false, val createdAt: Long = System.currentTimeMillis())

@Serializable
private data class FeatureState(val grants: List<ToolGrant> = emptyList(), val compactions: List<CompactionEvent> = emptyList(),
    val schedules: List<com.forgemind.app.scheduling.ScheduledPrompt> = emptyList(),
    val runs: List<com.forgemind.app.scheduling.ScheduledRun> = emptyList())

/** Private feature state. Grants contain no credentials or command arguments; schedules contain the user's own prompts. */
class ConversationFeatureStore(context: Context) {
    private val prefs = context.getSharedPreferences("conversation_features", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var state = runCatching { json.decodeFromString<FeatureState>(prefs.getString("state", "{}")!!) }.getOrDefault(FeatureState())
    private val _grants = MutableStateFlow(state.grants)
    val grants = _grants.asStateFlow()
    private val _compactions = MutableStateFlow(state.compactions)
    val compactions = _compactions.asStateFlow()
    private val _schedules = MutableStateFlow(state.schedules)
    val schedules = _schedules.asStateFlow()
    @Synchronized private fun save(next: FeatureState) {
        check(prefs.edit().putString("state", json.encodeToString(next)).commit()) { "Не удалось сохранить настройки функций" }
        state = next; _grants.value = state.grants; _compactions.value = state.compactions; _schedules.value = state.schedules
    }
    @Synchronized fun allowed(sessionId: String, tool: String, fingerprint: String): Boolean =
        state.grants.any { it.sessionId == sessionId && it.toolName == tool && it.fingerprint == fingerprint }
    @Synchronized fun grant(value: ToolGrant) = save(state.copy(grants =
        (state.grants.filterNot { it.sessionId == value.sessionId && it.toolName == value.toolName } + value).takeLast(500)))
    @Synchronized fun revoke(sessionId: String, tool: String) = save(state.copy(grants =
        state.grants.filterNot { it.sessionId == sessionId && it.toolName == tool }))
    @Synchronized fun clearGrants(sessionId: String) = save(state.copy(grants = state.grants.filterNot { it.sessionId == sessionId }))
    @Synchronized fun record(event: CompactionEvent) = save(state.copy(compactions = (state.compactions + event).takeLast(200)))
    @Synchronized fun markCompactionRestored(sessionId: String) {
        val index=state.compactions.indexOfLast {it.sessionId==sessionId && !it.restored}
        if(index>=0) save(state.copy(compactions=state.compactions.mapIndexed {i,event -> if(i==index)event.copy(restored=true)else event}))
    }
    @Synchronized fun saveSchedule(task: com.forgemind.app.scheduling.ScheduledPrompt) {
        require(state.schedules.size < 100 || state.schedules.any { it.id == task.id }) { "Не больше 100 расписаний" }
        save(state.copy(schedules = state.schedules.filterNot { it.id == task.id } + task))
    }
    @Synchronized fun removeSchedule(id: String) = save(state.copy(schedules = state.schedules.filterNot { it.id == id }))
    @Synchronized fun replaceSchedule(task: com.forgemind.app.scheduling.ScheduledPrompt, expectedRevision: Long): Boolean {
        if (state.schedules.none { it.id == task.id && it.revision == expectedRevision }) return false
        saveSchedule(task)
        return true
    }
    @Synchronized fun claimRun(run: com.forgemind.app.scheduling.ScheduledRun): Boolean {
        if (state.runs.any { it.id == run.id }) return false
        save(state.copy(runs = (state.runs + run).takeLast(1000)))
        return true
    }
    @Synchronized fun runStatus(id: String): String? = state.runs.firstOrNull { it.id == id }?.status
    @Synchronized fun finishRun(id: String, status: String) = save(state.copy(runs = state.runs.map { if (it.id == id) it.copy(status = status) else it }))
    @Synchronized fun releaseUnstartedRun(id: String) = save(state.copy(runs = state.runs.filterNot { it.id == id }))
}
