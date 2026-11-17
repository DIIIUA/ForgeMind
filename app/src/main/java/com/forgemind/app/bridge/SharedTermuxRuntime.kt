package com.forgemind.app.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

/** Direct access is possible only after Android has placed both signed apps under the same UID. */
object SharedTermuxRuntime {
    const val PREFIX = "/data/data/com.termux/files/usr"
    const val HOME = "/data/data/com.termux/files/home"
    const val BASH = "$PREFIX/bin/bash"
    const val SESSION = "forge"

    fun hasSharedUid(context: Context): Boolean = runCatching {
        val termux = context.packageManager.getApplicationInfo("com.termux", 0)
        termux.uid == context.applicationInfo.uid
    }.getOrDefault(false)

    fun requireReady(context: Context) {
        check(hasSharedUid(context)) { "Direct Termux access requires the Termux plugin build and a compatible Termux installation." }
        check(File(BASH).canExecute()) { "Open Termux once to finish installing its shell, then reopen this terminal." }
    }

    fun environment(): Map<String, String> = System.getenv().toMutableMap().apply {
        put("HOME", HOME)
        put("PREFIX", PREFIX)
        put("TMPDIR", "$PREFIX/tmp")
        put("PATH", "$PREFIX/bin:/system/bin:/system/xbin")
        put("TERM", "xterm-256color")
        put("COLORTERM", "truecolor")
        put("LANG", "en_US.UTF-8")
        remove("LD_LIBRARY_PATH")
        remove("LD_PRELOAD")
        File("$PREFIX/lib/libtermux-exec.so").takeIf { it.isFile }?.let { put("LD_PRELOAD", it.absolutePath) }
    }

    fun startupArgs(session: String = SESSION): Array<String> {
        require(session == SESSION || ManagedTermuxSessions.validName(session))
        return arrayOf(BASH, "-lc",
        // Give Termux's service a moment to create the session first (see requestSessionViaTermux).
        "for ((i=0; i<120; i++)); do tmux has-session -t =$session 2>/dev/null && exec tmux attach-session -t =$session; sleep 0.25; done; " +
            "printf '\\nTermux is not ready. Open Termux, finish package setup, then reopen this tab.\\n'; exit 1")
    }

    /** Short-lived tmux client call with the Termux environment; returns its exit code, or -1. */
    fun tmuxQuery(vararg args: String, timeoutMs: Long = 5_000): Int = runCatching {
        val builder = ProcessBuilder(listOf("$PREFIX/bin/tmux") + args)
            .directory(File(HOME)).redirectErrorStream(true).redirectOutput(File("/dev/null"))
        builder.environment().apply { clear(); putAll(environment()) }
        val process = builder.start()
        if (!process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            -1
        } else process.exitValue()
    }.getOrDefault(-1)

    fun hasSession(): Boolean = File("$PREFIX/bin/tmux").canExecute() && tmuxQuery("has-session", "-t", SESSION) == 0

    /**
     * Keep a background task in Termux's foreground service for the session lifetime.
     * An unseen interactive Termux tab starts lazily when a view first gives it dimensions,
     * so it cannot bootstrap ForgeMind while Termux stays in the background.
     * The supervising shell and tmux server belong to Termux's process group, not ours.
     */
    fun requestSessionViaTermux(context: Context) {
        if (hasSession()) return
        val script = "command -v tmux >/dev/null 2>&1 || { pkg install -y tmux || exit; }; " +
            "tmux has-session -t $SESSION 2>/dev/null || tmux new-session -d -s $SESSION -n shell -x 120 -y 32 || exit; " +
            "while tmux has-session -t $SESSION 2>/dev/null; do sleep 5; done"
        startSupervisedTask(context, script)
    }

    fun requestManagedSession(context: Context, name: String, number: Int) {
        require(ManagedTermuxSessions.validName(name) && number >= 2)
        val owner = TermuxBridge.shellSingleQuote(context.packageName)
        // Capture the newly allocated ID. A metadata error must not leave an invisible shell.
        val script = "fm_id=${'$'}(tmux new-session -d -P -F '#{session_id}' -s $name -n shell -x 120 -y 32 -c $HOME $BASH -l) || exit; " +
            "tmux set-option -t \"${'$'}fm_id:\" @forgemind-owner $owner " +
            "\\; set-option -t \"${'$'}fm_id:\" @forgemind-number $number " +
            "\\; set-option -t \"${'$'}fm_id:\" @forgemind-keep 0 \\; set-option -t \"${'$'}fm_id:\" detach-on-destroy on " +
            "\\; set-option -t \"${'$'}fm_id:\" status-left '[$number] ' || { tmux kill-session -t =$name; exit 1; }; " +
            "while tmux has-session -t =$name 2>/dev/null; do sleep 5; done"
        startSupervisedTask(context, script)
    }

    private fun startSupervisedTask(context: Context, script: String) {
        val intent = Intent("com.termux.service_execute").apply {
            setClassName("com.termux", "com.termux.app.TermuxService")
            data = Uri.Builder().scheme("com.termux.file").path(BASH).build()
            putExtra("com.termux.execute.arguments", arrayOf("-lc", script))
            putExtra("com.termux.execute.cwd", HOME)
            putExtra("com.termux.execute.background", true)
        }
        checkNotNull(context.startForegroundService(intent)) { "Termux execution service is unavailable" }
    }

    /** The shared session exists, created by Termux's service. Waits up to [waitMs]. */
    suspend fun ensureSession(context: Context, waitMs: Long = 20_000): Boolean {
        if (hasSession()) return true
        requestSessionViaTermux(context)
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(250)
            if (hasSession()) return true
        }
        return false
    }

    fun openInTermux(context: Context, session: String = SESSION) {
        requireReady(context)
        require(session == SESSION || ManagedTermuxSessions.validName(session))
        // Termux's internal execution API is reachable by its shared-UID plugins.
        // It does not require enabling RUN_COMMAND for other apps in termux.properties.
        val intent = Intent("com.termux.service_execute").apply {
            setClassName("com.termux", "com.termux.app.TermuxService")
            data = Uri.Builder().scheme("com.termux.file").path(BASH).build()
            // Attach in a NEW native Termux tab; never send commands to its first/server tab.
            putExtra("com.termux.execute.arguments", startupArgs(session).drop(1).toTypedArray())
            putExtra("com.termux.execute.cwd", HOME)
            putExtra("com.termux.execute.background", false)
            putExtra("com.termux.execute.session_action", "2")
        }
        checkNotNull(context.startForegroundService(intent)) { "Termux execution service is unavailable" }
        // The button's foreground activity opens Termux; its background service need not
        // request overlay permission merely to bring the shared terminal to the front.
        context.startActivity(Intent().apply {
            setClassName("com.termux", "com.termux.app.TermuxActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        })
    }
}
