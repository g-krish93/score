package uk.co.cricrelay.stream

import android.app.Activity
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class StreamEvent(val event: String, val message: String = "")

data class StreamStatus(
    val previewReady: Boolean = false,
    val streaming: Boolean = false,
    val paused: Boolean = false,
    val lastEvent: StreamEvent? = null,
)

/**
 * Native streaming facade — replaces Flutter method channels.
 * Golden path constraints from [StreamCameraEngine] are preserved.
 */
@Singleton
class StreamController @Inject constructor() {
    private val _status = MutableStateFlow(StreamStatus())
    val status: StateFlow<StreamStatus> = _status.asStateFlow()

    private var activity: Activity? = null

    init {
        StreamCameraEngine.setStatusListener { event, message ->
            _status.value = _status.value.copy(
                previewReady = StreamCameraEngine.isPreviewReady,
                streaming = StreamCameraEngine.isStreaming,
                paused = StreamCameraEngine.isStreamPaused,
                lastEvent = StreamEvent(event, message),
            )
        }
    }

    fun attachActivity(activity: Activity) {
        this.activity = activity
    }

    fun detachActivity() {
        activity = null
    }

    fun preparePreview(
        width: Int = 1280,
        height: Int = 720,
        fps: Int = 30,
        bitrateBps: Int = 2_500_000,
        rotation: Int = 0,
    ): Boolean {
        if (StreamCameraEngine.isStreaming) return StreamCameraEngine.isPreviewReady
        val ok = StreamCameraEngine.preparePreview(width, height, fps, bitrateBps, rotation)
        _status.value = _status.value.copy(previewReady = ok)
        return ok
    }

    fun showNativePreview() {
        activity?.let {
            CameraPreviewHost.elevateComposeUi(it)
            CameraPreviewHost.refreshPreviewSurface()
        }
    }

    fun hideNativePreview() {
        activity?.let { CameraPreviewHost.hide(it) }
    }

    fun ensureComposeAboveCamera() {
        activity?.let { CameraPreviewHost.elevateComposeUi(it) }
    }

    fun refreshNativePreview() {
        activity?.let {
            CameraPreviewHost.elevateComposeUi(it)
            CameraPreviewHost.refreshPreviewSurface()
        }
    }

    /** Feed the live device orientation (Surface.ROTATION_* in degrees) from a sensor listener. */
    fun setDeviceOrientation(surfaceRotationDegrees: Int) {
        StreamCameraEngine.setDeviceOrientation(surfaceRotationDegrees)
    }

    fun setPreviewOverlayListener(listener: ((ByteArray, Int, Int) -> Unit)?) {
        StreamCameraEngine.setPreviewOverlayListener(listener)
    }

    fun updateOverlay(url: String, layout: StreamCameraEngine.OverlayLayout) {
        StreamCameraEngine.updateOverlay(url, layout)
    }

    fun startStream(
        rtmpUrl: String,
        streamKey: String,
        overlayUrl: String,
        layout: StreamCameraEngine.OverlayLayout,
        width: Int = 1280,
        height: Int = 720,
        bitrateBps: Int = 2_500_000,
        fps: Int = 30,
    ): String {
        val act = activity ?: error("No activity attached")
        if (!StreamCameraEngine.isPreviewReady) error("Camera preview not ready yet")
        StreamCameraEngine.startStream(rtmpUrl, streamKey, overlayUrl, width, height, bitrateBps, fps, layout)
        startForegroundService(act)
        val endpoint = StreamCaptureService.buildEndpoint(rtmpUrl, streamKey)
        _status.value = _status.value.copy(streaming = true, previewReady = true)
        return endpoint
    }

    fun stopStream() {
        StreamCameraEngine.stopStream()
        activity?.let { stopForegroundService(it) }
        _status.value = _status.value.copy(streaming = false, paused = false)
    }

    fun pauseStream() {
        StreamCameraEngine.pauseStream()
        _status.value = _status.value.copy(paused = true)
    }

    fun resumeStream() {
        StreamCameraEngine.resumeStream()
        _status.value = _status.value.copy(paused = false)
    }

    fun setZoom(level: Float) = StreamCameraEngine.setZoom(level)

    fun minZoom(): Float = StreamCameraEngine.minZoom()

    fun maxZoom(): Float = StreamCameraEngine.maxZoom()

    fun currentZoom(): Float = StreamCameraEngine.currentZoom()

    fun tapToFocusAt(viewWidth: Int, viewHeight: Int, x: Float, y: Float) =
        StreamCameraEngine.tapToFocusAt(viewWidth, viewHeight, x, y)

    fun setVideoStabilization(enabled: Boolean) = StreamCameraEngine.setVideoStabilization(enabled)

    fun setKeepScreenOnDuringStream(enabled: Boolean) = StreamCameraEngine.setKeepScreenOnDuringStream(enabled)

    private fun startForegroundService(activity: Activity) {
        try {
            val intent = Intent(activity, StreamCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.startForegroundService(intent)
            } else {
                activity.startService(intent)
            }
        } catch (_: Exception) {
        }
    }

    private fun stopForegroundService(activity: Activity) {
        try {
            activity.stopService(Intent(activity, StreamCaptureService::class.java))
        } catch (_: Exception) {
        }
    }
}
