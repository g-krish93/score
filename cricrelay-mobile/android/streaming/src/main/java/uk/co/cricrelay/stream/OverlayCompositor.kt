package uk.co.cricrelay.stream

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender

/**
 * Scoreboard burn-in: owns the off-screen [OverlayWebViewCapture], the scoreboard GL filter +
 * bitmap lifecycle, and the three refresh loops (live stream, pre-stream GL preview, PNG push
 * to the studio UI). Extracted from [StreamCameraEngine]; owns no camera, layout, or cadence
 * state — all of it is read through the injected providers so the engine stays the single
 * source of truth. In particular the refresh cadence (overlayRefreshMs) stays engine-side,
 * fed by [ThermalMonitor]. Main-thread discipline matches the engine: every mutation runs on
 * (or is posted to) the main handler by the callers, exactly as before the extraction.
 */
internal class OverlayCompositor(
    private val mainHandler: Handler,
    private val camera: () -> CameraSession?,
    private val activity: () -> Activity?,
    private val appContext: () -> Context?,
    private val overlayUrl: () -> String,
    private val layout: () -> StreamCameraEngine.OverlayLayout,
    /** Effective encoded canvas (post rotation swap), width to height. */
    private val canvasSize: () -> Pair<Int, Int>,
    /** Scoreboard refresh cadence — engine state, scaled by [ThermalMonitor]. */
    private val refreshMs: () -> Long,
    private val pausedForMemory: () -> Boolean,
    private val streamPaused: () -> Boolean,
    private val isPortrait: () -> Boolean,
    private val previewListener: () -> ((ByteArray, Int, Int) -> Unit)?,
    private val applyOpacity: (Bitmap, Float) -> Bitmap,
    /** Watermark + sponsor re-ensure, run alongside every scoreboard frame apply. */
    private val ensureSiblingBurnIns: () -> Unit,
    private val warn: (key: String, message: String) -> Unit,
    private val emit: (event: String, message: String) -> Unit,
) {

    private var overlayCapture: OverlayWebViewCapture? = null
    private var imageFilter: ImageObjectFilterRender? = null
    private var lastOverlayBitmap: Bitmap? = null
    // Scoreboard bitmap size, cached at setImage time. RootEncoder's TextureLoader recycles the
    // bitmap right after the GL upload while the filter keeps the reference, so reading it back
    // later (setDefaultScale) logs "Called getWidth() on a recycle()'d bitmap" on every refresh.
    private var overlayBitmapWidth = 0
    private var overlayBitmapHeight = 0
    private var overlayRunnable: Runnable? = null
    private var previewOverlayRunnable: Runnable? = null
    private var previewOverlayPushActive = false
    private var previewOverlayRefreshRunnable: Runnable? = null
    private var overlayCaptureInFlight = false
    // Consecutive null overlay captures while live; a sustained streak means the score bar is
    // silently missing from the broadcast. Touched only on the capture executor thread.
    private var overlayCaptureFailStreak = 0
    // The board sprite's actual on-screen band (%), updated each time the board is placed.
    // SponsorLayer reads it through its boardBand provider for scroll_above/below_board.
    private var boardTopPct: Float = 84f
    private var boardBottomPct: Float = 100f

    /** True when the scoreboard GL filter is attached (drives resume-refresh decisions). */
    val hasFilter: Boolean
        get() = imageFilter != null

    /** Board sprite's real on-screen band (topPct to bottomPct), as last placed. */
    fun boardBand(): Pair<Float, Float> = boardTopPct to boardBottomPct

    private fun captureOverlayAfterStyleChange() {
        val streaming = camera()?.isStreaming == true && !streamPaused()
        captureAndApplyOverlayInternal(requireStreaming = streaming)
    }

    fun ensureOverlayCapture(): OverlayWebViewCapture? {
        val act = activity() ?: return null
        if (overlayCapture == null) {
            overlayCapture = OverlayWebViewCapture(act).also { capture ->
                capture.onStyleApplied = {
                    mainHandler.post { captureOverlayAfterStyleChange() }
                }
                capture.onPageReady = {
                    CricrelayLog.d("overlay WebView page ready — capture via applyMeasureScript")
                }
            }
        }
        return overlayCapture
    }

    /** Full teardown — ViewModel cleared or streaming session ended. Main thread only. */
    fun destroyOverlayCapture() {
        overlayCapture?.destroy()
        overlayCapture = null
    }

    /** Raster width = encoded frame width (capped by device tier) so overlay fills every stream size. */
    fun syncOverlayCaptureWidth() {
        val ctx = appContext() ?: activity()?.applicationContext ?: return
        val tierMax = DeviceCapabilities.maxOverlayCaptureWidth(DeviceCapabilities.tier(ctx))
        val w = canvasSize().first.coerceIn(320, tierMax.coerceAtMost(StreamCameraEngine.MAX_WIDTH))
        ensureOverlayCapture()?.setCaptureWidth(w)
    }

    /**
     * Load the themed page + current style into the capture WebView, then dispatch the right
     * refresh loop via [StreamOverlayPolicy] (the tail of the engine's updateOverlay).
     */
    fun applyOverlayConfig() {
        val l = layout()
        val themedUrl = OverlayThemeBridge.urlWithTheme(overlayUrl(), l.theme)
        ensureOverlayCapture()?.apply {
            loadUrl(themedUrl)
            setStyle(l.fontScale, l.bgColor, l.textColor, l.theme)
        }
        stopPreviewOverlayRefresh()
        val refreshMode = StreamOverlayPolicy.refreshMode(
            isStreaming = camera()?.isStreaming == true,
            hasPreviewListener = previewListener() != null,
            overlayUrlBlank = false,
        )
        when (refreshMode) {
            StreamOverlayPolicy.RefreshMode.StreamRefresh -> {
                if (imageFilter != null && lastOverlayBitmap != null) {
                    applyOverlaySprite()
                }
                if (imageFilter != null) {
                    startOverlayRefresh()
                }
            }
            StreamOverlayPolicy.RefreshMode.PreviewGlRefresh -> startPreviewOverlayRefresh()
            StreamOverlayPolicy.RefreshMode.None -> {
                stopPreviewOverlayPush()
                stopPreviewOverlayRefresh()
            }
        }
    }

    /** Overlay URL may be set before the GL view attaches an Activity — restart capture then. */
    fun resumeOverlayPreviewIfNeeded() {
        if (overlayUrl().isEmpty() || activity() == null) return
        syncOverlayCaptureWidth()
        val l = layout()
        ensureOverlayCapture()?.apply {
            loadUrl(OverlayThemeBridge.urlWithTheme(overlayUrl(), l.theme))
            setStyle(
                l.fontScale,
                l.bgColor,
                l.textColor,
                l.theme,
            )
        }
        when (
            StreamOverlayPolicy.refreshMode(
                isStreaming = camera()?.isStreaming == true,
                hasPreviewListener = previewListener() != null,
                overlayUrlBlank = false,
            )
        ) {
            StreamOverlayPolicy.RefreshMode.StreamRefresh -> {
                if (imageFilter != null && overlayRunnable == null) startOverlayRefresh()
            }
            StreamOverlayPolicy.RefreshMode.PreviewGlRefresh -> startPreviewOverlayRefresh()
            StreamOverlayPolicy.RefreshMode.None -> Unit
        }
    }

    /**
     * After a glInterface swap the scoreboard filter is gone — rebuild filter, sprite, and
     * refresh loop. Callers gate on overlay URL + pause state, as before the extraction.
     */
    fun reattachAfterSwap() {
        ensureOverlayFilter()
        if (lastOverlayBitmap != null) applyOverlaySprite()
        startOverlayRefresh()
    }

    fun recycleOverlayBitmap() {
        val bmp = lastOverlayBitmap
        lastOverlayBitmap = null
        if (bmp == null) return
        // GL draw runs on a pool thread — defer recycle until after the filter stops sampling.
        mainHandler.postDelayed({
            bmp.takeIf { !it.isRecycled }?.recycle()
        }, 1500)
    }

    /**
     * stopPreview / prepareVideo tears down the GL filter chain; drop the reference WITHOUT
     * touching glInterface so the next ensure re-attaches a fresh filter instead of reusing a
     * detached object.
     */
    fun dropStaleRefs() {
        imageFilter = null
    }

    fun clearOverlayFilter() {
        val cam = camera() ?: return
        imageFilter?.let { filter ->
            try {
                cam.removeFilter(filter)
            } catch (_: Exception) {
            }
        }
        imageFilter = null
    }

    private fun ensureOverlayFilter() {
        val cam = camera() ?: return
        if (imageFilter != null) {
            if (lastOverlayBitmap != null) applyOverlaySprite()
            return
        }
        if (!cam.isOnPreview && !cam.isStreaming) return
        try {
            val filter = ImageObjectFilterRender()
            cam.addFilter(filter)
            imageFilter = filter
        } catch (e: Exception) {
            emit(StreamCaptureService.EVENT_ERROR, "Overlay filter failed: ${e.message}")
        }
    }

    /**
     * Position the scoreboard sprite using RootEncoder's 0–100% coordinate system
     * (see [com.pedro.encoder.input.gl.Sprite] — 0,0 is top-left, 100,100 is bottom-right).
     */
    private fun applyOverlaySprite() {
        val filter = imageFilter ?: return
        if (overlayBitmapWidth <= 0 || overlayBitmapHeight <= 0) return
        val (canvasW, canvasH) = canvasSize()
        // Base scale from the dimensions cached at setImage time, NOT filter.setDefaultScale:
        // RootEncoder already recycled the bitmap the filter holds, so setDefaultScale would
        // read a recycle()'d bitmap on every refresh (see OverlaySpriteLayout.defaultScale).
        val base = OverlaySpriteLayout.defaultScale(
            overlayBitmapWidth, overlayBitmapHeight, canvasW, canvasH,
        )
        // Uniform, aspect-locked board scale: a single multiplier drives both axes so the fixed-
        // aspect strip never distorts (matches the Arrange pinch gesture). heightFraction is kept
        // in sync with widthFraction by OverlayLayoutPrefs.withBoardScale, so width is authoritative.
        val boardScale = layout().widthFraction.coerceIn(0.25f, 0.98f) / REF_OVERLAY_WIDTH_FRACTION
        val fitted = OverlaySpriteLayout.fitScale(base.x, base.y, boardScale, boardScale, maxPercent = 100f)
        filter.setScale(fitted.x, fitted.y)
        val scale = filter.getScale()
        val pos = OverlaySpriteLayout.computePosition(
            OverlaySpriteLayout.Params(
                scaleX = scale.x,
                scaleY = scale.y,
                anchorX = layout().anchorX,
                anchorY = layout().anchorY,
                bottomMarginFraction = layout().bottomMarginFraction,
                horizontalInsetFraction = layout().horizontalInsetFraction,
            ),
        )
        filter.setPosition(pos.x, pos.y)
        // Remember the board's actual on-screen band so the sponsor's above/below-board modes track
        // the real (flush-bottom, pinch-scaled) board instead of a stale anchorY estimate.
        boardTopPct = pos.y
        boardBottomPct = (pos.y + scale.y).coerceAtMost(100f)
    }

    fun startOverlayRefresh() {
        if (pausedForMemory()) return
        stopOverlayRefresh()
        val interval = refreshMs()
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

    fun stopOverlayRefresh() {
        overlayRunnable?.let { mainHandler.removeCallbacks(it) }
        overlayRunnable = null
    }

    fun startPreviewOverlayPush() {
        if (previewOverlayPushActive) return
        if (camera()?.isStreaming == true || pausedForMemory()) return
        if (overlayUrl().isEmpty() || previewListener() == null) {
            CricrelayLog.w(
                "startPreviewOverlayPush skipped: urlEmpty=${overlayUrl().isEmpty()} listener=${previewListener() != null}",
            )
            return
        }
        previewOverlayPushActive = true
        CricrelayLog.d("startPreviewOverlayPush: url=${overlayUrl()}")
        val interval = (refreshMs() * 2).coerceIn(1000L, 2500L)
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

    fun stopPreviewOverlayPush() {
        previewOverlayPushActive = false
        previewOverlayRunnable?.let { mainHandler.removeCallbacks(it) }
        previewOverlayRunnable = null
    }

    fun startPreviewOverlayRefresh() {
        if (camera()?.isStreaming == true || pausedForMemory()) return
        stopPreviewOverlayRefresh()
        if (overlayUrl().isEmpty()) return
        ensureOverlayCapture()?.loadUrl(OverlayThemeBridge.urlWithTheme(overlayUrl(), layout().theme))
        val interval = (refreshMs() * 2).coerceIn(800L, 2500L)
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

    fun stopPreviewOverlayRefresh() {
        previewOverlayRefreshRunnable?.let { mainHandler.removeCallbacks(it) }
        previewOverlayRefreshRunnable = null
    }

    private fun pushPreviewOverlayFrame() {
        if (camera()?.isStreaming == true || pausedForMemory() || overlayCaptureInFlight) return
        val listener = previewListener() ?: return
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
        val cam = camera() ?: return
        if (requireStreaming) {
            if (!cam.isStreaming || streamPaused()) return
        } else if (!cam.isOnPreview || cam.isStreaming) {
            return
        }
        if (overlayCaptureInFlight) return
        syncOverlayCaptureWidth()
        val capture = overlayCapture ?: return
        overlayCaptureInFlight = true
        capture.captureAsync { captured ->
            overlayCaptureInFlight = false
            if (captured == null) {
                // A sustained streak of failed captures while live means the score bar has
                // silently vanished from the broadcast (e.g. the WebView never measured).
                if (requireStreaming &&
                    ++overlayCaptureFailStreak == OVERLAY_CAPTURE_WARN_STREAK
                ) {
                    warn(
                        "overlay_capture",
                        "Scoreboard overlay is not rendering — the stream may be missing the score bar.",
                    )
                }
                return@captureAsync
            }
            overlayCaptureFailStreak = 0
            val forGl = applyOpacity(
                captured.copy(Bitmap.Config.ARGB_8888, false),
                layout().opacity,
            )
            if (forGl != captured) {
                captured.recycle()
            }
            runOnMain {
                val liveCam = camera() ?: return@runOnMain
                if (requireStreaming) {
                    if (!liveCam.isStreaming || streamPaused()) {
                        if (!forGl.isRecycled) forGl.recycle()
                        return@runOnMain
                    }
                } else if (!liveCam.isOnPreview || liveCam.isStreaming) {
                    if (!forGl.isRecycled) forGl.recycle()
                    return@runOnMain
                }
                ensureOverlayFilter()
                ensureSiblingBurnIns()
                val filter = imageFilter ?: run {
                    if (!forGl.isRecycled) forGl.recycle()
                    return@runOnMain
                }
                val previous = lastOverlayBitmap
                lastOverlayBitmap = forGl
                overlayBitmapWidth = forGl.width
                overlayBitmapHeight = forGl.height
                filter.setImage(forGl)
                applyOverlaySprite()
                runCatching {
                    val s = filter.getScale()
                    CricrelayLog.d(
                        "overlaySprite: bitmap=${overlayBitmapWidth}x$overlayBitmapHeight " +
                            "portrait=${isPortrait()} scale=${s.x}x${s.y}",
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

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private companion object {
        // Reference prefs (the defaults in OverlayLayoutPrefs). When the user sliders sit at
        // these values the overlay bitmap renders at its NATIVE aspect ratio — wMul/hMul == 1.
        const val REF_OVERLAY_WIDTH_FRACTION = 1.0f

        @Suppress("unused")
        const val REF_OVERLAY_HEIGHT_FRACTION = 0.16f

        const val OVERLAY_CAPTURE_WARN_STREAK = 8
    }
}
