package uk.co.cricrelay.stream

import android.content.Context
import android.util.Range
import android.view.MotionEvent
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView

/**
 * [CameraSession] over RootEncoder's [RtmpCamera2]. Every override is a 1:1 delegation to the
 * call [StreamCameraEngine] made directly before the extraction, and the [Camera2Controls]
 * reflection (sensor-space tap-to-focus, focus-distance lock, EIS mode 2, capture intent) is
 * contained here — RootEncoder specifics never reach the engine's boundary. Construction can
 * throw exactly like `RtmpCamera2(view, checker)` did; the engine's attachView try/catch owns
 * that failure.
 */
internal class RtmpCameraSession(
    view: OpenGlView,
    listener: CameraSession.Listener,
) : CameraSession {

    private val connectChecker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) = listener.onConnectionStarted(url)
        override fun onConnectionSuccess() = listener.onConnectionSuccess()
        override fun onConnectionFailed(reason: String) = listener.onConnectionFailed(reason)
        override fun onNewBitrate(bitrate: Long) = listener.onNewBitrate(bitrate)
        override fun onDisconnect() = listener.onDisconnect()
        override fun onAuthError() = listener.onAuthError()
        override fun onAuthSuccess() = listener.onAuthSuccess()
    }

    private val camera = RtmpCamera2(view, connectChecker)

    override val isStreaming: Boolean
        get() = camera.isStreaming

    override val isOnPreview: Boolean
        get() = camera.isOnPreview

    override val streamWidth: Int
        get() = camera.streamWidth

    override val streamHeight: Int
        get() = camera.streamHeight

    override fun prepareAudio(
        bitrate: Int,
        sampleRate: Int,
        isStereo: Boolean,
        echoCanceler: Boolean,
        noiseSuppressor: Boolean,
    ): Boolean = camera.prepareAudio(bitrate, sampleRate, isStereo, echoCanceler, noiseSuppressor)

    override fun prepareVideo(width: Int, height: Int, fps: Int, bitrate: Int, rotation: Int): Boolean =
        camera.prepareVideo(width, height, fps, bitrate, rotation)

    override fun startPreview() {
        camera.startPreview()
    }

    override fun stopPreview() {
        camera.stopPreview()
    }

    override fun startStream(endpoint: String) {
        camera.startStream(endpoint)
    }

    override fun stopStream() {
        camera.stopStream()
    }

    override fun replaceView(context: Context) {
        camera.replaceView(context)
    }

    override fun replaceView(view: OpenGlView) {
        camera.replaceView(view)
    }

    override fun addFilter(filter: BaseFilterRender) {
        camera.glInterface.addFilter(filter)
    }

    override fun removeFilter(filter: BaseFilterRender) {
        camera.glInterface.removeFilter(filter)
    }

    override fun setReTries(count: Int) {
        camera.streamClient.setReTries(count)
    }

    override fun reTry(delayMs: Long, reason: String): Boolean =
        camera.streamClient.reTry(delayMs, reason)

    override fun hasCongestion(percentUsed: Float): Boolean =
        camera.streamClient.hasCongestion(percentUsed)

    override fun getDroppedVideoFrames(): Long =
        camera.streamClient.getDroppedVideoFrames()

    override fun setVideoBitrateOnFly(bitrate: Int) {
        camera.setVideoBitrateOnFly(bitrate)
    }

    override fun enableAudio() {
        camera.enableAudio()
    }

    override fun disableAudio() {
        camera.disableAudio()
    }

    override fun setZoom(level: Float) {
        camera.setZoom(level)
    }

    override val zoomRange: Range<Float>
        get() = camera.zoomRange

    override val zoom: Float
        get() = camera.zoom

    override fun enableAutoFocus(): Boolean = camera.enableAutoFocus()

    override fun disableAutoFocus(): Boolean = camera.disableAutoFocus()

    override fun tapToFocus(event: MotionEvent): Boolean = camera.tapToFocus(event)

    override fun enableVideoStabilization() {
        camera.enableVideoStabilization()
    }

    override fun disableVideoStabilization() {
        camera.disableVideoStabilization()
    }

    override fun enableOpticalVideoStabilization() {
        camera.enableOpticalVideoStabilization()
    }

    override fun disableOpticalVideoStabilization() {
        camera.disableOpticalVideoStabilization()
    }

    override fun setEisMode(mode: Int, live: Boolean): Boolean =
        Camera2Controls.setEisMode(camera, mode, live)

    override fun applyCaptureQuality(live: Boolean): Boolean =
        Camera2Controls.applyCaptureQuality(camera, live)

    override fun lockFocusAtCurrentDistance(): Boolean =
        Camera2Controls.lockFocusAtCurrentDistance(camera)

    override fun tapToFocusSensor(viewW: Int, viewH: Int, x: Float, y: Float, frontFacing: Boolean): Boolean =
        Camera2Controls.tapToFocus(camera, viewW, viewH, x, y, frontFacing)

    override fun reflectOk(): Boolean = Camera2Controls.reflectOk(camera)
}
