package uk.co.cricrelay.stream

import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.os.Handler
import android.os.Looper
import com.pedro.library.rtmp.RtmpCamera2
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Reaches past RootEncoder's convenience wrappers to the raw Camera2 CaptureRequest.Builder +
 * CameraCaptureSession so we can run a correct tap-to-focus and set the cinematic
 * (PREVIEW_STABILIZATION) EIS mode that RootEncoder 2.4.8's API can't express.
 *
 * Why: RootEncoder's own tapToFocus builds its metering rectangle from raw VIEW pixels (the HAL
 * needs sensor active-array coordinates) at metering weight 0 (ignored by the HAL) — so taps do
 * nothing. And enableVideoStabilization() hard-codes EIS mode 1, never PREVIEW_STABILIZATION (2).
 *
 * All reflection is guarded; every accessor returns null on failure so callers fall back to
 * RootEncoder's own (weaker) methods — this never crashes the stream. Field names are verified
 * against RootEncoder 2.4.8 (do not bump the dependency without re-checking them); the keep rules
 * in the app's proguard-rules.pro preserve them if minification is ever enabled.
 */
internal object Camera2Controls {

    private fun manager(cam: RtmpCamera2): Any? = try {
        val f = com.pedro.library.base.Camera2Base::class.java.getDeclaredField("cameraManager")
        f.isAccessible = true
        f.get(cam)
    } catch (_: Throwable) {
        null
    }

    private fun field(obj: Any, name: String): Any? = try {
        val f = obj.javaClass.getDeclaredField(name)
        f.isAccessible = true
        f.get(obj)
    } catch (_: Throwable) {
        null
    }

    private fun builder(cam: RtmpCamera2): CaptureRequest.Builder? =
        manager(cam)?.let { field(it, "builderInputSurface") as? CaptureRequest.Builder }

    private fun session(cam: RtmpCamera2): CameraCaptureSession? =
        manager(cam)?.let { field(it, "cameraCaptureSession") as? CameraCaptureSession }

    private fun handler(cam: RtmpCamera2): Handler? =
        manager(cam)?.let { field(it, "cameraHandler") as? Handler }

    private fun characteristics(cam: RtmpCamera2): CameraCharacteristics? =
        runCatching { cam.cameraCharacteristics }.getOrNull()

    /** True when the reflected builder + session are reachable (i.e. the correct path will run). */
    fun reflectOk(cam: RtmpCamera2): Boolean =
        builder(cam) != null && session(cam) != null

    // ---- Tap to focus (sensor-space, weighted, AF+AE, trigger cycle) ---------------------------

    /**
     * Correct Camera2 tap-to-focus: view→sensor active-array coordinate transform (per sensor
     * orientation), a metering rectangle at METERING_WEIGHT_MAX covering ~10% of the frame, AF and
     * AE regions, and a real one-shot AF cycle (CANCEL → START, then resume repeating with the
     * trigger idle so the converged lens + regions persist). One-shot AUTO converge-and-hold suits
     * a tripod cricket camera better than continuous hunting; the padlock lock/unlock sits on top.
     *
     * @return true if the focus was issued; false ⇒ caller should fall back to RootEncoder's
     * tapToFocus(MotionEvent).
     */
    fun tapToFocus(
        cam: RtmpCamera2,
        viewW: Int,
        viewH: Int,
        x: Float,
        y: Float,
        frontFacing: Boolean,
    ): Boolean {
        val b = builder(cam) ?: return false
        val s = session(cam) ?: return false
        val h = handler(cam)
        val ch = characteristics(cam) ?: return false
        val active: Rect = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return false
        val sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val maxAf = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        val maxAe = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        if (viewW <= 0 || viewH <= 0 || maxAf <= 0) return false

        // 1) normalized tap in the displayed (upright) preview
        val nx = (x / viewW).coerceIn(0f, 1f)
        val ny = (y / viewH).coerceIn(0f, 1f)

        // 2) view space -> sensor active-array space, per sensor orientation.
        //    Back camera is almost always SENSOR_ORIENTATION=90 => (sx,sy)=(ny, 1-nx).
        var sx: Float
        val sy: Float
        when (((sensorOrientation % 360) + 360) % 360) {
            90 -> { sx = ny; sy = 1f - nx }
            270 -> { sx = 1f - ny; sy = nx }
            180 -> { sx = 1f - nx; sy = 1f - ny }
            else -> { sx = nx; sy = ny } // 0
        }
        if (frontFacing) sx = 1f - sx // front preview is mirrored

        val cx = (active.left + sx * active.width()).toInt()
        val cy = (active.top + sy * active.height()).toInt()

        // 3) metering rect ~10% of the smaller sensor dimension, MAX weight, clamped in-bounds.
        val half = (minOf(active.width(), active.height()) * 0.05f).toInt().coerceAtLeast(1)
        val left = (cx - half).coerceIn(active.left, active.right - 2 * half)
        val top = (cy - half).coerceIn(active.top, active.bottom - 2 * half)
        val region = MeteringRectangle(left, top, 2 * half, 2 * half, MeteringRectangle.METERING_WEIGHT_MAX)
        val regions = arrayOf(region)

        return try {
            // 4) proper one-shot AF: cancel any running AF, set AUTO + regions, then trigger START.
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AF_REGIONS, regions)
            if (maxAe > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, regions)

            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            s.capture(b.build(), null, h)

            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            s.capture(b.build(), null, h)

            // Resume repeating with the trigger idle so the regions + converged lens persist
            // and preview/encoder frames keep flowing.
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            s.setRepeatingRequest(b.build(), null, h)
            true
        } catch (_: Throwable) {
            false
        }
    }

    // ---- Capture quality -----------------------------------------------------------------------

    /**
     * Tell the HAL this is video recording, not a bare preview. RootEncoder builds its request
     * from TEMPLATE_PREVIEW (verified in 2.4.8 bytecode: createCaptureRequest(1)) and sets no
     * processing hints, so Pixel-class devices serve the leaner preview pipeline — one reason the
     * stream looks softer/noisier than the stock camera app. CONTROL_CAPTURE_INTENT_VIDEO_RECORD
     * selects the video-tuned 3A + processing path, and FAST noise-reduction/edge match what
     * TEMPLATE_RECORD would have requested (never HIGH_QUALITY here: the HAL may drop frame rate
     * for it, and a live stream can't afford that).
     *
     * @return true if applied; false ⇒ reflection unavailable (harmless, stream just keeps the
     * preview-grade processing).
     */
    fun applyCaptureQuality(cam: RtmpCamera2, live: Boolean): Boolean {
        val b = builder(cam) ?: return false
        val ch = characteristics(cam) ?: return false
        return try {
            b.set(
                CaptureRequest.CONTROL_CAPTURE_INTENT,
                CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD,
            )
            ch.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.let { modes ->
                if (modes.contains(CaptureRequest.NOISE_REDUCTION_MODE_FAST)) {
                    b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                }
            }
            ch.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.let { modes ->
                if (modes.contains(CaptureRequest.EDGE_MODE_FAST)) {
                    b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                }
            }
            if (live) session(cam)?.setRepeatingRequest(b.build(), null, handler(cam))
            CricrelayLog.d("applyCaptureQuality: video-record intent + FAST NR/edge applied (live=$live)")
            true
        } catch (_: Throwable) {
            false
        }
    }

    // ---- Focus lock ----------------------------------------------------------------------------

    /**
     * Freeze the lens at its current (converged) position: read LENS_FOCUS_DISTANCE from one
     * capture result, then switch to AF_MODE_OFF holding that exact distance.
     *
     * Why: RootEncoder's disableAutoFocus() (2.4.8, decompiled) sets AF_MODE_OFF without a lens
     * distance, so the HAL applies the builder's default (0 = infinity) and a converged tap focus
     * is visibly thrown away the moment the operator locks. Locking must hold the lens where the
     * tap left it.
     *
     * Blocks up to ~600ms waiting for the capture result (delivered on RootEncoder's camera
     * handler thread — never the calling looper, guarded below).
     *
     * @return true if locked at the current distance; false ⇒ caller should fall back to
     * RootEncoder's disableAutoFocus().
     */
    fun lockFocusAtCurrentDistance(cam: RtmpCamera2): Boolean {
        val b = builder(cam) ?: return false
        val s = session(cam) ?: return false
        val h = handler(cam) ?: return false
        if (h.looper == Looper.myLooper()) return false // blocking here would deadlock the result
        val ch = characteristics(cam) ?: return false
        val modes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: return false
        if (!modes.contains(CaptureRequest.CONTROL_AF_MODE_OFF)) return false

        val latch = CountDownLatch(1)
        var distance: Float? = null
        return try {
            s.capture(
                b.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        distance = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                        latch.countDown()
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure,
                    ) {
                        latch.countDown()
                    }
                },
                h,
            )
            if (!latch.await(600, TimeUnit.MILLISECONDS)) return false
            val d = distance ?: return false // LEGACY HALs may not report it — fall back
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, d)
            s.setRepeatingRequest(b.build(), null, h)
            true
        } catch (_: Throwable) {
            false
        }
    }

    // ---- Stabilization -------------------------------------------------------------------------

    /**
     * Apply an explicit EIS mode (0/1/2) on the reflected builder — used for the CINEMATIC level
     * (PREVIEW_STABILIZATION = 2, API 33+) that enableVideoStabilization() can't express. Clamps
     * down to the best mode the device advertises. Returns false ⇒ caller should fall back.
     *
     * @param live true to setRepeatingRequest now (preview running); false to only set on the
     * builder (prepare time, before startPreview — the value bakes into the first request).
     */
    fun setEisMode(cam: RtmpCamera2, mode: Int, live: Boolean): Boolean {
        val b = builder(cam) ?: return false
        val ch = characteristics(cam) ?: return false
        val available: IntArray =
            ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: return false
        val chosen = if (available.contains(mode)) {
            mode
        } else {
            available.filter { it <= mode }.maxOrNull() ?: 0
        }
        return try {
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, chosen)
            if (live) {
                val s = session(cam)
                val h = handler(cam)
                s?.setRepeatingRequest(b.build(), null, h)
                // Debug readback: log the EIS mode the HAL actually confirms, so a device test
                // can tell "requested 2" from "engaged 2" without a manual CaptureCallback rig.
                s?.capture(
                    b.build(),
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult,
                        ) {
                            val actual = result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)
                            CricrelayLog.d("setEisMode requested=$mode chosen=$chosen halReports=$actual")
                        }
                    },
                    h,
                )
            } else {
                CricrelayLog.d("setEisMode requested=$mode chosen=$chosen (builder-only)")
            }
            true
        } catch (_: Throwable) {
            false
        }
    }
}
