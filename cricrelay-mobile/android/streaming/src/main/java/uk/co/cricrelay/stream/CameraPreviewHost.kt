package uk.co.cricrelay.stream

import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.pedro.encoder.utils.gl.AspectRatioMode
import com.pedro.library.view.OpenGlView

/**
 * Hosts the camera [OpenGlView] for the broadcast studio.
 *
 * **Compose path (preferred):** [createOpenGlView] + [bindEmbedded] from an [AndroidView]
 * factory so the SurfaceView lives inside the Compose tree (fixes black preview on Pixel).
 *
 * **Legacy path:** [show] attaches GL at activity content index 0 (Flutter-style).
 */
object CameraPreviewHost {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var openGlView: OpenGlView? = null
    private var hostActivity: Activity? = null
    private var embeddedMode = false
    private var surfaceCallback: SurfaceHolder.Callback? = null

    val isShowing: Boolean
        get() = openGlView != null

    fun createOpenGlView(activity: Activity): OpenGlView {
        return OpenGlView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setKeepScreenOn(false)
            // No opaque background: a SurfaceView with a background paints over its
            // punch-through hole (AOSP SurfaceView.draw), which leaves the camera black.
            setBackgroundColor(Color.TRANSPARENT)
            setAspectRatioMode(AspectRatioMode.Fill)
            try {
                // Default z-order = surface sits behind the window and punches a
                // transparent hole so Compose UI composites on top. This is the
                // standard "camera behind UI" path used by native camera apps.
                setZOrderOnTop(false)
                setZOrderMediaOverlay(false)
            } catch (_: Exception) {
            }
        }
    }

    /** Creates, binds, and returns the preview surface for Compose [AndroidView]. */
    fun createAndBindPreviewSurface(activity: Activity): View {
        val gl = createOpenGlView(activity)
        bindEmbedded(gl, activity)
        return gl
    }

    fun unbindPreviewSurface(view: View, activity: Activity) {
        val gl = view as? OpenGlView ?: return
        unbindEmbedded(gl, activity)
    }

    /** Bind GL created inside Compose [AndroidView]. */
    fun bindEmbedded(view: OpenGlView, activity: Activity) {
        runOnMain {
            if (openGlView != null && openGlView !== view) {
                detachInternal(openGlView!!, removeFromParent = !embeddedMode)
            }
            embeddedMode = true
            openGlView = view
            hostActivity = activity
            StreamCameraEngine.attachView(view, activity)
            installSurfaceCallback(view, activity)
            elevateComposeUi(activity)
            CricrelayLog.d("CameraPreviewHost.bindEmbedded: gl attached in Compose")
            view.post { refreshPreviewSurface() }
        }
    }

    fun unbindEmbedded(view: OpenGlView, activity: Activity) {
        runOnMain {
            if (openGlView !== view) return@runOnMain
            detachInternal(view, removeFromParent = false)
            embeddedMode = false
            openGlView = null
            hostActivity = null
        }
    }

    /** Legacy activity-root attach (kept for compatibility). */
    fun show(activity: Activity) {
        runOnMain {
            if (embeddedMode) {
                refreshPreviewSurface()
                elevateComposeUi(activity)
                return@runOnMain
            }
            if (openGlView != null && hostActivity === activity) {
                refreshPreviewSurface()
                elevateComposeUi(activity)
                return@runOnMain
            }
            hide(activity)
            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@runOnMain
            val gl = createOpenGlView(activity)
            root.addView(gl, 0)
            embeddedMode = false
            hostActivity = activity
            openGlView = gl
            StreamCameraEngine.attachView(gl, activity)
            installSurfaceCallback(gl, activity)
            elevateComposeUi(activity)
            CricrelayLog.d("CameraPreviewHost.show: gl attached at activity root")
            gl.post { refreshPreviewSurface() }
        }
    }

    /**
     * Camera renders on a SurfaceView behind the window that punches a transparent
     * hole. For the camera to show, every Compose host view above it must be
     * transparent (no opaque ViewGroup background). This walks the content tree and
     * clears any opaque host backgrounds. No elevation/z reordering is needed — the
     * SurfaceView hole-punch handles compositing.
     */
    fun elevateComposeUi(activity: Activity) {
        runOnMain {
            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@runOnMain
            val gl = openGlView
            root.setBackgroundColor(Color.TRANSPARENT)
            makeComposeHostsTransparent(root)
            if (gl != null && !embeddedMode && root.indexOfChild(gl) != 0) {
                root.removeView(gl)
                root.addView(gl, 0)
            }
            root.invalidate()
            CricrelayLog.d(
                "elevateComposeUi: embedded=$embeddedMode rootChildren=${root.childCount}",
            )
        }
    }

    /** @deprecated Use [elevateComposeUi] — kept for call-site compatibility. */
    fun elevateFlutterUi(activity: Activity) = elevateComposeUi(activity)

    private fun makeComposeHostsTransparent(view: View) {
        if (OverlaySpriteLayout.shouldForceTransparentBackground(view.javaClass.name)) {
            view.setBackgroundColor(Color.TRANSPARENT)
        }
        if (view !is ViewGroup) return
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i) ?: continue
            makeComposeHostsTransparent(child)
        }
    }

    private fun installSurfaceCallback(gl: OpenGlView, activity: Activity) {
        removeSurfaceCallback(gl)
        val callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (width > 64 && height > 64 && holder.surface.isValid) {
                    StreamCameraEngine.onPreviewSurfaceReady()
                    elevateComposeUi(activity)
                    mainHandler.postDelayed({ elevateComposeUi(activity) }, 50)
                    mainHandler.postDelayed({ elevateComposeUi(activity) }, 250)
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                StreamCameraEngine.onPreviewSurfaceLost(gl)
            }
        }
        surfaceCallback = callback
        gl.holder.addCallback(callback)
    }

    private fun removeSurfaceCallback(gl: OpenGlView) {
        surfaceCallback?.let { gl.holder.removeCallback(it) }
        surfaceCallback = null
    }

    fun refreshPreviewSurface() {
        runOnMain {
            val gl = openGlView ?: return@runOnMain
            hostActivity?.let { elevateComposeUi(it) }
            if (gl.width > 64 && gl.height > 64) {
                StreamCameraEngine.onPreviewSurfaceReady()
            } else {
                gl.post { refreshPreviewSurface() }
            }
        }
    }

    fun hide(activity: Activity) {
        runOnMain {
            val gl = openGlView ?: return@runOnMain
            if (hostActivity != null && hostActivity !== activity) return@runOnMain
            detachInternal(gl, removeFromParent = !embeddedMode)
            embeddedMode = false
            openGlView = null
            hostActivity = null
        }
    }

    fun hideCurrent() {
        val act = hostActivity ?: return
        hide(act)
    }

    private fun detachInternal(gl: OpenGlView, removeFromParent: Boolean) {
        removeSurfaceCallback(gl)
        StreamCameraEngine.detachView(gl)
        if (removeFromParent) {
            (gl.parent as? ViewGroup)?.removeView(gl)
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}
