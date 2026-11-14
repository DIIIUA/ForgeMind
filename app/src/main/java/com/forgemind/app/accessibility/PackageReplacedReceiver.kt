package com.forgemind.app.accessibility

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Automatically recovers ForgeAccessibilityService after an in-place APK update
 * (`ACTION_MY_PACKAGE_REPLACED`) on OEM ROMs (such as Xiaomi HyperOS / MIUI) that move
 * bound Accessibility services into "Crashed services" or flip `accessibility_enabled=0`
 * when the old app process is killed during package installation.
 *
 * Self-healing runs silently whenever `WRITE_SECURE_SETTINGS` has been granted once
 * via ADB or Shizuku (`pm grant com.forgemind.app android.permission.WRITE_SECURE_SETTINGS`)
 * AND the service was already authorized by the user.
 */
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
