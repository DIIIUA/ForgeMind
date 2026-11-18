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
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.forgemind.app.ForgeMindApp
import com.forgemind.app.MainActivity
import com.forgemind.app.R
import com.forgemind.app.i18n.LocaleHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

class AgentForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        activeService=this
        enterForeground(getString(R.string.chat_agent_working))
    }

    private fun enterForeground(text: String): Boolean = try {
        val type=if(Build.VERSION.SDK_INT>=29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this,NOTIFICATION_ID,buildNotification(text),type)
        serviceReady=true
        pendingReady.toList().forEach{it.complete(Unit)};pendingReady.clear()
        true
    } catch(failure: Exception) {
        serviceReady=false
        pendingReady.toList().forEach{it.completeExceptionally(failure)};pendingReady.clear()
        android.util.Log.e("ForgeMindAgent","Foreground notification failed",failure)
        stopSelf()
        false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val status=intent?.getStringExtra(EXTRA_STATUS)?.takeIf{it.isNotBlank()} ?: getString(R.string.chat_agent_working)
        if(!enterForeground(status))return START_NOT_STICKY
        if (intent?.action == ACTION_STOP_AGENT) {
            runCatching {
                (application as? ForgeMindApp)?.container?.agentEngine?.cancelRun()
            }
            scheduleShutdown()
            return START_NOT_STICKY
        }
        if(!wantedRunning) {scheduleShutdown();return START_NOT_STICKY}

        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ForgeMind:AgentWork")?.apply {
                setReferenceCounted(false)
                runCatching { acquire(30 * 60 * 1000L) }
            }
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
        if(activeService===this) {activeService=null;serviceReady=false}
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
        private val main by lazy {Handler(Looper.getMainLooper())}
        private var activeService: AgentForegroundService?=null
        private var serviceReady=false
        private var wantedRunning=false
        private val pendingReady=mutableListOf<CompletableDeferred<Unit>>()
        private val shutdown=Runnable {
            if(!wantedRunning) activeService?.let { service ->
                serviceReady=false
                ServiceCompat.stopForeground(service,ServiceCompat.STOP_FOREGROUND_REMOVE)
                service.stopSelf()
            }
        }
        private fun scheduleShutdown() {
            main.removeCallbacks(shutdown)
            main.postDelayed(shutdown,1000L)
        }

        /** Do not start model work until Android has accepted the foreground notification. */
        suspend fun startAndAwait(context: Context,status: String="") {
            val ready=CompletableDeferred<Unit>()
            val app=context.applicationContext
            main.post {
                if(ready.isCancelled)return@post
                wantedRunning=true
                main.removeCallbacks(shutdown)
                pendingReady+=ready
                val current=activeService
                if(current!=null && serviceReady) {
                    current.enterForeground(status.ifBlank{current.getString(R.string.chat_agent_working)})
                } else try {
                    val intent=Intent(app,AgentForegroundService::class.java).putExtra(EXTRA_STATUS,status)
                    if(Build.VERSION.SDK_INT>=26)app.startForegroundService(intent)else app.startService(intent)
                } catch(failure: Exception) {pendingReady.remove(ready);ready.completeExceptionally(failure)}
            }
            try {withTimeout(10000){ready.await()}} finally {
                ready.cancel()
                main.post {pendingReady.remove(ready)}
            }
        }

        fun stop(@Suppress("UNUSED_PARAMETER") context: Context) {
            runCatching {
                main.post {wantedRunning=false;scheduleShutdown()}
            }
        }
    }
}
