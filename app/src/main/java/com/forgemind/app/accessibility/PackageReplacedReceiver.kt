package com.forgemind.app.accessibility

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Waits for the already-authorized service after an APK update without toggling its entry. */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val appCtx = context.applicationContext
        ForgeAccessibilityService.registerAppContext(appCtx)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ForgeAccessibilityService.ensureConnectedOrSelfHeal(appCtx)
            } finally {
                runCatching { pending.finish() }
            }
        }
    }
}
