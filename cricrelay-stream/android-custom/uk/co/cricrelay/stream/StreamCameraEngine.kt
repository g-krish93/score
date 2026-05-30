package uk.co.cricrelay.stream

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Camera RTMP + scoreboard overlay (RootEncoder / RtmpCamera2).
 *
 * Golden path (do not deviate — re-preparing or stopPreview during Go Live crashes Pixel):
 * 1. attachView + prepareAudio/prepareVideo once
 * 2. startPreview
 * 3. startStream(endpoint) on Go Live — no second prepareVideo
 * 4. overlay WebView + GL filter only after RTMP connects
 */
object StreamCameraEngine : ConnectChecker {

    data class OverlayLayout(
        val heightFraction: Float = 0.22f,
        val widthFraction: Float = 0.88f,
        val anchorX: Float = 0.5f,
        val anchorY: Float = 0.85f,
        val bottomMarginFraction: Float = 0.02f,
        val horizontalInsetFraction: Float = 0.02f,
    )

    private const val MAX_WIDTH = 1280
    private const val MAX_HEIGHT = 720
    private const val DEFAULT_BITRATE = 2500000
    private const val DEFAULT_FPS = 30

    private var camera: RtmpCamera2? = null
    private var openGlView: OpenGlView? = null
    private var activity: Activity? = null
    private var appContext: Context? = null
    private var imageFilter: ImageObjectFilterRender? = null
    private var overlayCapture: OverlayWebViewCapture? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayRunnable: Runnable? = null
    private var streamWidth = MAX_WIDTH
    private var streamHeight = MAX_HEIGHT
    private var streamFps = DEFAULT_FPS
    private var streamBitrate = DEFAULT_BITRATE
    private var streamRotation = 0
    private var streamIsPortrait = false
    private var overlayLayout = OverlayLayout()
    private var overlayUrl: String = ""
    private var statusListener: ((String, String) -> Unit)? = null
    private var encoderPrepared = false
    private var lastOverlayBitmap: Bitmap? = null
    private var pendingOverlayAfterConnect = false
    private var prepareInFlight = false
    private var videoStabilizationEnabled = true
    private var keepScreenOnDuringStream = false
    private var audioManager: AudioManager? = null

    val isPreviewReady: Boolean
        get() = camera != null && openGlView != null && encoderPrepared && (camera?.isOnPreview == true)

    fun setStatusListener(listener: ((String, String) -> Unit)?) {
        statusListener = listener
    }

    fun setKeepScreenOnDuringStream(enabled: Boolean) {
        keepScreenOnDuringStream = enabled
    }

    fun setVideoStabilization(enabled: Boolean) {
        videoStabilizationEnabled = enabled
        val cam = camera ?: return
        if (cam.isStreaming) return
        runOnMain {
            try {
                if (enabled) {
                    cam.enableVideoStabilization()
                }
            } catch (_: Exception) {
            }
        }
    }

    fun attachView(view: OpenGlView, act: Activity) {
        appContext = act.applicationContext
        audioManager = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        activity = act
        if (openGlView === view && camera != null) return
        if (openGlView !== view) {
            releaseCamera()
        }
        openGlView = view
        if (camera == null) {
            camera = try {
                RtmpCamera2(view, this)
            } catch (e: Exception) {
                emit(StreamCaptureService.EVENT_ERROR, "Camera init failed: ${e.message ?: "unknown"}")
                null
            }
        }
    }

    fun detachView(view: OpenGlView) {
        if (openGlView !== view) return
        runOnMainSync { releaseCamera() }
    }

    fun preparePreview(
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int = streamBitrate,
        rotation: Int = 0,
    ): Boolean {
        streamIsPortrait = height > width
        streamRotation = rotation.coerceIn(0, 360)
        if (streamIsPortrait) {
            streamWidth = width.coerceIn(360, MAX_HEIGHT)
            streamHeight = height.coerceIn(640, MAX_WIDTH)
        } else {
            streamWidth = width.coerceIn(640, MAX_WIDTH)
            streamHeight = height.coerceIn(360, MAX_HEIGHT)
        }
        streamFps = fps.coerceIn(24, 30)
        streamBitrate = bitrate.coerceIn(800000, 4500000)
        var ok = false
        try {
            runOnMainSync { ok = preparePreviewOnMain() }
        } catch (_: Exception) {
            return false
        }
        return ok
    }

    /** Re-prepare encoder when orientation changes before Go Live (not while streaming). */
    fun resetPreviewForOrientation(
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        rotation: Int,
    ): Boolean {
        if (camera?.isStreaming == true) return false
        runOnMainSync {
            try {
                camera?.stopPreview()
            } catch (_: Exception) {
            }
            encoderPrepared = false
        }
        return preparePreview(width, height, fps, bitrate, rotation)
    }

    /** Called from OpenGlView SurfaceHolder.Callback when holder.surface is valid. */
    fun onPreviewSurfaceReady() {
        runOnMain {
            val view = openGlView ?: return@runOnMain
            if (view.width < 64 || view.height < 64) return@runOnMain
            if (!isPreviewSurfaceValid(view)) {
                view.post { onPreviewSurfaceReady() }
                return@runOnMain
            }
            preparePreviewOnMain()
        }
    }

    fun updateOverlay(url: String, layout: OverlayLayout) {
        runOnMain {
            if (url.isNotEmpty()) {
                overlayUrl = url
            }
            overlayLayout = layout
            if (overlayUrl.isNotEmpty() && camera?.isStreaming == true) {
                ensureOverlayCapture()?.loadUrl(overlayUrl)
                applyOverlaySprite()
            } else if (imageFilter != null) {
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
                requestStreamAudioFocus()
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

    fun updateNotificationElapsed(elapsedLabel: String) {
        StreamCaptureService.updateElapsed(appContext, elapsedLabel)
    }

    private fun isPreviewSurfaceValid(view: OpenGlView): Boolean {
        return try {
            val surface = view.holder.surface
            surface != null && surface.isValid
        } catch (_: Exception) {
            false
        }
    }

    private fun requestStreamAudioFocus() {
        try {
            audioManager?.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        } catch (_: Exception) {
        }
    }

    /** Prepare encoder + preview once. Never call stopPreview here while streaming. */
    private fun preparePreviewOnMain(): Boolean {
        val cam = camera ?: return false
        val view = openGlView ?: return false
        if (view.width < 64 || view.height < 64) {
            view.post { onPreviewSurfaceReady() }
            return false
        }
        if (!isPreviewSurfaceValid(view)) {
            view.post { onPreviewSurfaceReady() }
            return false
        }
        if (encoderPrepared && cam.isOnPreview) return true
        if (cam.isStreaming) return true
        if (prepareInFlight) return false

        if (encoderPrepared && !cam.isOnPreview) {
            return try {
                cam.startPreview()
                cam.isOnPreview
            } catch (_: Exception) {
                false
            }
        }

        prepareInFlight = true
        return try {
            val audioOk = cam.prepareAudio(128 * 1024, 32_000, true, false, false)
            var videoOk = cam.prepareVideo(streamWidth, streamHeight, streamFps, streamBitrate, streamRotation)
            if (!videoOk) {
                if (streamIsPortrait) {
                    streamWidth = MAX_HEIGHT
                    streamHeight = MAX_WIDTH
                } else {
                    streamWidth = MAX_WIDTH
                    streamHeight = MAX_HEIGHT
                }
                streamBitrate = DEFAULT_BITRATE
                videoOk = cam.prepareVideo(streamWidth, streamHeight, streamFps, streamBitrate, streamRotation)
            }
            if (!audioOk || !videoOk) {
                encoderPrepared = false
                return false
            }
            if (videoStabilizationEnabled) {
                try {
                    cam.enableVideoStabilization()
                } catch (_: Exception) {
                }
            }
            encoderPrepared = true
            if (!cam.isOnPreview) {
                cam.startPreview()
            }
            cam.isOnPreview
        } catch (_: Exception) {
            encoderPrepared = false
            false
        } finally {
            prepareInFlight = false
        }
    }

    /** Go Live: start RTMP only — encoder was prepared at preview time. */
    private fun startStreamOnMain(endpoint: String) {
        val cam = camera ?: throw IllegalStateException("Camera not initialized")
        if (!encoderPrepared || !cam.isOnPreview) {
            if (!preparePreviewOnMain()) {
                throw IllegalStateException("Camera preview not ready — wait for preview before Go Live")
            }
        }
        if (cam.isStreaming) return

        emit(StreamCaptureService.EVENT_PREPARING, "Starting stream…")
        if (keepScreenOnDuringStream) {
            openGlView?.keepScreenOn = true
        }
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
        try {
            if (camera?.isOnPreview != true && encoderPrepared) {
                camera?.startPreview()
            }
        } catch (_: Exception) {
        }
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
    }

    private fun ensureOverlayCapture(): OverlayWebViewCapture? {
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
        }, 2000)
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
        var thrown: Throwable? = null
        mainHandler.post {
            try {
                block()
            } catch (t: Throwable) {
                thrown = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(25, TimeUnit.SECONDS)) {
            throw IllegalStateException("Camera operation timed out")
        }
        thrown?.let { throw it }
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
            cam.glInterface.addFilter(filter)
            imageFilter = filter
            applyOverlaySprite()
        } catch (e: Exception) {
            emit(StreamCaptureService.EVENT_ERROR, "Overlay filter failed: ${e.message}")
        }
    }

    private fun applyOverlaySprite() {
        val filter = imageFilter ?: return
        val wFrac = overlayLayout.widthFraction.coerceIn(0.25f, 0.95f)
        val hFrac = overlayLayout.heightFraction.coerceIn(0.12f, 0.45f)
        val scaleX = (wFrac * 1.1f).coerceIn(0.35f, 1.2f)
        val scaleY = (hFrac * 2.8f).coerceIn(0.25f, 1.2f)
        filter.setScale(scaleX, scaleY)
        val ax = overlayLayout.anchorX.coerceIn(0.05f, 0.95f)
        val ay = overlayLayout.anchorY.coerceIn(0.05f, 0.95f)
        val x = (ax - 0.5f) * 2f
        val y = 1f - ay * 2f
        filter.setPosition(x, y)
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
        mainHandler.postDelayed(runnable, 1200)
    }

    private fun stopOverlayRefresh() {
        overlayRunnable?.let { mainHandler.removeCallbacks(it) }
        overlayRunnable = null
    }

    private fun captureAndApplyOverlay() {
        val filter = imageFilter ?: return
        val wFrac = overlayLayout.widthFraction.coerceIn(0.25f, 0.95f)
        val w = (streamWidth * wFrac).toInt().coerceIn(320, 1920)
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
