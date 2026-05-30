package uk.co.cricrelay.stream

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/** Placeholder — native camera plugin missing. Reinstall APK from cricrelay.co.uk. */
class StreamRtmpPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware {

    private var channel: MethodChannel? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, "uk.co.cricrelay.stream/rtmp")
        channel?.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "isCaptureSupported" -> result.success(false)
            "isCameraReady" -> result.success(false)
            "prepareCamera", "resetCameraOrientation" -> result.success(false)
            "getDeviceCapabilities" -> result.success(emptyMap<String, Any>())
            "pauseStream", "resumeStream",
            "setKeepScreenOnDuringStream", "setVideoStabilization",
            "setPipAspectRatio", "setPipWhenLive", "updateStreamNotification",
            "lockActivityOrientation",
            -> result.success(null)
            "isStreamPaused" -> result.success(false)
            "tapToFocus" -> result.success(mapOf("focused" to false, "locked" to false))
            "unlockFocus", "isFocusLocked" -> result.success(false)
            "getZoomRange" -> result.success(mapOf("min" to 1.0, "max" to 1.0, "current" to 1.0))
            "setZoom", "updateOverlay", "showNativePreview", "hideNativePreview" -> result.success(null)
            "startStream", "stopStream" -> result.error(
                "capture",
                "Native camera streaming is not in this build. Install the latest APK from cricrelay.co.uk.",
                null,
            )
            else -> result.notImplemented()
        }
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {}

    override fun onDetachedFromActivityForConfigChanges() {}

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {}

    override fun onDetachedFromActivity() {}
}
