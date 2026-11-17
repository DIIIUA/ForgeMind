package com.forgemind.app

import android.app.Activity
import android.app.Instrumentation
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.room.Room
import com.forgemind.app.data.db.*
import com.forgemind.app.data.memory.*
import com.forgemind.app.data.archive.ChatArchiveManager
import com.forgemind.app.data.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.InetAddress
import java.util.UUID

object MemoryScenario {
    fun run(test: Instrumentation) {
        val results = mutableListOf<String>()
        val context = test.targetContext
        try {
            check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic data: emulator only" }
            runBlocking {
                withContext(Dispatchers.IO) {
                    val name = "qa-memory-${UUID.randomUUID()}.db"
                    val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
                    val schema = Json.parseToJsonElement(test.context.assets.open("forgemind-schema-v6.json").bufferedReader().readText()).jsonObject["database"]!!.jsonObject
                    SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
                        schema["entities"]!!.jsonArray.forEach { element ->
                            val entity = element.jsonObject
                            val table = entity["tableName"]!!.jsonPrimitive.content
                            old.execSQL(entity["createSql"]!!.jsonPrimitive.content.replace("\u0024{TABLE_NAME}", table))
                            (entity["indices"] as? JsonArray).orEmpty().forEach { old.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\u0024{TABLE_NAME}", table)) }
                            if (table in setOf("chat_sessions", "chat_messages", "compaction_checkpoints")) {
                                val values = ContentValues()
                                entity["fields"]!!.jsonArray.forEach { field ->
                                    val f=field.jsonObject
                                    if (f["notNull"]?.jsonPrimitive?.booleanOrNull == true) {
                                        val column=f["columnName"]!!.jsonPrimitive.content
                                        if(f["affinity"]!!.jsonPrimitive.content == "TEXT") values.put(column, "") else values.put(column, 0)
                                    }
                                }
                                values.put("id", if(table == "chat_sessions") "legacy" else table)
                                if(table == "chat_sessions") values.put("title", "MIGRATION_PRESERVED")
                                else values.put("sessionId", "legacy")
                                if(table == "chat_messages") { values.put("role", "USER"); values.put("content", "ORIGINAL_MESSAGE") }
                                if(table == "compaction_checkpoints") values.put("previousSummary", "CHECKPOINT_PRESERVED")
                                old.insertOrThrow(table,null,values)
                            }
                        }
                        old.version=6
                    }
                    val db=Room.databaseBuilder(context,ForgeMindDatabase::class.java,name).addMigrations(ForgeMindDatabase.MIGRATION_6_7, ForgeMindDatabase.MIGRATION_7_8).build()
                    try {
                        check(db.sessionDao().getById("legacy")!!.title == "MIGRATION_PRESERVED")
                        check(db.messageDao().getForSession("legacy").single().content == "ORIGINAL_MESSAGE")
                        check(db.sessionDao().allCompactionCheckpoints().single().previousSummary == "CHECKPOINT_PRESERVED")
                        check(db.memoryDao().allNotes().isEmpty())
                        results += "room_6_to_8_preserves_messages_and_checkpoints=PASS"
                        val folder=ChatFolderEntity("folder","Original")
                        db.folderDao().upsert(folder)
                        for(id in listOf("a","b","c")) db.sessionDao().upsert(ChatSessionEntity(id,id,folderId=if(id=="c") null else folder.id,providerId="p",modelId="m"))
                        val repo=MemoryRepository(db)
                        val a=repo.context("a");val b=repo.context("b");val c=repo.context("c").copy(modelId="other")
                        for(scope in MemoryScope.entries.filter { it != MemoryScope.ASSISTANT }) repo.save(a,scope,scope.name,"MEMORY_${scope.name}")
                        check(repo.visible(a).size==4 && repo.visible(b).size==3 && repo.visible(c).size==1)
                        val own=repo.visible(a).first { it.scopeType=="CHAT" }
                        check(runCatching { repo.search(b,"",own.id,20,0) }.isFailure)
                        results += "chat_folder_model_global_scope_isolation=PASS"
                        val unicode=MemoryPolicy.note(a,MemoryScope.CHAT,"Название","ПАМЯТЬ кириллица")
                        db.memoryDao().insert(unicode)
                        check(repo.visible(a,"память").single().id==unicode.id)
                        check(repo.visible(a,limit=2,offset=2).map { it.id }.intersect(repo.visible(a,limit=2).map { it.id }.toSet()).isEmpty())
                        results += "cyrillic_search_and_pagination=PASS"
                        repo.toggle(a,unicode)
                        check(!repo.requestBlock(a,2048).contains("кириллица"))
                        check(runCatching { repo.search(a,"",unicode.id,20,0) }.isFailure)
                        results += "disabled_notes_excluded_from_request_and_search=PASS"
                        val proposal=repo.propose(a,"create",null,"New","PENDING_ONLY",null)
                        check(repo.propose(a,"create",null,"New","PENDING_ONLY",null).id==proposal.id)
                        check(db.memoryDao().proposalCount("a")==1 && !repo.requestBlock(a,2048).contains("PENDING_ONLY"))
                        results += "proposal_is_pending_and_deduplicated=PASS"
                        check(runCatching { repo.approve("b",proposal.id) }.isFailure)
                        repo.approve("a",proposal.id)
                        check(repo.requestBlock(a,2048).contains("PENDING_ONLY"))
                        check(runCatching { repo.approve("a",proposal.id) }.isFailure)
                        results += "approval_once_and_source_chat_guard=PASS"
                        val stale=repo.propose(a,"update",null,"Changed","MODEL_UPDATE",own.id)
                        repo.save(a,MemoryScope.CHAT,"User edit","OWNER_EDIT",own)
                        check(runCatching { repo.approve("a",stale.id) }.isFailure)
                        check(db.memoryDao().note(own.id)!!.content=="OWNER_EDIT" && db.memoryDao().proposal(stale.id)!=null)
                        repo.reject("a",stale.id)
                        results += "stale_proposal_preserves_owner_edit=PASS"
                        val current=db.memoryDao().note(own.id)!!
                        val update=repo.propose(a,"update",null,"Updated","APPROVED_UPDATE",current.id)
                        repo.approve("a",update.id)
                        check(db.memoryDao().note(own.id)!!.content=="APPROVED_UPDATE")
                        val delete=repo.propose(a,"delete",null,null,null,own.id)
                        check(db.memoryDao().note(own.id)!=null)
                        repo.approve("a",delete.id)
                        check(db.memoryDao().note(own.id)==null)
                        results += "update_and_delete_require_confirmation=PASS"
                        db.folderDao().upsert(folder.copy(name="Renamed"))
                        check(repo.visible(b).any { it.scopeType=="FOLDER" })
                        results += "folder_rename_does_not_cascade_memory=PASS"
                        val pending=repo.propose(a,"create","CHAT","Persistent","WAITING_ARCHIVE",null)
                        val archive=ChatArchiveManager(db,File(context.cacheDir,"qa-memory-files"))
                        val bytes=ByteArrayOutputStream();archive.exportFullZip(bytes)
                        val restored=Room.inMemoryDatabaseBuilder(context,ForgeMindDatabase::class.java).build()
                        try {
                            val importer=ChatArchiveManager(restored,File(context.cacheDir,"qa-memory-import"))
                            importer.importFullZip(bytes.toByteArray().inputStream())
                            check(restored.memoryDao().allNotes()==db.memoryDao().allNotes())
                            check(restored.memoryDao().proposal(pending.id)!=null)
                            check(restored.memoryDao().note(unicode.id)!!.enabled==false)
                            val collision=restored.memoryDao().allNotes().first()
                            restored.memoryDao().update(collision.copy(content="LOCAL_WINS",revision=collision.revision+1))
                            importer.importFullZip(bytes.toByteArray().inputStream())
                            check(restored.memoryDao().note(collision.id)!!.content=="LOCAL_WINS")
                            results += "full_archive_memory_pending_and_id_conflicts=PASS"
                        } finally { restored.close() }
                        val selected=ByteArrayOutputStream();archive.exportFullZip(selected,setOf("a"))
                        val selectedDb=Room.inMemoryDatabaseBuilder(context,ForgeMindDatabase::class.java).build()
                        try {
                            ChatArchiveManager(selectedDb,File(context.cacheDir,"qa-memory-selected")).importFullZip(selected.toByteArray().inputStream())
                            check(selectedDb.memoryDao().allNotes().all { it.scopeType=="CHAT" && it.scopeKey=="a" })
                            check(selectedDb.memoryDao().allNotes().isNotEmpty())
                            results += "selected_archive_excludes_shared_memory=PASS"
                        } finally { selectedDb.close() }
                        repeat(19) { repo.propose(a,"create",null,"Pending $it","cap $it",null) }
                        check(runCatching { repo.propose(a,"create",null,"Overflow","cap",null) }.isFailure)
                        check(db.memoryDao().proposalCount("a")==20)
                        results += "pending_proposals_bounded=PASS"
                        db.folderDao().deleteById("folder")
                        check(db.memoryDao().allNotes().none { it.scopeType=="FOLDER" })
                        db.sessionDao().deleteById("a")
                        check(db.memoryDao().allNotes().none { it.chatId=="a" } && db.memoryDao().proposalCount("a")==0)
                        check(db.memoryDao().allNotes().any { it.scopeType=="GLOBAL" })
                        results += "deleted_chat_folder_cascade_only_owned_memory=PASS"
                    } finally { db.close(); context.deleteDatabase(name) }
                }
                engineRoundtrip(test, results)
                results += MemoryUiScenario.run(test)
                results += ContextGuardScenario.run(context,test.context)
            }
            results += "ALL_PASS"
            File(context.filesDir,"memory-test.txt").writeText(results.joinToString("\n"))
            test.finish(Activity.RESULT_OK,Bundle().apply { putString("results",results.joinToString("\n")) })
        } catch(error: Throwable) {
            results += "FAIL: ${error.stackTraceToString()}"
            File(context.filesDir,"memory-test.txt").writeText(results.joinToString("\n"))
            test.finish(Activity.RESULT_CANCELED,Bundle().apply { putString("results",results.joinToString("\n")) })
        }
    }

    private suspend fun engineRoundtrip(test: Instrumentation, results: MutableList<String>) {
        val c=(test.targetContext.applicationContext as ForgeMindApp).container
        val old=c.settingsRepo.agentSettings.value
        val provider=ProviderConfig("qa-memory-${UUID.randomUUID()}",modelId="qa-memory-model",rawEndpointUrl=true,
            wireProtocolOverride=WireProtocol.OPENAI_COMPAT,toolCallingMode=ToolCallingMode.XML_JSON,enableThinking=false)
        val sid="qa-memory-${UUID.randomUUID()}"
        val foreign="qa-foreign-${UUID.randomUUID()}"
        val profile=AssistantProfileEntity("qa-profile-${UUID.randomUUID()}","QA profile",instructions="ASSISTANT_REQUEST_MARKER")
        val repo=MemoryRepository(c.db)
        val server=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        server.soTimeout=25000
        val requests=java.util.concurrent.CopyOnWriteArrayList<String>()
        var worker: Job?=null
        try {
            c.settingsRepo.saveProviderConfig(provider.copy(baseUrl="http://127.0.0.1:${server.localPort}/chat"))
            c.settingsRepo.saveAgentSettings(old.copy(memoryEnabled=true,memoryBudgetTokens=2048,autoCompactContext=false,autoApproveAll=true,thinkEveryToolCalls=0,enableTools=true,maxTurns=3))
            c.db.sessionDao().upsert(ChatSessionEntity(sid,"Memory QA",providerId=provider.providerId,modelId=provider.modelId))
            c.db.sessionDao().upsert(ChatSessionEntity(foreign,"Foreign QA",providerId=provider.providerId,modelId=provider.modelId))
            c.db.assistantDao().insert(profile)
            c.db.assistantDao().assign(sid,profile.id)
            val current=repo.context(sid)
            repo.save(current,MemoryScope.ASSISTANT,"Assistant memory","ASSISTANT_MEMORY_MARKER")
            repo.save(current,MemoryScope.CHAT,"Approved","APPROVED_REQUEST_MARKER")
            repo.save(repo.context(foreign),MemoryScope.CHAT,"Private","FOREIGN_REQUEST_MARKER")
            repo.propose(current,"create",null,"Draft","PENDING_REQUEST_MARKER",null)
            worker=CoroutineScope(Dispatchers.IO).launch {
                repeat(4) { index ->
                    server.accept().use { socket ->
                        socket.soTimeout=15000
                        val input=socket.getInputStream();val header=StringBuilder()
                        while(!header.endsWith("\r\n\r\n")) { val b=input.read();check(b>=0);header.append(b.toChar()) }
                        val length=header.lines().first { it.startsWith("Content-Length:",true) }.substringAfter(':').trim().toInt()
                        val body=ByteArray(length);var count=0
                        while(count<length) { val n=input.read(body,count,length-count);check(n>0);count+=n }
                        requests+=body.toString(Charsets.UTF_8)
                        val answer=if(index==0) """<tool_call>{"name":"memory_propose","arguments":{"title":"Agent draft","content":"AGENT_DRAFT_ONLY"}}</tool_call>""" else "MEMORY_ROUNDTRIP_DONE"
                        val data=buildJsonObject { putJsonArray("choices") { addJsonObject { putJsonObject("delta") { put("content",answer) } } } }
                        val stream="data: $data\n\ndata: [DONE]\n\n".toByteArray()
                        socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${stream.size}\r\nConnection: close\r\n\r\n".toByteArray());write(stream);flush() }
                    }
                }
            }
            suspend fun send() {
                val completed=CompletableDeferred<String>()
                withContext(Dispatchers.Main) { c.agentEngine.sendUserTurn(sid,"Check memory",emptyList()) { completed.complete(it) } }
                withTimeout(25000) { while(c.agentEngine.isRunning.value) delay(40) }
                check(completed.isCompleted && completed.await().contains("MEMORY_ROUNDTRIP_DONE"))
            }
            send()
            check(requests.size==2)
            check(requests.all { it.contains("APPROVED_REQUEST_MARKER") && !it.contains("FOREIGN_REQUEST_MARKER") && !it.contains("PENDING_REQUEST_MARKER") })
            check(c.db.memoryDao().allNotes().none { it.content=="AGENT_DRAFT_ONLY" })
            val draft=c.db.memoryDao().allProposals().single { it.sourceSessionId==sid && it.content=="AGENT_DRAFT_ONLY" }
            check(c.db.messageDao().getForSession(sid).any { it.toolName=="memory_propose" && !it.isError })
            results += "real_engine_xml_proposal_stays_pending_in_autopilot=PASS"
            results += "real_request_contains_only_approved_scoped_memory=PASS"
            check(requests.all { it.contains("ASSISTANT_REQUEST_MARKER") && it.contains("ASSISTANT_MEMORY_MARKER") })
            results += "real_request_contains_profile_and_isolated_assistant_memory=PASS"
            repo.approve(sid,draft.id)
            send()
            val third=Json.parseToJsonElement(requests[2]).jsonObject["messages"]!!.jsonArray
            check(third.filter { it.jsonObject["role"]?.jsonPrimitive?.content=="system" }.any { it.toString().contains("AGENT_DRAFT_ONLY") })
            results += "approved_memory_visible_on_next_engine_request=PASS"
            c.settingsRepo.saveAgentSettings(c.settingsRepo.agentSettings.value.copy(memoryEnabled=false))
            send()
            val fourth=Json.parseToJsonElement(requests[3]).jsonObject["messages"]!!.jsonArray
            check(fourth.filter { it.jsonObject["role"]?.jsonPrimitive?.content=="system" }.none { it.toString().contains("APPROVED_REQUEST_MARKER") })
            results += "memory_toggle_removes_request_context=PASS"
            check(c.toolRegistry.execute("memory_search","{}",WorkspaceEnvType.INTERNAL,"default",sid,"",c.settingsRepo.agentSettings.value,c.settingsRepo.searchSettings.value).isError)
            results += "disabled_memory_tool_reports_unavailable=PASS"
            c.settingsRepo.saveAgentSettings(c.settingsRepo.agentSettings.value.copy(memoryEnabled=true))
            val originNote=MemoryPolicy.note(current,MemoryScope.MODEL,"Original","ORIGINAL_MODEL_MEMORY")
            val newNote=MemoryPolicy.note(current.copy(modelId="changed"),MemoryScope.MODEL,"New","NEW_MODEL_MEMORY")
            c.db.memoryDao().insert(originNote);c.db.memoryDao().insert(newNote)
            try {
                val originId="qa-origin"
                c.db.messageDao().upsert(ChatMessageEntity(originId,sid,orderIndex=999,role="ASSISTANT",providerId=provider.providerId,modelId=provider.modelId))
                c.db.sessionDao().updateModel(sid,provider.providerId,"changed")
                val search=c.toolRegistry.execute("memory_search","""{"query":"MODEL_MEMORY"}""",WorkspaceEnvType.INTERNAL,"default",sid,originId,c.settingsRepo.agentSettings.value,c.settingsRepo.searchSettings.value)
                check(!search.isError && search.text.contains("ORIGINAL_MODEL_MEMORY") && !search.text.contains("NEW_MODEL_MEMORY"))
                check(c.toolRegistry.requiresApproval("memory_propose","{}",old.copy(autoApproveAll=true,blockedTools=listOf("memory_propose"))))
                check("memory_propose" in com.forgemind.app.agent.AgentToolRegistry.SHELL_AND_WRITE_HOST_TOOLS)
                results += "tool_memory_scope_uses_origin_model_during_picker_change=PASS"
                results += "blocked_tools_and_readonly_host_guards_preserved=PASS"
            } finally { c.db.memoryDao().delete(originNote.id);c.db.memoryDao().delete(newNote.id) }
            worker.join()
        } finally {
            server.close(); worker?.cancelAndJoin();c.agentEngine.cancelRun()
            c.db.sessionDao().deleteById(sid);c.db.sessionDao().deleteById(foreign)
            c.settingsRepo.saveAgentSettings(old);c.settingsRepo.removeProvider(provider.providerId)
            if(c.db.assistantDao().get(profile.id)!=null) AssistantRepository(c.db).delete(profile)
        }
    }
}
