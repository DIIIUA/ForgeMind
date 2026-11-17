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
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

object SemanticAssistantScenario {
    fun run(test: Instrumentation) {
        val context=test.targetContext
        val results=mutableListOf<String>()
        fun progress(text: String) { File(context.filesDir,"semantic-progress.txt").writeText((results+text).joinToString("\n")) }
        fun pass(text: String) { results += "$text=PASS";progress("running") }
        try {
            check(android.os.Build.HARDWARE in setOf("ranchu","goldfish")) { "Emulator only" }
            runBlocking {
                withContext(Dispatchers.IO) {
                    val name="qa-assistants-${UUID.randomUUID()}.db"
                    val file=context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
                    val schema=Json.parseToJsonElement(test.context.assets.open("forgemind-schema-v7.json").bufferedReader().readText()).jsonObject["database"]!!.jsonObject
                    SQLiteDatabase.openOrCreateDatabase(file,null).use { old ->
                        schema["entities"]!!.jsonArray.forEach { value ->
                            val entity=value.jsonObject;val table=entity["tableName"]!!.jsonPrimitive.content
                            old.execSQL(entity["createSql"]!!.jsonPrimitive.content.replace("\u0024{TABLE_NAME}",table))
                            (entity["indices"] as? JsonArray).orEmpty().forEach { old.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\u0024{TABLE_NAME}",table)) }
                            if(table in setOf("chat_sessions","chat_messages","memory_notes","memory_proposals")) {
                                val row=ContentValues()
                                entity["fields"]!!.jsonArray.forEach { f -> val field=f.jsonObject
                                    if(field["notNull"]?.jsonPrimitive?.booleanOrNull==true) {
                                        if(field["affinity"]!!.jsonPrimitive.content=="TEXT") row.put(field["columnName"]!!.jsonPrimitive.content,"") else row.put(field["columnName"]!!.jsonPrimitive.content,0)
                                    }
                                }
                                row.put("id",if(table=="chat_sessions") "legacy" else table)
                                when(table) {
                                    "chat_sessions" -> row.put("title","LEGACY_CHAT")
                                    "chat_messages" -> { row.put("sessionId","legacy");row.put("role","USER");row.put("content","LEGACY_MESSAGE") }
                                    "memory_notes" -> { row.put("scopeType","CHAT");row.put("scopeKey","legacy");row.put("chatId","legacy");row.put("title","Legacy note");row.put("content","LEGACY_MEMORY");row.put("revision",1);row.put("enabled",1);row.put("searchText","legacy memory") }
                                    "memory_proposals" -> { row.put("sourceSessionId","legacy");row.put("action","create");row.put("scopeType","CHAT");row.put("scopeKey","legacy");row.put("title","Legacy pending");row.put("content","LEGACY_PENDING") }
                                }
                                old.insertOrThrow(table,null,row)
                            }
                        }
                        old.version=7
                    }
                    val db=Room.databaseBuilder(context,ForgeMindDatabase::class.java,name).addMigrations(ForgeMindDatabase.MIGRATION_7_8).build()
                    try {
                        check(db.sessionDao().getById("legacy")!!.assistantId==null)
                        check(db.messageDao().getForSession("legacy").single().content=="LEGACY_MESSAGE")
                        check(db.memoryDao().allNotes().single().content=="LEGACY_MEMORY")
                        check(db.memoryDao().allProposals().single().content=="LEGACY_PENDING")
                        pass("room_7_to_8_preserves_chats_memory_and_pending_proposals")
                        val profiles=AssistantRepository(db);val memory=MemoryRepository(db)
                        val profile=AssistantProfileEntity("profile-a","Research",category="Work",instructions="PROFILE_SYSTEM",defaultProviderId="p",defaultModelId="m",historyMessageLimit=7)
                        profiles.save(profile)
                        for(id in listOf("a","b","c")) db.sessionDao().upsert(ChatSessionEntity(id,id,providerId="original",modelId="original",customSystemPrompt="KEEP_CHAT_INSTRUCTIONS"))
                        profiles.attach("a",profile.id,true);profiles.attach("b",profile.id,false)
                        check(db.sessionDao().getById("a")!!.modelId=="m")
                        check(db.sessionDao().getById("b")!!.modelId=="original")
                        check(db.sessionDao().getById("a")!!.customSystemPrompt=="KEEP_CHAT_INSTRUCTIONS")
                        pass("profile_selection_optional_default_model_preserves_chat_instruction")
                        memory.save(memory.context("a"),MemoryScope.ASSISTANT,"Shared","ASSISTANT_ONLY")
                        check(memory.visible(memory.context("b")).single().content=="ASSISTANT_ONLY")
                        check(memory.visible(memory.context("c")).isEmpty())
                        db.sessionDao().updateModel("b","another","other")
                        check(memory.visible(memory.context("b")).single().content=="ASSISTANT_ONLY")
                        pass("assistant_memory_shared_only_with_same_profile_across_models")
                        val pending=memory.propose(memory.context("a"),"create","ASSISTANT","Draft","PROFILE_PENDING",null)
                        check(!memory.requestBlock(memory.context("b"),2048).contains("PROFILE_PENDING"))
                        memory.approve("a",pending.id)
                        check(memory.requestBlock(memory.context("b"),2048).contains("PROFILE_PENDING"))
                        pass("assistant_memory_proposals_still_require_confirmation")
                        profiles.save(profile.copy(name="Renamed"),profile.revision)
                        check(runCatching { profiles.save(profile.copy(name="Stale"),profile.revision) }.isFailure)
                        check(memory.visible(memory.context("a")).size==2)
                        pass("profile_rename_preserves_memory_and_rejects_stale_edit")
                        val bytes=ByteArrayOutputStream()
                        ChatArchiveManager(db,File(context.cacheDir,"qa-assistant-files")).exportFullZip(bytes)
                        val imported=Room.inMemoryDatabaseBuilder(context,ForgeMindDatabase::class.java).build()
                        try {
                            val archive=ChatArchiveManager(imported,File(context.cacheDir,"qa-assistant-import"))
                            archive.importFullZip(bytes.toByteArray().inputStream())
                            check(imported.assistantDao().get(profile.id)!!.name=="Renamed")
                            check(imported.sessionDao().getById("a")!!.assistantId==profile.id)
                            check(imported.memoryDao().allNotes().count { it.scopeType=="ASSISTANT" }==2)
                            val old=imported.assistantDao().get(profile.id)!!
                            AssistantRepository(imported).save(old.copy(name="Local edit"),old.revision)
                            archive.importFullZip(bytes.toByteArray().inputStream())
                            check(imported.assistantDao().get(profile.id)!!.name=="Local edit")
                            pass("archive_restores_profiles_scopes_and_preserves_local_profile_edits")
                        } finally { imported.close() }
                        profiles.delete(db.assistantDao().get(profile.id)!!)
                        check(db.sessionDao().getById("a")!!.assistantId==null && db.sessionDao().getById("a")!!.modelId=="m")
                        check(db.memoryDao().allNotes().single().content=="LEGACY_MEMORY")
                        check(db.messageDao().getForSession("legacy").single().content=="LEGACY_MESSAGE")
                        pass("profile_delete_detaches_chats_and_only_removes_profile_memory")
                    } finally { db.close();context.deleteDatabase(name) }
                }
                val c=(context.applicationContext as ForgeMindApp).container
                progress("Downloading or verifying pinned local model")
                withTimeout(240000) { c.semanticSearch.download() }
                check(c.semanticSearch.ready.value)
                pass("model_download_or_hash_validation")
                val documents=listOf(
                    SemanticDocument("doctor","Мне нужно записаться на приём к врачу."),
                    SemanticDocument("recipe","Пирог выпекают в духовке около часа."),
                    SemanticDocument("plane","Самолёт вылетает утром из аэропорта."),
                    SemanticDocument("code","Ошибка компиляции Kotlin в исходном коде приложения.")
                )
                val russian=c.semanticSearch.rank("Как попасть к доктору?",documents)
                check(russian.first().id=="doctor") { "Russian ranking: $russian" }
                val english=c.semanticSearch.rank("book a medical appointment",documents)
                check(english.first().id=="doctor") { "Cross-language ranking: $english" }
                pass("real_local_russian_paraphrase_and_cross_language_vectors")
                val repeat=c.semanticSearch.rank("Как попасть к доктору?",documents)
                check(repeat==russian)
                pass("persistent_vector_cache_reproduces_ranking")
                val appDb=c.db;val memory=MemoryRepository(appDb)
                val sid="qa-semantic-${UUID.randomUUID()}";val other="qa-other-${UUID.randomUUID()}"
                try {
                    appDb.sessionDao().upsert(ChatSessionEntity(sid,"Semantic QA"))
                    appDb.sessionDao().upsert(ChatSessionEntity(other,"Other QA"))
                    val ctx=memory.context(sid)
                    for(doc in documents) memory.save(ctx,MemoryScope.CHAT,doc.id,doc.text)
                    memory.save(memory.context(other),MemoryScope.CHAT,"Foreign","Как попасть к доктору? FOREIGN_EXACT")
                    memory.propose(ctx,"create",null,"Pending","Как попасть к доктору? PENDING_EXACT",null)
                    val (total, hits)=memory.semanticSearch(ctx,"Как попасть к доктору?",c.semanticSearch,2)
                    check(total==4 && hits.size==2 && hits.first().first.title=="doctor")
                    check(hits.none { it.first.title in setOf("Foreign","Pending") })
                    val second=memory.semanticSearch(ctx,"Как попасть к доктору?",c.semanticSearch,2,2).second
                    check(second.map { it.first.id }.intersect(hits.map { it.first.id }.toSet()).isEmpty())
                    pass("semantic_memory_scope_pending_isolation_and_pagination")
                    val old=hits.first().first
                    memory.save(ctx,MemoryScope.CHAT,"doctor","Новый текст про шоколадный торт и рецепт выпечки.",old)
                    val fresh=memory.semanticSearch(ctx,"Как испечь десерт?",c.semanticSearch,4).second
                    check(fresh.any { it.first.id==old.id && it.first.content.contains("шоколадный") })
                    memory.toggle(ctx,appDb.memoryDao().note(old.id)!!)
                    check(memory.semanticSearch(ctx,"шоколадный торт",c.semanticSearch,4).second.none { it.first.id==old.id })
                    pass("edited_notes_reindexed_and_disabled_notes_excluded")
                } finally { appDb.sessionDao().deleteById(sid);appDb.sessionDao().deleteById(other) }
                progress("Ranking actual tool catalog with local model")
                val start=System.currentTimeMillis()
                val catalog=Json.parseToJsonElement(c.toolRegistry.searchCapabilities("files","прочитать содержимое файла","semantic",5)).jsonObject
                val toolResults=catalog["results"]!!.jsonArray
                check(toolResults.any { it.jsonObject["name"]!!.jsonPrimitive.content=="file_read" })
                check(toolResults.all { "parameters" in it.jsonObject })
                check(Json.parseToJsonElement(c.toolRegistry.searchCapabilities("","","semantic",1)).jsonObject["built_in_count"]!!.jsonPrimitive.int==66)
                File(context.filesDir,"semantic-timings.txt").writeText("tool_area_search_ms=${System.currentTimeMillis()-start}\n")
                pass("semantic_tool_ranking_keeps_schemas_and_full_catalog_contract")
                results += AssistantProfileUiScenario.run(test)
                progress("ALL_PASS")
            }
            results += "ALL_PASS"
            File(context.filesDir,"semantic-assistants-test.txt").writeText(results.joinToString("\n"))
            test.finish(Activity.RESULT_OK,Bundle().apply { putString("results",results.joinToString("\n")) })
        } catch(error: Throwable) {
            results += "FAIL: ${error.stackTraceToString()}"
            File(context.filesDir,"semantic-assistants-test.txt").writeText(results.joinToString("\n"))
            test.finish(Activity.RESULT_CANCELED,Bundle().apply { putString("results",results.joinToString("\n")) })
        }
    }
}
