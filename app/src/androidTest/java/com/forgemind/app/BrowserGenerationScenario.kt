package com.forgemind.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import com.forgemind.app.data.db.ChatSessionEntity
import com.forgemind.app.data.model.*
import com.forgemind.app.ui.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.net.ServerSocket
import java.net.InetAddress
import java.util.UUID

object BrowserGenerationScenario {
    fun run(test: Instrumentation) {
        val ctx=test.targetContext
        val report=File(ctx.filesDir,"browser-generation-test.txt")
        val results=mutableListOf<String>()
        val server=ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))
        val inBrowser=CompletableDeferred<Unit>()
        val inAgentBrowser=CompletableDeferred<Unit>()
        var worker: Job?=null
        var phase="initialization"
        val pages=ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))
        val pageUrl="http://127.0.0.1:${pages.localPort}/generation"
        kotlin.concurrent.thread(isDaemon=true,name="generation-page-fixture") {
            while(!pages.isClosed) {
                val socket=runCatching { pages.accept() }.getOrNull() ?: break
                runCatching { socket.use {
                    it.soTimeout=5000
                    val reader=it.getInputStream().bufferedReader()
                    while(!reader.readLine().isNullOrEmpty()) { }
                    val body="<html><head><title>Browser generation QA</title></head><body>Generation continues here</body></html>".toByteArray()
                    it.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body);flush()
                    }
                } }
            }
        }
        fun <T> main(block: ()->T): T { var value:Result<T>?=null; test.runOnMainSync { value=runCatching(block) };return value!!.getOrThrow() }
        try {
            check(android.os.Build.HARDWARE in setOf("ranchu","goldfish")) { "Emulator only" }
            val c=(ctx.applicationContext as ForgeMindApp).container
            val activity=test.startActivitySync(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val vm=main { ViewModelProvider(activity)[MainViewModel::class.java] }
            fun hasAttachedWebView(view: android.view.View): Boolean = when(view) {
                is android.webkit.WebView -> view.isAttachedToWindow && view.width>0 && view.height>0
                is android.view.ViewGroup -> (0 until view.childCount).any { hasAttachedWebView(view.getChildAt(it)) }
                else -> false
            }
            fun findText(text: String): List<AccessibilityNodeInfo> {
                val matches=mutableListOf<AccessibilityNodeInfo>()
                fun visit(node: AccessibilityNodeInfo?) {
                    if(node==null) return
                    if(node.text?.toString()==text || node.contentDescription?.toString()==text) matches+=node
                    for(i in 0 until node.childCount) visit(node.getChild(i))
                }
                visit(test.uiAutomation.rootInActiveWindow)
                return matches
            }
            runBlocking {
                withTimeout(15000) { while(vm.currentSession.value==null) delay(50) }
                val previousSession=vm.currentSessionId.value
                val previousSettings=c.settingsRepo.agentSettings.value
                val sid="qa-browser-stream-${UUID.randomUUID()}"
                val provider=ProviderConfig("qa-browser-stream",modelId="test",baseUrl="http://127.0.0.1:${server.localPort}/chat",
                    rawEndpointUrl=true,wireProtocolOverride=WireProtocol.OPENAI_COMPAT,toolCallingMode=ToolCallingMode.XML_JSON)
                c.settingsRepo.saveProviderConfig(provider)
                c.settingsRepo.saveAgentSettings(previousSettings.copy(enableTools=true,autoApproveAll=false,autoApproveSafe=false,
                    autoApproveShell=false,autoCompactContext=false,thinkEveryToolCalls=0,maxTurns=3))
                c.db.sessionDao().upsert(ChatSessionEntity(sid,"Browser generation QA",providerId=provider.providerId,modelId="test"))
                worker=launch(Dispatchers.IO) {
                    repeat(2) { index ->
                        val accepted=try { server.accept() } catch(error: java.net.SocketException) {
                            if(server.isClosed) return@launch else throw error
                        }
                        accepted.use { socket ->
                        socket.soTimeout=20000
                        val input=socket.getInputStream();val header=StringBuilder()
                        while(!header.endsWith("\r\n\r\n")) { val b=input.read();check(b>=0);header.append(b.toChar()) }
                        val length=header.lines().first { it.startsWith("Content-Length:",true) }.substringAfter(':').trim().toInt()
                        var left=length;val buffer=ByteArray(8192)
                        while(left>0) { val n=input.read(buffer,0,minOf(left,buffer.size));check(n>0);left-=n }
                        val output=socket.getOutputStream()
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray());output.flush()
                        fun frame(key:String,value:String) {
                            val json=buildJsonObject { putJsonArray("choices") { addJsonObject { putJsonObject("delta") { put(key,value) } } } }
                            output.write("data: $json\n\n".toByteArray());output.flush()
                        }
                        if(index==0) {
                            frame("reasoning_content","BEFORE_BROWSER ")
                            withTimeout(20000) { inBrowser.await() };delay(200)
                            frame("reasoning_content","DURING_BROWSER ")
                            withTimeout(20000) { inAgentBrowser.await() };delay(200)
                            frame("reasoning_content","DURING_AGENT_BROWSER ");delay(250)
                            frame("content","""<tool_call>{"name":"run_command","arguments":{"command":"printf BROWSER_TOOL_OK","target_env":"internal"}}</tool_call>""")
                        } else frame("content","BROWSER_GENERATION_DONE")
                        output.write("data: [DONE]\n\n".toByteArray());output.flush()
                    } }
                }
                try {
                    main { vm.selectSession(sid);vm.selectSection(AppSection.CHAT) }
                    delay(400)
                    val reply=CompletableDeferred<String>()
                    main { c.agentEngine.sendUserTurn(sid,"Run the synthetic test",emptyList()) { reply.complete(it) } }
                    suspend fun thought(marker:String) = withTimeout(12000) {
                        while(c.db.messageDao().getForSession(sid).none { it.thinking.contains(marker) }) delay(50)
                        check(c.agentEngine.isRunning.value)
                    }
                    phase="first reasoning frame"
                    thought("BEFORE_BROWSER")
                    phase="user WebView attachment"
                    main { c.browserController.createOrSelectUserTab(initialUrl=pageUrl);c.browserController.setAgentMode(false);vm.selectSection(AppSection.PREVIEW) }
                    withTimeout(12000) { while(!main { hasAttachedWebView(activity.window.decorView) }) delay(50) }
                    delay(700);inBrowser.complete(Unit)
                    thought("DURING_BROWSER")
                    results += "reasoning_continues_in_user_browser=PASS"
                    phase="agent WebView attachment"
                    main { c.browserController.createOrSelectTab(initialUrl=pageUrl);c.browserController.setAgentMode(true) };delay(400)
                    withTimeout(12000) { while(!main { hasAttachedWebView(activity.window.decorView) }) delay(50) }
                    inAgentBrowser.complete(Unit)
                    thought("DURING_AGENT_BROWSER")
                    results += "reasoning_continues_in_agent_browser=PASS"
                    phase="browser approval"
                    withTimeout(12000) { while(c.agentEngine.pendingApprovals.value.isEmpty()) delay(50) }
                    val approval=c.agentEngine.pendingApprovals.value.first()
                    phase="locating Compose approval node"
                    withTimeout(10000) {
                        while(findText(approval.title).isEmpty()) delay(100)
                    }
                    results += "tool_approval_visible_in_browser=PASS"
                    val screenshot=test.uiAutomation.takeScreenshot()
                    File(ctx.filesDir,"browser-agent-approval.png").outputStream().use { screenshot?.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                    phase="clicking Compose approval"
                    val allow=activity.getString(R.string.action_allow)
                    val node=findText(allow).first()
                    var clickable:AccessibilityNodeInfo?=node
                    while(clickable!=null && !clickable.isClickable) clickable=clickable.parent
                    check(clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
                    check(withTimeout(20000) { reply.await() }.contains("BROWSER_GENERATION_DONE"))
                    withTimeout(5000) { while(c.agentEngine.isRunning.value) delay(25) }
                    results += "browser_approval_resumes_and_finishes_agent=PASS"
                    main { vm.selectSection(AppSection.CHAT) };delay(400)
                    val messages=c.db.messageDao().getForSession(sid)
                    check(messages.any { it.thinking.contains("BEFORE_BROWSER") && it.thinking.contains("DURING_AGENT_BROWSER") })
                    check(messages.last().content.contains("BROWSER_GENERATION_DONE"))
                    results += "return_to_chat_preserves_thinking_and_answer=PASS"

                    phase="direct terminal command"
                    val commandFile=File(ctx.cacheDir,"qa-chat-command-${UUID.randomUUID()}.txt")
                    val command="printf 'CHAT_%s_OK\\n' COMMAND >> ${com.forgemind.app.bridge.TermuxBridge.shellSingleQuote(commandFile.absolutePath)}; cat ${com.forgemind.app.bridge.TermuxBridge.shellSingleQuote(commandFile.absolutePath)}"
                    main { c.terminalManager.selectTab("qa-no-tab");vm.updateComposerText("/term $command");vm.sendMessage() }
                    delay(300)
                    check(vm.pendingChatCommand.value?.command==command)
                    check(vm.composerText.value=="/term $command")
                    main { vm.dismissChatCommand() }
                    check(vm.composerText.value=="/term $command")
                    results += "chat_command_cancel_preserves_draft=PASS"
                    main { vm.sendMessage();vm.confirmChatCommand();vm.confirmChatCommand() }
                    withTimeout(15000) { while(vm.terminalBusy.value || vm.composerText.value.isNotEmpty()) delay(50) }
                    val tab=c.terminalManager.activeTabId.value!!
                    try {
                        try {
                            withTimeout(8000) { while(!commandFile.exists()) delay(100) }
                            delay(250)
                            check(commandFile.readText()=="CHAT_COMMAND_OK\n") { "Command executed more than once or returned wrong result" }
                        } catch(error:Throwable) {
                            throw IllegalStateException("Command marker missing/wrong; terminal="+c.terminalManager.tabs.value.firstOrNull { it.id==tab }?.output?.takeLast(2500),error)
                        }
                        check(c.terminalManager.tabs.value.first { it.id==tab }.managedSession!=null)
                        results += "chat_command_confirm_uses_separate_managed_session=PASS"
                    } finally { c.terminalManager.closeManagedTermux(tab);commandFile.delete() }
                } finally {
                    server.close();worker?.cancelAndJoin();c.agentEngine.cancelRun()
                    c.settingsRepo.saveAgentSettings(previousSettings);c.settingsRepo.removeProvider(provider.providerId)
                    main { previousSession?.let(vm::selectSession);vm.selectSection(AppSection.CHAT) }
                    c.db.sessionDao().deleteById(sid)
                }
            }
            report.writeText(results.joinToString("\n")+"\nALL_PASS\n")
            test.finish(Activity.RESULT_OK,Bundle().apply { putString("stream",report.readText()) })
        } catch(error:Throwable) {
            report.writeText(results.joinToString("\n")+"\nFAIL during $phase: ${error.stackTraceToString()}")
            test.finish(Activity.RESULT_CANCELED,Bundle().apply { putString("stream",report.readText()) })
        } finally { server.close();pages.close() }
    }
}
