package uk.co.cricrelay.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpDisplay

class StreamCaptureService : Service(), ConnectChecker {

    private var display: RtmpDisplay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
        val rtmpUrl = intent.getStringExtra(EXTRA_RTMP_URL) ?: ""
        val streamKey = intent.getStringExtra(EXTRA_STREAM_KEY) ?: ""
        val width = intent.getIntExtra(EXTRA_WIDTH, 1280).coerceIn(320, 1920)
        val height = intent.getIntExtra(EXTRA_HEIGHT, 720).coerceIn(240, 1080)
        val bitrate = intent.getIntExtra(EXTRA_BITRATE, 2_500_000).coerceIn(400_000, 8_000_000)
        val fps = intent.getIntExtra(EXTRA_FPS, 30).coerceIn(15, 60)
        startForeground(NOTIF_ID, buildNotification())
        if (data == null) {
            emitStatus(EVENT_ERROR, "Screen capture data missing")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val endpoint = buildEndpoint(rtmpUrl, streamKey)
            if (!endpoint.startsWith("rtmp://")) {
                emitStatus(EVENT_ERROR, "Invalid RTMP URL: $endpoint")
                stopSelf()
                return START_NOT_STICKY
            }
            emitStatus(EVENT_PREPARING, endpoint)
            display = RtmpDisplay(this, true, this).apply {
                setIntentResult(resultCode, data)
                glInterface.setForceRender(true, 15)
            }
            val audioOk = display?.prepareAudio() == true
            // RootEncoder 2.4.8: prepareVideo(width, height, fps, bitrate, rotation, dpi)
            val videoOk = display?.prepareVideo(width, height, fps, bitrate, 0, 320) == true
            if (!audioOk || !videoOk) {
                emitStatus(EVENT_ERROR, "Could not prepare camera/audio for stream")
                stopSelf()
                return START_NOT_STICKY
            }
            display?.startStream(endpoint)
        } catch (e: Exception) {
            emitStatus(EVENT_ERROR, e.message ?: "Stream start failed")
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        display?.stopStream()
        display?.stopRecord()
        display = null
        super.onDestroy()
    }

    override fun onConnectionStarted(url: String) {
        emitStatus(EVENT_CONNECTING, url)
    }

    override fun onConnectionSuccess() {
        emitStatus(EVENT_CONNECTED, "")
    }

    override fun onConnectionFailed(reason: String) {
        emitStatus(EVENT_ERROR, reason.ifBlank { "RTMP connection failed" })
        stopSelf()
    }

    override fun onNewBitrate(bitrate: Long) {}

    override fun onDisconnect() {
        emitStatus(EVENT_DISCONNECTED, "")
        stopSelf()
    }

    override fun onAuthError() {
        emitStatus(
            EVENT_ERROR,
            "YouTube rejected the stream key. In Studio, start the live event first, then copy URL + key again.",
        )
        stopSelf()
    }

    override fun onAuthSuccess() {}

    private fun emitStatus(event: String, message: String) {
        sendBroadcast(
            Intent(ACTION_STATUS).apply {
                setPackage(packageName)
                putExtra(EXTRA_EVENT, event)
                putExtra(EXTRA_MESSAGE, message)
            },
        )
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
            .setContentText("Streaming to YouTube")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STATUS = "uk.co.cricrelay.stream.STATUS"
        const val EXTRA_EVENT = "event"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_RTMP_URL = "rtmpUrl"
        const val EXTRA_STREAM_KEY = "streamKey"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_BITRATE = "bitrateBps"
        const val EXTRA_FPS = "fps"

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
