package com.forgemind.app.scheduling

import android.content.Context
import androidx.work.*
import com.forgemind.app.data.prefs.ConversationFeatureStore
import java.util.concurrent.TimeUnit

class ScheduleService(private val context: Context, private val store: ConversationFeatureStore,
    private val db: com.forgemind.app.data.db.ForgeMindDatabase) {
    suspend fun manage(sessionId: String, args: kotlinx.serialization.json.JsonObject): String {
        fun text(key: String) = (args[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim().orEmpty()
        val action=text("action").ifBlank { "list" }
        if (action=="list") return kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(ScheduledPrompt.serializer()),store.schedules.value)
        val old=store.schedules.value.firstOrNull { it.id==text("id") }
        if (action!="create") require(old!=null) { "Расписание не найдено" }
        when(action) {
            "delete" -> delete(old!!.id)
            "pause" -> save(old!!.copy(enabled=false))
            "enable" -> save(old!!.copy(enabled=true))
            "run_now" -> save(old!!.copy(id=java.util.UUID.randomUUID().toString(),kind="once",dateTime=java.time.Instant.now().plusSeconds(5).toString(),enabled=true,revision=1))
            "create","update" -> {
                val target=text("session_id").ifBlank { old?.sessionId ?: sessionId }
                require(db.sessionDao().getById(target)!=null) { "Укажите существующий чат" }
                save(ScheduledPrompt(old?.id ?: java.util.UUID.randomUUID().toString(),target,
                    text("name").ifBlank { old?.name.orEmpty() },text("prompt").ifBlank { old?.prompt.orEmpty() },
                    text("kind").ifBlank { old?.kind ?: "once" },text("date_time").ifBlank { old?.dateTime.orEmpty() },
                    text("time_zone").ifBlank { old?.timeZone ?: java.time.ZoneId.systemDefault().id },revision=old?.revision ?: 1))
            }
            else -> error("action: list/create/update/pause/enable/delete/run_now")
        }
        return "Расписание изменено: $action. Android может отложить запуск из-за сети или энергосбережения."
    }
    fun save(task: ScheduledPrompt) {
        SchedulePolicy.validate(task)
        val current = store.schedules.value.firstOrNull { it.id == task.id }
        check(current == null || current.revision == task.revision) { "Расписание изменилось. Откройте его заново." }
        val next = task.copy(revision = (current?.revision ?: 0) + 1,
            nextRunAt = if (task.enabled) SchedulePolicy.next(task,System.currentTimeMillis()) else 0,
            lastStatus = if (task.enabled) "Ожидает" else "Отключено", lastError = "")
        val saved = if (current == null) { store.saveSchedule(next);true } else store.replaceSchedule(next,current.revision)
        check(saved) { "Расписание изменилось" }
        WorkManager.getInstance(context).cancelAllWorkByTag(tag(task.id))
        if (next.enabled) enqueue(next)
    }
    fun delete(id: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag(tag(id))
        store.removeSchedule(id)
    }
    fun restore() { store.schedules.value.filter { it.enabled && it.nextRunAt > 0 }.forEach(::enqueue) }
    fun enqueue(task: ScheduledPrompt) {
        val request = OneTimeWorkRequestBuilder<ScheduledPromptWorker>()
            .setInputData(workDataOf("task_id" to task.id,"revision" to task.revision,"due" to task.nextRunAt))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay((task.nextRunAt-System.currentTimeMillis()).coerceAtLeast(0),TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.LINEAR,30,TimeUnit.SECONDS)
            .addTag(tag(task.id)).build()
        WorkManager.getInstance(context).enqueueUniqueWork(SchedulePolicy.occurrence(task),ExistingWorkPolicy.KEEP,request)
    }
    fun advance(task: ScheduledPrompt, success: Boolean, error: String = "") {
        val current = store.schedules.value.firstOrNull { it.id == task.id } ?: return
        if (current.revision != task.revision || !current.enabled) return
        val next = if (task.kind == "daily") current.copy(nextRunAt = SchedulePolicy.next(current,System.currentTimeMillis()),
            lastStatus = if(success) "Выполнено" else "Ошибка",lastError=error,lastRunAt=System.currentTimeMillis())
        else current.copy(enabled=false,lastStatus=if(success) "Выполнено" else "Ошибка",lastError=error,lastRunAt=System.currentTimeMillis())
        if (store.replaceSchedule(next,current.revision) && next.enabled) enqueue(next)
    }
    private fun tag(id: String) = "forgemind-schedule-$id"
}
