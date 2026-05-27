package uk.co.cricrelay.stream

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class StreamRtmpPlugin : FlutterPlugin, MethodChannel.MethodCallHandler,
    ActivityAware {

    private var activity: Activity? = null
    private var channel: MethodChannel? = null
    private var pendingResult: MethodChannel.Result? = null
    private var pendingRtmpUrl: String? = null
    private var pendingStreamKey: String? = null

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
                pendingRtmpUrl = url
                pendingStreamKey = key
                pendingResult = result
                val mgr = act.getSystemService(Activity.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                act.startActivityForResult(mgr.createScreenCaptureIntent(), REQ_CAPTURE)
            }
            "stopStream" -> {
                val act = activity
                if (act != null) {
                    act.stopService(Intent(act, StreamCaptureService::class.java))
                }
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
        pendingRtmpUrl = null
        pendingStreamKey = null
        if (res == null || act == null || url == null || key == null) return
        if (resultCode != Activity.RESULT_OK || data == null) {
            res.error("capture", "Screen capture permission denied", null)
            return
        }
        val intent = Intent(act, StreamCaptureService::class.java).apply {
            putExtra(StreamCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(StreamCaptureService.EXTRA_DATA, data)
            putExtra(StreamCaptureService.EXTRA_RTMP_URL, url)
            putExtra(StreamCaptureService.EXTRA_STREAM_KEY, key)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            act.startForegroundService(intent)
        } else {
            act.startService(intent)
        }
        res.success(null)
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
    }

    companion object {
        const val REQ_CAPTURE = 9912
    }
}
