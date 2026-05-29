package uk.co.cricrelay.stream

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.ViewGroup
import android.widget.FrameLayout
import com.pedro.library.view.OpenGlView

/**
 * Hosts [OpenGlView] on the Activity content root (behind Flutter).
 * Avoids Flutter PlatformView GL conflicts that crash RTMP on Go Live.
 */
object CameraPreviewHost {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var openGlView: OpenGlView? = null
    private var hostActivity: Activity? = null

    val isShowing: Boolean
        get() = openGlView != null

    fun show(activity: Activity) {
        runOnMain {
            if (openGlView != null && hostActivity === activity) {
                StreamCameraEngine.onPreviewSurfaceReady()
                return@runOnMain
            }
            hide(activity)
            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@runOnMain
            val gl = OpenGlView(activity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setKeepScreenOn(false)
            }
            root.addView(gl, 0)
            hostActivity = activity
            openGlView = gl
            StreamCameraEngine.attachView(gl, activity)
            gl.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                val w = right - left
                val h = bottom - top
                if (w > 64 && h > 64) {
                    StreamCameraEngine.onPreviewViewSized()
                }
            }
            gl.post {
                if (gl.width > 64 && gl.height > 64) {
                    StreamCameraEngine.onPreviewViewSized()
                }
            }
        }
    }

    fun hide(activity: Activity) {
        runOnMain {
            val gl = openGlView ?: return@runOnMain
            if (hostActivity != null && hostActivity !== activity) return@runOnMain
            StreamCameraEngine.detachView(gl)
            (gl.parent as? ViewGroup)?.removeView(gl)
            openGlView = null
            hostActivity = null
        }
    }

    fun hideCurrent() {
        val act = hostActivity ?: return
        hide(act)
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}
