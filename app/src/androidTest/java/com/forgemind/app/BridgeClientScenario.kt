package com.forgemind.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.forgemind.app.bridge.*
import com.forgemind.app.data.model.WorkspaceEnvType
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

/** End-to-end checks through the real main application's UID and client code. */
class BridgeClientScenario : Instrumentation() {
    private var helper = false
    override fun onCreate(arguments: Bundle?) { helper = arguments?.getString("helper") == "true"; super.onCreate(arguments); start() }
    override fun onStart() {
        val results = mutableListOf<String>()
        var failure: Throwable? = null
        try {
            check(android.os.Build.HARDWARE in setOf("ranchu","goldfish")) { "Emulator only" }
            // Instrumentation.onCreate runs before Application.onCreate finishes.
            waitForIdleSync()
            val context = targetContext
            val container = (context.applicationContext as ForgeMindApp).container
            val bridge = container.termuxBridge
            val workspace = container.workspaceManager
            val manager = container.terminalManager
            val root = RemoteTermuxClient.HOME + "/main-bridge-probe-" + UUID.randomUUID()
            val privateFixture = File(context.filesDir, "bridge-private-probe.txt")
            val createdTabs = mutableListOf<String>()
            runBlocking {
                try {
                    if (helper) {
                        com.forgemind.app.setup.TermuxHelperSetup.prepare(context)
                        val configured = TermuxDaemonApi.config(context)
                        check(runCatching { TermuxDaemonApi(configured.copy(token = "0".repeat(64))).info() }.isFailure)
                        check(bridge.remote.connect().info().getString("version") == "1.0.0")
                        com.forgemind.app.setup.TermuxHelperSetup.prepare(context)
                        results += "bundled_helper_install_idempotent_authenticated=PASS"
                    }
                    check(!bridge.hasUnifiedTermux())
                    check(context.applicationInfo.uid != context.packageManager.getApplicationInfo("com.termux",0).uid)
                    check(bridge.remote.available())
                    bridge.updateProfiles(listOf(TermuxBridge.defaultTermuxProfile()))
                    check(bridge.usesRemoteBridge())
                    results += "main_separate_uid_authenticated_bridge=PASS"
                    workspace.createFolder(WorkspaceEnvType.TERMUX_SSH,root).getOrThrow()
                    val result = bridge.runCommand("printf 'MAIN_EXEC_OK\\n'; pwd",root,10)
                    check(result.exitCode == 0 && result.stdout.contains("MAIN_EXEC_OK") && result.stdout.contains(root)) { result.toString() }
                    results += "main_command_route=PASS"
                    privateFixture.writeText("synthetic-private-value")
                    check(runCatching { bridge.remote.file(privateFixture.path,"r").close() }.isFailure)
                    val privateRead = bridge.runCommand("cat " + TermuxBridge.shellSingleQuote(privateFixture.path),root,10)
                    check(privateRead.exitCode != 0 && !privateRead.stdout.contains("synthetic-private-value"))
                    results += "main_private_data_not_readable_from_termux=PASS"
                    val filename = "$root/roundtrip.txt"
                    workspace.writeRawBytes(WorkspaceEnvType.TERMUX_SSH,filename,"main-client-✓".toByteArray()).getOrThrow()
                    check(workspace.readRawBytes(WorkspaceEnvType.TERMUX_SSH,filename).getOrThrow().decodeToString()=="main-client-✓")
                    check(workspace.listFiles(WorkspaceEnvType.TERMUX_SSH,root).getOrThrow().any { it.name=="roundtrip.txt" })
                    check(workspace.deleteFile(WorkspaceEnvType.TERMUX_SSH,filename).isFailure)
                    workspace.deleteFile(WorkspaceEnvType.TERMUX_SSH,filename,userConfirmed=true).getOrThrow()
                    results += "main_file_api_and_mandatory_delete_confirmation=PASS"
                    check(container.toolRegistry.requiresExecutionApproval("file_delete",
                        """{"target_env":"termux_ssh","path":"/tmp/example","userConfirmed":true}""",
                        com.forgemind.app.data.model.AgentSettings(autoApproveAll=true,allowedTools=listOf("file_delete")),
                        WorkspaceEnvType.INTERNAL))
                    results += "autopilot_and_forged_tool_confirmation_cannot_bypass_delete_approval=PASS"
                    val start = bridge.runCommandAsync("sleep 1; printf 'TASK_MAIN_OK\\n'",root).getOrThrow()
                    val tasks = CommandTaskService(bridge)
                    val record = tasks.track(start)
                    check(record.backend==CommandTaskBackend.REMOTE_BRIDGE)
                    check(tasks.track(start).id==record.id)
                    bridge.updateProfiles(listOf(TermuxBridge.defaultTermuxProfile().copy(host="unreachable.invalid")))
                    check(!bridge.usesRemoteBridge())
                    val end=System.currentTimeMillis()+10_000
                    var status=tasks.controller.status(record.id)
                    while(!status.finished && System.currentTimeMillis()<end) { delay(100); status=tasks.controller.status(record.id) }
                    check(status.finished && status.exitCode==0 && status.output.contains("TASK_MAIN_OK")) { status.toString() }
                    results += "persisted_task_keeps_original_bridge_route=PASS"
                    bridge.updateProfiles(listOf(TermuxBridge.defaultTermuxProfile()))
                    val tab = manager.openManagedTermux(true); createdTabs += tab
                    withContext(Dispatchers.Main) { manager.sendLine(tab,"stty -echo; printf 'MAIN_%s\\n' PTY") }
                    suspend fun awaitOutput(id:String, marker:String) {
                        val deadline=System.currentTimeMillis()+10_000
                        while(System.currentTimeMillis()<deadline) {
                            if(manager.tabs.value.firstOrNull { it.id==id }?.output.orEmpty().contains(marker)) return
                            delay(100)
                        }
                        error("Terminal marker missing: $marker\n" + manager.tabs.value.firstOrNull { it.id==id }?.output.orEmpty().takeLast(2000))
                    }
                    awaitOutput(tab,"MAIN_PTY")
                    withContext(Dispatchers.Main) {
                        manager.tabs.value.first { it.id==tab }.nativeSession!!.updateSize(101,37,8,16)
                    }
                    // A tmux status line occupies pane rows. Verify the actual client PTY size,
                    // and wait for the asynchronous resize rather than sampling it too early.
                    val target=TermuxBridge.shellSingleQuote("="+manager.tabs.value.first { it.id==tab }.managedSession!!.name)
                    withContext(Dispatchers.Main) { manager.sendLine(tab,
                        """for ((i=0;i<100;i++)); do size=${'$'}(tmux list-clients -t $target -F '#{client_height} #{client_width}'); if [ "${'$'}size" = '37 101' ]; then printf 'RESIZE_%s\n' OK; break; fi; sleep 0.1; done""") }
                    awaitOutput(tab,"RESIZE_OK")
                    withContext(Dispatchers.Main) { manager.sendLine(tab,"sleep 60") }
                    delay(300)
                    withContext(Dispatchers.Main) { manager.sendCtrlC(tab); manager.sendLine(tab,"printf 'MAIN_%s\\n' INTERRUPTED") }
                    awaitOutput(tab,"MAIN_INTERRUPTED")
                    results += "main_remote_terminal_input_resize_ctrl_c=PASS"
                    manager.setSessionKeep(tab,true)
                    manager.restartManagedTermux(tab)
                    val replacement = manager.activeTabId.value!!
                    createdTabs += replacement
                    check(replacement != tab && manager.tabs.value.any { it.id==tab && it.isAlive })
                    results += "main_kept_session_survives_replace=PASS"
                    if (helper) {
                        val api = bridge.remote.connect()
                        val id = UUID.randomUUID().toString()
                        val request = Bundle().apply {
                            putString("action", "start"); putString("id", id)
                            putString("command", "printf x >> once.txt; printf 'err-marker' >&2; exit 7")
                            putString("cwd", root)
                        }
                        api.tasks(request)
                        var done = api.tasks(Bundle().apply { putString("action", "status"); putString("id", id) })
                        val deadline = System.currentTimeMillis() + 10_000
                        while (done.getString("state") == "RUNNING" && System.currentTimeMillis() < deadline) {
                            delay(100); done = api.tasks(Bundle().apply { putString("action", "status"); putString("id", id) })
                        }
                        check(done.getString("state") == "COMPLETED" && done.getInt("exitCode") == 7) { done.toString() }
                        check(done.getString("stderr").orEmpty().contains("err-marker"))
                        api.tasks(request)
                        check(workspace.readRawBytes(WorkspaceEnvType.TERMUX_SSH,"$root/once.txt").getOrThrow().decodeToString() == "x")
                        val conflict = Bundle(request).apply { putString("command", "echo other") }
                        check(runCatching { api.tasks(conflict) }.isFailure)
                        results += "task_stable_id_no_replay_stderr_exit_code=PASS"
                        val long = bridge.runCommandAsync("sleep 60", root).getOrThrow()
                        val cancelled = bridge.remote.task(long.pid,"cancel")
                        check(cancelled.getString("state") == "CANCELLED") { cancelled.toString() }
                        check(bridge.remote.available())
                        results += "cancel_only_owned_task_helper_still_available=PASS"
                        check(runCatching { bridge.remote.file(com.forgemind.app.setup.TermuxHelperSetup.ROOT + "/token","r") }.isFailure)
                        check(runCatching { bridge.remote.files("delete",RemoteTermuxClient.HOME,confirmed=true) }.isFailure)
                        val bytes = ByteArray(200_000) { (it % 251).toByte() }
                        workspace.writeRawBytes(WorkspaceEnvType.TERMUX_SSH,"$root/binary.dat",bytes).getOrThrow()
                        check(workspace.readRawBytes(WorkspaceEnvType.TERMUX_SSH,"$root/binary.dat").getOrThrow().contentEquals(bytes))
                        results += "binary_stream_chunk_write_protected_helper_and_home=PASS"
                    }
                } finally {
                    for(tab in createdTabs) runCatching { manager.closeManagedTermux(tab) }
                    runCatching { bridge.remote.files("delete",root,confirmed=true) }
                    privateFixture.delete()
                }
            }
        } catch(error: Throwable) { failure=error; results += "FAIL=" + error.stackTraceToString() }
        val text=results.joinToString("\n")
        File(targetContext.filesDir,"bridge-client-scenario.txt").writeText(text)
        finish(if(failure==null) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream",text+"\n"); putBoolean("passed",failure==null) })
    }
}
