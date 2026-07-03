package uk.co.cricrelay.stream

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import com.pedro.encoder.input.gl.render.filters.BlackFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.utils.gl.AspectRatioMode
import com.pedro.library.util.BitrateAdapter
import com.pedro.library.view.OpenGlView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Camera RTMP + scoreboard overlay (RootEncoder / RtmpCamera2, behind [CameraSession]).
 *
 * Golden path (do not deviate — re-preparing or stopPreview during Go Live crashes Pixel):
 * 1. attachView + prepareAudio/prepareVideo once
 * 2. startPreview
 * 3. startStream(endpoint) on Go Live — no second prepareVideo
 * 4. overlay WebView + GL filter when RTMP connects (preview uses Flutter WebView)
 */
object StreamCameraEngine : CameraSession.Listener {

    /** Live broadcast health, published ~once a second while streaming (null when not live). */
    data class StreamStats(
        val sentBitrateBps: Long,
        val targetBitrateBps: Int,
        val maxBitrateBps: Int,
        val width: Int,
        val height: Int,
        val fps: Int,
        val congested: Boolean,
        val droppedVideoFrames: Long,
    )

    data class OverlayLayout(
        val heightFraction: Float = 0.16f,
        val widthFraction: Float = 1.0f,
        val anchorX: Float = 0.5f,
        val anchorY: Float = 0.85f,
        val bottomMarginFraction: Float = 0f,
        val horizontalInsetFraction: Float = 0f,
        val fontScale: Float = 1.0f,
        val bgColor: String = "",
        val textColor: String = "",
        val opacity: Float = 1.0f,
        val watermarkEnabled: Boolean = true,
        val watermarkText: String = "Visit cricrelay.co.uk",
        val sponsorEnabled: Boolean = false,
        val sponsorLogoUrl: String = "",
        val sponsorLogoUrls: List<String> = emptyList(),
        val sponsorLayoutMode: String = "single",
        val sponsorCarouselIntervalSec: Float = 6f,
        val sponsorDisplayMode: String = "static",
        val sponsorPositionX: Float = 0.92f,
        val sponsorPositionY: Float = 0.88f,
        val sponsorSizeScale: Float = 1f,
        val sponsorOpacity: Float = 1f,
        val sponsorScrollSpeed: Float = 1f,
        val sponsorScrollDirection: String = "rtl",
        val theme: String = "barlow",
        val bowlingIslandEnabled: Boolean = true,
    )

    // Also caps the overlay raster width in OverlayCompositor (capture never exceeds the encoder).
    internal const val MAX_WIDTH = 1920
    private const val MAX_HEIGHT = 1080
    private const val DEFAULT_BITRATE = 2500000
    private const val MAX_BITRATE = 8_000_000
    private const val DEFAULT_FPS = 30

    private var camera: CameraSession? = null
    private var openGlView: OpenGlView? = null
    private var activity: Activity? = null
    private var appContext: Context? = null
    private var watermarkFilter: ImageObjectFilterRender? = null
    private var appliedWatermarkText: String? = null
    // TODO(paywall): once Stripe is wired, free-tier streams force the watermark on
    // regardless of the admin toggle — for now the toggle in Board Edit wins.
    private const val IS_FREE_USER = true
    private val mainHandler = Handler(Looper.getMainLooper())

    // Sponsor burn-ins live in their own layer; it reads camera/layout/canvas lazily through
    // these providers so the engine stays the single source of truth for session state.
    // (Explicit type: the boardBand/ensureSiblingBurnIns lambdas make the two layers
    // mutually referential, which defeats type inference.)
    private val sponsorLayer: SponsorLayer = SponsorLayer(
        mainHandler = mainHandler,
        camera = { camera },
        appContext = { appContext },
        layout = { overlayLayout },
        canvasSize = { encodedCanvasWidth() to encodedCanvasHeight() },
        boardBand = { overlayCompositor.boardBand() },
        applyOpacity = ::applyBitmapOpacity,
        warn = ::warnBurnInOnce,
    )

    // Scoreboard burn-in (WebView rasterize -> GL sprite + refresh loops) lives in its own
    // layer; camera, layout, and cadence are read lazily through these providers — the refresh
    // cadence (overlayRefreshMs) stays here, fed by ThermalMonitor.
    private val overlayCompositor: OverlayCompositor = OverlayCompositor(
        mainHandler = mainHandler,
        camera = { camera },
        activity = { activity },
        appContext = { appContext },
        overlayUrl = { overlayUrl },
        layout = { overlayLayout },
        canvasSize = { encodedCanvasWidth() to encodedCanvasHeight() },
        refreshMs = { overlayRefreshMs },
        pausedForMemory = { overlayPausedForMemory },
        streamPaused = { streamPaused },
        isPortrait = { streamIsPortrait },
        previewListener = { previewOverlayListener },
        applyOpacity = ::applyBitmapOpacity,
        ensureSiblingBurnIns = {
            ensureWatermarkFilter()
            sponsorLayer.ensure()
        },
        warn = ::warnBurnInOnce,
        emit = ::emit,
    )

    // Thermal mechanics (Q+ listener / pre-Q poll); the meaning of a status change —
    // overlay-refresh scaling + the "thermal" UI event — stays in onThermalStatusChanged.
    private val thermalMonitor = ThermalMonitor(mainHandler, ::onThermalStatusChanged)
    private var previewOverlayListener: ((ByteArray, Int, Int) -> Unit)? = null
    // Conservative 720p until preparePreview resolves the device tier — never assume 1080p.
    private var streamWidth = 1280
    private var streamHeight = 720
    private var streamFps = DEFAULT_FPS
    private var streamBitrate = DEFAULT_BITRATE
    private var streamRotation = 0
    private var streamIsPortrait = false
    private var preparedVideoRotation = -1
    private var sensorSurfaceRotation = -1
    private var overlayLayout = OverlayLayout()
    private var overlayUrl: String = ""
    private var statusListener: ((String, String) -> Unit)? = null

    // Single source of truth for the session's phase. The legacy state booleans below are
    // derived views, so every read keeps its old name while writes go through applyIntent —
    // an invalid transition (e.g. a stale surface-loss callback trying to tear down a live
    // encoder) is refused and logged instead of corrupting state.
    private var phase: StreamPhase = StreamPhase.Idle
    private val encoderPrepared: Boolean
        get() = phase != StreamPhase.Idle
    private var pendingOverlayAfterConnect = false
    private var prepareInFlight = false
    private var previewSurfaceRunnable: Runnable? = null
    // 0 = off, 1 = standard (EIS ON + OIS), 2 = cinematic (EIS PREVIEW_STABILIZATION + OIS).
    // Mirrors shared StabilizationLevel; :streaming has no dependency on :shared.
    private var stabilizationLevel = 1
    private var reflectOkLogged = false
    private var keepScreenOnDuringStream = false
    private var audioManager: AudioManager? = null
    private var pauseBlackFilter: BlackFilterRender? = null
    private val streamPaused: Boolean
        get() = (phase as? StreamPhase.Live)?.paused == true
    private var micMuted = false
    // MID until attachView measures the device — "auto" resolution must never guess 1080p
    // on a phone we haven't sized up yet.
    private var deviceTier = DeviceCapabilities.Tier.MID
    private var overlayRefreshMs = 500L
    private var surfaceValid = true
    // Deferred-restore retry. restoreOnViewRendering parks the encoder offscreen while the
    // keyguard is up, but onStart and the surface-ready callback both fire DURING the unlock
    // animation while isKeyguardLocked is still true — with no later lifecycle event, the
    // preview stayed black for the rest of the broadcast. ACTION_USER_PRESENT is the
    // authoritative "keyguard actually dismissed" signal; the poll is a safety net in case
    // the broadcast is missed. Both are disarmed on successful restore or stream stop.
    private var userPresentReceiver: BroadcastReceiver? = null
    private var restoreRetryRunnable: Runnable? = null
    private const val RESTORE_RETRY_MS = 1000L
    // True while the encoder renders to the offscreen GL interface (screen locked / app
    // backgrounded without PiP). Keeps the broadcast alive when the SurfaceView surface is gone.
    private val backgroundRendering: Boolean
        get() = (phase as? StreamPhase.Live)?.background == true
    private var overlayPausedForMemory = false
    private var focusLocked = false
    // Reconnect attempts used for the current outage (written on the RTMP callback thread,
    // reset on connect success / stream start). Schedule lives in StreamReconnectPolicy.
    @Volatile
    private var reconnectAttempt = 0
    // Burn-in degradations already surfaced this broadcast — warn once, not once per frame.
    // Main-thread only (warnBurnInOnce hops to main).
    private val burnInWarned = mutableSetOf<String>()
    // Keep-alive service failure already surfaced this broadcast (reset at stream start).
    // Main-thread only, like burnInWarned.
    private var keepAliveWarned = false
    // startForegroundService is asynchronous and the service stops itself on a refused
    // startForeground — how long to wait before trusting isForegroundActive.
    private const val KEEPALIVE_VERIFY_MS = 4_000L
    // Adaptive bitrate: created on RTMP connect, fed by onNewBitrate, steps the encoder
    // bitrate up/down with the real network via setVideoBitrateOnFly.
    private var bitrateAdapter: BitrateAdapter? = null
    private var currentTargetBitrate = DEFAULT_BITRATE
    private var statsListener: ((StreamStats?) -> Unit)? = null

    val isStreaming: Boolean
        get() = camera?.isStreaming == true

    val isStreamPaused: Boolean
        get() = streamPaused && isStreaming

    val isPreviewReady: Boolean
        get() = camera != null && openGlView != null && encoderPrepared && (camera?.isOnPreview == true)

    fun setStatusListener(listener: ((String, String) -> Unit)?) {
        statusListener = listener
    }

    /**
     * Dispatch a phase transition. Validation only — physical control flow stays with the
     * existing camera-truth guards; a refusal means the caller acted on stale state and the
     * phase (plus every boolean derived from it) is left untouched.
     */
    private fun applyIntent(intent: StreamPhasePolicy.Intent): Boolean {
        val next = StreamPhasePolicy.next(phase, intent)
        if (next == null) {
            CricrelayLog.w("phase: $intent refused in $phase")
            return false
        }
        if (next != phase) CricrelayLog.d("phase: $phase -> $next ($intent)")
        phase = next
        return true
    }

    /** Broadcast-health updates ~1/sec while live; a null value means the stream ended. */
    fun setStatsListener(listener: ((StreamStats?) -> Unit)?) {
        statsListener = listener
    }

    fun setKeepScreenOnDuringStream(enabled: Boolean) {
        keepScreenOnDuringStream = enabled
    }

    /** Back-compat shim for boolean callers (e.g. old remote payloads). */
    fun setVideoStabilization(enabled: Boolean) = setStabilizationLevel(if (enabled) 1 else 0)

    fun setStabilizationLevel(level: Int) {
        stabilizationLevel = level.coerceIn(0, 2)
        val cam = camera ?: return
        if (cam.isStreaming) return // pre-stream setting: changing EIS live can hiccup the encoder
        runOnMain { applyStabilization(cam, live = true) }
    }

    /**
     * Off = EIS 0 + OIS 0; Standard = EIS ON(1) + OIS ON; Cinematic = EIS PREVIEW_STABILIZATION(2)
     * + OIS ON. RootEncoder's enableVideoStabilization() hard-codes EIS mode 1, so cinematic goes
     * through the reflected builder ([Camera2Controls.setEisMode]) and clamps down to the best
     * supported mode on older devices; if reflection is unavailable it degrades to EIS 1.
     */
    private fun applyStabilization(cam: CameraSession, live: Boolean) {
        CricrelayLog.d("applyStabilization level=$stabilizationLevel live=$live")
        try {
            when (stabilizationLevel) {
                0 -> {
                    cam.disableVideoStabilization()
                    runCatching { cam.disableOpticalVideoStabilization() }
                }
                1 -> {
                    cam.enableVideoStabilization()
                    runCatching { cam.enableOpticalVideoStabilization() }
                }
                2 -> {
                    if (!cam.setEisMode(2, live)) {
                        cam.enableVideoStabilization()
                    }
                    runCatching { cam.enableOpticalVideoStabilization() }
                }
            }
        } catch (_: Exception) {
        }
    }

    /** One-time diagnostic so a debug run shows whether the correct path (not fallback) is live. */
    private fun logReflectStateOnce(cam: CameraSession) {
        if (reflectOkLogged) return
        reflectOkLogged = true
        CricrelayLog.d("Camera2Controls.reflectOk=${cam.reflectOk()}")
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
                // Surface gone while live — keep the encoder fed via the offscreen GL interface
                // instead of letting the SurfaceView's GL thread starve (the freeze bug).
                onEnterBackground()
                return@runOnMain
            }
            applyIntent(StreamPhasePolicy.Intent.Release)
        }
    }

    /**
     * Screen locked / app backgrounded without PiP: swap the live encoder from the on-screen
     * SurfaceView to RootEncoder's offscreen GL interface so frames keep flowing. No-op unless
     * we're actually streaming. Filters live on the glInterface, so they must be re-attached
     * after the swap — [reattachBurnInsAfterSwap] does that.
     */
    fun onEnterBackground() {
        runOnMain {
            val cam = camera ?: return@runOnMain
            if (!cam.isStreaming || backgroundRendering) return@runOnMain
            val ctx = appContext ?: return@runOnMain
            try {
                overlayCompositor.stopOverlayRefresh()
                cam.replaceView(ctx)
                applyIntent(StreamPhasePolicy.Intent.EnterBackground)
                surfaceValid = false
                dropStaleGlFilterRefs()
                CricrelayLog.d("onEnterBackground: encoder -> offscreen GL")
            } catch (e: Exception) {
                CricrelayLog.w("onEnterBackground replaceView failed: ${e.message}")
                return@runOnMain
            }
            reattachBurnInsAfterSwap()
        }
    }

    /**
     * Foreground / preview surface restored: swap the live encoder back to the on-screen view.
     * If no valid surface is available yet, defer to [onPreviewSurfaceReady] which retries once the
     * SurfaceView reports a usable size.
     */
    fun onExitBackground() {
        // Foreground is the one state where a previously refused keep-alive start (remote Go
        // Live from the background) is guaranteed startable — re-assert before the next lock.
        ensureKeepAliveService()
        runOnMain {
            if (!backgroundRendering) return@runOnMain
            if (camera?.isStreaming != true) {
                // Phase says live-in-background but the camera isn't streaming — the session
                // ended without a clean stop, so reconcile the phase instead of leaving a
                // stale Live behind.
                cancelRestoreRetry()
                applyIntent(StreamPhasePolicy.Intent.Stop)
                return@runOnMain
            }
            val view = openGlView ?: return@runOnMain
            if (!isPreviewSurfaceValid(view) || view.width < 64 || view.height < 64) {
                view.post { onPreviewSurfaceReady() }
                return@runOnMain
            }
            restoreOnViewRendering(view)
        }
    }

    private fun restoreOnViewRendering(view: OpenGlView) {
        val cam = camera ?: return
        if (!cam.isStreaming) {
            cancelRestoreRetry()
            applyIntent(StreamPhasePolicy.Intent.Stop)
            return
        }
        // The lockscreen rotation / AOD can hand us a valid-looking surface while the display
        // is dark; accepting it flaps camera + EGL every few seconds of a lock and sprays
        // garbage frames into the broadcast. Stay parked offscreen until the operator is
        // actually looking at the screen. onStart / surface-ready fire while the keyguard is
        // still dismissing, so a deferral here must arm its own retry — nothing else fires
        // after the unlock animation completes.
        if (!StreamLifecyclePolicy.shouldRestoreOnView(isDeviceInteractive(), isKeyguardLocked())) {
            CricrelayLog.d("restoreOnViewRendering deferred: screen off / keyguard locked")
            scheduleRestoreRetry()
            return
        }
        try {
            cam.replaceView(view)
            applyIntent(StreamPhasePolicy.Intent.ExitBackground)
            surfaceValid = true
            dropStaleGlFilterRefs()
            CricrelayLog.d("onExitBackground: encoder -> on-screen view")
        } catch (e: Exception) {
            CricrelayLog.w("restoreOnViewRendering replaceView failed: ${e.message}")
            return
        }
        cancelRestoreRetry()
        activity?.let { CameraPreviewHost.elevateComposeUi(it) }
        reattachBurnInsAfterSwap()
    }

    /**
     * Arm the deferred-restore retry: an [Intent.ACTION_USER_PRESENT] receiver (fires the
     * moment the keyguard is really gone) plus a slow poll as a safety net. The retry funnels
     * back through [onExitBackground], so all the usual guards apply and a still-locked
     * keyguard simply re-arms via the deferred branch above.
     */
    private fun scheduleRestoreRetry() {
        registerUserPresentReceiver()
        if (restoreRetryRunnable != null) return
        val runnable = object : Runnable {
            override fun run() {
                restoreRetryRunnable = null
                onExitBackground()
            }
        }
        restoreRetryRunnable = runnable
        mainHandler.postDelayed(runnable, RESTORE_RETRY_MS)
    }

    private fun registerUserPresentReceiver() {
        if (userPresentReceiver != null) return
        val ctx = appContext ?: return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_USER_PRESENT) return
                CricrelayLog.d("user present: retrying preview restore")
                onExitBackground()
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(
                    receiver,
                    IntentFilter(Intent.ACTION_USER_PRESENT),
                    Context.RECEIVER_NOT_EXPORTED,
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_PRESENT))
            }
            userPresentReceiver = receiver
        } catch (e: Exception) {
            CricrelayLog.w("user-present receiver failed: ${e.message}")
        }
    }

    private fun cancelRestoreRetry() {
        restoreRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        restoreRetryRunnable = null
        val receiver = userPresentReceiver ?: return
        userPresentReceiver = null
        try {
            appContext?.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
    }

    /** After a glInterface swap the scoreboard + watermark filters are gone — rebuild them. */
    private fun reattachBurnInsAfterSwap() {
        mainHandler.postDelayed({
            val cam = camera ?: return@postDelayed
            if (cam.isStreaming != true) return@postDelayed
            ensureWatermarkFilter()
            sponsorLayer.ensure()
            if (overlayUrl.isNotEmpty() && !streamPaused) {
                overlayCompositor.reattachAfterSwap()
            }
        }, 300)
    }

    /** Effective encoded frame size (post rotation swap) — used for the PiP aspect ratio. */
    fun currentStreamAspect(): Pair<Int, Int> {
        val w = if (streamIsPortrait) streamHeight else streamWidth
        val h = if (streamIsPortrait) streamWidth else streamHeight
        return w.coerceAtLeast(1) to h.coerceAtLeast(1)
    }

    /** System low memory — pause expensive overlay capture until restored. */
    fun onMemoryPressure() {
        runOnMain {
            overlayPausedForMemory = true
            overlayCompositor.stopOverlayRefresh()
            overlayCompositor.recycleOverlayBitmap()
        }
    }

    fun onMemoryRestored() {
        runOnMain {
            if (!overlayPausedForMemory) return@runOnMain
            overlayPausedForMemory = false
            if (camera?.isStreaming == true && !streamPaused && overlayUrl.isNotEmpty()) {
                overlayCompositor.startOverlayRefresh()
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

    private fun onThermalStatusChanged(status: Int) {
        overlayRefreshMs = when {
            status >= PowerManager.THERMAL_STATUS_SEVERE ->
                (DeviceCapabilities.overlayRefreshMs(deviceTier) * 2.5).toLong().coerceAtMost(3500L)
            status >= PowerManager.THERMAL_STATUS_MODERATE ->
                (DeviceCapabilities.overlayRefreshMs(deviceTier) * 1.5).toLong().coerceAtMost(2500L)
            else -> DeviceCapabilities.overlayRefreshMs(deviceTier)
        }
        emit("thermal", status.toString())
    }

    /** Manual mitigation for the overheat banner's "Lower quality" button. */
    fun stepDownQuality() {
        // TODO(spike): RootEncoder 2.4.8 live-bitrate API — stop+restart if unavailable.
    }

    fun attachView(view: OpenGlView, act: Activity) {
        refreshDeviceTier(act.applicationContext)
        thermalMonitor.register(act.applicationContext)
        appContext = act.applicationContext
        audioManager = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        activity = act
        if (openGlView === view && camera != null) {
            // Same view re-attached (e.g. returning from background) — restore on-view rendering.
            if (backgroundRendering) onExitBackground()
            return
        }
        if (camera?.isStreaming == true) {
            // A live stream must survive a preview re-bind (returning from lock / PiP / navigation).
            // Never releaseCamera() here — that stops RTMP. Re-point the encoder at the new view.
            openGlView = view
            view.setBackgroundColor(Color.TRANSPARENT)
            view.setAspectRatioMode(AspectRatioMode.Fill)
            applyIntent(StreamPhasePolicy.Intent.EnterBackground)
            onExitBackground()
            return
        }
        if (openGlView !== view) {
            releaseCamera()
        }
        openGlView = view
        view.setBackgroundColor(Color.TRANSPARENT)
        view.setAspectRatioMode(AspectRatioMode.Fill)
        if (camera == null) {
            camera = try {
                RtmpCameraSession(view, this)
            } catch (e: Exception) {
                emit(StreamCaptureService.EVENT_ERROR, "Camera init failed: ${e.message ?: "unknown"}")
                null
            }
        }
        overlayCompositor.resumeOverlayPreviewIfNeeded()
    }

    fun detachView(view: OpenGlView) {
        if (openGlView !== view) return
        if (camera?.isStreaming == true) {
            // Preview surface going away while live (navigated away / Compose disposed):
            // keep broadcasting from the offscreen GL interface instead of stopping RTMP.
            runOnMainSync {
                onEnterBackground()
                openGlView = null
            }
            return
        }
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
        // width/height/bitrate <= 0 mean "auto": pick by device tier (HIGH phones capture and
        // stream 1080p; the camera session itself runs at this size, so preview sharpness and
        // stream quality both track it).
        val reqWidth = if (width > 0) width else DeviceCapabilities.defaultStreamWidth(deviceTier)
        val reqHeight = if (height > 0) height else DeviceCapabilities.defaultStreamHeight(deviceTier)
        val reqBitrate = if (bitrate > 0) bitrate else DeviceCapabilities.defaultStreamBitrate(deviceTier)
        streamWidth = reqWidth.coerceIn(640, MAX_WIDTH)
        streamHeight = reqHeight.coerceIn(360, MAX_HEIGHT)
        streamFps = fps.coerceIn(24, 30)
        streamBitrate = reqBitrate.coerceIn(800000, MAX_BITRATE)
        var ok = false
        try {
            runOnMainSync { ok = preparePreviewOnMain() }
        } catch (_: Exception) {
            return false
        }
        return ok
    }

    /** True when this device's tier defaults to 1080p capture. */
    fun supports1080p(): Boolean = deviceTier == DeviceCapabilities.Tier.HIGH

    /**
     * Re-prepare the pipeline at an explicit quality right before Go Live (pre-stream only —
     * resolution is fixed once live). No-op when the requested quality is already prepared.
     */
    fun ensurePreparedQuality(width: Int, height: Int, bitrateBps: Int): Boolean {
        if (camera?.isStreaming == true) return false
        var alreadyPrepared = false
        runOnMainSync {
            alreadyPrepared = encoderPrepared && camera?.isOnPreview == true &&
                streamWidth == width && streamHeight == height && streamBitrate == bitrateBps
        }
        if (alreadyPrepared) return true
        CricrelayLog.d("ensurePreparedQuality: re-preparing at ${width}x$height @ ${bitrateBps / 1000}kbps")
        return resetPreviewForOrientation(width, height, streamFps, bitrateBps, streamRotation)
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
            applyIntent(StreamPhasePolicy.Intent.Release)
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
                overlayCompositor.startPreviewOverlayPush()
            } else {
                overlayCompositor.stopPreviewOverlayPush()
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

    /**
     * Drop the sensor override so [currentSurfaceRotation] falls back to the display rotation.
     * Used when the studio locks the activity orientation: the locked display is then the truth,
     * and the next surfaceChanged re-prepares against it.
     */
    fun clearDeviceOrientation() {
        runOnMain {
            sensorSurfaceRotation = -1
            if (camera?.isStreaming == true) return@runOnMain
            val act = activity ?: return@runOnMain
            val enc = encoderRotationForSurface(displayRotationDegrees(act))
            if (enc != streamRotation || !encoderPrepared || camera?.isOnPreview != true) {
                updatePreviewRotation(enc)
            }
        }
    }

    fun isFocusLocked(): Boolean = focusLocked

    fun unlockFocus(): Boolean {
        var ok = false
        runOnMainSync {
            focusLocked = false
            try {
                ok = camera?.enableAutoFocus() == true
            } catch (_: Exception) {
                ok = false
            }
        }
        return ok
    }

    /**
     * Freeze autofocus at its current (converged) distance so a fielder, umpire, or passer-by
     * crossing between the camera and the pitch can't pull focus off the strip. The operator
     * frames + taps the pitch (AF converges), then locks — the lens is held at the exact
     * converged distance. At long range the depth of field comfortably spans both ends of the
     * pitch, so one locked distance keeps the whole strip sharp. [unlockFocus] resumes AF.
     *
     * Uses [Camera2Controls.lockFocusAtCurrentDistance] (reads LENS_FOCUS_DISTANCE from a capture
     * result, then AF_MODE_OFF at that distance). RootEncoder's disableAutoFocus() alone sets AF
     * off WITHOUT a lens distance, which resets the lens to the builder default (infinity) and
     * visibly loses the tapped focus — kept only as the reflection-unavailable fallback.
     */
    fun lockFocus(): Boolean {
        var ok = false
        runOnMainSync {
            val cam = camera ?: return@runOnMainSync
            ok = cam.lockFocusAtCurrentDistance()
            if (!ok) {
                try {
                    ok = cam.disableAutoFocus()
                } catch (_: Exception) {
                    ok = false
                }
            }
            focusLocked = ok
        }
        return ok
    }

    /**
     * Tap to focus at a point. Tapping always releases an existing lock so the operator can
     * re-aim and re-lock. Runs a correct sensor-space Camera2 focus ([Camera2Controls.tapToFocus]:
     * one-shot converge-and-hold with AF+AE metering at the tap); falls back to RootEncoder's
     * tapToFocus(MotionEvent) only if reflection is unavailable. Returns the focus result and the
     * resulting lock state.
     */
    fun tapToFocusAt(viewWidth: Int, viewHeight: Int, x: Float, y: Float): Map<String, Any> {
        var focused = false
        runOnMainSync {
            val cam = camera ?: return@runOnMainSync
            val view = openGlView ?: return@runOnMainSync
            val w = if (viewWidth > 0) viewWidth else view.width
            val h = if (viewHeight > 0) viewHeight else view.height
            if (w < 1 || h < 1) return@runOnMainSync

            val px = x.coerceIn(0f, w.toFloat())
            val py = y.coerceIn(0f, h.toFloat())

            if (focusLocked) {
                try {
                    cam.enableAutoFocus()
                } catch (_: Exception) {
                }
                focusLocked = false
            }

            logReflectStateOnce(cam)
            focused = cam.tapToFocusSensor(w, h, px, py, frontFacing = false)
            if (!focused) {
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
            }
        }
        return mapOf("focused" to focused, "locked" to focusLocked)
    }

    private fun resetFocusState() {
        focusLocked = false
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
                if (backgroundRendering && camera?.isStreaming == true) {
                    // Returning from offscreen rendering: swap the live encoder back to this view.
                    restoreOnViewRendering(gl)
                    return@runOnMain
                }
                if (prepareInFlight || camera?.isStreaming == true) return@runOnMain
                // Don't short-circuit when the device orientation changed since the last
                // prepare — a configChanges rotation resizes the surface in place, and
                // this callback is the only signal. preparePreviewOnMain handles the
                // stop-and-reprepare when the rotation differs.
                if (encoderPrepared && camera?.isOnPreview == true &&
                    preparedVideoRotation == encoderRotationForSurface(currentSurfaceRotation())
                ) {
                    return@runOnMain
                }
                val ok = preparePreviewOnMain()
                if (!ok) {
                    CricrelayLog.w("onPreviewSurfaceReady: preparePreviewOnMain not ready, will retry on next surface event")
                }
                if (ok) {
                    emit(StreamCaptureService.EVENT_PREVIEW_READY, "${streamWidth}x${streamHeight}")
                    CricrelayLog.d("preview ready ${streamWidth}x${streamHeight} onPreview=${camera?.isOnPreview}")
                    activity?.let { CameraPreviewHost.elevateComposeUi(it) }
                    overlayCompositor.resumeOverlayPreviewIfNeeded()
                    // Re-establish the watermark after every (re)prepare — covers rotation
                    // and streams with no scoreboard (where the capture loop never runs).
                    ensureWatermarkFilter()
                    sponsorLayer.ensure()
                }
            }
        }
        previewSurfaceRunnable = runnable
        mainHandler.postDelayed(runnable, 80)
    }

    private fun encodedCanvasWidth(): Int =
        if (streamIsPortrait) streamHeight else streamWidth

    private fun encodedCanvasHeight(): Int =
        if (streamIsPortrait) streamWidth else streamHeight

    fun updateOverlay(url: String, layout: OverlayLayout) {
        runOnMain {
            if (url.isNotEmpty()) {
                overlayUrl = url
            }
            overlayLayout = layout
            overlayCompositor.syncOverlayCaptureWidth()
            ensureWatermarkFilter()
            sponsorLayer.ensure()
            if (overlayUrl.isEmpty()) return@runOnMain
            overlayCompositor.applyOverlayConfig()
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
        if (!endpoint.startsWith("rtmp://") && !endpoint.startsWith("rtmps://")) {
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

    // Device display/lock state for StreamLifecyclePolicy.shouldRestoreOnView. On any read
    // failure fall back to the pre-gate behavior (restore allowed) rather than risk leaving
    // the encoder parked offscreen with the operator watching a frozen preview.
    private fun isDeviceInteractive(): Boolean {
        val ctx = appContext ?: return true
        return try {
            (ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive != false
        } catch (_: Exception) {
            true
        }
    }

    private fun isKeyguardLocked(): Boolean {
        val ctx = appContext ?: return false
        return try {
            (ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true
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
                applyIntent(StreamPhasePolicy.Intent.Release)
                resetFocusState()
            } else {
                ensureWatermarkFilter()
                sponsorLayer.ensure()
                return true
            }
        }
        if (cam.isStreaming) return true
        if (prepareInFlight) {
            view.postDelayed({ onPreviewSurfaceReady() }, 120)
            return false
        }

        // Every (re)prepare must encode the orientation the phone is held in NOW.
        // Without this, the surfaceChanged path after a configChanges rotation
        // prepares with the rotation captured when the studio was first opened.
        if (activity != null) applyActivityRotation()

        if (encoderPrepared && !cam.isOnPreview) {
            if (preparedVideoRotation == streamRotation) {
                return try {
                    cam.startPreview()
                    cam.isOnPreview
                } catch (_: Exception) {
                    false
                }
            }
            applyIntent(StreamPhasePolicy.Intent.Release)
        }

        // Reaching a full prepare while the camera still claims isOnPreview means the preview
        // surface died and came back (lock/unlock inside the studio): OpenGlView.surfaceDestroyed
        // stopped the view's GL thread and nothing restarts it implicitly (2.4.8 bytecode —
        // surfaceCreated/surfaceChanged never touch the render loop), while isOnPreview stays
        // true because stopPreview was never called. Without this stop, the startPreview below
        // is skipped and the "successful" prepare renders into a dead surface: camera connected,
        // engine reporting onPreview=true, screen black. Stop first so prepare + startPreview
        // run the same cold path as a rotation change.
        if (cam.isOnPreview) {
            dropStaleGlFilterRefs()
            try {
                cam.stopPreview()
            } catch (_: Exception) {
            }
            resetFocusState()
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
                applyIntent(StreamPhasePolicy.Intent.Release)
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
                applyIntent(StreamPhasePolicy.Intent.Release)
                CricrelayLog.e("prepareVideo FAILED on all tiers — preview stays black")
                return false
            }
            if (stabilizationLevel > 0 && deviceTier != DeviceCapabilities.Tier.LOW) {
                // Builder-only at prepare (no setRepeatingRequest): the preview hasn't started,
                // so the value bakes into the first repeating request — same as RootEncoder.
                applyStabilization(cam, live = false)
            }
            applyIntent(StreamPhasePolicy.Intent.Prepare)
            preparedVideoRotation = streamRotation
            if (!cam.isOnPreview) {
                cam.startPreview()
            }
            val ready = cam.isOnPreview
            if (ready) {
                // RootEncoder only creates its builder/session when the camera opens
                // (startPreview) — the pre-prepare pass above could bake at most EIS 1 via
                // RootEncoder's flag replay. Now that the reflected builder exists: switch the
                // HAL to its video-tuned processing path, top Cinematic up to
                // PREVIEW_STABILIZATION(2), and log the truthful reflect state.
                cam.applyCaptureQuality(live = true)
                if (stabilizationLevel == 2 && deviceTier != DeviceCapabilities.Tier.LOW) {
                    applyStabilization(cam, live = true)
                }
                logReflectStateOnce(cam)
            }
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
                overlayCompositor.syncOverlayCaptureWidth()
                ensureWatermarkFilter()
                sponsorLayer.ensure()
            }
            ready
        } catch (t: Exception) {
            applyIntent(StreamPhasePolicy.Intent.Release)
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
        val reqBitrate = streamBitrate.coerceIn(800000, MAX_BITRATE)
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
                applyIntent(StreamPhasePolicy.Intent.Release)
                resetFocusState()
            } catch (_: Exception) {
            }
            if (!preparePreviewOnMain()) {
                throw IllegalStateException("Camera preview not ready — wait for preview before Go Live")
            }
        }

        overlayCompositor.stopPreviewOverlayPush()
        overlayCompositor.stopPreviewOverlayRefresh()
        emit(StreamCaptureService.EVENT_PREPARING, "Starting stream…")
        if (keepScreenOnDuringStream) {
            openGlView?.keepScreenOn = true
        }
        // Arm the self-heal: a ground-side network blip must reconnect on its own — the
        // operator is filming, not watching the phone.
        reconnectAttempt = 0
        burnInWarned.clear()
        keepAliveWarned = false
        runCatching { cam.setReTries(StreamReconnectPolicy.MAX_ATTEMPTS) }
        try {
            cam.startStream(endpoint)
            applyIntent(StreamPhasePolicy.Intent.GoLive)
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
        applyIntent(StreamPhasePolicy.Intent.Release)
        resetFocusState()
        if (!preparePreviewOnMain()) {
            CricrelayLog.w("resetEncoderAfterRtmpStop: preview re-prepare failed")
        }
    }

    private fun stopStreamInternal() {
        pendingOverlayAfterConnect = false
        cancelRestoreRetry()
        applyIntent(StreamPhasePolicy.Intent.Stop)
        // Disarm the self-heal: an intentional stop must not race a pending reconnect.
        reconnectAttempt = 0
        runCatching { camera?.setReTries(0) }
        bitrateAdapter = null
        statsListener?.invoke(null)
        removePauseBlackFilter()
        overlayCompositor.stopOverlayRefresh()
        overlayCompositor.clearOverlayFilter()
        clearWatermarkFilter()
        overlayCompositor.recycleOverlayBitmap()
        openGlView?.keepScreenOn = false
        abandonStreamAudioFocus()
        resetEncoderAfterRtmpStop()
        if (overlayUrl.isNotEmpty()) {
            overlayCompositor.startPreviewOverlayRefresh()
        }
    }

    private fun pauseStreamInternal() {
        val cam = camera ?: return
        if (!cam.isStreaming || streamPaused) return
        applyIntent(StreamPhasePolicy.Intent.Pause)
        overlayCompositor.stopOverlayRefresh()
        try {
            cam.disableAudio()
        } catch (_: Exception) {
        }
        try {
            if (pauseBlackFilter == null) {
                val filter = BlackFilterRender()
                pauseBlackFilter = filter
                cam.addFilter(filter)
            }
        } catch (_: Exception) {
        }
        emit(StreamCaptureService.EVENT_PAUSED, "")
    }

    private fun resumeStreamInternal() {
        val cam = camera ?: return
        if (!cam.isStreaming || !streamPaused) return
        applyIntent(StreamPhasePolicy.Intent.Resume)
        removePauseBlackFilter()
        try {
            if (!micMuted) cam.enableAudio()
        } catch (_: Exception) {
        }
        if (overlayUrl.isNotEmpty() && overlayCompositor.hasFilter) {
            overlayCompositor.startOverlayRefresh()
        }
        emit(StreamCaptureService.EVENT_RESUMED, "")
    }

    fun setMicMuted(muted: Boolean) {
        micMuted = muted
        val cam = camera ?: return
        if (streamPaused) return
        try {
            if (muted) cam.disableAudio() else cam.enableAudio()
        } catch (_: Exception) {
        }
    }

    fun isMicMuted(): Boolean = micMuted

    fun setSponsorLayer(enabled: Boolean, logoUrls: List<String>) {
        runOnMain {
            overlayLayout = overlayLayout.copy(
                sponsorEnabled = enabled,
                sponsorLogoUrls = logoUrls,
                sponsorLogoUrl = logoUrls.firstOrNull().orEmpty(),
            )
            sponsorLayer.ensure()
        }
    }

    private fun removePauseBlackFilter() {
        val cam = camera ?: return
        pauseBlackFilter?.let { filter ->
            try {
                cam.removeFilter(filter)
            } catch (_: Exception) {
            }
        }
        pauseBlackFilter = null
    }

    private fun releaseCamera() {
        appContext?.let { thermalMonitor.unregister(it) }
        cancelRestoreRetry()
        overlayCompositor.stopPreviewOverlayPush()
        if (camera?.isStreaming == true) {
            stopStreamInternal()
        } else {
            overlayCompositor.stopPreviewOverlayRefresh()
            overlayCompositor.recycleOverlayBitmap()
            overlayCompositor.clearOverlayFilter()
            clearWatermarkFilter()
            resetFocusState()
            try {
                camera?.stopPreview()
            } catch (_: Exception) {
            }
            applyIntent(StreamPhasePolicy.Intent.Release)
            surfaceValid = false
        }
        // Keep overlay WebView alive across studio navigation to avoid reload flicker.
        camera = null
        openGlView = null
    }

    /** Full teardown — ViewModel cleared or streaming session ended. */
    fun destroyOverlayCapture() {
        runOnMain { overlayCompositor.destroyOverlayCapture() }
    }

    private fun attachOverlayAfterConnect() {
        if (!pendingOverlayAfterConnect) return
        pendingOverlayAfterConnect = false
        mainHandler.postDelayed({
            try {
                if (overlayUrl.isNotEmpty()) {
                    overlayCompositor.ensureOverlayCapture()?.loadUrl(
                        OverlayThemeBridge.urlWithTheme(
                            overlayUrl,
                            overlayLayout.theme,
                            overlayLayout.bowlingIslandEnabled,
                        ),
                    )
                }
                overlayCompositor.startOverlayRefresh()
            } catch (e: Exception) {
                emit(StreamCaptureService.EVENT_ERROR, "Scoreboard overlay failed: ${e.message}")
            }
        }, 800)
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
        overlayCompositor.dropStaleRefs()
        sponsorLayer.dropStaleRefs()
        appliedWatermarkText = null
    }

    private const val WATERMARK_TEXT_SIZE = 34f
    // Crop-safe geometry (heights, edges) lives in WatermarkSpriteLayout — shared with the
    // sponsor logos in SponsorLayer.

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
        val h = WatermarkSpriteLayout.BMP_HEIGHT
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
                cam.addFilter(it)
                watermarkFilter = it
            }
        } catch (e: Exception) {
            CricrelayLog.w("Watermark filter failed: ${e.message}")
            warnBurnInOnce("watermark", "Watermark could not be added to the stream.")
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
                cam.removeFilter(filter)
            } catch (_: Exception) {
            }
        }
        watermarkFilter = null
        appliedWatermarkText = null
    }

    private fun applyWatermarkSprite(filter: ImageObjectFilterRender) {
        val canvasW = encodedCanvasWidth()
        val canvasH = encodedCanvasHeight()
        // No setDefaultScale: it reads the bitmap RootEncoder already recycled after the GL
        // upload (logging a recycle()'d-bitmap warning), and its result is dead anyway — the
        // scale is fully overwritten by setScale/setPosition below.
        val sprite = WatermarkSpriteLayout.compute(
            WatermarkSpriteLayout.Params(
                canvasW = canvasW,
                canvasH = canvasH,
                bitmapWidth = watermarkBitmapWidth(appliedWatermarkText ?: ""),
                bitmapHeight = WatermarkSpriteLayout.BMP_HEIGHT,
                heightPct = WatermarkSpriteLayout.HEIGHT_PCT,
                rightEdgePct = WatermarkSpriteLayout.RIGHT_EDGE_PCT,
                topPct = WatermarkSpriteLayout.TOP_PCT,
                maxWidthPct = WatermarkSpriteLayout.MAX_WIDTH_PCT,
            ),
        )
        filter.setScale(sprite.scaleX, sprite.scaleY)
        filter.setPosition(sprite.positionX, sprite.positionY)
    }

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

    private fun emit(event: String, message: String) {
        statusListener?.invoke(event, message)
    }

    /**
     * A burn-in (scoreboard / watermark / sponsor) failing must reach the operator — a silent
     * catch means they find out from the VOD after the match. One warning per element per
     * broadcast; [burnInWarned] is cleared at stream start.
     */
    private fun warnBurnInOnce(key: String, message: String) {
        mainHandler.post {
            if (burnInWarned.add(key)) {
                CricrelayLog.w("burn-in degraded [$key]: $message")
                emit(StreamCaptureService.EVENT_OVERLAY_WARNING, message)
            }
        }
    }

    /**
     * [StreamCaptureService] is the broadcast's cached-app-freezer exemption: without it the
     * OS freezes the whole process ~35s after the screen locks and viewers get dead air. The
     * Go Live start can be refused silently (a remote start_broadcast while the app is
     * backgrounded throws ForegroundServiceStartNotAllowedException on 12+), so re-assert it
     * whenever RTMP (re)connects and when the app returns to the foreground — the moment a
     * refused start becomes startable again.
     */
    fun ensureKeepAliveService() {
        mainHandler.post {
            val ctx = appContext ?: return@post
            if (!isStreaming || StreamCaptureService.isForegroundActive) return@post
            CricrelayLog.w("live without keep-alive service — restarting")
            if (!StreamCaptureService.start(ctx)) {
                warnKeepAliveOnce()
                return@post
            }
            mainHandler.postDelayed({
                if (isStreaming && !StreamCaptureService.isForegroundActive) warnKeepAliveOnce()
            }, KEEPALIVE_VERIFY_MS)
        }
    }

    private fun warnKeepAliveOnce() {
        if (keepAliveWarned) return
        keepAliveWarned = true
        CricrelayLog.e("keep-alive service could not be started while live")
        emit(
            StreamCaptureService.EVENT_KEEPALIVE_WARNING,
            "Broadcast protection unavailable — keep the app open and the screen on, " +
                "or the stream may freeze.",
        )
    }

    override fun onConnectionStarted(url: String) {
        emit(StreamCaptureService.EVENT_CONNECTING, url)
    }

    override fun onConnectionSuccess() {
        val wasReconnect = reconnectAttempt > 0
        reconnectAttempt = 0
        // Refill the retry budget so a later outage gets the full schedule again.
        runCatching { camera?.setReTries(StreamReconnectPolicy.MAX_ATTEMPTS) }
        // Follow the real network: RootEncoder reports the sent bitrate once a second
        // (onNewBitrate); the adapter steps the encoder toward the prepared maximum when there
        // is headroom and cuts it on congestion (setVideoBitrateOnFly = live MediaCodec param).
        // Resolution stays fixed for the session — mid-stream re-prepare is the golden-path crash.
        currentTargetBitrate = streamBitrate
        bitrateAdapter = BitrateAdapter { bitrate ->
            currentTargetBitrate = bitrate
            try {
                camera?.setVideoBitrateOnFly(bitrate)
            } catch (_: Exception) {
            }
        }.apply { setMaxBitrate(streamBitrate) }
        attachOverlayAfterConnect()
        // A live RTMP session without the foreground service is one screen lock away from
        // the freezer — verify (and if needed restore) it on every connect and reconnect.
        ensureKeepAliveService()
        emit(StreamCaptureService.EVENT_CONNECTED, if (wasReconnect) "Reconnected" else "")
    }

    override fun onConnectionFailed(reason: String) {
        val cam = camera
        val detail = reason.ifBlank { "RTMP connection failed" }
        // Self-heal first: reTry keeps the encoder running and re-dials the socket, so a network
        // blip at the ground resumes the broadcast without a manual Go Live. RootEncoder refuses
        // to retry malformed endpoints, so those fall straight through to the teardown below.
        if (cam != null) {
            val delayMs = StreamReconnectPolicy.backoffMs(reconnectAttempt)
            val retrying = runCatching { cam.reTry(delayMs, reason) }.getOrDefault(false)
            if (retrying) {
                reconnectAttempt++
                CricrelayLog.w("RTMP reconnect $reconnectAttempt/${StreamReconnectPolicy.MAX_ATTEMPTS} in ${delayMs}ms: $detail")
                emit(
                    StreamCaptureService.EVENT_RECONNECTING,
                    "Connection lost — reconnecting ($reconnectAttempt of ${StreamReconnectPolicy.MAX_ATTEMPTS})…",
                )
                return
            }
        }
        pendingOverlayAfterConnect = false
        endLostStream(detail)
    }

    /**
     * Out of retries (or the failure isn't retryable): tear the session down so the UI can't sit
     * on a dead LIVE badge, then tell the operator the broadcast is gone. The teardown re-prepares
     * the preview, so the next Go Live starts clean.
     */
    private fun endLostStream(detail: String) {
        mainHandler.post {
            if (camera?.isStreaming == true) {
                stopStreamInternal()
            }
            emit(StreamCaptureService.EVENT_STREAM_LOST, detail)
        }
    }

    override fun onNewBitrate(bitrate: Long) {
        val cam = camera ?: return
        if (!cam.isStreaming) return
        // >20% of the RTMP send cache in use = the uplink can't keep up right now.
        val congested = runCatching { cam.hasCongestion(20f) }.getOrDefault(false)
        bitrateAdapter?.adaptBitrate(bitrate, congested)
        val stats = StreamStats(
            sentBitrateBps = bitrate,
            targetBitrateBps = currentTargetBitrate,
            maxBitrateBps = streamBitrate,
            width = encodedCanvasWidth(),
            height = encodedCanvasHeight(),
            fps = streamFps,
            congested = congested,
            droppedVideoFrames = runCatching { cam.getDroppedVideoFrames() }.getOrDefault(0L),
        )
        mainHandler.post { statsListener?.invoke(stats) }
    }

    override fun onDisconnect() {
        pendingOverlayAfterConnect = false
        bitrateAdapter = null
        mainHandler.post { statsListener?.invoke(null) }
        emit(StreamCaptureService.EVENT_DISCONNECTED, "")
    }

    override fun onAuthError() {
        pendingOverlayAfterConnect = false
        // Auth won't heal on its own — no retry; tear down so the encoder doesn't run against
        // a session the platform already rejected.
        endLostStream("Stream key rejected. Start the live event in Studio/dashboard first, then try again.")
    }

    override fun onAuthSuccess() {}
}
