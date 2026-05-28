package uk.co.cricrelay.stream



import android.app.Activity

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

    private var streamFps = 30

    private var streamBitrate = 2_500_000

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

        runOnMainSync {

            preparePreviewOnMain(width, height, fps, streamBitrate)

        }

    }



    fun updateOverlay(url: String, layout: OverlayLayout) {

        runOnMain {

            overlayUrl = url

            overlayLayout = layout

            overlayCapture?.loadUrl(url)

            applyOverlaySprite()

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

        var error: Exception? = null

        runOnMainSync {

            try {

                startStreamOnMain(rtmpUrl, streamKey, url, width, height, bitrate, fps, layout)

            } catch (e: Exception) {

                error = e

                emit(StreamCaptureService.EVENT_ERROR, e.message ?: "Stream start failed")

            }

        }

        error?.let { throw it }

    }



    fun stopStream() {

        runOnMain {

            stopOverlayRefresh()

            try {

                camera?.stopStream()

            } catch (_: Exception) {

            }

            previewPrepared = false

        }

    }



    fun release() {

        runOnMain {

            stopStream()

            stopOverlayRefresh()

            overlayCapture?.destroy()

            overlayCapture = null

            clearOverlayFilter()

            try {

                camera?.stopPreview()

            } catch (_: Exception) {

            }

            camera = null

            previewPrepared = false

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



    private fun runOnMain(block: () -> Unit) {

        if (Looper.myLooper() == Looper.getMainLooper()) {

            block()

        } else {

            mainHandler.post(block)

        }

    }



    /** Runs on the main thread and waits — Flutter must not return before prepare/start finishes. */

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

        if (!latch.await(20, TimeUnit.SECONDS)) {

            throw IllegalStateException("Camera operation timed out")

        }

    }



    private fun preparePreviewOnMain(width: Int, height: Int, fps: Int, bitrate: Int) {

        val cam = camera ?: return

        if (previewPrepared && streamWidth == width && streamHeight == height && streamFps == fps) {

            if (!cam.isOnPreview) {

                try {

                    cam.startPreview()

                } catch (_: Exception) {

                }

            }

            return

        }

        resetEncoder(cam, width, height, fps, bitrate)

        if (!cam.isOnPreview) {

            try {

                cam.startPreview()

            } catch (e: Exception) {

                emit(StreamCaptureService.EVENT_ERROR, "Camera preview failed: ${e.message}")

                return

            }

        }

        previewPrepared = true

        if (overlayUrl.isNotEmpty()) {

            overlayCapture?.loadUrl(overlayUrl)

        }

    }



    private fun startStreamOnMain(

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

        overlayLayout = layout

        overlayUrl = url

        val endpoint = StreamCaptureService.buildEndpoint(rtmpUrl, streamKey)

        if (!endpoint.startsWith("rtmp://")) {

            emit(StreamCaptureService.EVENT_ERROR, "Invalid RTMP URL")

            return

        }

        emit(StreamCaptureService.EVENT_PREPARING, endpoint)



        if (!cam.isStreaming) {

            resetEncoder(cam, width, height, fps, bitrate)

            try {

                if (!cam.isOnPreview) {

                    cam.startPreview()

                }

            } catch (e: Exception) {

                emit(StreamCaptureService.EVENT_ERROR, "Camera preview failed: ${e.message}")

                throw e

            }

            previewPrepared = true

        }



        if (url.isNotEmpty()) {

            overlayCapture?.loadUrl(url)

        }



        // Overlay is optional — never crash the stream if WebView/GL filter fails.

        try {

            ensureOverlayFilter()

            startOverlayRefresh()

        } catch (_: Exception) {

            stopOverlayRefresh()

        }



        if (!cam.isStreaming) {

            cam.startStream(endpoint)

        }

    }



    private fun resetEncoder(cam: RtmpCamera2, width: Int, height: Int, fps: Int, bitrate: Int) {

        stopOverlayRefresh()

        clearOverlayFilter()

        try {

            if (cam.isStreaming) cam.stopStream()

        } catch (_: Exception) {

        }

        try {

            if (cam.isOnPreview) cam.stopPreview()

        } catch (_: Exception) {

        }

        streamWidth = width

        streamHeight = height

        streamFps = fps

        streamBitrate = bitrate

        previewPrepared = false

        val audioOk = cam.prepareAudio(128 * 1024, 32000, true, false, false)

        val videoOk = cam.prepareVideo(width, height, fps, bitrate, 0)

        if (!audioOk || !videoOk) {

            throw IllegalStateException("Could not prepare camera/audio for stream")

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

        val filter = ImageObjectFilterRender()

        filter.setPosition(TranslateTo.BOTTOM)

        cam.glInterface.addFilter(filter)

        imageFilter = filter

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

                try {

                    captureAndApplyOverlay()

                } catch (_: Exception) {

                }

                mainHandler.postDelayed(this, 500)

            }

        }

        overlayRunnable = runnable

        mainHandler.postDelayed(runnable, 800)

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


