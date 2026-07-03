package uk.co.cricrelay.stream

import android.content.Context
import android.util.Range
import android.view.MotionEvent
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.library.view.OpenGlView

/**
 * Thin seam over the RTMP camera pipeline (RootEncoder's RtmpCamera2) so [StreamCameraEngine]
 * and the burn-in layers ([OverlayCompositor], [SponsorLayer]) depend on an interface instead
 * of RootEncoder types. Pure indirection: every member maps 1:1 onto the RootEncoder (or
 * [Camera2Controls] reflection) call the engine made directly before the extraction — see
 * [RtmpCameraSession]. The engine's golden-path ordering constraints apply unchanged.
 */
internal interface CameraSession {

    /** RTMP connection callbacks, mirroring RootEncoder's ConnectChecker 1:1. */
    interface Listener {
        fun onConnectionStarted(url: String)
        fun onConnectionSuccess()
        fun onConnectionFailed(reason: String)
        fun onNewBitrate(bitrate: Long)
        fun onDisconnect()
        fun onAuthError()
        fun onAuthSuccess()
    }

    val isStreaming: Boolean
    val isOnPreview: Boolean

    /** Raw (pre-rotation-swap) prepared encoder dims — may throw before prepare, like RootEncoder. */
    val streamWidth: Int
    val streamHeight: Int

    // ---- Prepare / preview / stream lifecycle (golden path order lives in the engine) --------

    fun prepareAudio(
        bitrate: Int,
        sampleRate: Int,
        isStereo: Boolean,
        echoCanceler: Boolean,
        noiseSuppressor: Boolean,
    ): Boolean

    fun prepareVideo(width: Int, height: Int, fps: Int, bitrate: Int, rotation: Int): Boolean

    fun startPreview()

    fun stopPreview()

    fun startStream(endpoint: String)

    fun stopStream()

    /** Swap the encoder to offscreen GL rendering (screen locked / backgrounded). */
    fun replaceView(context: Context)

    /** Swap the encoder back onto an on-screen view. */
    fun replaceView(view: OpenGlView)

    // ---- GL filter chain (burn-ins) -----------------------------------------------------------

    fun addFilter(filter: BaseFilterRender)

    fun removeFilter(filter: BaseFilterRender)

    // ---- Stream client (reconnect / congestion) -----------------------------------------------

    fun setReTries(count: Int)

    fun reTry(delayMs: Long, reason: String): Boolean

    fun hasCongestion(percentUsed: Float): Boolean

    fun getDroppedVideoFrames(): Long

    fun setVideoBitrateOnFly(bitrate: Int)

    // ---- Audio ----------------------------------------------------------------------------------

    fun enableAudio()

    fun disableAudio()

    // ---- Zoom / focus ---------------------------------------------------------------------------

    fun setZoom(level: Float)

    val zoomRange: Range<Float>

    val zoom: Float

    fun enableAutoFocus(): Boolean

    fun disableAutoFocus(): Boolean

    /** RootEncoder's view-coordinate tap-to-focus — fallback when [tapToFocusSensor] can't run. */
    fun tapToFocus(event: MotionEvent): Boolean

    // ---- Stabilization --------------------------------------------------------------------------

    fun enableVideoStabilization()

    fun disableVideoStabilization()

    fun enableOpticalVideoStabilization()

    fun disableOpticalVideoStabilization()

    // ---- Camera2Controls reflection surface (false ⇒ caller falls back / degrades gracefully) --

    /** Explicit EIS mode (cinematic = PREVIEW_STABILIZATION 2); see [Camera2Controls.setEisMode]. */
    fun setEisMode(mode: Int, live: Boolean): Boolean

    /** Video-record capture intent + FAST NR/edge; see [Camera2Controls.applyCaptureQuality]. */
    fun applyCaptureQuality(live: Boolean): Boolean

    /** Freeze the lens at its converged distance; see [Camera2Controls.lockFocusAtCurrentDistance]. */
    fun lockFocusAtCurrentDistance(): Boolean

    /** Correct sensor-space tap-to-focus; see [Camera2Controls.tapToFocus]. */
    fun tapToFocusSensor(viewW: Int, viewH: Int, x: Float, y: Float, frontFacing: Boolean): Boolean

    /** True when the reflected builder + session are reachable (correct path, not fallback). */
    fun reflectOk(): Boolean
}
