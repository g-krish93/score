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
            // An elapsed tick must never resurrect a stopped service: plain startService
            // re-creates it without the freezer-exempting startForeground.
            if (!foregroundActive) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
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
        } catch (e: Exception) {
            // Refused bring-up (e.g. camera/mic FGS while the app is in the background on 14+).
            // Without startForeground there is no freezer exemption — the process is frozen
            // ~35s after the screen locks and viewers get dead air. The engine's keep-alive
            // check re-asserts on RTMP connect / foreground return and warns the operator.
            CricrelayLog.e("StreamCaptureService startForeground refused", e)
            foregroundActive = false
            stopSelf(startId)
            return START_NOT_STICKY
        }
        foregroundActive = true
        acquireStreamWakeLock()
        return START_STICKY
    }

    override fun onDestroy() {
        foregroundActive = false
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
        val body = when {
            elapsed.startsWith("Paused") -> elapsed
            elapsed.isNotEmpty() -> "Live · $elapsed"
            else -> "Camera stream active"
        }
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
        const val EVENT_PAUSED = "paused"
        const val EVENT_RESUMED = "resumed"
        const val EVENT_PREVIEW_READY = "preview_ready"

        /** Mid-broadcast connection drop; the engine is retrying on its own. */
        const val EVENT_RECONNECTING = "reconnecting"

        /** Reconnect attempts exhausted; the engine tore the session down. */
        const val EVENT_STREAM_LOST = "stream_lost"

        /** A burn-in (scoreboard / watermark / sponsor) degraded — stream continues without it. */
        const val EVENT_OVERLAY_WARNING = "overlay_warning"

        /**
         * The freezer-exempting foreground service could not be (re)started — a screen lock
         * or backgrounding may freeze the whole process mid-broadcast.
         */
        const val EVENT_KEEPALIVE_WARNING = "keepalive_warning"

        private const val NOTIF_ID = 4401

        @Volatile
        private var elapsedLabel: String = ""

        @Volatile
        private var foregroundActive = false

        /**
         * True from a successful [android.app.Service.startForeground] until destroy — i.e.
         * while the process actually holds its cached-app-freezer exemption.
         */
        val isForegroundActive: Boolean
            get() = foregroundActive

        /**
         * Start (or re-assert) the live keep-alive service. Returns false when the OS refuses
         * the start itself (ForegroundServiceStartNotAllowedException — e.g. a remote Go Live
         * while the app is backgrounded on Android 12+). A true return is not yet a running
         * foreground service: startForegroundService is asynchronous, so confirm with
         * [isForegroundActive] after a grace period.
         */
        fun start(context: Context): Boolean = try {
            val intent = Intent(context, StreamCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            true
        } catch (e: Exception) {
            CricrelayLog.e("StreamCaptureService start refused: ${e.message}")
            false
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, StreamCaptureService::class.java))
            } catch (_: Exception) {
            }
        }

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
