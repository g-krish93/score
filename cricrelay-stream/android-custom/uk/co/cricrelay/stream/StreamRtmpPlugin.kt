package uk.co.cricrelay.stream

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

    companion object {
        @Volatile
        var activity: Activity? = null
            private set
    }

    private var pluginActivity: Activity? = null
    private var appContext: Context? = null
    private var channel: MethodChannel? = null
    private var eventChannel: EventChannel? = null
    private var eventSink: EventChannel.EventSink? = null
    private val mainHandler = Handler(Looper.getMainLooper())

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

    private var statusReceiverRegistered = false

    private val engineStatusListener: (String, String) -> Unit = { event, message ->
        mainHandler.post {
            eventSink?.success(mapOf("event" to event, "message" to message))
            broadcastStatus(event, message)
        }
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        appContext = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "uk.co.cricrelay.stream/rtmp")
        channel?.setMethodCallHandler(this)
        eventChannel = EventChannel(binding.binaryMessenger, "uk.co.cricrelay.stream/rtmp_events")
        eventChannel?.setStreamHandler(this)
        StreamCameraEngine.setStatusListener(engineStatusListener)
        binding.platformViewRegistry.registerViewFactory(
            "cricrelay-camera-preview",
            CricrelayCameraViewFactory(),
        )
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        StreamCameraEngine.setStatusListener(null)
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

    private fun broadcastStatus(event: String, message: String) {
        val ctx = appContext ?: return
        ctx.sendBroadcast(
            Intent(StreamCaptureService.ACTION_STATUS).apply {
                setPackage(ctx.packageName)
                putExtra(StreamCaptureService.EXTRA_EVENT, event)
                putExtra(StreamCaptureService.EXTRA_MESSAGE, message)
            },
        )
    }

    private fun overlayLayoutFromCall(call: MethodCall): StreamCameraEngine.OverlayLayout {
        return StreamCameraEngine.OverlayLayout(
            heightFraction = (call.argument<Double>("overlayHeightFraction") ?: 0.22).toFloat(),
            bottomMarginFraction = (call.argument<Double>("overlayBottomMargin") ?: 8.0).toFloat() / 400f,
            horizontalInsetFraction = (call.argument<Double>("overlayHorizontalInset") ?: 8.0).toFloat() / 400f,
        )
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "isCaptureSupported" -> result.success(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            "isCameraReady" -> result.success(StreamCameraEngine.isPreviewReady)
            "prepareCamera" -> {
                val width = call.argument<Int>("width") ?: 1280
                val height = call.argument<Int>("height") ?: 720
                val fps = call.argument<Int>("fps") ?: 30
                val bitrate = call.argument<Int>("bitrateBps") ?: 2_500_000
                val ok = StreamCameraEngine.preparePreview(width, height, fps, bitrate)
                result.success(ok)
            }
            "getZoomRange" -> {
                result.success(
                    mapOf(
                        "min" to StreamCameraEngine.minZoom().toDouble(),
                        "max" to StreamCameraEngine.maxZoom().toDouble(),
                        "current" to StreamCameraEngine.currentZoom().toDouble(),
                    ),
                )
            }
            "setZoom" -> {
                val level = (call.argument<Double>("level") ?: 1.0).toFloat()
                StreamCameraEngine.setZoom(level)
                result.success(null)
            }
            "updateOverlay" -> {
                val url = call.argument<String>("overlayUrl") ?: ""
                if (url.isNotEmpty()) {
                    StreamCameraEngine.updateOverlay(url, overlayLayoutFromCall(call))
                }
                result.success(null)
            }
            "startStream" -> {
                val url = call.argument<String>("rtmpUrl") ?: ""
                val key = call.argument<String>("streamKey") ?: ""
                if (url.isBlank() || key.isBlank()) {
                    result.error("args", "rtmpUrl and streamKey required", null)
                    return
                }
                if (!StreamCameraEngine.isViewAttached) {
                    result.error("camera", "Camera preview not ready yet", null)
                    return
                }
                val act = pluginActivity
                if (act == null) {
                    result.error("activity", "No activity", null)
                    return
                }
                registerStatusReceiver()
                val overlayUrl = call.argument<String>("overlayUrl") ?: ""
                val width = call.argument<Int>("width") ?: 1280
                val height = call.argument<Int>("height") ?: 720
                val bitrate = call.argument<Int>("bitrateBps") ?: 2_500_000
                val fps = call.argument<Int>("fps") ?: 30
                val layout = overlayLayoutFromCall(call)
                val fg = Intent(act, StreamCaptureService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    act.startForegroundService(fg)
                } else {
                    act.startService(fg)
                }
                try {
                    StreamCameraEngine.startStream(url, key, overlayUrl, width, height, bitrate, fps, layout)
                    val endpoint = StreamCaptureService.buildEndpoint(url, key)
                    result.success(mapOf("endpoint" to endpoint))
                } catch (e: Exception) {
                    act.stopService(fg)
                    result.error("stream", e.message, null)
                }
            }
            "stopStream" -> {
                val act = pluginActivity
                StreamCameraEngine.stopStream()
                if (act != null) {
                    act.stopService(Intent(act, StreamCaptureService::class.java))
                }
                unregisterStatusReceiver()
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        pluginActivity = binding.activity
        activity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        pluginActivity = null
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        pluginActivity = binding.activity
        activity = binding.activity
    }

    override fun onDetachedFromActivity() {
        pluginActivity = null
        activity = null
        unregisterStatusReceiver()
    }
}
