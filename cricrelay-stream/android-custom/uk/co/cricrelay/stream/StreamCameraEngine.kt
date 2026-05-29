package uk.co.cricrelay.stream

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Camera RTMP encoder — prepare once, start stream without re-preparing (Pixel-safe).
 * Scoreboard overlay is applied only after RTMP connects.
 */
object StreamCameraEngine : ConnectChecker {

    data class OverlayLayout(
        val heightFraction: Float = 0.22f,
        val bottomMarginFraction: Float = 0.02f,
        val horizontalInsetFraction: Float = 0.02f,
    )

    private const val MAX_WIDTH = 1280
    private const val MAX_HEIGHT = 720
    private const val DEFAULT_BITRATE = 2_500_000
    private const val DEFAULT_FPS = 30

    private var camera: RtmpCamera2? = null
    private var openGlView: OpenGlView? = null
    private var activity: Activity? = null
    private var imageFilter: ImageObjectFilterRender? = null
    private var overlayCapture: OverlayWebViewCapture? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayRunnable: Runnable? = null
    private var streamWidth = MAX_WIDTH
    private var streamHeight = MAX_HEIGHT
    private var streamFps = DEFAULT_FPS
    private var streamBitrate = DEFAULT_BITRATE
    private var overlayLayout = OverlayLayout()
    private var overlayUrl: String = ""
    private var statusListener: ((String, String) -> Unit)? = null
    private var encoderPrepared = false
    private var preparedWidth = 0
    private var preparedHeight = 0
    private var lastOverlayBitmap: Bitmap? = null
    private var pendingOverlayAfterConnect = false

    val isViewAttached: Boolean
        get() = openGlView != null && camera != null

    val isPreviewReady: Boolean
        get() = isViewAttached && encoderPrepared && (camera?.isOnPreview == true)

    fun setStatusListener(listener: ((String, String) -> Unit)?) {
        statusListener = listener
    }

    fun attachView(view: OpenGlView, act: Activity) {
        openGlView = view
        activity = act
        if (camera != null) return
        camera = try {
            RtmpCamera2(view, this).apply {
                glInterface.autoHandleOrientation = true
            }
        } catch (e: Exception) {
            emit(StreamCaptureService.EVENT_ERROR, "Camera init failed: ${e.message ?: "unknown"}")
            null
        }
    }

    fun detachView(view: OpenGlView) {
        if (openGlView !== view) return
        runOnMainSync {
            releaseCamera()
        }
    }

    fun preparePreview(width: Int, height: Int, fps: Int, bitrate: Int = streamBitrate): Boolean {
        val w = width.coerceIn(640, MAX_WIDTH)
        val h = height.coerceIn(360, MAX_HEIGHT)
        val f = fps.coerceIn(24, 30)
        val b = bitrate.coerceIn(800_000, 4_500_000)
        streamWidth = w
        streamHeight = h
        streamFps = f
        streamBitrate = b
        var ok = false
        try {
            runOnMainSync {
                ok = preparePreviewOnMain()
            }
        } catch (_: Exception) {
            ok = false
        }
        return ok
    }

    fun onPreviewViewSized() {
        runOnMain {
            if (openGlView == null || openGlView!!.width < 64 || openGlView!!.height < 64) return@runOnMain
            if (!encoderPrepared) {
                preparePreviewOnMain()
            }
        }
    }

    fun updateOverlay(url: String, layout: OverlayLayout) {
        runOnMain {
            overlayUrl = url
            overlayLayout = layout
            if (url.isNotEmpty() && camera?.isStreaming == true) {
                ensureOverlayCapture()?.loadUrl(url)
                applyOverlaySprite()
            }
        }
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
        overlayLayout = layout
        overlayUrl = url
        pendingOverlayAfterConnect = url.isNotEmpty()

        val endpoint = StreamCaptureService.buildEndpoint(rtmpUrl, streamKey)
        if (!endpoint.startsWith("rtmp://")) {
            throw IllegalArgumentException("Invalid RTMP URL")
        }

        var error: Exception? = null
        runOnMainSync {
            try {
                startStreamOnMain(endpoint)
            } catch (e: Exception) {
                error = e
                emit(StreamCaptureService.EVENT_ERROR, e.message ?: "Stream start failed")
            }
        }
        error?.let { throw it }
    }

    fun stopStream() {
        runOnMainSync { stopStreamInternal() }
    }

    fun stopStreamFromService() {
        runOnMainSync {
            if (camera?.isStreaming != true) return@runOnMainSync
            stopStreamInternal()
        }
    }

    fun setZoom(level: Float) {
        runOnMain {
            try {
                camera?.setZoom(level)
            } catch (_: Exception) {
            }
        }
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

    private fun preparePreviewOnMain(): Boolean {
        val cam = camera ?: return false
        val view = openGlView ?: return false
        if (view.width < 2 || view.height < 2) {
            view.post { preparePreviewOnMain() }
            return false
        }
        if (encoderPrepared && cam.isOnPreview &&
            streamWidth == preparedWidth && streamHeight == preparedHeight
        ) {
            return true
        }
        if (encoderPrepared && !cam.isStreaming) {
            try {
                cam.stopPreview()
            } catch (_: Exception) {
            }
            encoderPrepared = false
        }
        if (cam.isStreaming) return true

        return try {
            val audioOk = cam.prepareAudio(128 * 1024, 44_100, false, true, true)
            var videoOk = cam.prepareVideo(streamWidth, streamHeight, streamFps, streamBitrate, 0, 320)
            if (!videoOk) {
                streamWidth = MAX_WIDTH
                streamHeight = MAX_HEIGHT
                streamBitrate = DEFAULT_BITRATE
                videoOk = cam.prepareVideo(streamWidth, streamHeight, streamFps, streamBitrate, 0, 320)
            }
            if (!audioOk || !videoOk) {
                encoderPrepared = false
                return false
            }
            encoderPrepared = true
            preparedWidth = streamWidth
            preparedHeight = streamHeight
            if (!cam.isOnPreview) {
                cam.startPreview()
            }
            cam.isOnPreview
        } catch (_: Exception) {
            encoderPrepared = false
            false
        }
    }

    /**
     * Go Live: never call prepareVideo/prepareAudio again — that crashes many devices mid-preview.
     */
    private fun startStreamOnMain(endpoint: String) {
        val cam = camera ?: throw IllegalStateException("Camera not initialized")
        if (!encoderPrepared || !cam.isOnPreview) {
            if (!preparePreviewOnMain()) {
                throw IllegalStateException("Camera preview not ready — wait for preview before Go Live")
            }
        }
        if (cam.isStreaming) return

        emit(StreamCaptureService.EVENT_PREPARING, "Starting stream…")
        openGlView?.keepScreenOn = true
        try {
            cam.startStream(endpoint)
        } catch (t: Throwable) {
            openGlView?.keepScreenOn = false
            throw IllegalStateException(
                "RTMP start failed: ${t.message ?: t.javaClass.simpleName}",
                t,
            )
        }
    }

    private fun stopStreamInternal() {
        pendingOverlayAfterConnect = false
        stopOverlayRefresh()
        clearOverlayFilter()
        recycleOverlayBitmap()
        try {
            camera?.stopStream()
        } catch (_: Exception) {
        }
        openGlView?.keepScreenOn = false
    }

    private fun releaseCamera() {
        stopStreamInternal()
        overlayCapture?.destroy()
        overlayCapture = null
        try {
            camera?.stopPreview()
        } catch (_: Exception) {
        }
        camera = null
        openGlView = null
        encoderPrepared = false
        preparedWidth = 0
        preparedHeight = 0
    }
        val act = activity ?: return null
        if (overlayCapture == null) {
            overlayCapture = OverlayWebViewCapture(act)
        }
        return overlayCapture
    }

    private fun attachOverlayAfterConnect() {
        if (!pendingOverlayAfterConnect) return
        pendingOverlayAfterConnect = false
        mainHandler.postDelayed({
            try {
                if (overlayUrl.isNotEmpty()) {
                    ensureOverlayCapture()?.loadUrl(overlayUrl)
                }
                ensureOverlayFilter()
                startOverlayRefresh()
            } catch (e: Exception) {
                emit(StreamCaptureService.EVENT_ERROR, "Scoreboard overlay failed: ${e.message}")
            }
        }, 2500)
    }

    private fun recycleOverlayBitmap() {
        lastOverlayBitmap?.takeIf { !it.isRecycled }?.recycle()
        lastOverlayBitmap = null
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun runOnMainSync(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(25, TimeUnit.SECONDS)) {
            throw IllegalStateException("Camera operation timed out")
        }
    }

    private fun clearOverlayFilter() {
        val cam = camera ?: return
        imageFilter?.let { filter ->
            try {
                cam.glInterface.removeFilter(filter)
            } catch (_: Exception) {
            }
        }
        imageFilter = null
    }

    private fun ensureOverlayFilter() {
        val cam = camera ?: return
        if (imageFilter != null) {
            applyOverlaySprite()
            return
        }
        if (!cam.isStreaming) return
        try {
            val filter = ImageObjectFilterRender()
            filter.setPosition(TranslateTo.BOTTOM)
            cam.glInterface.addFilter(filter)
            imageFilter = filter
            applyOverlaySprite()
        } catch (e: Exception) {
            emit(StreamCaptureService.EVENT_ERROR, "Overlay filter failed: ${e.message}")
        }
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
                try {
                    captureAndApplyOverlay()
                } catch (_: Exception) {
                }
                mainHandler.postDelayed(this, 500)
            }
        }
        overlayRunnable = runnable
        mainHandler.postDelayed(runnable, 1500)
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
        lastOverlayBitmap?.takeIf { !it.isRecycled }?.recycle()
        lastOverlayBitmap = bmp
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
        attachOverlayAfterConnect()
        emit(StreamCaptureService.EVENT_CONNECTED, "")
    }

    override fun onConnectionFailed(reason: String) {
        pendingOverlayAfterConnect = false
        emit(StreamCaptureService.EVENT_ERROR, reason.ifBlank { "RTMP connection failed" })
    }

    override fun onNewBitrate(bitrate: Long) {}

    override fun onDisconnect() {
        pendingOverlayAfterConnect = false
        emit(StreamCaptureService.EVENT_DISCONNECTED, "")
    }

    override fun onAuthError() {
        pendingOverlayAfterConnect = false
        emit(
            StreamCaptureService.EVENT_ERROR,
            "Stream key rejected. Start the live event in Studio/dashboard first, then try again.",
        )
    }

    override fun onAuthSuccess() {}
}
