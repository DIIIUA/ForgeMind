package com.forgemind.app.data.prefs

import android.content.Context
import com.forgemind.app.data.model.ChatAttachment
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ChatDraft(val text: String = "", val attachments: List<ChatAttachment> = emptyList(), val commandMode: Boolean = false)

/** Application-owned writer: drafts survive ViewModel disposal and never share a chat key. */
class ChatDraftStore(context: Context, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("chat_drafts_v1", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val cache = mutableMapOf<String, ChatDraft>()
    private val pending = mutableMapOf<String, ChatDraft>()
    private val lock = Any()
    private val writes = Mutex()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    val error = MutableStateFlow<String?>(null)

    init { scope.launch { for (ignored in signal) { delay(120); runCatching { flush() }.onFailure { error.value = "Не удалось сохранить черновик: ${it.message}" } } } }

    fun get(sessionId: String): ChatDraft = synchronized(lock) {
        cache.getOrPut(sessionId) {
            runCatching { json.decodeFromString<ChatDraft>(prefs.getString(sessionId, null) ?: return@getOrPut ChatDraft()) }
                .getOrElse { error.value = "Не удалось прочитать черновик"; ChatDraft() }
        }
    }

    fun set(sessionId: String, draft: ChatDraft) {
        synchronized(lock) { cache[sessionId] = draft; pending[sessionId] = draft }
        signal.trySend(Unit)
    }

    fun remove(sessionId: String) = set(sessionId, ChatDraft())
    fun flushSoon() { scope.launch { runCatching { flush() }.onFailure { error.value = "Не удалось сохранить черновик: ${it.message}" } } }

    suspend fun flush() = withContext(Dispatchers.IO) { writes.withLock {
        val batch = synchronized(lock) { pending.toMap().also { pending.clear() } }
        if (batch.isEmpty()) return@withLock
        try {
            val edit = prefs.edit()
            batch.forEach { (id, draft) ->
                if (draft == ChatDraft()) edit.remove(id) else edit.putString(id, json.encodeToString(draft))
            }
            check(edit.commit()) { "Ошибка записи" }
            error.value = null
        } catch (failure: Exception) {
            synchronized(lock) { batch.forEach { (id, draft) -> pending.putIfAbsent(id, draft) } }
            throw failure
        }
    } }
}
