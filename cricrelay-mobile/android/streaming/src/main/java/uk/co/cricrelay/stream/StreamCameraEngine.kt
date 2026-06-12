package uk.co.cricrelay.stream

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
 * 4. overlay WebView + GL filter when RTMP connects (preview uses Flutter WebView)
 */
object StreamCameraEngine : ConnectChecker {

    data class OverlayLayout(
        val heightFraction: Float = 0.16f,
        val widthFraction: Float = 0.92f,
        val anchorX: Float = 0.5f,
        val anchorY: Float = 0.85f,
        val bottomMarginFraction: Float = 0.02f,
        val horizontalInsetFraction: Float = 0.02f,
        val fontScale: Float = 1.0f,
        val bgColor: String = "",
        val textColor: String = "",
        val opacity: Float = 1.0f,
        val watermarkEnabled: Boolean = true,
        val watermarkText: String = "Visit cricrelay.co.uk",
    )

    private const val MAX_WIDTH = 1280
    // Reference prefs (the defaults in OverlayLayoutPrefs). When the user sliders sit at
    // these values the overlay bitmap renders at its NATIVE aspect ratio — wMul/hMul == 1.
    private const val REF_OVERLAY_WIDTH_FRACTION = 0.92f
    private const val REF_OVERLAY_HEIGHT_FRACTION = 0.16f
    private const val MAX_HEIGHT = 720
    private const val DEFAULT_BITRATE = 2500000
    private const val DEFAULT_FPS = 30

    private var camera: RtmpCamera2? = null
    private var openGlView: OpenGlView? = null
    private var activity: Activity? = null
    private var appContext: Context? = null
    private var imageFilter: ImageObjectFilterRender? = null
    private var watermarkFilter: ImageObjectFilterRender? = null
    private var appliedWatermarkText: String? = null
    // TODO(paywall): once Stripe is wired, free-tier streams force the watermark on
    // regardless of the admin toggle — for now the toggle in Board Edit wins.
    private const val IS_FREE_USER = true
    private var overlayCapture: OverlayWebViewCapture? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayRunnable: Runnable? = null
    private var previewOverlayRunnable: Runnable? = null
    private var previewOverlayPushActive = false
    private var previewOverlayRefreshRunnable: Runnable? = null
    private var previewOverlayListener: ((ByteArray, Int, Int) -> Unit)? = null
    private var streamWidth = MAX_WIDTH
    private var streamHeight = MAX_HEIGHT
    private var streamFps = DEFAULT_FPS
    private var streamBitrate = DEFAULT_BITRATE
    private var streamRotation = 0
    private var streamIsPortrait = false
    private var preparedVideoRotation = -1
    private var sensorSurfaceRotation = -1
    private var overlayLayout = OverlayLayout()
    private var overlayUrl: String = ""
    private var statusListener: ((String, String) -> Unit)? = null
    private var encoderPrepared = false
    private var lastOverlayBitmap: Bitmap? = null
    private var pendingOverlayAfterConnect = false
    private var prepareInFlight = false
    private var previewSurfaceRunnable: Runnable? = null
    private var videoStabilizationEnabled = true
    private var keepScreenOnDuringStream = false
    private var audioManager: AudioManager? = null
    private var pauseBlackFilter: BlackFilterRender? = null
    private var streamPaused = false
    private var deviceTier = DeviceCapabilities.Tier.HIGH
    private var overlayRefreshMs = 500L
    private var surfaceValid = true
    private var overlayPausedForMemory = false
    private var overlayCaptureInFlight = false
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
    fun onPreviewSurfaceLost(view: OpenGlView? = null) {
        runOnMain {
            // Stale SurfaceHolder callbacks from a replaced OpenGlView must not tear down
            // the active preview (black screen when re-entering studio).
            if (view != null && openGlView != null && view !== openGlView) {
                CricrelayLog.d("onPreviewSurfaceLost: ignored stale surface")
                return@runOnMain
            }
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
        view.setBackgroundColor(Color.TRANSPARENT)
        view.setAspectRatioMode(AspectRatioMode.Fill)
        if (camera == null) {
            camera = try {
                RtmpCamera2(view, this)
            } catch (e: Exception) {
                emit(StreamCaptureService.EVENT_ERROR, "Camera init failed: ${e.message ?: "unknown"}")
                null
            }
        }
        resumeOverlayPreviewIfNeeded()
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
        if (activity != null) {
            applyActivityRotation()
        } else {
            streamRotation = rotation.coerceIn(0, 360)
            streamIsPortrait = streamRotation == 90 || streamRotation == 270
        }
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
            dropStaleGlFilterRefs()
            try {
                camera?.stopPreview()
            } catch (_: Exception) {
            }
            encoderPrepared = false
            resetFocusState()
        }
        return preparePreview(width, height, fps, bitrate, rotation)
    }

    /**
     * Re-prepare the whole pipeline whenever rotation changes.
     *
     * RootEncoder's prepareVideo(w, h, fps, bitrate, rotation) drives everything via prepareGlView:
     * it swaps w<->h when rotation is 90/270 (so 1280x720 + rot=90 yields a true 720x1280 portrait
     * encoder), sets isPortrait, and sets the SHARED camera rotation used by both preview and
     * encoder. The preview (previewOrientation) and encoder (streamOrientation) must stay equal —
     * we leave both at 0 and never call setStreamRotation, so re-preparing keeps preview and stream
     * in lockstep with the physical orientation. OBS-level: rotating the canvas re-inits the pipeline.
     */
    fun updatePreviewRotation(rotation: Int): Boolean {
        if (camera?.isStreaming == true) return false
        return resetPreviewForOrientation(streamWidth, streamHeight, streamFps, streamBitrate, rotation)
    }

    fun setPreviewOverlayListener(listener: ((ByteArray, Int, Int) -> Unit)?) {
        runOnMain {
            previewOverlayListener = listener
            if (listener != null && !isStreaming && overlayUrl.isNotEmpty()) {
                startPreviewOverlayPush()
            } else {
                stopPreviewOverlayPush()
            }
        }
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

    /** RootEncoder prepareVideo rotation from a display rotation (back camera, sensor 90°). */
    private fun encoderRotationForSurface(surfaceDeg: Int): Int = when (surfaceDeg) {
        90 -> 0
        180 -> 270
        270 -> 180
        else -> 90
    }

    fun encoderRotationForDisplay(act: Activity): Int =
        encoderRotationForSurface(displayRotationDegrees(act))

    /** Live device orientation (sensor) takes priority over the locked Activity display. */
    private fun currentSurfaceRotation(): Int {
        if (sensorSurfaceRotation == 0 || sensorSurfaceRotation == 90 ||
            sensorSurfaceRotation == 180 || sensorSurfaceRotation == 270
        ) {
            return sensorSurfaceRotation
        }
        return activity?.let { displayRotationDegrees(it) } ?: 0
    }

    private fun applyActivityRotation() {
        streamRotation = encoderRotationForSurface(currentSurfaceRotation())
        streamIsPortrait = streamRotation == 90 || streamRotation == 270
    }

    /**
     * Apply the phone's physical orientation (from an OrientationEventListener) so the
     * stream follows how the operator holds the device at Go Live time — not how the
     * studio was first opened. Ignored while streaming (RTMP orientation is fixed).
     */
    fun setDeviceOrientation(surfaceRotationDeg: Int) {
        val normalized = when (surfaceRotationDeg) {
            0, 90, 180, 270 -> surfaceRotationDeg
            else -> return
        }
        if (normalized == sensorSurfaceRotation) return
        sensorSurfaceRotation = normalized
        runOnMain {
            if (camera?.isStreaming == true) return@runOnMain
            val enc = encoderRotationForSurface(normalized)
            if (enc == streamRotation && encoderPrepared && camera?.isOnPreview == true) {
                return@runOnMain
            }
            updatePreviewRotation(enc)
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
        val view = openGlView ?: return
        previewSurfaceRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            previewSurfaceRunnable = null
            runOnMain {
                surfaceValid = true
                overlayPausedForMemory = false
                val gl = openGlView ?: return@runOnMain
                if (gl.width < 64 || gl.height < 64) {
                    CricrelayLog.w("onPreviewSurfaceReady: view too small ${gl.width}x${gl.height}, retrying")
                    gl.postDelayed({ onPreviewSurfaceReady() }, 120)
                    return@runOnMain
                }
                if (!isPreviewSurfaceValid(gl)) {
                    CricrelayLog.w("onPreviewSurfaceReady: surface not valid yet, retrying")
                    gl.postDelayed({ onPreviewSurfaceReady() }, 120)
                    return@runOnMain
                }
                if (prepareInFlight || camera?.isStreaming == true) return@runOnMain
                if (encoderPrepared && camera?.isOnPreview == true) return@runOnMain
                val ok = preparePreviewOnMain()
                if (!ok) {
                    CricrelayLog.w("onPreviewSurfaceReady: preparePreviewOnMain not ready, will retry on next surface event")
                }
                if (ok) {
                    emit(StreamCaptureService.EVENT_PREVIEW_READY, "${streamWidth}x${streamHeight}")
                    CricrelayLog.d("preview ready ${streamWidth}x${streamHeight} onPreview=${camera?.isOnPreview}")
                    activity?.let { CameraPreviewHost.elevateComposeUi(it) }
                    resumeOverlayPreviewIfNeeded()
                    // Re-establish the watermark after every (re)prepare — covers rotation
                    // and streams with no scoreboard (where the capture loop never runs).
                    ensureWatermarkFilter()
                }
            }
        }
        previewSurfaceRunnable = runnable
        mainHandler.postDelayed(runnable, 80)
    }

    fun updateOverlay(url: String, layout: OverlayLayout) {
        runOnMain {
            if (url.isNotEmpty()) {
                overlayUrl = url
            }
            overlayLayout = layout
            ensureWatermarkFilter()
            if (overlayUrl.isEmpty()) return@runOnMain
            ensureOverlayCapture()?.apply {
                setStyle(layout.fontScale, layout.bgColor, layout.textColor)
                loadUrl(overlayUrl)
            }
            stopPreviewOverlayRefresh()
            when (
                StreamOverlayPolicy.refreshMode(
                    isStreaming = camera?.isStreaming == true,
                    hasPreviewListener = previewOverlayListener != null,
                    overlayUrlBlank = false,
                )
            ) {
                StreamOverlayPolicy.RefreshMode.StreamRefresh -> {
                    if (imageFilter != null && lastOverlayBitmap != null) {
                        applyOverlaySprite()
                    }
                    if (imageFilter != null) {
                        startOverlayRefresh()
                    }
                }
                StreamOverlayPolicy.RefreshMode.PreviewPush -> startPreviewOverlayPush()
                StreamOverlayPolicy.RefreshMode.None -> stopPreviewOverlayPush()
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
                val clamped = level.coerceIn(minZoom(), maxZoom())
                camera?.setZoom(clamped)
            } catch (_: Exception) {
            }
        }
    }

    fun minZoom(): Float = 1f

    fun maxZoom(): Float {
        val cam = camera ?: return 1f
        return try {
            cam.zoomRange.upper.toFloat().coerceAtLeast(minZoom())
        } catch (_: Exception) {
            1f
        }
    }

    fun currentZoom(): Float {
        val cam = camera ?: return minZoom()
        return try {
            cam.zoom.coerceIn(minZoom(), maxZoom())
        } catch (_: Exception) {
            minZoom()
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
        if (!surfaceValid) return false
        if (view.width < 64 || view.height < 64) {
            view.post { onPreviewSurfaceReady() }
            return false
        }
        if (!isPreviewSurfaceValid(view)) {
            view.post { onPreviewSurfaceReady() }
            return false
        }
        if (encoderPrepared && cam.isOnPreview) {
            val previousRotation = streamRotation
            applyActivityRotation()
            if (!cam.isStreaming && previousRotation != streamRotation) {
                dropStaleGlFilterRefs()
                try {
                    cam.stopPreview()
                } catch (_: Exception) {
                }
                encoderPrepared = false
                resetFocusState()
            } else {
                ensureWatermarkFilter()
                return true
            }
        }
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
            // Do NOT call setStreamRotation here. prepareVideo(.., streamRotation) below drives the
            // whole pipeline: prepareGlView swaps the encoder size, sets isPortrait, and sets the
            // shared camera rotation. setStreamRotation would override ONLY the encoder's
            // streamOrientation (not the preview's previewOrientation), adding an extra 90° to the
            // stream alone — the exact cause of "preview upright, RTMP output sideways".
            val audioOk = cam.prepareAudio(128 * 1024, 32_000, true, false, false)
            if (!audioOk) {
                encoderPrepared = false
                CricrelayLog.e("prepareAudio FAILED — preview cannot start (mic permission/codec?)")
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
                CricrelayLog.w(
                    "prepareVideo tier ${tier.width}x${tier.height}@${tier.fps} rot=$streamRotation failed, stepping down",
                )
            }
            if (!videoOk) {
                encoderPrepared = false
                CricrelayLog.e("prepareVideo FAILED on all tiers — preview stays black")
                return false
            }
            if (videoStabilizationEnabled && deviceTier != DeviceCapabilities.Tier.LOW) {
                try {
                    cam.enableVideoStabilization()
                } catch (_: Exception) {
                }
            }
            encoderPrepared = true
            preparedVideoRotation = streamRotation
            if (!cam.isOnPreview) {
                cam.startPreview()
            }
            val ready = cam.isOnPreview
            // RootEncoder stores the raw (pre-rotation) dims in width/height, but when rotation is
            // 90/270 it builds the encoder MediaFormat as createVideoFormat(height, width) — i.e. the
            // actual encoded frame is swapped to portrait. Report the effective (post-swap) size.
            val rawW = runCatching { cam.streamWidth }.getOrDefault(streamWidth)
            val rawH = runCatching { cam.streamHeight }.getOrDefault(streamHeight)
            val effW = if (streamIsPortrait) rawH else rawW
            val effH = if (streamIsPortrait) rawW else rawH
            CricrelayLog.d(
                "preparePreviewOnMain done: onPreview=$ready in=${streamWidth}x${streamHeight} " +
                    "rot=$streamRotation portrait=$streamIsPortrait encodedFrame=${effW}x${effH}",
            )
            if (ready) {
                ensureWatermarkFilter()
            }
            ready
        } catch (t: Exception) {
            encoderPrepared = false
            CricrelayLog.e("preparePreviewOnMain threw — camera open/preview failed", t)
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
        if (cam.isStreaming) {
            CricrelayLog.w("startStreamOnMain: previous stream still active — stopping before retry")
            try {
                cam.stopStream()
            } catch (_: Exception) {
            }
            if (!preparePreviewOnMain()) {
                throw IllegalStateException("Camera not ready after stopping previous stream")
            }
        }

        // Re-sync the encoder to the phone's current physical orientation. The whole
        // pipeline (preview + encoder) is configured by prepareVideo's rotation, which is
        // locked at first prepare. Re-prepare here — the only safe window before RTMP — so
        // the output matches how the phone is held at Go Live, not how the studio was first
        // opened.
        applyActivityRotation()
        if (encoderPrepared && cam.isOnPreview && preparedVideoRotation != streamRotation) {
            CricrelayLog.d(
                "Go Live re-sync encoder rotation $preparedVideoRotation -> $streamRotation",
            )
            try {
                dropStaleGlFilterRefs()
                cam.stopPreview()
                encoderPrepared = false
                resetFocusState()
            } catch (_: Exception) {
            }
            if (!preparePreviewOnMain()) {
                throw IllegalStateException("Camera preview not ready — wait for preview before Go Live")
            }
        }

        stopPreviewOverlayPush()
        stopPreviewOverlayRefresh()
        emit(StreamCaptureService.EVENT_PREPARING, "Starting stream…")
        if (keepScreenOnDuringStream) {
            openGlView?.keepScreenOn = true
        }
        try {
            cam.startStream(endpoint)
            activity?.let {
                CameraPreviewHost.refreshPreviewSurface()
                CameraPreviewHost.elevateComposeUi(it)
            }
        } catch (t: Throwable) {
            openGlView?.keepScreenOn = false
            throw IllegalStateException(
                "RTMP start failed: ${t.message ?: t.javaClass.simpleName}",
                t,
            )
        }
    }

    /** After RTMP ends, recycle the encoder so the next Go Live starts from a clean preview. */
    private fun resetEncoderAfterRtmpStop() {
        val cam = camera ?: return
        if (cam.isStreaming) {
            try {
                cam.stopStream()
            } catch (_: Exception) {
            }
        }
        try {
            if (cam.isOnPreview) {
                cam.stopPreview()
            }
        } catch (_: Exception) {
        }
        encoderPrepared = false
        resetFocusState()
        if (!preparePreviewOnMain()) {
            CricrelayLog.w("resetEncoderAfterRtmpStop: preview re-prepare failed")
        }
    }

    private fun stopStreamInternal() {
        pendingOverlayAfterConnect = false
        streamPaused = false
        removePauseBlackFilter()
        stopOverlayRefresh()
        clearOverlayFilter()
        recycleOverlayBitmap()
        openGlView?.keepScreenOn = false
        abandonStreamAudioFocus()
        resetEncoderAfterRtmpStop()
        if (previewOverlayListener != null && overlayUrl.isNotEmpty()) {
            startPreviewOverlayPush()
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
        stopPreviewOverlayPush()
        if (camera?.isStreaming == true) {
            stopStreamInternal()
        } else {
            stopPreviewOverlayRefresh()
            recycleOverlayBitmap()
            clearOverlayFilter()
            resetFocusState()
            try {
                camera?.stopPreview()
            } catch (_: Exception) {
            }
            encoderPrepared = false
            surfaceValid = false
        }
        // Keep overlay WebView alive across studio navigation to avoid reload flicker.
        camera = null
        openGlView = null
    }

    /** Full teardown — ViewModel cleared or streaming session ended. */
    fun destroyOverlayCapture() {
        runOnMain {
            overlayCapture?.destroy()
            overlayCapture = null
        }
    }

    private fun ensureOverlayCapture(): OverlayWebViewCapture? {
        val act = activity ?: return null
        if (overlayCapture == null) {
            overlayCapture = OverlayWebViewCapture(act).also { capture ->
                capture.onPageReady = {
                    // Periodic preview push handles refresh; avoid an extra capture on load.
                    CricrelayLog.d("overlay WebView page ready — preview push will refresh")
                }
            }
        }
        return overlayCapture
    }

    /** Overlay URL may be set before the GL view attaches an Activity — restart capture then. */
    private fun resumeOverlayPreviewIfNeeded() {
        if (overlayUrl.isEmpty() || activity == null) return
        ensureOverlayCapture()?.apply {
            setStyle(overlayLayout.fontScale, overlayLayout.bgColor, overlayLayout.textColor)
            loadUrl(overlayUrl)
        }
        when (
            StreamOverlayPolicy.refreshMode(
                isStreaming = camera?.isStreaming == true,
                hasPreviewListener = previewOverlayListener != null,
                overlayUrlBlank = false,
            )
        ) {
            StreamOverlayPolicy.RefreshMode.StreamRefresh -> {
                if (imageFilter != null && overlayRunnable == null) startOverlayRefresh()
            }
            StreamOverlayPolicy.RefreshMode.PreviewPush -> startPreviewOverlayPush()
            StreamOverlayPolicy.RefreshMode.None -> Unit
        }
    }

    private fun attachOverlayAfterConnect() {
        if (!pendingOverlayAfterConnect) return
        pendingOverlayAfterConnect = false
        mainHandler.postDelayed({
            try {
                if (overlayUrl.isNotEmpty()) {
                    ensureOverlayCapture()?.loadUrl(overlayUrl)
                }
                startOverlayRefresh()
            } catch (e: Exception) {
                emit(StreamCaptureService.EVENT_ERROR, "Scoreboard overlay failed: ${e.message}")
            }
        }, 800)
    }

    private fun recycleOverlayBitmap() {
        val bmp = lastOverlayBitmap
        lastOverlayBitmap = null
        if (bmp == null) return
        // GL draw runs on a pool thread — defer recycle until after the filter stops sampling.
        mainHandler.postDelayed({
            bmp.takeIf { !it.isRecycled }?.recycle()
        }, 1500)
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

    /**
     * stopPreview / prepareVideo tears down the GL filter chain; drop refs so the next
     * ensure* call re-attaches filters instead of reusing detached objects.
     */
    private fun dropStaleGlFilterRefs() {
        watermarkFilter = null
        imageFilter = null
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
        clearWatermarkFilter()
    }

    private const val WATERMARK_TEXT_SIZE = 34f
    private const val WATERMARK_BMP_HEIGHT = 80
    private const val WATERMARK_HEIGHT_PCT = 5.5f
    // The preview renders AspectRatioMode.Fill (aspect-fill), so the frame edges are cropped
    // off-screen — portrait crops the sides, landscape crops top/bottom. These keep the
    // watermark inside the crop-safe zone in BOTH orientations on tall (up to ~22:9) phones:
    // right edge pulled in from 100%, and pushed down from the very top.
    private const val WATERMARK_RIGHT_EDGE_PCT = 84f
    private const val WATERMARK_TOP_PCT = 13f
    private const val WATERMARK_MAX_WIDTH_PCT = 68f

    private fun watermarkTextPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 200
        textSize = WATERMARK_TEXT_SIZE
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    private fun watermarkBitmapWidth(text: String): Int =
        (watermarkTextPaint().measureText(text) + 48f).toInt().coerceAtLeast(160)

    private fun buildWatermarkBitmap(text: String): Bitmap {
        val w = watermarkBitmapWidth(text)
        val h = WATERMARK_BMP_HEIGHT
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            alpha = 130
        }
        canvas.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), 14f, 14f, bg)
        canvas.drawText(text, w / 2f, h / 2f + 12f, watermarkTextPaint())
        return bmp
    }

    private fun ensureWatermarkFilter() {
        val cam = camera ?: return
        val wantText = overlayLayout.watermarkText.trim()
        if (!overlayLayout.watermarkEnabled || wantText.isEmpty()) {
            clearWatermarkFilter()
            return
        }
        if (!cam.isOnPreview && !cam.isStreaming) return
        val filter = watermarkFilter ?: try {
            ImageObjectFilterRender().also {
                cam.glInterface.addFilter(it)
                watermarkFilter = it
            }
        } catch (e: Exception) {
            CricrelayLog.w("Watermark filter failed: ${e.message}")
            return
        }
        // Rebuild the bitmap only when the text changes (expensive)…
        if (appliedWatermarkText != wantText) {
            try {
                filter.setImage(buildWatermarkBitmap(wantText))
                appliedWatermarkText = wantText
            } catch (e: Exception) {
                CricrelayLog.w("Watermark image failed: ${e.message}")
            }
        }
        // …but always re-apply the sprite geometry so it tracks orientation / resolution
        // changes (same cadence as the scoreboard sprite).
        applyWatermarkSprite(filter)
    }

    private fun clearWatermarkFilter() {
        val cam = camera ?: return
        watermarkFilter?.let { filter ->
            try {
                cam.glInterface.removeFilter(filter)
            } catch (_: Exception) {
            }
        }
        watermarkFilter = null
        appliedWatermarkText = null
    }

    private fun applyWatermarkSprite(filter: ImageObjectFilterRender) {
        val canvasW = if (streamIsPortrait) streamHeight else streamWidth
        val canvasH = if (streamIsPortrait) streamWidth else streamHeight
        filter.setDefaultScale(canvasW, canvasH)
        val sprite = WatermarkSpriteLayout.compute(
            WatermarkSpriteLayout.Params(
                canvasW = canvasW,
                canvasH = canvasH,
                bitmapWidth = watermarkBitmapWidth(appliedWatermarkText ?: ""),
                bitmapHeight = WATERMARK_BMP_HEIGHT,
                heightPct = WATERMARK_HEIGHT_PCT,
                rightEdgePct = WATERMARK_RIGHT_EDGE_PCT,
                topPct = WATERMARK_TOP_PCT,
                maxWidthPct = WATERMARK_MAX_WIDTH_PCT,
            ),
        )
        filter.setScale(sprite.scaleX, sprite.scaleY)
        filter.setPosition(sprite.positionX, sprite.positionY)
    }

    private fun ensureOverlayFilter() {
        val cam = camera ?: return
        if (imageFilter != null) {
            if (lastOverlayBitmap != null) applyOverlaySprite()
            return
        }
        if (!cam.isOnPreview && !cam.isStreaming) return
        try {
            val filter = ImageObjectFilterRender()
            cam.glInterface.addFilter(filter)
            imageFilter = filter
        } catch (e: Exception) {
            emit(StreamCaptureService.EVENT_ERROR, "Overlay filter failed: ${e.message}")
        }
    }

    /**
     * Position the scoreboard sprite using RootEncoder's 0–100% coordinate system
     * (see [com.pedro.encoder.input.gl.Sprite] — 0,0 is top-left, 100,100 is bottom-right).
     */
    /** Bake a uniform alpha into the overlay bitmap (used for the GL sprite path). */
    private fun applyBitmapOpacity(src: Bitmap, opacity: Float): Bitmap {
        if (opacity >= 0.999f) return src
        return try {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint().apply { alpha = (opacity.coerceIn(0f, 1f) * 255f).toInt() }
            canvas.drawBitmap(src, 0f, 0f, paint)
            if (src !== out && !src.isRecycled) src.recycle()
            out
        } catch (_: Exception) {
            src
        }
    }

    private fun applyOverlaySprite() {
        val filter = imageFilter ?: return
        val canvasW = if (streamIsPortrait) streamHeight else streamWidth
        val canvasH = if (streamIsPortrait) streamWidth else streamHeight
        filter.setDefaultScale(canvasW, canvasH)
        val base = filter.getScale()
        val wMul = overlayLayout.widthFraction.coerceIn(0.25f, 0.98f) / REF_OVERLAY_WIDTH_FRACTION
        val hMul = overlayLayout.heightFraction.coerceIn(0.10f, 0.28f) / REF_OVERLAY_HEIGHT_FRACTION
        val fitted = OverlaySpriteLayout.fitScale(base.x, base.y, wMul, hMul)
        filter.setScale(fitted.x, fitted.y)
        val scale = filter.getScale()
        val pos = OverlaySpriteLayout.computePosition(
            OverlaySpriteLayout.Params(
                scaleX = scale.x,
                scaleY = scale.y,
                anchorX = overlayLayout.anchorX,
                anchorY = overlayLayout.anchorY,
                bottomMarginFraction = overlayLayout.bottomMarginFraction,
                horizontalInsetFraction = overlayLayout.horizontalInsetFraction,
            ),
        )
        filter.setPosition(pos.x, pos.y)
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

    private fun startPreviewOverlayPush() {
        if (previewOverlayPushActive) return
        if (camera?.isStreaming == true || overlayPausedForMemory) return
        if (overlayUrl.isEmpty() || previewOverlayListener == null) {
            CricrelayLog.w(
                "startPreviewOverlayPush skipped: urlEmpty=${overlayUrl.isEmpty()} listener=${previewOverlayListener != null}",
            )
            return
        }
        previewOverlayPushActive = true
        CricrelayLog.d("startPreviewOverlayPush: url=$overlayUrl")
        val interval = (overlayRefreshMs * 2).coerceIn(1000L, 2500L)
        val runnable = object : Runnable {
            override fun run() {
                try {
                    pushPreviewOverlayFrame()
                } catch (_: Exception) {
                }
                mainHandler.postDelayed(this, interval)
            }
        }
        previewOverlayRunnable = runnable
        mainHandler.postDelayed(runnable, 1000)
    }

    private fun stopPreviewOverlayPush() {
        previewOverlayPushActive = false
        previewOverlayRunnable?.let { mainHandler.removeCallbacks(it) }
        previewOverlayRunnable = null
    }

    private fun startPreviewOverlayRefresh() {
        if (camera?.isStreaming == true || overlayPausedForMemory) return
        stopPreviewOverlayRefresh()
        if (overlayUrl.isEmpty()) return
        ensureOverlayCapture()?.loadUrl(overlayUrl)
        val interval = (overlayRefreshMs * 2).coerceIn(800L, 2500L)
        val runnable = object : Runnable {
            override fun run() {
                try {
                    captureAndApplyOverlayForPreview()
                } catch (_: Exception) {
                }
                mainHandler.postDelayed(this, interval)
            }
        }
        previewOverlayRefreshRunnable = runnable
        mainHandler.postDelayed(runnable, 400)
    }

    private fun stopPreviewOverlayRefresh() {
        previewOverlayRefreshRunnable?.let { mainHandler.removeCallbacks(it) }
        previewOverlayRefreshRunnable = null
    }

    private fun pushPreviewOverlayFrame() {
        if (camera?.isStreaming == true || overlayPausedForMemory || overlayCaptureInFlight) return
        val listener = previewOverlayListener ?: return
        val capture = ensureOverlayCapture()
        if (capture == null) {
            CricrelayLog.w("pushPreviewOverlayFrame: no activity for overlay capture")
            return
        }
        capture.ensureAttached()
        overlayCaptureInFlight = true
        capture.captureAsync { captured ->
            overlayCaptureInFlight = false
            if (captured == null) return@captureAsync
            val w = captured.width
            val h = captured.height
            val stream = java.io.ByteArrayOutputStream()
            captured.compress(Bitmap.CompressFormat.PNG, 85, stream)
            if (!captured.isRecycled) {
                captured.recycle()
            }
            val bytes = stream.toByteArray()
            mainHandler.post { listener(bytes, w, h) }
        }
    }

    private fun captureAndApplyOverlay() {
        captureAndApplyOverlayInternal(requireStreaming = true)
    }

    private fun captureAndApplyOverlayForPreview() {
        captureAndApplyOverlayInternal(requireStreaming = false)
    }

    private fun captureAndApplyOverlayInternal(requireStreaming: Boolean) {
        val cam = camera ?: return
        if (requireStreaming) {
            if (!cam.isStreaming || streamPaused) return
        } else if (!cam.isOnPreview || cam.isStreaming) {
            return
        }
        if (overlayCaptureInFlight) return
        val capture = overlayCapture ?: return
        overlayCaptureInFlight = true
        capture.captureAsync { captured ->
            overlayCaptureInFlight = false
            if (captured == null) return@captureAsync
            val forGl = applyBitmapOpacity(
                captured.copy(Bitmap.Config.ARGB_8888, false),
                overlayLayout.opacity,
            )
            if (forGl != captured) {
                captured.recycle()
            }
            runOnMain {
                val liveCam = camera ?: return@runOnMain
                if (requireStreaming) {
                    if (!liveCam.isStreaming || streamPaused) {
                        if (!forGl.isRecycled) forGl.recycle()
                        return@runOnMain
                    }
                } else if (!liveCam.isOnPreview || liveCam.isStreaming) {
                    if (!forGl.isRecycled) forGl.recycle()
                    return@runOnMain
                }
                ensureOverlayFilter()
                ensureWatermarkFilter()
                val filter = imageFilter ?: run {
                    if (!forGl.isRecycled) forGl.recycle()
                    return@runOnMain
                }
                val previous = lastOverlayBitmap
                lastOverlayBitmap = forGl
                filter.setImage(forGl)
                applyOverlaySprite()
                runCatching {
                    val s = filter.getScale()
                    CricrelayLog.d(
                        "overlaySprite: bitmap=${forGl.width}x${forGl.height} " +
                            "portrait=$streamIsPortrait scale=${s.x}x${s.y}",
                    )
                }
                if (previous != null && previous !== forGl && !previous.isRecycled) {
                    mainHandler.postDelayed({
                        previous.takeIf { !it.isRecycled && it !== lastOverlayBitmap }?.recycle()
                    }, 1000)
                }
            }
        }
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
