package uk.co.cricrelay.stream

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

/**
 * Foreground service while live: keeps process priority and CPU awake when the screen is off.
 * Video is encoded from the camera + overlay in [StreamCameraEngine], not from screen capture.
 */
class StreamCaptureService : Service() {

    private var partialWakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireStreamWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        partialWakeLock?.release()
        partialWakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "cricrelay:stream",
        ).apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 60 * 1000L)
        }
    }

    private fun releaseStreamWakeLock() {
        partialWakeLock?.let {
            if (it.isHeld) it.release()
        }
        partialWakeLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UPDATE_ELAPSED) {
            val label = intent.getStringExtra(EXTRA_ELAPSED) ?: ""
            updateNotification(label)
            return START_STICKY
        }
        try {
            val notification = buildNotification(elapsedLabel)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        acquireStreamWakeLock()
        return START_STICKY
    }

    override fun onDestroy() {
        releaseStreamWakeLock()
        super.onDestroy()
    }

    private fun updateNotification(label: String) {
        elapsedLabel = label
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification(label))
    }

    private fun buildNotification(elapsed: String): Notification {
        val channelId = "cricrelay_stream"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId,
                "CricRelay live stream",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val launch = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = if (elapsed.isNotEmpty()) "Live · $elapsed" else "Camera stream active"
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("CricRelay Live")
            .setContentText(body)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setContentIntent(launch)
            .addAction(
                android.R.drawable.ic_menu_view,
                "Return to stream",
                launch,
            )
            .build()
    }

    companion object {
        const val ACTION_STATUS = "uk.co.cricrelay.stream.STATUS"
        const val ACTION_UPDATE_ELAPSED = "uk.co.cricrelay.stream.UPDATE_ELAPSED"
        const val EXTRA_EVENT = "event"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_ELAPSED = "elapsed"

        const val EVENT_PREPARING = "preparing"
        const val EVENT_CONNECTING = "connecting"
        const val EVENT_CONNECTED = "connected"
        const val EVENT_ERROR = "error"
        const val EVENT_DISCONNECTED = "disconnected"

        private const val NOTIF_ID = 4401

        @Volatile
        private var elapsedLabel: String = ""

        fun buildEndpoint(rtmpUrl: String, streamKey: String): String {
            var server = rtmpUrl.trim().trimEnd('/')
            val key = streamKey.trim()
            if (server.isEmpty()) return ""
            if (key.isEmpty()) return server
            if (server.endsWith("/$key")) return server
            return "$server/$key"
        }

        fun updateElapsed(context: Context?, label: String) {
            val ctx = context ?: return
            val intent = Intent(ctx, StreamCaptureService::class.java).apply {
                action = ACTION_UPDATE_ELAPSED
                putExtra(EXTRA_ELAPSED, label)
            }
            try {
                ctx.startService(intent)
            } catch (_: Exception) {
            }
        }
    }
}
