package com.forgemind.app.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.forgemind.app.ForgeMindApp
import com.forgemind.app.MainActivity
import com.forgemind.app.R
import com.forgemind.app.i18n.LocaleHelper

class AgentForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_AGENT) {
            runCatching {
                (application as? ForgeMindApp)?.container?.agentEngine?.cancelRun()
            }
            stopSelf()
            return START_NOT_STICKY
        }

        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ForgeMind:AgentWork")?.apply {
                setReferenceCounted(false)
                runCatching { acquire(30 * 60 * 1000L) }
            }
        }

        val statusText = intent?.getStringExtra(EXTRA_STATUS)?.takeIf { it.isNotBlank() }
            ?: getString(R.string.chat_agent_working)
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(statusText), fgsType)
        } catch (_: Throwable) {
            runCatching { stopSelf() }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        runCatching {
            (application as? ForgeMindApp)?.container?.agentEngine?.cancelRun()
        }
        stopSelf(startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AgentForegroundService::class.java).apply {
            action = ACTION_STOP_AGENT
        }
        val stopPi = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_forgemind)
            .setContentTitle("ForgeMind Agent")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.action_stop), stopPi)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "AI Agent Service",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "forgemind_agent_channel"
        private const val NOTIFICATION_ID = 1042
        private const val EXTRA_STATUS = "status"
        const val ACTION_STOP_AGENT = "com.forgemind.app.action.STOP_AGENT"

        fun start(context: Context, status: String = "") {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                putExtra(EXTRA_STATUS, status)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, AgentForegroundService::class.java))
            }
        }
    }
}
