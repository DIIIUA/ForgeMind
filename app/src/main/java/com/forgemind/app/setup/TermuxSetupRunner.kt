package com.forgemind.app.setup

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.forgemind.app.bridge.CommandExecResult
import com.forgemind.app.bridge.TermuxResultReceiver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

object TermuxSetupRunner {
    suspend fun prepare(context: Context, script: String = TermuxSetupCommand.script): CommandExecResult = withContext(Dispatchers.IO) {
        check(context.checkSelfPermission("com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED)
        val token = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<CommandExecResult>()
        TermuxResultReceiver.pendingResults[token] = deferred
        val resultIntent = Intent(context, TermuxResultReceiver::class.java).apply {
            // Unique identity as well as extras: no hash collision can replace another callback.
            data = android.net.Uri.parse("forgemind-setup://result/$token")
            putExtra(TermuxResultReceiver.EXTRA_TOKEN, token)
        }
        val callback = PendingIntent.getBroadcast(context, 0, resultIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE)
        try {
            context.startForegroundService(Intent("com.termux.RUN_COMMAND").apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", callback)
            })
            // No SSH fallback and no automatic replay when an installation outcome is unknown.
            withTimeoutOrNull(180_000) { deferred.await() }
                ?: CommandExecResult("", "", -1, timedOut = true)
        } finally {
            TermuxResultReceiver.pendingResults.remove(token)
            callback.cancel()
        }
    }
}
