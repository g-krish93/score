package uk.co.cricrelay.stream

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class StreamRtmpPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware, EventChannel.StreamHandler {

    private var activity: Activity? = null
    private var appContext: Context? = null
    private var channel: MethodChannel? = null
    private var eventChannel: EventChannel? = null
    private var eventSink: EventChannel.EventSink? = null
    private var pendingResult: MethodChannel.Result? = null
    private var pendingRtmpUrl: String? = null
    private var pendingStreamKey: String? = null
    private var pendingWidth: Int = 1280
    private var pendingHeight: Int = 720
    private var pendingBitrate: Int = 2_500_000
    private var pendingFps: Int = 30
    private val mainHandler = Handler(Looper.getMainLooper())
    private var statusReceiverRegistered = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val event = intent.getStringExtra(StreamCaptureService.EXTRA_EVENT) ?: return
            val message = intent.getStringExtra(StreamCaptureService.EXTRA_MESSAGE) ?: ""
            mainHandler.post {
                eventSink?.success(mapOf("event" to event, "message" to message))
            }
        }
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        appContext = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "uk.co.cricrelay.stream/rtmp")
        channel?.setMethodCallHandler(this)
        eventChannel = EventChannel(binding.binaryMessenger, "uk.co.cricrelay.stream/rtmp_events")
        eventChannel?.setStreamHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        unregisterStatusReceiver()
        channel?.setMethodCallHandler(null)
        channel = null
        eventChannel?.setStreamHandler(null)
        eventChannel = null
        appContext = null
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        eventSink = events
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
    }

    private fun registerStatusReceiver() {
        if (statusReceiverRegistered) return
        val ctx = appContext ?: return
        val filter = IntentFilter(StreamCaptureService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            ctx.registerReceiver(statusReceiver, filter)
        }
        statusReceiverRegistered = true
    }

    private fun unregisterStatusReceiver() {
        if (!statusReceiverRegistered) return
        try {
            appContext?.unregisterReceiver(statusReceiver)
        } catch (_: IllegalArgumentException) {
        }
        statusReceiverRegistered = false
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "isCaptureSupported" -> result.success(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            "startStream" -> {
                val url = call.argument<String>("rtmpUrl") ?: ""
                val key = call.argument<String>("streamKey") ?: ""
                if (url.isBlank() || key.isBlank()) {
                    result.error("args", "rtmpUrl and streamKey required", null)
                    return
                }
                val act = activity
                if (act == null) {
                    result.error("activity", "No activity", null)
                    return
                }
                registerStatusReceiver()
                pendingRtmpUrl = url
                pendingStreamKey = key
                pendingWidth = call.argument<Int>("width") ?: 1280
                pendingHeight = call.argument<Int>("height") ?: 720
                pendingBitrate = call.argument<Int>("bitrateBps") ?: 2_500_000
                pendingFps = call.argument<Int>("fps") ?: 30
                pendingResult = result
                val mgr = act.getSystemService(Activity.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                act.startActivityForResult(mgr.createScreenCaptureIntent(), REQ_CAPTURE)
            }
            "stopStream" -> {
                val act = activity
                if (act != null) {
                    act.stopService(Intent(act, StreamCaptureService::class.java))
                }
                unregisterStatusReceiver()
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_CAPTURE) return
        val res = pendingResult
        pendingResult = null
        val act = activity
        val url = pendingRtmpUrl
        val key = pendingStreamKey
        val width = pendingWidth
        val height = pendingHeight
        val bitrate = pendingBitrate
        val fps = pendingFps
        pendingRtmpUrl = null
        pendingStreamKey = null
        if (res == null || act == null || url == null || key == null) return
        if (resultCode != Activity.RESULT_OK || data == null) {
            res.error("capture", "Screen capture permission denied", null)
            return
        }
        val endpoint = StreamCaptureService.buildEndpoint(url, key)
        val intent = Intent(act, StreamCaptureService::class.java).apply {
            putExtra(StreamCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(StreamCaptureService.EXTRA_DATA, data)
            putExtra(StreamCaptureService.EXTRA_RTMP_URL, url)
            putExtra(StreamCaptureService.EXTRA_STREAM_KEY, key)
            putExtra(StreamCaptureService.EXTRA_WIDTH, width)
            putExtra(StreamCaptureService.EXTRA_HEIGHT, height)
            putExtra(StreamCaptureService.EXTRA_BITRATE, bitrate)
            putExtra(StreamCaptureService.EXTRA_FPS, fps)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            act.startForegroundService(intent)
        } else {
            act.startService(intent)
        }
        res.success(mapOf("endpoint" to endpoint))
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addActivityResultListener { requestCode, resultCode, data ->
            onActivityResult(requestCode, resultCode, data)
            true
        }
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addActivityResultListener { requestCode, resultCode, data ->
            onActivityResult(requestCode, resultCode, data)
            true
        }
    }

    override fun onDetachedFromActivity() {
        activity = null
        unregisterStatusReceiver()
    }

    companion object {
        const val REQ_CAPTURE = 9912
    }
}
