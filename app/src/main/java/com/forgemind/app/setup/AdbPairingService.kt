package com.forgemind.app.setup

import android.app.*
import android.content.Intent
import android.os.IBinder
import com.forgemind.app.R
import kotlinx.coroutines.*
import java.util.UUID

/** User-started, time-limited notification flow; never needs overlay or Accessibility. */
class AdbPairingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var discovery: LocalAdbDiscovery? = null
    private var session = ""
    private var pairing = false
    private lateinit var notifications: NotificationManager
    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.adb_title), NotificationManager.IMPORTANCE_HIGH))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "stop" -> { stopSelf(); return START_NOT_STICKY }
            "reply" -> {
                if (intent.getStringExtra("session") != session) { stopSelf(); return START_NOT_STICKY }
                if (pairing) return START_NOT_STICKY
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence("code")?.toString().orEmpty()
                val port = intent.getIntExtra("port", -1)
                pairing = true
                notifications.notify(ID, notification(getString(R.string.adb_working)))
                discovery?.close(); discovery = null
                scope.launch {
                    val client = LocalAdb.get(this@AdbPairingService)
                    if (client.pair(port, code)) client.connect()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    notifications.notify(ID + 1, notification(client.state.value.message))
                    stopSelf()
                }
            }
            else -> {
                if (discovery != null || pairing) return START_NOT_STICKY
                session = UUID.randomUUID().toString()
                startForeground(ID, notification(getString(R.string.adb_searching)))
                discovery = LocalAdbDiscovery(this, LocalAdbDiscovery.PAIRING) { port ->
                    if (!pairing) notifications.notify(ID, notification(getString(R.string.adb_enter_code), port))
                }.also { it.start() }
                scope.launch { delay(180_000); if (!pairing) stopSelf() }
            }
        }
        return START_NOT_STICKY
    }
    private fun notification(text: String, port: Int? = null): Notification {
        val open = PendingIntent.getActivity(this, 90, Intent(this, com.forgemind.app.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_forgemind)
            .setContentTitle(getString(R.string.adb_title)).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open).setOnlyAlertOnce(true)
        if (port != null) {
            val reply = Intent(this, AdbPairingService::class.java).setAction("reply")
                .putExtra("port", port).putExtra("session", session)
            val pending = PendingIntent.getForegroundService(this, 91, reply, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(Notification.Action.Builder(null, getString(R.string.adb_enter_code), pending)
                .addRemoteInput(RemoteInput.Builder("code").setLabel(getString(R.string.adb_code)).build()).build())
        }
        val stop = PendingIntent.getService(this, 92, Intent(this, AdbPairingService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        builder.addAction(Notification.Action.Builder(null, getString(R.string.action_cancel), stop).build())
        return builder.build()
    }
    override fun onDestroy() { discovery?.close(); scope.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object { private const val CHANNEL = "local_adb_pairing"; private const val ID = 7301 }
}
