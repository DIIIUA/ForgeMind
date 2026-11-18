package com.forgemind.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import com.forgemind.app.data.db.*
import com.forgemind.app.data.model.*
import com.forgemind.app.data.prefs.*
import com.forgemind.app.ui.MainViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.io.File
import java.net.ServerSocket
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

object ChatContentScenario {
    fun run(test: Instrumentation) {
        val ctx = test.targetContext
        val checks = mutableListOf<String>()
        var failed: Throwable? = null
        fun pass(name: String) { checks += "$name=PASS"; File(ctx.filesDir,"chat-content-progress.txt").writeText(checks.joinToString("\n")) }
        try {
            check(android.os.Build.HARDWARE in setOf("ranchu","goldfish")) { "Emulator only" }
            test.uiAutomation.serviceInfo = test.uiAutomation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            val c = (ctx.applicationContext as ForgeMindApp).container
            val prefix = "content-qa-" + UUID.randomUUID()
            val sid = "$prefix-a"; val other = "$prefix-b"; val pid = "custom-$prefix"
            val requests = LinkedBlockingQueue<JsonObject>()
            val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            val fixture = thread(isDaemon=true) {
                try { while (!socket.isClosed) socket.accept().use { client ->
                    val input = client.getInputStream()
                    fun line(): String { val bytes=java.io.ByteArrayOutputStream(); while(true) {val b=input.read();if(b<0||b==10)break;bytes.write(b)};return bytes.toString("UTF-8").trimEnd('\r') }
                    val method = line()
                    var size=0
                    while(true) {val header=line();if(header.isEmpty())break;if(header.startsWith("Content-Length:",true))size=header.substringAfter(':').trim().toInt()}
                    val body=input.readNBytes(size).toString(Charsets.UTF_8)
                    if(method.startsWith("POST "))requests.put(Json.parseToJsonElement(body).jsonObject)
                    val sse="data: {\"choices\":[{\"delta\":{\"content\":\"SEARCH_TEST_DONE [[NO_TOOL_NEEDED]]\"}}]}\n\ndata: [DONE]\n\n"
                    val bytes=sse.toByteArray()
                    client.getOutputStream().apply {write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());write(bytes);flush()}
                } } catch (_: Exception) { }
            }
            runBlocking {
                suspend fun <T> main(block: () -> T): T = withContext(Dispatchers.Main) { block() }
                suspend fun until(block: suspend () -> Boolean) = withTimeout(45000) { while(!block())delay(60) }
                fun nodes(): List<AccessibilityNodeInfo> {
                    val result=mutableListOf<AccessibilityNodeInfo>()
                    fun visit(n: AccessibilityNodeInfo?) {if(n==null)return;result+=n;for(i in 0 until n.childCount)visit(n.getChild(i))}
                    val windows=test.uiAutomation.windows.sortedByDescending {it.layer}
                    if(windows.isEmpty())visit(test.uiAutomation.rootInActiveWindow) else windows.forEach {visit(it.root)}
                    return result
                }
                suspend fun click(text: String) {
                    try {withTimeout(15000) {while(true) {var n=nodes().firstOrNull {it.text?.toString()==text||it.contentDescription?.toString()==text};while(n!=null&&!n.isClickable)n=n.parent
                        if(n?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)break;delay(100)}};delay(250)}
                    catch (failure: Exception) {
                        File(ctx.filesDir,"chat-content-click-failure.txt").writeText("CLICK: $text\n" + nodes().joinToString("\n") { n -> val r=android.graphics.Rect().also(n::getBoundsInScreen);"${n.packageName} | ${n.text} | ${n.contentDescription} | clickable=${n.isClickable} | $r" })
                        val screenshot=test.uiAutomation.takeScreenshot()
                        File(ctx.filesDir,"chat-content-click-failure.png").outputStream().use{screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};screenshot.recycle()
                        throw IllegalStateException("Could not click: $text", failure)
                    }
                }
                c.settingsRepo.loadAll()
                val oldSettings=c.settingsRepo.agentSettings.value
                val oldSession=c.settingsRepo.activeSessionIdFlow().first()
                val activity=test.startActivitySync(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                val vm=main { ViewModelProvider(activity)[MainViewModel::class.java] }
                val attachment=File(ctx.cacheDir,"$prefix.txt").apply {writeText("Synthetic draft attachment")}
                val draftScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
                try {
                    main {vm.setupWizard.defer()}
                    // Build an authentic schema9 database, migrate it and read its preserved row.
                    val dbName="$prefix-migration"
                    val schema=Json.parseToJsonElement(test.context.assets.open("forgemind-schema-v9.json").bufferedReader().readText()).jsonObject["database"]!!.jsonObject
                    android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath(dbName),null).use { old ->
                        schema["entities"]!!.jsonArray.forEach { element ->
                            val entity=element.jsonObject;val table=entity["tableName"]!!.jsonPrimitive.content
                            old.execSQL(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}",table))
                            entity["indices"]?.jsonArray?.forEach {old.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}",table))}
                            if(table in setOf("chat_sessions","chat_messages")) {
                                val values=android.content.ContentValues()
                                entity["fields"]!!.jsonArray.forEach { f -> val field=f.jsonObject
                                    if(field["notNull"]?.jsonPrimitive?.booleanOrNull==true) {
                                        val key=field["columnName"]!!.jsonPrimitive.content
                                        if(field["affinity"]!!.jsonPrimitive.content=="TEXT")values.put(key,when(key){"id"->if(table=="chat_sessions")"legacy-chat" else "legacy-message";"sessionId"->"legacy-chat";"role"->"USER";"content"->"LEGACY_MIGRATION_9";else->"legacy"})else values.put(key,0)
                                    }
                                };old.insertOrThrow(table,null,values)
                            }
                        }; old.version=9
                    }
                    val migrated=Room.databaseBuilder(ctx,ForgeMindDatabase::class.java,dbName).addMigrations(ForgeMindDatabase.MIGRATION_9_10).build()
                    try {val row=migrated.messageDao().getForSession("legacy-chat").single();check(row.content=="LEGACY_MIGRATION_9"&&!row.excludedFromContext)}finally {migrated.close();ctx.deleteDatabase(dbName)}
                    pass("room9_to10_preserves_history_with_exclusion_off_by_default")

                    val u0=ChatMessageEntity("$prefix-u0",sid,0,"USER",content="QUESTION_PRIVATE_SENTINEL",parentMessageId=ConversationBranching.ROOT_KEY)
                    val a0=ChatMessageEntity("$prefix-a0",sid,1,"ASSISTANT",content="FIRST_EXCLUDED_SENTINEL",parentMessageId=u0.id)
                    val u1=ChatMessageEntity("$prefix-u1",sid,2,"USER",content="Вопрос про ЁЖИК 100%_",parentMessageId=a0.id)
                    val a1=ChatMessageEntity("$prefix-a1",sid,3,"ASSISTANT",content="Visible selection fragment; ЁЖИК 100%_ in this answer " + "padding ".repeat(500) + "second ЁЖИК 100%_ near the end",parentMessageId=u1.id)
                    val alt=ChatMessageEntity("$prefix-alt",sid,4,"ASSISTANT",content="HIDDEN_BRANCH_SENTINEL",parentMessageId=u1.id)
                    c.db.sessionDao().upsert(ChatSessionEntity(sid,"Content QA",providerId=pid,modelId="fixture",branchSelectionsJson=ConversationBranching.withSelection("{}",u1.id,a1.id)))
                    c.db.sessionDao().upsert(ChatSessionEntity(other,"Other QA"))
                    c.db.messageDao().upsertAll(listOf(u0,a0,u1,a1,alt))
                    val att=ChatAttachment("$prefix-att","synthetic.txt","text/plain",attachment.path)
                    c.chatDrafts.set(sid,ChatDraft("DRAFT_A",listOf(att),true))
                    main {vm.selectSession(sid)}
                    until {vm.currentSession.value?.id==sid}
                    check(vm.composerText.value=="DRAFT_A"&&vm.commandMode.value&&vm.draftAttachments.value.single().id==att.id)
                    main {vm.selectSession(other);vm.updateComposerText("DRAFT_B");vm.selectSession(sid)}
                    check(vm.composerText.value=="DRAFT_A"&&vm.commandMode.value)
                    main {vm.selectSession(other)};check(vm.composerText.value=="DRAFT_B")
                    vm.flushDrafts()
                    val reopened=ChatDraftStore(ctx,draftScope)
                    check(reopened.get(sid)==ChatDraft("DRAFT_A",listOf(att),true)&&reopened.get(other).text=="DRAFT_B")
                    pass("draft_text_attachment_and_command_mode_are_per_chat_and_persisted")

                    main {vm.selectSession(sid);vm.quoteFragment("/term echo example")}
                    check(!vm.commandMode.value&&vm.composerText.value.contains("> /term echo example"))
                    check(c.db.messageDao().getForSession(sid).size==5&&!c.agentEngine.isRunning.value)
                    pass("quotation_preserves_draft_and_cannot_autosend_a_terminal_command")

                    val global=com.forgemind.app.data.model.ChatSearchRepository(c.db).search("ёжик 100%_")
                    check(global.any{it.first.id==sid&&it.second.messageId!=null&&it.second.excerpt.contains("ЁЖИК")})
                    main {vm.searchSessions("HIDDEN_BRANCH_SENTINEL")}
                    until {vm.sessionSearchHits.value[sid]?.messageId==alt.id}
                    c.db.sessionDao().updateCompaction(sid,"OLD_BRANCH_SUMMARY",4,100)
                    main {vm.openSearchResult(sid)}
                    until {vm.resolvedBranchView.value.activeMessages.lastOrNull()?.id==alt.id&&vm.chatSearchTargetId.value==alt.id}
                    check(c.db.sessionDao().getById(sid)!!.compactedSummary.isEmpty())
                    pass("global_literal_unicode_search_opens_the_matching_hidden_branch_without_old_summary")
                    main {vm.searchSessions("QUESTION_PRIVATE_SENTINEL");vm.searchSessions("HIDDEN_BRANCH_SENTINEL");vm.searchSessions("")}
                    delay(500);check(vm.searchResults.value.isEmpty()&&vm.sessionSearchHits.value.isEmpty())
                    pass("cleared_search_cannot_be_replaced_by_a_stale_result")

                    main {vm.selectMessageBranch(u1.id,a1.id);vm.chatSearchTarget.value=null;vm.chatSearchQuery.value="ёжик 100%_";vm.showChatSearch.value=true}
                    until {vm.currentChatSearchHits.value.size==3&&vm.chatSearchTargetId.value==u1.id}
                    click("↓");until {vm.chatSearchTargetId.value==a1.id}
                    val firstOffset=vm.chatSearchTarget.value!!.offset
                    click("↓");until {vm.chatSearchTarget.value?.messageId==a1.id && vm.chatSearchTarget.value!!.offset>firstOffset}
                    val secondOffset=vm.chatSearchTarget.value!!.offset
                    until {
                        val textNode=nodes().firstOrNull {it.text?.toString()==a1.content} ?: return@until false
                        val args=Bundle().apply {putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX,secondOffset);putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH,1)}
                        textNode.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY,args)
                        @Suppress("DEPRECATION")
                        val location=textNode.extras.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)?.firstOrNull() as? android.graphics.RectF
                        location!=null && location.top>200 && location.bottom<900
                    }
                    click("↑");until {vm.chatSearchTarget.value?.offset==firstOffset}
                    click("↑");until {vm.chatSearchTargetId.value==u1.id}
                    pass("in_chat_search_navigates_every_occurrence_and_scrolls_the_exact_text_into_view")
                    click("Закрыть поиск")
                    main {vm.updateComposerText("");vm.setCommandMode(false);vm.removeDraftAttachment(att.id)}
                    until {nodes().any{it.text?.toString()==a1.content}}
                    val node=nodes().first{it.text?.toString()==a1.content}
                    val rect=android.graphics.Rect().also(node::getBoundsInScreen)
                    val time=android.os.SystemClock.uptimeMillis()
                    fun touch(action:Int,at:Long) {val e=android.view.MotionEvent.obtain(time,at,action,(rect.left+38).toFloat(),(rect.top+12).toFloat(),0);e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;try{check(test.uiAutomation.injectInputEvent(e,true))}finally{e.recycle()}}
                    touch(android.view.MotionEvent.ACTION_DOWN,time);delay(850);touch(android.view.MotionEvent.ACTION_UP,android.os.SystemClock.uptimeMillis())
                    click("Цитата");until {vm.composerText.value.startsWith("> ")}
                    val selected=vm.composerText.value.removePrefix("> ").trim()
                    check(selected.isNotBlank()&&a1.content.contains(selected)&&selected.length<a1.content.length)
                    pass("native_selected_fragment_is_quoted_without_sending")

                    c.db.sessionDao().updateCompaction(sid,"OLD_SECRET_SUMMARY",2,100)
                    main {vm.exclusionRequest.value=a0};click("Исключить")
                    until {c.db.messageDao().getMessage(sid,a0.id)!!.excludedFromContext}
                    main {vm.setMessageExclusion(u0,true,true)}
                    until {c.db.messageDao().getMessage(sid,u0.id)!!.excludedFromContext}
                    check(c.db.messageDao().getForSession(sid).size==5&&c.db.sessionDao().getById(sid)!!.compactedSummary.isEmpty())
                    pass("exclude_message_and_turn_preserves_history_and_resets_old_summary")

                    c.settingsRepo.saveAgentSettings(oldSettings.copy(enableTools=false,autoCompactContext=false,thinkEveryToolCalls=0,maxTurns=2))
                    c.settingsRepo.saveProviderConfig(ProviderConfig(pid,modelId="fixture",baseUrl="http://127.0.0.1:${socket.localPort}/chat",rawEndpointUrl=true,wireProtocolOverride=WireProtocol.OPENAI_COMPAT,historyMessageLimit=0))
                    c.agentEngine.sendUserTurn(sid,"after-exclusion",emptyList())
                    until {!c.agentEngine.isRunning.value&&c.db.messageDao().getForSession(sid).any{it.content.contains("SEARCH_TEST_DONE")}}
                    val excludedWire=requests.poll(10,TimeUnit.SECONDS)!!["messages"].toString()
                    check(!excludedWire.contains("FIRST_EXCLUDED_SENTINEL")&&!excludedWire.contains("QUESTION_PRIVATE_SENTINEL")&&!excludedWire.contains("OLD_SECRET_SUMMARY"))
                    main {vm.setMessageExclusion(u0,true,false)}
                    until {!c.db.messageDao().getMessage(sid,u0.id)!!.excludedFromContext}
                    c.agentEngine.sendUserTurn(sid,"after-restore",emptyList())
                    until {!c.agentEngine.isRunning.value}
                    val restoredWire=requests.poll(10,TimeUnit.SECONDS)!!["messages"].toString()
                    check(restoredWire.contains("FIRST_EXCLUDED_SENTINEL")&&restoredWire.contains("QUESTION_PRIVATE_SENTINEL"))
                    pass("real_http_request_omits_excluded_history_and_restore_returns_it")

                    main {vm.setMessageExclusion(a0,false,true)}
                    until {c.db.messageDao().getMessage(sid,a0.id)!!.excludedFromContext}
                    val archive=File(ctx.cacheDir,"$prefix.zip")
                    archive.outputStream().use {c.archiveManager.exportFullZip(it,setOf(sid))}
                    c.db.sessionDao().deleteById(sid)
                    c.archiveManager.applyPreview(c.archiveManager.previewImport(archive))
                    check(c.db.messageDao().getMessage(sid,a0.id)!!.excludedFromContext)
                    archive.delete()
                    pass("archive5_restores_context_exclusion_flags")
                    val bitmap=test.uiAutomation.takeScreenshot()
                    File(ctx.filesDir,"chat-content.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
                } finally {
                    c.agentEngine.cancelRun()
                    c.settingsRepo.saveAgentSettings(oldSettings)
                    c.settingsRepo.removeProvider(pid)
                    c.chatDrafts.remove(sid);c.chatDrafts.remove(other);c.chatDrafts.flush()
                    c.db.sessionDao().deleteByIds(listOf(sid,other));attachment.delete()
                    oldSession?.let {main {vm.selectSession(it)}}
                    draftScope.cancel();main {activity.finish()}
                    socket.close();fixture.join(1000)
                }
            }
        } catch (error: Throwable) {failed=error;checks+="FAIL="+error.stackTraceToString()}
        val result=checks.joinToString("\n")
        File(ctx.filesDir,"chat-content-scenario.txt").writeText(result)
        test.finish(if(failed==null)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply{putString("stream",result+"\n");putBoolean("passed",failed==null)})
    }
}
