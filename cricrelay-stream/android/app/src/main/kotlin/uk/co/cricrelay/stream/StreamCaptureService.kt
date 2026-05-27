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
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        val rtmpUrl = intent.getStringExtra(EXTRA_RTMP_URL) ?: ""
        val streamKey = intent.getStringExtra(EXTRA_STREAM_KEY) ?: ""
        startForeground(NOTIF_ID, buildNotification())
        if (data != null) {
            try {
                display = RtmpDisplay(this, true, this).apply {
                    setIntentResult(resultCode, data)
                }
                val endpoint = if (rtmpUrl.endsWith("/")) "$rtmpUrl$streamKey" else "$rtmpUrl/$streamKey"
                if (display?.prepareVideo() == true) {
                    display?.startStream(endpoint)
                }
            } catch (_: Exception) {
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        display?.stopStream()
        display?.stopRecord()
        display = null
        super.onDestroy()
    }

    override fun onConnectionStarted(url: String) {}

    override fun onConnectionSuccess() {}

    override fun onConnectionFailed(reason: String) {
        stopSelf()
    }

    override fun onNewBitrate(bitrate: Long) {}

    override fun onDisconnect() {
        stopSelf()
    }

    override fun onAuthError() {
        stopSelf()
    }

    override fun onAuthSuccess() {}

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
            .setContentTitle("CricRelay Stream")
            .setContentText("Live on YouTube")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_RTMP_URL = "rtmpUrl"
        const val EXTRA_STREAM_KEY = "streamKey"
        private const val NOTIF_ID = 4401
    }
}
