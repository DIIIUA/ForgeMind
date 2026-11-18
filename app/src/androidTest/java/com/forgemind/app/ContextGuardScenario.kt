package com.forgemind.app

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.forgemind.app.data.db.*
import com.forgemind.app.data.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.ServerSocket
import java.net.InetAddress
import java.util.UUID

/** Synthetic isolated-emulator data only. Never run this harness on the user's phone. */
object ContextGuardScenario {
    suspend fun run(context: Context, testContext: Context): List<String> {
        val results = mutableListOf<String>()
        withContext(Dispatchers.IO) {
            val name = "qa-migration-${UUID.randomUUID()}.db"
            val path = context.getDatabasePath(name)
            path.parentFile!!.mkdirs()
            val schema = Json.parseToJsonElement(testContext.assets.open("forgemind-schema-v5.json").bufferedReader().readText()).jsonObject["database"]!!.jsonObject
            SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
                schema["entities"]!!.jsonArray.forEach { element ->
                    val entity = element.jsonObject
                    val table = entity["tableName"]!!.jsonPrimitive.content
                    old.execSQL(entity["createSql"]!!.jsonPrimitive.content.replace("\u0024{TABLE_NAME}",table))
                    (entity["indices"] as? JsonArray).orEmpty().forEach { old.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\u0024{TABLE_NAME}",table)) }
                    if (table == "chat_sessions" || table == "chat_messages") {
                        val values = ContentValues()
                        entity["fields"]!!.jsonArray.forEach { field ->
                            val f=field.jsonObject
                            if (f["notNull"]?.jsonPrimitive?.booleanOrNull == true) {
                                val column=f["columnName"]!!.jsonPrimitive.content
                                if (f["affinity"]!!.jsonPrimitive.content=="TEXT") values.put(column,"") else values.put(column,0)
                            }
                        }
                        values.put("id",if(table=="chat_sessions") "qa-chat" else "qa-message")
                        if(table=="chat_sessions") { values.put("title","QA migration"); values.put("compactedSummary","old memory") }
                        else { values.put("sessionId","qa-chat"); values.put("role","USER"); values.put("content","ORIGINAL_HISTORY_MARKER") }
                        old.insertOrThrow(table,null,values)
                    }
                }
                old.version=5
            }
            val db=Room.databaseBuilder(context,ForgeMindDatabase::class.java,name).addMigrations(ForgeMindDatabase.MIGRATION_5_6, ForgeMindDatabase.MIGRATION_6_7, ForgeMindDatabase.MIGRATION_7_8, ForgeMindDatabase.MIGRATION_8_9).build()
            try {
                val session=db.sessionDao().getById("qa-chat")!!
                check(session.historyMessageLimit==-1 && session.compactedSummary=="old memory")
                check(db.messageDao().getMessage("qa-chat","qa-message")!!.content=="ORIGINAL_HISTORY_MARKER")
                results += "room_5_to_8_preserves_chat_and_messages=PASS"
                val checkpoint=CompactionCheckpointEntity("qa-checkpoint",session.id,"old memory",0,0,"new memory",1)
                db.sessionDao().applyCompactionCheckpoint(checkpoint,10)
                check(db.sessionDao().getById(session.id)!!.compactedSummary=="new memory")
                check(db.sessionDao().undoLatestCompaction(session.id))
                check(db.sessionDao().getById(session.id)!!.compactedSummary=="old memory")
                check(!db.sessionDao().undoLatestCompaction(session.id))
                check(db.messageDao().getForSession(session.id).single().content=="ORIGINAL_HISTORY_MARKER")
                results += "compaction_checkpoint_undo_preserves_originals=PASS"
                check(runCatching { db.sessionDao().applyCompactionCheckpoint(checkpoint.copy(id="conflict",previousSummary="stale"),10) }.isFailure)
                check(db.sessionDao().allCompactionCheckpoints().size==1)
                results += "compaction_conflict_does_not_commit_checkpoint=PASS"
            } finally { db.close(); context.deleteDatabase(name) }
        }

        val c=(context.applicationContext as ForgeMindApp).container
        val old=c.settingsRepo.agentSettings.value
        val favorites=c.settingsRepo.favoriteModels.value.map { it.split("::",limit=2) }.filter { it.size==2 }
        favorites.forEach { c.settingsRepo.toggleFavoriteModel(it[0],it[1]) }
        val provider=ProviderConfig("qa-text-${UUID.randomUUID()}",modelId="test",rawEndpointUrl=true,
            wireProtocolOverride=WireProtocol.OPENAI_COMPAT,toolCallingMode=ToolCallingMode.XML_JSON,enableThinking=false)
        val xml="""<tool_call>{"name":"capabilities_catalog","arguments":{}}</tool_call>"""
        try {
            for (mode in listOf("execute","example","disabled","thinking_tool","thinking_example","thinking_twice")) {
                val server=ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))
                val requests=java.util.concurrent.CopyOnWriteArrayList<String>()
                val sid="qa-xml-${UUID.randomUUID()}"
                val ready=c.settingsRepo.saveProviderConfig(provider.copy(baseUrl="http://127.0.0.1:${server.localPort}/chat"))
                c.settingsRepo.saveAgentSettings(old.copy(enableTools=mode!="disabled",autoApproveAll=true,autoCompactContext=false,thinkEveryToolCalls=0,maxTurns=3))
                c.db.sessionDao().upsert(ChatSessionEntity(sid,"XML QA",providerId=provider.providerId,modelId="test"))
                val worker=CoroutineScope(Dispatchers.IO).launch {
                    repeat(when(mode) { "execute","thinking_example","thinking_twice" -> 2; "thinking_tool" -> 3; else -> 1 }) { index ->
                        server.accept().use { socket ->
                            socket.soTimeout=20000
                            val input=socket.getInputStream()
                            val header=StringBuilder()
                            while (!header.endsWith("\r\n\r\n")) { val b=input.read(); check(b>=0); header.append(b.toChar()) }
                            val length=header.lines().first { it.startsWith("Content-Length:",true) }.substringAfter(':').trim().toInt()
                            val body=ByteArray(length); var count=0
                            while(count<length) { val n=input.read(body,count,length-count); check(n>0); count+=n }
                            requests+=body.toString(Charsets.UTF_8)
                            val thinking = mode.startsWith("thinking_") && (index==0 || mode=="thinking_twice")
                            val answer=when {
                                thinking -> "PRIVATE_THOUGHT_MARKER: example of a proposed call.\n$xml"
                                mode=="thinking_tool" && index==1 -> xml
                                mode=="example" || mode=="thinking_example" -> "```xml\n$xml\n```"
                                index>0 -> "XML_ROUNDTRIP_DONE"
                                else -> xml
                            }
                            val data=buildJsonObject { putJsonArray("choices") { addJsonObject { putJsonObject("delta") { put(if(thinking) "reasoning_content" else "content",answer) } } } }
                            val stream="data: $data\n\ndata: [DONE]\n\n".toByteArray()
                            socket.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${stream.size}\r\nConnection: close\r\n\r\n".toByteArray());write(stream);flush()
                            }
                        }
                    }
                }
                try {
                    val completed=CompletableDeferred<String>()
                    withContext(Dispatchers.Main) { c.agentEngine.sendUserTurn(sid,"Return the requested response",emptyList()) { completed.complete(it) } }
                    withTimeout(35000) { while(c.agentEngine.isRunning.value) kotlinx.coroutines.delay(40) }
                    val reply=if(completed.isCompleted) completed.await() else ""
                    worker.join()
                    val messages=c.db.messageDao().getForSession(sid)
                    if(mode=="execute" || mode=="thinking_tool") {
                        check(messages.count { it.role=="TOOL" && it.toolName=="capabilities_catalog" }==1)
                        check(reply.contains("XML_ROUNDTRIP_DONE"))
                        check(requests.size==(if(mode=="thinking_tool") 3 else 2) && requests.last().contains("capabilities_catalog"))
                        check(requests.all { "tools" !in Json.parseToJsonElement(it).jsonObject })
                    } else if(mode=="thinking_twice") {
                        check(requests.size==2 && messages.none { it.role=="TOOL" })
                        check(messages.last().isError && messages.last().thinking.contains("PRIVATE_THOUGHT_MARKER"))
                    } else {
                        check(messages.none { it.role=="TOOL" })
                        check(reply.contains(xml))
                    }
                    if(mode.startsWith("thinking_")) {
                        check(requests.drop(1).none { it.contains("PRIVATE_THOUGHT_MARKER") })
                        check(messages.first { it.role=="ASSISTANT" }.thinking.contains("PRIVATE_THOUGHT_MARKER"))
                    }
                    results += "xml_tool_${mode}_engine_roundtrip=PASS"
                } finally { server.close();worker.cancelAndJoin();c.agentEngine.cancelRun();c.db.sessionDao().deleteById(sid) }
            }
        } finally {
            c.settingsRepo.saveAgentSettings(old);c.settingsRepo.removeProvider(provider.providerId)
            favorites.asReversed().forEach { c.settingsRepo.toggleFavoriteModel(it[0],it[1]) }
        }
        return results
    }
}
