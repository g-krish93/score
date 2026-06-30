package uk.co.cricrelay.stream

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.filters.BlackFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.utils.gl.AspectRatioMode
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.hypot

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
        val fontScale: Float = 1.0f,
        val bgColor: String = "",
        val textColor: String = "",
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
    private var pauseBlackFilter: BlackFilterRender? = null
    private var streamPaused = false
    private var deviceTier = DeviceCapabilities.Tier.HIGH
    private var overlayRefreshMs = 500L
    private var maxOverlayCaptureWidth = 960
    private var surfaceValid = true
    private var overlayPausedForMemory = false
    private var focusLocked = false
    private var lastFocusTapX = -1f
    private var lastFocusTapY = -1f
    private var lastFocusTapAt = 0L

    val isStreaming: Boolean
        get() = camera?.isStreaming == true

    val isStreamPaused: Boolean
        get() = streamPaused && isStreaming

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
                } else {
                    cam.disableVideoStabilization()
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Surface lost (rotation, PiP) — wait for surfaceChanged before prepare again. */
    fun onPreviewSurfaceLost() {
        runOnMain {
            surfaceValid = false
            if (camera?.isStreaming == true) {
                stopOverlayRefresh()
                return@runOnMain
            }
            encoderPrepared = false
        }
    }

    /** System low memory — pause expensive overlay capture until restored. */
    fun onMemoryPressure() {
        runOnMain {
            overlayPausedForMemory = true
            stopOverlayRefresh()
            recycleOverlayBitmap()
        }
    }

    fun onMemoryRestored() {
        runOnMain {
            if (!overlayPausedForMemory) return@runOnMain
            overlayPausedForMemory = false
            if (camera?.isStreaming == true && !streamPaused && overlayUrl.isNotEmpty()) {
                startOverlayRefresh()
            }
        }
    }

    private fun refreshDeviceTier(context: Context) {
        deviceTier = DeviceCapabilities.tier(context)
        overlayRefreshMs = DeviceCapabilities.overlayRefreshMs(deviceTier)
        maxOverlayCaptureWidth = DeviceCapabilities.maxOverlayCaptureWidth(deviceTier)
        if (DeviceCapabilities.isPowerSaveMode(context) || DeviceCapabilities.isThermalStressed(context)) {
            overlayRefreshMs = (overlayRefreshMs * 1.5).toLong().coerceAtMost(2500L)
        }
    }

    fun attachView(view: OpenGlView, act: Activity) {
        refreshDeviceTier(act.applicationContext)
        appContext = act.applicationContext
        audioManager = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        activity = act
        if (openGlView === view && camera != null) return
        if (openGlView !== view) {
            releaseCamera()
        }
        openGlView = view
        view.setBackgroundColor(Color.BLACK)
        view.setAspectRatioMode(AspectRatioMode.Fill)
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
        if (camera?.isStreaming == true) return isPreviewReady
        streamRotation = rotation.coerceIn(0, 360)
        streamIsPortrait = streamRotation == 90 || streamRotation == 270
        streamWidth = width.coerceIn(640, MAX_WIDTH)
        streamHeight = height.coerceIn(360, MAX_HEIGHT)
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
            resetFocusState()
        }
        return preparePreview(width, height, fps, bitrate, rotation)
    }

    /** Fast path: rotate preview without tearing down encoder (before Go Live). */
    fun updatePreviewRotation(rotation: Int): Boolean {
        if (camera?.isStreaming == true) return false
        streamRotation = rotation.coerceIn(0, 360)
        streamIsPortrait = streamRotation == 90 || streamRotation == 270
        var ok = false
        runOnMainSync {
            val view = openGlView ?: return@runOnMainSync
            view.setAspectRatioMode(AspectRatioMode.Fill)
            view.setStreamRotation(streamRotation)
            try {
                view.setRotation(streamRotation)
                ok = camera?.isOnPreview == true
            } catch (_: Exception) {
                ok = false
            }
        }
        if (ok) return true
        return resetPreviewForOrientation(streamWidth, streamHeight, streamFps, streamBitrate, rotation)
    }

    fun displayRotationDegrees(act: Activity): Int {
        @Suppress("DEPRECATION")
        val rot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            act.display?.rotation ?: Surface.ROTATION_0
        } else {
            act.windowManager.defaultDisplay.rotation
        }
        return when (rot) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    fun isFocusLocked(): Boolean = focusLocked

    fun unlockFocus(): Boolean {
        var ok = false
        runOnMainSync {
            focusLocked = false
            lastFocusTapAt = 0L
            try {
                ok = camera?.enableAutoFocus() == true
            } catch (_: Exception) {
                ok = false
            }
        }
        return ok
    }

    /**
     * Tap once to focus; tap again on the same spot within ~900ms to lock focus (disable AF).
     * If focus is locked, the next tap unlocks and focuses at the new point.
     */
    fun tapToFocusAt(viewWidth: Int, viewHeight: Int, x: Float, y: Float): Map<String, Any> {
        var focused = false
        var locked = focusLocked
        runOnMainSync {
            val cam = camera ?: return@runOnMainSync
            val view = openGlView ?: return@runOnMainSync
            val w = if (viewWidth > 0) viewWidth else view.width
            val h = if (viewHeight > 0) viewHeight else view.height
            if (w < 1 || h < 1) return@runOnMainSync

            val px = x.coerceIn(0f, w.toFloat())
            val py = y.coerceIn(0f, h.toFloat())
            val now = System.currentTimeMillis()
            val slop = 48f * view.context.resources.displayMetrics.density
            val doubleTap = !focusLocked &&
                now - lastFocusTapAt < 900 &&
                lastFocusTapX >= 0f &&
                hypot(px - lastFocusTapX, py - lastFocusTapY) < slop

            if (focusLocked) {
                try {
                    cam.enableAutoFocus()
                } catch (_: Exception) {
                }
                focusLocked = false
            }

            val event = MotionEvent.obtain(
                SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP,
                px,
                py,
                0,
            )
            try {
                focused = cam.tapToFocus(event)
            } catch (_: Exception) {
                focused = false
            } finally {
                event.recycle()
            }

            if (doubleTap && focused) {
                try {
                    if (cam.disableAutoFocus()) {
                        focusLocked = true
                    }
                } catch (_: Exception) {
                }
            }

            lastFocusTapX = px
            lastFocusTapY = py
            lastFocusTapAt = now
            locked = focusLocked
        }
        return mapOf("focused" to focused, "locked" to locked)
    }

    private fun resetFocusState() {
        focusLocked = false
        lastFocusTapAt = 0L
        lastFocusTapX = -1f
        lastFocusTapY = -1f
    }

    /** Called from OpenGlView SurfaceHolder.Callback when holder.surface is valid. */
    fun onPreviewSurfaceReady() {
        runOnMain {
            surfaceValid = true
            overlayPausedForMemory = false
            val view = openGlView ?: return@runOnMain
            if (view.width < 64 || view.height < 64) return@runOnMain
            if (!isPreviewSurfaceValid(view)) {
                view.post { onPreviewSurfaceReady() }
                return@runOnMain
            }
            val ok = preparePreviewOnMain()
            if (ok) {
                emit(StreamCaptureService.EVENT_PREVIEW_READY, "${streamWidth}x${streamHeight}")
            }
        }
    }

    fun updateOverlay(url: String, layout: OverlayLayout) {
        runOnMain {
            if (url.isNotEmpty()) {
                overlayUrl = url
            }
            overlayLayout = layout
            ensureOverlayCapture()?.setStyle(layout.fontScale, layout.bgColor, layout.textColor)
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

    /** Pause RTMP output (black video + muted audio) while keeping the connection alive. */
    fun pauseStream() {
        runOnMainSync { pauseStreamInternal() }
    }

    /** Resume RTMP output after [pauseStream]. */
    fun resumeStream() {
        runOnMainSync { resumeStreamInternal() }
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

    private fun abandonStreamAudioFocus() {
        try {
            audioManager?.abandonAudioFocus(null)
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
        if (prepareInFlight) {
            view.postDelayed({ onPreviewSurfaceReady() }, 120)
            return false
        }

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
            openGlView?.setAspectRatioMode(AspectRatioMode.Fill)
            openGlView?.setStreamRotation(streamRotation)
            try {
                openGlView?.setRotation(streamRotation)
            } catch (_: Exception) {
            }
            val audioOk = cam.prepareAudio(128 * 1024, 32_000, true, false, false)
            if (!audioOk) {
                encoderPrepared = false
                return false
            }
            var videoOk = false
            for (tier in buildVideoFallbackTiers()) {
                streamWidth = tier.width
                streamHeight = tier.height
                streamFps = tier.fps
                streamBitrate = tier.bitrate
                videoOk = cam.prepareVideo(streamWidth, streamHeight, streamFps, streamBitrate, streamRotation)
                if (videoOk) break
            }
            if (!videoOk) {
                encoderPrepared = false
                return false
            }
            if (videoStabilizationEnabled && deviceTier != DeviceCapabilities.Tier.LOW) {
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

    private data class VideoTier(val width: Int, val height: Int, val fps: Int, val bitrate: Int)

    /** Step down through resolutions until prepareVideo succeeds on budget phones. */
    private fun buildVideoFallbackTiers(): List<VideoTier> {
        val reqW = streamWidth
        val reqH = streamHeight
        val reqFps = streamFps.coerceIn(24, 30)
        val reqBitrate = streamBitrate.coerceIn(800000, 4500000)
        val landscapeSteps = listOf(
            VideoTier(reqW, reqH, reqFps, reqBitrate),
            VideoTier(1280, 720, reqFps.coerceAtMost(30), reqBitrate.coerceAtMost(2500000)),
            VideoTier(854, 480, 30, 1500000),
            VideoTier(640, 360, 24, 800000),
        )
        return landscapeSteps.distinctBy { "${it.width}x${it.height}" }
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
        streamPaused = false
        removePauseBlackFilter()
        stopOverlayRefresh()
        clearOverlayFilter()
        recycleOverlayBitmap()
        try {
            camera?.stopStream()
        } catch (_: Exception) {
        }
        openGlView?.keepScreenOn = false
        abandonStreamAudioFocus()
        try {
            if (camera?.isOnPreview != true && encoderPrepared) {
                camera?.startPreview()
            }
        } catch (_: Exception) {
        }
    }

    private fun pauseStreamInternal() {
        val cam = camera ?: return
        if (!cam.isStreaming || streamPaused) return
        streamPaused = true
        stopOverlayRefresh()
        try {
            cam.disableAudio()
        } catch (_: Exception) {
        }
        try {
            if (pauseBlackFilter == null) {
                val filter = BlackFilterRender()
                pauseBlackFilter = filter
                cam.glInterface.addFilter(filter)
            }
        } catch (_: Exception) {
        }
        emit(StreamCaptureService.EVENT_PAUSED, "")
    }

    private fun resumeStreamInternal() {
        val cam = camera ?: return
        if (!cam.isStreaming || !streamPaused) return
        streamPaused = false
        removePauseBlackFilter()
        try {
            cam.enableAudio()
        } catch (_: Exception) {
        }
        if (overlayUrl.isNotEmpty() && imageFilter != null) {
            startOverlayRefresh()
        }
        emit(StreamCaptureService.EVENT_RESUMED, "")
    }

    private fun removePauseBlackFilter() {
        val cam = camera ?: return
        pauseBlackFilter?.let { filter ->
            try {
                cam.glInterface.removeFilter(filter)
            } catch (_: Exception) {
            }
        }
        pauseBlackFilter = null
    }

    private fun releaseCamera() {
        stopStreamInternal()
        overlayCapture?.destroy()
        overlayCapture = null
        resetFocusState()
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
                    ensureOverlayCapture()?.setStyle(
                        overlayLayout.fontScale,
                        overlayLayout.bgColor,
                        overlayLayout.textColor,
                    )
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
        val aspect = streamWidth.toFloat() / streamHeight.coerceAtLeast(1)
        filter.setScale(wFrac.coerceIn(0.35f, 1.0f), (hFrac * aspect).coerceIn(0.12f, 0.55f))
        val ax = overlayLayout.anchorX.coerceIn(0.05f, 0.95f)
        val bottomFrac = overlayLayout.bottomMarginFraction.coerceIn(0f, 0.2f)
        val ay = (1f - bottomFrac - hFrac / 2f).coerceIn(0.55f, 0.95f)
        // Normalized anchor: 0,0 top-left → GL position with y up (+ = top).
        filter.setPosition((ax - 0.5f) * 2f, (0.5f - ay) * 2f)
    }

    private fun startOverlayRefresh() {
        if (overlayPausedForMemory) return
        stopOverlayRefresh()
        val interval = overlayRefreshMs
        val runnable = object : Runnable {
            override fun run() {
                try {
                    captureAndApplyOverlay()
                } catch (_: Exception) {
                }
                mainHandler.postDelayed(this, interval)
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
        val rawW = (streamWidth * wFrac).toInt()
        val w = rawW.coerceIn(160, maxOverlayCaptureWidth.coerceAtMost(1920))
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
