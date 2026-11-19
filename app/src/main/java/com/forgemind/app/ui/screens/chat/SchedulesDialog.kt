package com.forgemind.app.ui.screens.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.forgemind.app.R
import com.forgemind.app.scheduling.ScheduledPrompt
import com.forgemind.app.ui.MainViewModel
import com.forgemind.app.ui.theme.ForgeColors
import com.forgemind.app.ui.theme.ForgeType
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

@Composable
fun SchedulesDialog(vm: MainViewModel) {
    val tasks by vm.scheduledPrompts.collectAsState()
    val session by vm.currentSession.collectAsState()
    val error by vm.scheduleError.collectAsState()
    var edit by remember { mutableStateOf<ScheduledPrompt?>(null) }
    var deleting by remember { mutableStateOf<ScheduledPrompt?>(null) }

    AlertDialog(
        onDismissRequest = { vm.showSchedules.value = false },
        containerColor = ForgeColors.SurfaceCard,
        titleContentColor = ForgeColors.TextPrimary,
        textContentColor = ForgeColors.TextPrimary,
        title = { Text(stringResource(R.string.chat_menu_schedules), fontSize = ForgeType.Title, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    stringResource(R.string.sched_desc),
                    fontSize = ForgeType.Meta,
                    color = ForgeColors.TextSecondary
                )
                error?.let { Text(it, color = ForgeColors.DangerRed, fontSize = ForgeType.Meta) }
                TextButton(
                    enabled = session != null,
                    onClick = {
                        edit = ScheduledPrompt(
                            UUID.randomUUID().toString(),
                            session!!.id,
                            "",
                            "",
                            dateTime = LocalDateTime.now().plusMinutes(5).withSecond(0).withNano(0).toString()
                        )
                    }
                ) { Text(stringResource(R.string.sched_create_for_chat)) }
                tasks.forEach { task ->
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.outlinedCardColors(containerColor = ForgeColors.SurfaceElevated)
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(task.name, fontSize = ForgeType.Body, fontWeight = FontWeight.SemiBold, color = ForgeColors.TextPrimary)
                            Text("${task.kind}: ${task.dateTime} · ${task.timeZone}", fontSize = ForgeType.Caption, color = ForgeColors.TextSecondary)
                            Text(
                                task.lastStatus + if (task.lastError.isNotBlank()) ": ${task.lastError}" else "",
                                fontSize = ForgeType.Caption,
                                color = ForgeColors.TextSecondary
                            )
                            Row {
                                TextButton(onClick = { edit = task }) { Text(stringResource(R.string.action_edit)) }
                                TextButton(onClick = { vm.saveSchedule(task.copy(enabled = !task.enabled)) }) {
                                    Text(stringResource(if (task.enabled) R.string.sched_disable else R.string.sched_enable))
                                }
                                TextButton(onClick = { deleting = task }) { Text(stringResource(R.string.action_delete), color = ForgeColors.DangerRed) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.showSchedules.value = false }) { Text(stringResource(R.string.action_close)) } }
    )

    edit?.let { task ->
        var name by remember(task) { mutableStateOf(task.name) }
        var prompt by remember(task) { mutableStateOf(task.prompt) }
        var daily by remember(task) { mutableStateOf(task.kind == "daily") }
        var time by remember(task) { mutableStateOf(task.dateTime) }
        var zone by remember(task) { mutableStateOf(task.timeZone) }
        AlertDialog(
            onDismissRequest = { edit = null },
            containerColor = ForgeColors.SurfaceCard,
            titleContentColor = ForgeColors.TextPrimary,
            textContentColor = ForgeColors.TextPrimary,
            title = { Text(stringResource(R.string.sched_editor_title), fontSize = ForgeType.Title, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    error?.let { Text(it, color = ForgeColors.DangerRed, fontSize = ForgeType.Meta) }
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.sched_name_label)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(prompt, { prompt = it }, label = { Text(stringResource(R.string.sched_prompt_label)) }, minLines = 3, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(daily, {
                            daily = it
                            time = if (it) "09:00" else LocalDateTime.now().plusMinutes(5).withSecond(0).withNano(0).toString()
                        })
                        Text(stringResource(R.string.sched_daily), fontSize = ForgeType.Body)
                    }
                    OutlinedTextField(
                        time,
                        { time = it },
                        label = { Text(stringResource(if (daily) R.string.sched_time_daily_label else R.string.sched_time_once_label)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        zone,
                        { zone = it },
                        label = { Text(stringResource(R.string.sched_timezone_label)) },
                        supportingText = { Text(stringResource(R.string.sched_timezone_example_fmt, ZoneId.systemDefault().id)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && prompt.isNotBlank(),
                    onClick = {
                        vm.saveSchedule(
                            task.copy(
                                name = name,
                                prompt = prompt,
                                kind = if (daily) "daily" else "once",
                                dateTime = time,
                                timeZone = zone,
                                enabled = true
                            )
                        ) { edit = null }
                    }
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { edit = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    deleting?.let { task ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = ForgeColors.SurfaceCard,
            titleContentColor = ForgeColors.TextPrimary,
            textContentColor = ForgeColors.TextPrimary,
            title = { Text(stringResource(R.string.sched_delete_title), fontSize = ForgeType.Title, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    stringResource(R.string.sched_delete_body_fmt, task.name),
                    fontSize = ForgeType.Body,
                    color = ForgeColors.TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.deleteSchedule(task.id); deleting = null }) {
                    Text(stringResource(R.string.action_delete), color = ForgeColors.DangerRed)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}
