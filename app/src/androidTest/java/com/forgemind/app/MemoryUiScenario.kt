package com.forgemind.app

import android.app.Instrumentation
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import com.forgemind.app.ui.*
import com.forgemind.app.data.db.ChatSessionEntity
import com.forgemind.app.data.memory.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

object MemoryUiScenario {
    suspend fun run(test: Instrumentation): List<String> {
        fun <T> main(block: () -> T): T { var result: Result<T>?=null;test.runOnMainSync { result=runCatching(block) };return result!!.getOrThrow() }
        val context=test.targetContext
        val c=(context.applicationContext as ForgeMindApp).container
        val activity=test.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val vm=main { ViewModelProvider(activity)[MainViewModel::class.java] }
        val sid="qa-memory-ui-${UUID.randomUUID()}"
        val previous=vm.currentSessionId.value
        val repo=MemoryRepository(c.db)
        val results=mutableListOf<String>()
        fun find(text: String): List<AccessibilityNodeInfo> {
            val nodes=mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo?) {
                if(node==null) return
                if(node.text?.toString()==text || node.contentDescription?.toString()==text) nodes+=node
                for(i in 0 until node.childCount) visit(node.getChild(i))
            }
            visit(test.uiAutomation.rootInActiveWindow)
            return nodes
        }
        suspend fun click(text: String) {
            withTimeout(10000) {
                while(true) {
                    var node=find(text).firstOrNull()
                    while(node!=null && !node.isClickable) node=node.parent
                    if(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true) break
                    delay(100)
                }
            }
            delay(300)
        }
        try {
            c.db.sessionDao().upsert(ChatSessionEntity(sid,"Проверка памяти",providerId="qa-ui",modelId="test"))
            val ctx=repo.context(sid)
            repo.save(ctx,MemoryScope.CHAT,"Название проекта","Тестовая запись: применять только в этом чате.")
            val pending=repo.propose(ctx,"create",null,"Предпочтение","Показывать температуры в градусах Цельсия.",null)
            main { vm.selectSession(sid);vm.selectSection(AppSection.CHAT) }
            withTimeout(15000) { while(vm.currentSession.value?.id!=sid) delay(50) }
            click("Память: ожидают подтверждения 1")
            withTimeout(10000) { while(find("Использовать память").isEmpty()) delay(50) }
            results += "memory_banner_opens_manager=PASS"
            click("Посмотреть предложение")
            check(find("Показывать температуры в градусах Цельсия.").isNotEmpty())
            check(c.db.memoryDao().allNotes().none { it.chatId==sid && it.title=="Предпочтение" })
            val screenshot=test.uiAutomation.takeScreenshot()
            File(context.filesDir,"memory-proposal.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            screenshot.recycle()
            click("Подтвердить")
            withTimeout(10000) { while(c.db.memoryDao().proposal(pending.id)!=null) delay(50) }
            check(c.db.memoryDao().allNotes().any { it.chatId==sid && it.title=="Предпочтение" })
            results += "memory_ui_review_and_explicit_approval=PASS"
            val reject=repo.propose(ctx,"create",null,"Отклонить","Не должно стать памятью",null)
            click("Посмотреть предложение");click("Отклонить")
            withTimeout(10000) { while(c.db.memoryDao().proposal(reject.id)!=null) delay(50) }
            check(c.db.memoryDao().allNotes().none { it.chatId==sid && it.title=="Отклонить" })
            results += "memory_ui_rejection_keeps_notes_unchanged=PASS"
            click("Закрыть")
        } finally {
            previous?.let { main { vm.selectSession(it) } }
            c.db.sessionDao().deleteById(sid)
            main { activity.finish() }
        }
        return results
    }
}
