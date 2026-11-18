package com.forgemind.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import com.forgemind.app.data.archive.*
import com.forgemind.app.data.db.*
import com.forgemind.app.data.model.*
import com.forgemind.app.data.prefs.*
import com.forgemind.app.scheduling.*
import com.forgemind.app.ui.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.net.ServerSocket
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Synthetic loopback only. No phone, external account, Termux, or user's server involved. */
object ConversationFeaturesScenario {
    fun run(test: Instrumentation) {
        val results=mutableListOf<String>();var failed:Throwable?=null
        fun pass(name:String) { results+="$name=PASS";File(test.targetContext.filesDir,"features69-progress.txt").writeText(results.joinToString("\n")) }
        fun <T> main(block:()->T):T { var value:Result<T>?=null;test.runOnMainSync {value=runCatching(block)};return value!!.getOrThrow() }
        val ctx=test.targetContext;val c=(ctx.applicationContext as ForgeMindApp).container
        val prefix="qa69-${UUID.randomUUID()}";val sid=prefix;val pid="custom_$prefix"
        val responses=LinkedBlockingQueue<String>();val requests=LinkedBlockingQueue<JsonObject>()
        val socket=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        val fixture=kotlin.concurrent.thread(isDaemon=true,name="features69-loopback") {
            while(!socket.isClosed) {
                val accepted=runCatching {socket.accept()}.getOrNull() ?: break
                runCatching {accepted.use { conn ->
                    conn.soTimeout=20000;val input=conn.getInputStream();val header=StringBuilder()
                    while(!header.endsWith("\r\n\r\n")){val b=input.read();check(b>=0);header.append(b.toChar())}
                    val length=header.lines().first {it.startsWith("Content-Length:",true)}.substringAfter(':').trim().toInt()
                    val body=ByteArray(length);var read=0
                    while(read<length){val n=input.read(body,read,length-read);check(n>0);read+=n}
                    requests.put(Json.parseToJsonElement(body.decodeToString()).jsonObject)
                    val content=responses.poll(20,TimeUnit.SECONDS) ?: error("No fixture response")
                    val holding=content=="__HOLD_REASONING__"
                    val frame=buildJsonObject {putJsonArray("choices"){addJsonObject{putJsonObject("delta"){
                        put(if(holding) "reasoning_content" else "content",if(holding) "VOICE_HOLD_MARKER" else content)
                    }}}}
                    conn.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\ndata: $frame\n\n".toByteArray())
                        if(!holding)write("data: [DONE]\n\n".toByteArray())
                        flush()
                    }
                    if(holding)while(input.read()!=-1){ } // only cancellation releases this stream
                }}
            }
        }
        try {
            check(android.os.Build.HARDWARE in setOf("ranchu","goldfish")) {"Emulator only"}
            test.uiAutomation.serviceInfo=test.uiAutomation.serviceInfo.apply {
                flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            runBlocking {
                c.settingsRepo.loadAll()
                withContext(Dispatchers.IO) {
                    val name="$prefix-migration.db";val file=ctx.getDatabasePath(name).apply {parentFile!!.mkdirs()}
                    val schema=Json.parseToJsonElement(test.context.assets.open("forgemind-schema-v8.json").bufferedReader().readText()).jsonObject["database"]!!.jsonObject
                    android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file,null).use { old ->
                        schema["entities"]!!.jsonArray.forEach { value ->
                            val entity=value.jsonObject;val table=entity["tableName"]!!.jsonPrimitive.content
                            old.execSQL(entity["createSql"]!!.jsonPrimitive.content.replace("\u0024{TABLE_NAME}",table))
                            (entity["indices"] as? JsonArray).orEmpty().forEach {old.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\u0024{TABLE_NAME}",table))}
                            if(table=="assistant_profiles") {
                                val row=android.content.ContentValues()
                                entity["fields"]!!.jsonArray.forEach { f -> val field=f.jsonObject
                                    if(field["notNull"]?.jsonPrimitive?.booleanOrNull==true) {
                                        val key=field["columnName"]!!.jsonPrimitive.content
                                        if(field["affinity"]!!.jsonPrimitive.content=="TEXT")row.put(key,"legacy")else row.put(key,1)
                                    }
                                };old.insertOrThrow(table,null,row)
                            }
                        };old.version=8
                    }
                    val migrated=Room.databaseBuilder(ctx,ForgeMindDatabase::class.java,name).addMigrations(ForgeMindDatabase.MIGRATION_8_9, ForgeMindDatabase.MIGRATION_9_10).build()
                    try {check(migrated.assistantDao().get("legacy")!!.generationConfigJson=="{}")} finally {migrated.close();ctx.deleteDatabase(name)}
                    pass("room8_to9_preserves_existing_profile_and_inherits_provider_parameters")
                }
                val previousSettings=c.settingsRepo.agentSettings.value;val previousVoice=c.settingsRepo.voiceSettings.value
                val oldTasks=c.conversationFeatures.schedules.value
                check(oldTasks.none {it.enabled}) {"Use an isolated emulator without active schedules"}
                val oldProvider=c.settingsRepo.providerConfigs.value[pid]
                val activity=test.startActivitySync(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                val vm=main {ViewModelProvider(activity)[MainViewModel::class.java]}
                val oldSession=vm.currentSessionId.value
                fun nodes():List<AccessibilityNodeInfo> {
                    val all=mutableListOf<AccessibilityNodeInfo>()
                    fun visit(n:AccessibilityNodeInfo?){if(n==null)return;all+=n;for(i in 0 until n.childCount)visit(n.getChild(i))}
                    val windows=test.uiAutomation.windows.sortedByDescending{it.layer}
                    if(windows.isEmpty())visit(test.uiAutomation.rootInActiveWindow)else windows.forEach{visit(it.root)}
                    return all
                }
                suspend fun click(text:String) {
                    withTimeout(10000){while(true){var n=nodes().firstOrNull{it.text?.toString()==text || it.contentDescription?.toString()==text}
                        while(n!=null&&!n.isClickable)n=n.parent
                        if(n?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)break
                        delay(100)}};delay(200)
                }
                suspend fun until(predicate:suspend ()->Boolean){withTimeout(45000){while(!predicate())delay(50)}}
                try {
                    main {vm.setupWizard.defer()}
                    val profile=AssistantProfileEntity(prefix,"Preset QA",generationConfigJson="""{"temperature":0.2,"maxTokens":512,"reasoning":"off"}""")
                    c.db.assistantDao().insert(profile)
                    c.db.sessionDao().upsert(ChatSessionEntity(sid,"Features QA",providerId=pid,modelId="fixture",assistantId=prefix))
                    c.settingsRepo.saveProviderConfig(ProviderConfig(pid,modelId="fixture",baseUrl="http://127.0.0.1:${socket.localPort}/chat",rawEndpointUrl=true,
                        wireProtocolOverride=WireProtocol.OPENAI_COMPAT,toolCallingMode=ToolCallingMode.XML_JSON))
                    c.settingsRepo.saveVoiceSettings(previousVoice.copy(autoSpeakReplies=false))
                    c.settingsRepo.saveAgentSettings(previousSettings.copy(enableTools=false,autoCompactContext=false,thinkEveryToolCalls=0,maxTurns=4))
                    main {vm.selectSession(sid);vm.selectSection(AppSection.CHAT)}
                    until {vm.currentSession.value?.id==sid}
                    responses.put("SCHEDULE_DONE [[NO_TOOL_NEEDED]]")
                    val task=ScheduledPrompt(prefix,sid,"Synthetic schedule","Run once",dateTime=java.time.Instant.now().plusSeconds(3).toString(),timeZone="UTC")
                    c.schedules.save(task)
                    until {c.conversationFeatures.schedules.value.first{it.id==prefix}.lastStatus=="Выполнено"}
                    check(c.db.messageDao().getForSession(sid).count{it.role=="USER"&&it.content=="Run once"}==1)
                    pass("workmanager_runs_scheduled_turn_once_and_disables_once_task")
                    val request=requests.poll(10,TimeUnit.SECONDS) ?: error("Missing scheduled HTTP request")
                    check(request["temperature"]!!.jsonPrimitive.float==0.2f && request["max_tokens"]!!.jsonPrimitive.int==512)
                    pass("profile_generation_preset_reaches_real_http_request")
                    val schedule=c.conversationFeatures.schedules.value.first{it.id==prefix}
                    val occurrence=ScheduledRun("test-dedup-$prefix",prefix)
                    check(c.conversationFeatures.claimRun(occurrence)&&!c.conversationFeatures.claimRun(occurrence))
                    check(!c.conversationFeatures.replaceSchedule(schedule.copy(name="stale"),schedule.revision+1))
                    pass("schedule_ledger_rejects_replay_and_stale_edits")
                    check(c.toolRegistry.requiresExecutionApproval("schedule_manage","""{"action":"create"}""",AgentSettings(autoApproveAll=true),WorkspaceEnvType.INTERNAL))
                    check(!c.toolRegistry.requiresExecutionApproval("schedule_manage","""{"action":"list"}""",AgentSettings(),WorkspaceEnvType.INTERNAL))
                    pass("agent_schedule_mutations_require_owner_approval_even_in_autopilot")
                    val unknown=task.copy(id="$prefix-unknown",dateTime=java.time.Instant.now().plusSeconds(3).toString())
                    c.schedules.save(unknown)
                    val savedUnknown=c.conversationFeatures.schedules.value.first{it.id==unknown.id}
                    c.conversationFeatures.claimRun(ScheduledRun(SchedulePolicy.occurrence(savedUnknown),unknown.id))
                    until {c.conversationFeatures.schedules.value.first{it.id==unknown.id}.lastStatus=="Нужна проверка"}
                    check(c.db.messageDao().getForSession(sid).count{it.role=="USER"&&it.content=="Run once"}==1)
                    pass("unknown_scheduled_result_pauses_without_replaying_prompt")
                    main {vm.showSchedules.value=true};click("Создать для этого чата");click("Отмена");click("Закрыть")
                    pass("schedule_ui_create_form_and_cancel_are_accessible")
                    val source=c.db.messageDao().getForSession(sid).last{it.role=="ASSISTANT"}
                    main {vm.editConversationMessage(source,"EDITED_ANSWER extra fragment context",false)}
                    until {c.db.messageDao().getForSession(sid).any{it.content=="EDITED_ANSWER extra fragment context"}}
                    check(c.db.messageDao().getForSession(sid).any{it.id==source.id&&it.content==source.content})
                    val edited=c.db.messageDao().getForSession(sid).first{it.content=="EDITED_ANSWER extra fragment context"}
                    check(ConversationBranching.resolve(c.db.messageDao().getForSession(sid),c.db.sessionDao().getById(sid)!!.branchSelectionsJson).activeMessages.last().id==edited.id)
                    pass("assistant_edit_creates_selected_alternate_and_preserves_original")
                    responses.put("REGENERATED_ANSWER [[NO_TOOL_NEEDED]]")
                    main {vm.prepareRegeneration(edited)};click("Перегенерировать")
                    until {!c.agentEngine.isRunning.value && c.db.messageDao().getForSession(sid).any{it.content.contains("REGENERATED_ANSWER")}}
                    click("Вернуться к прежнему")
                    until {ConversationBranching.resolve(c.db.messageDao().getForSession(sid),c.db.sessionDao().getById(sid)!!.branchSelectionsJson).activeMessages.last().id==edited.id}
                    check(c.db.messageDao().getForSession(sid).any{it.content.contains("REGENERATED_ANSWER")})
                    pass("regeneration_confirmation_and_undo_preserve_both_answer_variants")
                    until {nodes().any{it.text?.toString()==edited.content}}
                    val selectionNode=nodes().first{it.text?.toString()==edited.content}
                    val selectionBounds=android.graphics.Rect().also(selectionNode::getBoundsInScreen)
                    val eventTime=android.os.SystemClock.uptimeMillis()
                    fun touch(action:Int,time:Long) {
                        val event=android.view.MotionEvent.obtain(eventTime,time,action,(selectionBounds.left+40).toFloat(),(selectionBounds.top+12).toFloat(),0)
                        event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                        try {check(test.uiAutomation.injectInputEvent(event,true))}finally{event.recycle()}
                    }
                    touch(android.view.MotionEvent.ACTION_DOWN,eventTime);delay(850)
                    touch(android.view.MotionEvent.ACTION_UP,android.os.SystemClock.uptimeMillis())
                    until {nodes().any{it.text?.toString()=="Всё"}}
                    click("Ветка");click("Создать")
                    until {vm.currentSessionId.value!=sid && vm.currentSession.value?.title=="Features QA (1)"}
                    val branchId=vm.currentSessionId.value!!
                    val fragmentText=c.db.messageDao().getForSession(branchId).single().content
                    val quotation=fragmentText.substringAfter(":\n\n")
                    check(quotation.isNotBlank() && edited.content.contains(quotation) && quotation.length<edited.content.length)
                    pass("native_text_selection_creates_numbered_fragment_chat_without_changing_source")
                    main {vm.selectSession(sid)};until {vm.currentSession.value?.id==sid}
                    click("Чтение");check(nodes().any{it.text?.toString()=="Закреплять начало нового ответа"});click("Закрыть")
                    pass("reading_controls_settings_are_accessible")
                    // Approval arguments are the exact executed values, not just a text editor preview.
                    c.settingsRepo.saveAgentSettings(c.settingsRepo.agentSettings.value.copy(enableTools=true,autoApproveAll=false,autoApproveSafe=false,autoApproveShell=false,
                        autoApproveFileWrite=false,autoApproveFileDelete=false,allowedTools=emptyList(),blockedTools=emptyList()))
                    val original="${prefix}-original.txt";val changed="${prefix}-changed.txt"
                    responses.put("""<tool_call>{"name":"file_write","arguments":{"path":"$original","content":"ORIGINAL","target_env":"internal"}}</tool_call>""")
                    responses.put("EDIT_TOOL_DONE [[NO_TOOL_NEEDED]]")
                    c.agentEngine.sendUserTurn(sid,"Write synthetic file",emptyList())
                    until {c.agentEngine.pendingApprovals.value.isNotEmpty()}
                    val approval=c.agentEngine.pendingApprovals.value.single()
                    main {vm.updateApprovalArguments(approval.id,"""{"path":"$changed","content":"CHANGED","target_env":"internal"}""");vm.rememberApproval(approval.id)}
                    until {!c.agentEngine.isRunning.value}
                    val root=c.workspaceManager.getInternalProjectDir("default")
                    check(File(root,changed).readText()=="CHANGED"&&!File(root,original).exists())
                    check(c.conversationFeatures.grants.value.any{it.sessionId==sid&&it.toolName=="file_write"})
                    pass("edited_approval_executes_reviewed_arguments_and_persists_scoped_grant")
                    main {vm.revokeToolGrant(sid,"file_write")}
                    until {c.conversationFeatures.grants.value.none{it.sessionId==sid}}
                    pass("revoking_grant_removes_future_auto_approval")
                    responses.put("""<tool_call>{"name":"file_delete","arguments":{"path":"$changed","target_env":"internal"}}</tool_call>""")
                    responses.put("CANCEL_DELETE_DONE [[NO_TOOL_NEEDED]]")
                    c.agentEngine.sendUserTurn(sid,"Delete synthetic file",emptyList())
                    until {c.agentEngine.pendingApprovals.value.isNotEmpty()}
                    val deletion=c.agentEngine.pendingApprovals.value.single()
                    main {vm.resolveApproval(deletion.id,true)};delay(200);main {vm.resolveApproval(deletion.id,false)}
                    until {!c.agentEngine.isRunning.value};check(File(root,changed).exists())
                    pass("destructive_grace_period_can_be_cancelled_before_execution")
                    responses.put("""<tool_call>{"name":"file_write","arguments":{"path":"$original","content":"SHOULD_NOT_RUN","target_env":"internal"}}</tool_call>""")
                    responses.put("NEW_INPUT_ACCEPTED [[NO_TOOL_NEEDED]]")
                    c.agentEngine.sendUserTurn(sid,"Wait for owner",emptyList())
                    until {c.agentEngine.pendingApprovals.value.isNotEmpty()}
                    c.agentEngine.enqueueUserInput(sid,"NEW_INPUT_WHILE_WAITING",emptyList(),com.forgemind.app.agent.QueuedInputMode.NEXT_STEP)
                    until {!c.agentEngine.isRunning.value}
                    check(!File(root,original).exists())
                    check(c.db.messageDao().getForSession(sid).any{it.role=="USER"&&it.content=="NEW_INPUT_WHILE_WAITING"})
                    pass("send_now_during_approval_declines_old_call_and_delivers_new_input")
                    requests.clear()
                    responses.put("__HOLD_REASONING__")
                    c.agentEngine.sendUserTurn(sid,"Begin held reasoning",emptyList())
                    until {c.db.messageDao().getForSession(sid).any{it.isStreaming&&it.thinking.contains("VOICE_HOLD_MARKER")}}
                    c.agentEngine.enqueueUserInput(sid,"WAIT_AFTER_VOICE",emptyList(),com.forgemind.app.agent.QueuedInputMode.AFTER_RUN)
                    responses.put("IMMEDIATE_VOICE_DONE [[NO_TOOL_NEEDED]]")
                    responses.put("QUEUED_AFTER_DONE [[NO_TOOL_NEEDED]]")
                    withTimeout(5000) {check(c.agentEngine.sendUserTurnImmediately(sid,"IMMEDIATE_VOICE_TEXT"))}
                    check(c.agentEngine.queuedInputs.value.none{it.text=="IMMEDIATE_VOICE_TEXT"})
                    until {!c.agentEngine.isRunning.value && c.agentEngine.queuedInputs.value.isEmpty() &&
                        c.db.messageDao().getForSession(sid).any{it.content.contains("QUEUED_AFTER_DONE")}}
                    val voiceMessages=c.db.messageDao().getForSession(sid)
                    check(voiceMessages.any{!it.isStreaming&&it.thinking.contains("VOICE_HOLD_MARKER")})
                    check(voiceMessages.filter{it.role=="USER"}.takeLast(3).map{it.content}==listOf("Begin held reasoning","IMMEDIATE_VOICE_TEXT","WAIT_AFTER_VOICE"))
                    val voiceRequests=mutableListOf<JsonObject>();requests.drainTo(voiceRequests)
                    val lastUsers=voiceRequests.map {request -> request["messages"]!!.jsonArray.last{it.jsonObject["role"]?.jsonPrimitive?.content=="user"}.jsonObject["content"]!!.jsonPrimitive.content}
                    check(lastUsers==listOf("Begin held reasoning","IMMEDIATE_VOICE_TEXT","WAIT_AFTER_VOICE"))
                    pass("ticker_voice_immediate_interrupt_preserves_partial_history_and_bypasses_queue")
                    File(root,changed).delete()
                    // Preview stable baseline, cancellation, changed-data rejection, and final report.
                    val archive=File(ctx.cacheDir,"$prefix.zip")
                    archive.outputStream().use {c.archiveManager.exportFullZip(it,setOf(sid))}
                    val preview=c.archiveManager.previewImport(archive)
                    check(preview.baseline==c.archiveManager.previewImport(archive).baseline)
                    c.db.sessionDao().upsert(c.db.sessionDao().getById(sid)!!.copy(title="Changed after preview"))
                    check(runCatching {c.archiveManager.applyPreview(preview)}.isFailure)
                    pass("archive_preview_has_stable_baseline_and_rejects_stale_apply")
                    val fresh=c.archiveManager.previewImport(archive)
                    main {vm.archivePreview.value=fresh};click("Применить импорт")
                    until {vm.archiveReport.value?.startsWith("Импорт завершён")==true}
                    check(c.db.sessionDao().getById(sid)!!.title=="Features QA")
                    click("Закрыть")
                    pass("archive_comparison_dialog_applies_and_reports_result")
                    until {
                        val branch=nodes().lastOrNull{it.text?.toString()=="Ветка"}
                        val reading=nodes().firstOrNull{it.text?.toString()=="Чтение"}
                        if(branch==null||reading==null)false else {
                            val a=android.graphics.Rect().also(branch::getBoundsInScreen)
                            val b=android.graphics.Rect().also(reading::getBoundsInScreen)
                            a.bottom<=b.top
                        }
                    }
                    pass("reading_controls_do_not_cover_last_answer_actions")
                    // Serialized profile fields survived real Room + archive operations above.
                    check(c.db.assistantDao().get(prefix)!!.generationConfigJson==profile.generationConfigJson)
                    pass("room9_and_archive_preserve_generation_preset")
                    val bitmap=test.uiAutomation.takeScreenshot()
                    File(ctx.filesDir,"features69-chat.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
                } finally {
                    c.agentEngine.cancelRun();c.conversationFeatures.schedules.value.filter{it.id.startsWith(prefix)}.forEach{c.schedules.delete(it.id)}
                    c.settingsRepo.saveAgentSettings(previousSettings);c.settingsRepo.saveVoiceSettings(previousVoice)
                    c.conversationFeatures.clearGrants(sid)
                    c.workspaceManager.getInternalProjectDir("default").listFiles()?.filter{it.name.startsWith(prefix)}?.forEach{it.delete()}
                    c.db.sessionDao().getAll().filter{it.id==sid||it.title.startsWith("Features QA (")}.forEach{c.db.sessionDao().deleteById(it.id)}
                    c.db.assistantDao().delete(prefix)
                    if(oldProvider==null)c.settingsRepo.removeProvider(pid)else c.settingsRepo.saveProviderConfig(oldProvider)
                    oldSession?.let {main {vm.selectSession(it)}}
                    main {activity.finish()}
                }
            }
        } catch(error:Throwable){failed=error;results+="FAIL="+error.stackTraceToString()}
        finally {socket.close();fixture.join(1000)}
        val text=results.joinToString("\n");File(ctx.filesDir,"features69-scenario.txt").writeText(text)
        test.finish(if(failed==null)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply{putString("stream",text+"\n");putBoolean("passed",failed==null)})
    }
}
