package uk.co.cricrelay.stream

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView

/**
 * Camera RTMP + scoreboard image overlay. Only the camera feed and overlay are encoded —
 * not the Flutter UI (settings, buttons, etc.).
 */
object StreamCameraEngine : ConnectChecker {

    data class OverlayLayout(
        val heightFraction: Float = 0.22f,
        val bottomMarginFraction: Float = 0.02f,
        val horizontalInsetFraction: Float = 0.02f,
    )

    private var camera: RtmpCamera2? = null
    private var openGlView: OpenGlView? = null
    private var activity: Activity? = null
    private var imageFilter: ImageObjectFilterRender? = null
    private var overlayCapture: OverlayWebViewCapture? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayRunnable: Runnable? = null
    private var streamWidth = 1280
    private var streamHeight = 720
    private var overlayLayout = OverlayLayout()
    private var overlayUrl: String = ""
    private var statusListener: ((String, String) -> Unit)? = null
    private var previewPrepared = false

    val isViewAttached: Boolean
        get() = openGlView != null && camera != null

    fun setStatusListener(listener: ((String, String) -> Unit)?) {
        statusListener = listener
    }

    fun attachView(view: OpenGlView, act: Activity) {
        openGlView = view
        activity = act
        if (camera == null) {
            camera = RtmpCamera2(view, this)
        }
        if (overlayCapture == null) {
            overlayCapture = OverlayWebViewCapture(act)
        }
    }

    fun detachView(view: OpenGlView) {
        if (openGlView === view) {
            openGlView = null
        }
    }

    fun preparePreview(width: Int, height: Int, fps: Int) {
        val cam = camera ?: return
        if (previewPrepared && cam.isOnPreview) return
        streamWidth = width
        streamHeight = height
        val audioOk = cam.prepareAudio(128 * 1024, 32000, true, false, false)
        val videoOk = cam.prepareVideo(width, height, fps, 2_500_000, 0)
        if (!audioOk || !videoOk) return
        if (!cam.isOnPreview) {
            cam.startPreview()
        }
        previewPrepared = true
        if (overlayUrl.isNotEmpty()) {
            overlayCapture?.loadUrl(overlayUrl)
            ensureOverlayFilter()
            startOverlayRefresh()
        }
    }

    fun updateOverlay(url: String, layout: OverlayLayout) {
        overlayUrl = url
        overlayLayout = layout
        overlayCapture?.loadUrl(url)
        applyOverlaySprite()
    }

    fun startStream(
        rtmpUrl: String,
        streamKey: String,
        url: String,
        width: Int,
        height: Int,
        bitrate: Int,
        fps: Int,
        layout: OverlayLayout,
    ) {
        val cam = camera ?: throw IllegalStateException("Camera preview not ready")
        streamWidth = width
        streamHeight = height
        overlayLayout = layout
        overlayUrl = url
        val endpoint = StreamCaptureService.buildEndpoint(rtmpUrl, streamKey)
        if (!endpoint.startsWith("rtmp://")) {
            emit(StreamCaptureService.EVENT_ERROR, "Invalid RTMP URL")
            return
        }
        emit(StreamCaptureService.EVENT_PREPARING, endpoint)
        overlayCapture?.loadUrl(url)
        ensureOverlayFilter()
        val audioOk = cam.prepareAudio(128 * 1024, 32000, true, false, false)
        val videoOk = cam.prepareVideo(width, height, fps, bitrate, 0)
        if (!audioOk || !videoOk) {
            emit(StreamCaptureService.EVENT_ERROR, "Could not prepare camera/audio for stream")
            return
        }
        if (!cam.isOnPreview) {
            cam.startPreview()
        }
        startOverlayRefresh()
        cam.startStream(endpoint)
    }

    fun stopStream() {
        stopOverlayRefresh()
        camera?.stopStream()
    }

    fun release() {
        stopStream()
        stopOverlayRefresh()
        overlayCapture?.destroy()
        overlayCapture = null
        camera?.stopPreview()
        camera = null
        previewPrepared = false
        imageFilter = null
    }

    fun setZoom(level: Float) {
        camera?.setZoom(level)
    }

    fun minZoom(): Float = 1f

    fun maxZoom(): Float {
        val cam = camera ?: return 1f
        return try {
            cam.zoomRange.upper.toFloat()
        } catch (_: Exception) {
            1f
        }
    }

    fun currentZoom(): Float {
        val cam = camera ?: return 1f
        return try {
            cam.zoom
        } catch (_: Exception) {
            1f
        }
    }

    private fun ensureOverlayFilter() {
        val cam = camera ?: return
        val gl = cam.glInterface
        if (imageFilter == null) {
            imageFilter = ImageObjectFilterRender().also { filter ->
                gl.addFilter(filter)
                filter.setPosition(TranslateTo.BOTTOM)
            }
        }
        applyOverlaySprite()
    }

    private fun applyOverlaySprite() {
        val filter = imageFilter ?: return
        val inset = overlayLayout.horizontalInsetFraction.coerceIn(0f, 0.2f)
        val hFrac = overlayLayout.heightFraction.coerceIn(0.12f, 0.45f)
        val scaleX = (1f - inset * 2f).coerceIn(0.5f, 1f)
        val scaleY = (hFrac * 2.8f).coerceIn(0.25f, 1.2f)
        filter.setScale(scaleX, scaleY)
        val y = -1f + overlayLayout.bottomMarginFraction.coerceIn(0f, 0.15f) * 4f
        filter.setPosition(-inset * 0.5f, y)
    }

    private fun startOverlayRefresh() {
        stopOverlayRefresh()
        val runnable = object : Runnable {
            override fun run() {
                captureAndApplyOverlay()
                mainHandler.postDelayed(this, 400)
            }
        }
        overlayRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun stopOverlayRefresh() {
        overlayRunnable?.let { mainHandler.removeCallbacks(it) }
        overlayRunnable = null
    }

    private fun captureAndApplyOverlay() {
        val filter = imageFilter ?: return
        val w = (streamWidth * (1f - overlayLayout.horizontalInsetFraction * 2f)).toInt().coerceIn(320, 1920)
        val h = (streamHeight * overlayLayout.heightFraction).toInt().coerceIn(64, 500)
        val bmp = overlayCapture?.capture(w, h) ?: return
        filter.setImage(bmp)
        filter.setDefaultScale(streamWidth, streamHeight)
        applyOverlaySprite()
    }

    private fun emit(event: String, message: String) {
        statusListener?.invoke(event, message)
    }

    override fun onConnectionStarted(url: String) {
        emit(StreamCaptureService.EVENT_CONNECTING, url)
    }

    override fun onConnectionSuccess() {
        emit(StreamCaptureService.EVENT_CONNECTED, "")
    }

    override fun onConnectionFailed(reason: String) {
        emit(StreamCaptureService.EVENT_ERROR, reason.ifBlank { "RTMP connection failed" })
    }

    override fun onNewBitrate(bitrate: Long) {}

    override fun onDisconnect() {
        emit(StreamCaptureService.EVENT_DISCONNECTED, "")
    }

    override fun onAuthError() {
        emit(
            StreamCaptureService.EVENT_ERROR,
            "Stream key rejected. Start the live event in Studio/dashboard first, then try again.",
        )
    }

    override fun onAuthSuccess() {}
}
