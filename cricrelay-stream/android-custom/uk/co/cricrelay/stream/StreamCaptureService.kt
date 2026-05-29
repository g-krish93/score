package uk.co.cricrelay.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
        acquireStreamWakeLock()
        return START_STICKY
    }

    override fun onDestroy() {
        releaseStreamWakeLock()
        StreamCameraEngine.stopStreamFromService()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val channelId = "cricrelay_stream"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId,
                "CricRelay live stream",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("CricRelay Live")
            .setContentText("Camera stream active")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STATUS = "uk.co.cricrelay.stream.STATUS"
        const val EXTRA_EVENT = "event"
        const val EXTRA_MESSAGE = "message"

        const val EVENT_PREPARING = "preparing"
        const val EVENT_CONNECTING = "connecting"
        const val EVENT_CONNECTED = "connected"
        const val EVENT_ERROR = "error"
        const val EVENT_DISCONNECTED = "disconnected"

        private const val NOTIF_ID = 4401

        fun buildEndpoint(rtmpUrl: String, streamKey: String): String {
            var server = rtmpUrl.trim().trimEnd('/')
            val key = streamKey.trim()
            if (server.isEmpty()) return ""
            if (key.isEmpty()) return server
            if (server.endsWith("/$key")) return server
            return "$server/$key"
        }
    }
}
