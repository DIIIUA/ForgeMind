package com.forgemind.app

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import com.forgemind.app.ui.*
import com.forgemind.app.data.db.*
import com.forgemind.app.data.memory.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

object AssistantProfileUiScenario {
    suspend fun run(test: Instrumentation): List<String> {
        fun <T> main(block: ()->T): T { var value:Result<T>?=null;test.runOnMainSync {value=runCatching(block)};return value!!.getOrThrow() }
        val c=(test.targetContext.applicationContext as ForgeMindApp).container
        val activity=test.startActivitySync(Intent(test.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val vm=main { ViewModelProvider(activity)[MainViewModel::class.java] }
        val id="qa-profile-ui-${UUID.randomUUID()}";val name="QA ассистент"
        val old=vm.currentSessionId.value
        val results=mutableListOf<String>()
        fun nodes():List<AccessibilityNodeInfo> {
            val found=mutableListOf<AccessibilityNodeInfo>()
            fun visit(n:AccessibilityNodeInfo?) {if(n==null)return;found+=n;for(i in 0 until n.childCount)visit(n.getChild(i))}
            visit(test.uiAutomation.rootInActiveWindow);return found
        }
        suspend fun click(label:String) {
            withTimeout(15000) { while(true) {
                var node=nodes().firstOrNull {it.text?.toString()==label||it.contentDescription?.toString()==label}
                while(node!=null&&!node.isClickable)node=node.parent
                if(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)break
                nodes().firstOrNull {it.actionList.any {a->a.id==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD}}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                delay(100)
            } };delay(300)
        }
        fun type(node:AccessibilityNodeInfo,value:String) {check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)}))}
        try {
            c.db.sessionDao().upsert(ChatSessionEntity(id,"Профиль QA"))
            main {vm.selectSession(id);vm.selectSection(AppSection.CHAT)}
            withTimeout(15000) {while(vm.currentSession.value?.id!=id)delay(50)}
            click(activity.getString(R.string.chat_more));click("Профили ассистентов");click("Создать профиль")
            val fields=nodes().filter {it.isEditable}
            check(fields.size>=3)
            type(fields[0],name);type(fields[1],"Тесты");type(fields[2],"Отвечай кратко. UI_PROFILE_INSTRUCTION")
            click("Сохранить")
            withTimeout(10000) {while(c.db.assistantDao().all().none {it.name==name})delay(50)}
            val profile=c.db.assistantDao().all().single {it.name==name}
            check(profile.category=="Тесты"&&profile.instructions.contains("UI_PROFILE_INSTRUCTION"))
            results += "profile_ui_creates_instruction_and_category=PASS"
            click("Выбрать")
            withTimeout(10000) {while(c.db.sessionDao().getById(id)!!.assistantId!=profile.id)delay(50)}
            click("Ассистент: $name")
            val bitmap=test.uiAutomation.takeScreenshot()
            File(test.targetContext.filesDir,"assistant-profiles.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            results += "profile_ui_selection_and_active_badge=PASS"
            click("Без профиля")
            withTimeout(10000) {while(c.db.sessionDao().getById(id)!!.assistantId!=null)delay(50)}
            results += "profile_ui_detach_preserves_chat=PASS"
            val memory=MemoryRepository(c.db)
            memory.save(memory.context(id),MemoryScope.CHAT,"Doctor QA","Мне нужно записаться на приём к врачу.")
            memory.save(memory.context(id),MemoryScope.CHAT,"Cake QA","Пирог выпекают в духовке около часа.")
            click(activity.getString(R.string.chat_more));click("Память");click("По смыслу")
            type(nodes().first {it.isEditable},"book a medical appointment")
            delay(300);click("Найти")
            withTimeout(90000) {while(vm.semanticUiBusy.value || vm.semanticMemoryResults.value==null)delay(100)}
            check(vm.memoryError.value==null) {vm.memoryError.value.orEmpty()}
            check(vm.semanticMemoryResults.value!!.first().first.title=="Doctor QA")
            withTimeout(10000) {while(vm.semanticVisibleMemory.value.isEmpty())delay(50)}
            check(vm.semanticVisibleMemory.value.first().first.title=="Doctor QA")
            nodes().firstOrNull {it.actionList.any {a->a.id==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD}}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            delay(300)
            val searchBitmap=test.uiAutomation.takeScreenshot()
            File(test.targetContext.filesDir,"semantic-memory.png").outputStream().use {searchBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};searchBitmap.recycle()
            results += "semantic_memory_ui_search_renders_ranked_notes=PASS"
            main {vm.memoryQuery.value="Changed query"}
            withTimeout(10000) {while(vm.semanticMemoryResults.value!=null||vm.semanticVisibleMemory.value.isNotEmpty())delay(50)}
            results += "semantic_memory_ui_query_change_clears_stale_results=PASS"
            click("Поиск")
            check(nodes().any {it.text?.toString()=="Проверить модель"})
            click("Закрыть");click("Закрыть")
            results += "semantic_model_settings_ui_reports_downloaded_model=PASS"
        } finally {
            c.db.assistantDao().all().filter {it.name==name}.forEach {AssistantRepository(c.db).delete(it)}
            old?.let {main {vm.selectSession(it)}}
            c.db.sessionDao().deleteById(id)
            main {activity.finish()}
        }
        return results
    }
}
