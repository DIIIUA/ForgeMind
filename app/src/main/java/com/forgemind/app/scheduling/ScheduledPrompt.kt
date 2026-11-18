package com.forgemind.app.scheduling

import kotlinx.serialization.Serializable
import java.time.*

@Serializable
data class ScheduledPrompt(val id: String, val sessionId: String, val name: String, val prompt: String,
    val kind: String = "once", val dateTime: String, val timeZone: String = ZoneId.systemDefault().id,
    val enabled: Boolean = true, val revision: Long = 1, val nextRunAt: Long = 0,
    val lastStatus: String = "Ожидает", val lastError: String = "", val lastRunAt: Long = 0)

@Serializable
data class ScheduledRun(val id: String, val taskId: String, val status: String = "STARTED", val createdAt: Long = System.currentTimeMillis())

object SchedulePolicy {
    fun validate(task: ScheduledPrompt) {
        require(task.id.isNotBlank() && task.id.length<=200)
        require(task.name.isNotBlank() && task.name.length <= 120) { "Имя задачи: 1–120 символов" }
        require(task.prompt.isNotBlank() && task.prompt.length <= 32000) { "Текст задачи: 1–32000 символов" }
        require(task.sessionId.isNotBlank() && task.kind in setOf("once", "daily"))
        ZoneId.of(task.timeZone)
        if (task.kind=="daily") LocalTime.parse(task.dateTime)
        else runCatching { OffsetDateTime.parse(task.dateTime) }.getOrElse { LocalDateTime.parse(task.dateTime); null }
    }
    fun next(task: ScheduledPrompt, now: Long): Long {
        validate(task)
        val zone = ZoneId.of(task.timeZone)
        val instant = Instant.ofEpochMilli(now)
        val next = if (task.kind == "daily") {
            val time = LocalTime.parse(task.dateTime)
            val today = instant.atZone(zone).toLocalDate()
            val candidate = today.atTime(time).atZone(zone).toInstant()
            if (candidate.isAfter(instant)) candidate else today.plusDays(1).atTime(time).atZone(zone).toInstant()
        } else {
            runCatching { OffsetDateTime.parse(task.dateTime).toInstant() }
                .getOrElse { LocalDateTime.parse(task.dateTime).atZone(zone).toInstant() }
        }
        require(next.isAfter(instant)) { "Время запуска уже прошло" }
        return next.toEpochMilli()
    }
    fun occurrence(task: ScheduledPrompt): String = "${task.id}:${task.revision}:${task.nextRunAt}"
}
