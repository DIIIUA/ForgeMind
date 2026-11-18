package com.forgemind.app.scheduling

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.forgemind.app.ForgeMindApp
import com.forgemind.app.R
import kotlinx.coroutines.*

class ScheduledPromptWorker(context: Context, params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val c=(applicationContext as ForgeMindApp).container
        val task=c.conversationFeatures.schedules.value.firstOrNull { it.id==inputData.getString("task_id") } ?: return Result.success()
        if (!task.enabled || task.revision!=inputData.getLong("revision",-1) || task.nextRunAt!=inputData.getLong("due",-1)) return Result.success()
        if (c.db.sessionDao().getById(task.sessionId)==null) { c.schedules.advance(task,false,"Чат удалён");return Result.failure() }
        if (c.agentEngine.isRunning.value) return Result.retry()
        val occurrence=SchedulePolicy.occurrence(task)
        if (!c.conversationFeatures.claimRun(ScheduledRun(occurrence,task.id))) {
            if (c.conversationFeatures.runStatus(occurrence)=="DONE") c.schedules.advance(task,true)
            else c.conversationFeatures.replaceSchedule(task.copy(enabled=false,lastStatus="Нужна проверка",
                lastError="Предыдущий запуск прервался; результат неизвестен. Автоматический повтор не выполнен."),task.revision)
            return Result.success()
        }
        var started=false
        try {
            c.settingsRepo.loadAll()
            setForeground(foreground(task.name))
            val completion=CompletableDeferred<Boolean>()
            started=c.agentEngine.tryScheduledTurn(occurrence,task.sessionId,task.prompt) { completion.complete(it) }
            if (!started) { c.conversationFeatures.releaseUnstartedRun(occurrence);return Result.retry() }
            c.conversationFeatures.replaceSchedule(task.copy(lastStatus="Выполняется",lastRunAt=System.currentTimeMillis()),task.revision)
            val completed=withTimeout(25*60*1000L) { completion.await() }
            val lastReply=c.db.messageDao().getForSession(task.sessionId).lastOrNull { it.role=="ASSISTANT" }
            val success=completed && lastReply?.isError!=true
            c.conversationFeatures.finishRun(occurrence,if(success) "DONE" else "FAILED")
            c.schedules.advance(task,success,if(success) "" else "Ход остановлен или завершился ошибкой. См. чат.")
            return Result.success()
        } catch (cancelled: CancellationException) {
            if (started) c.agentEngine.cancelScheduledTurn(occurrence)
            withContext(NonCancellable) {
                c.conversationFeatures.finishRun(occurrence,"INTERRUPTED")
                val current=c.conversationFeatures.schedules.value.firstOrNull { it.id==task.id }
                if (current?.revision==task.revision) c.conversationFeatures.replaceSchedule(current.copy(enabled=false,
                    lastStatus="Прервано",lastError="Автоматический повтор отключён. Проверьте чат перед новым запуском."),task.revision)
            }
            throw cancelled
        } catch (failure: Exception) {
            if (started) c.agentEngine.cancelScheduledTurn(occurrence)
            c.conversationFeatures.finishRun(occurrence,"FAILED")
            c.schedules.advance(task,false,failure.message.orEmpty().take(500))
            return Result.failure()
        }
    }
    private fun foreground(name: String): ForegroundInfo {
        val manager=applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("scheduled_tasks","Задачи по расписанию",NotificationManager.IMPORTANCE_LOW))
        val notification=NotificationCompat.Builder(applicationContext,"scheduled_tasks")
            .setSmallIcon(R.drawable.ic_stat_forgemind).setContentTitle(name).setContentText("ForgeMind выполняет задачу. Подтверждения доступны в чате.")
            .setOngoing(true).build()
        return ForegroundInfo(39001,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
