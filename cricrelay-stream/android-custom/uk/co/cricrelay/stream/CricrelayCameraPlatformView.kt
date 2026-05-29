package uk.co.cricrelay.stream

import android.app.Activity
import android.content.Context
import android.view.View
import com.pedro.library.view.OpenGlView
import io.flutter.plugin.platform.PlatformView

class CricrelayCameraPlatformView(
    context: Context,
    private val activity: Activity,
) : PlatformView {

    private val openGlView = OpenGlView(context).apply {
        setKeepScreenOn(false)
    }

    private var attached = false

    private val layoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
        val w = right - left
        val h = bottom - top
        if (w > 64 && h > 64) {
            StreamCameraEngine.onPreviewViewSized()
        }
    }

    init {
        DebugTrace.log(
            "CricrelayCameraPlatformView.init",
            "PlatformView creating OpenGlView",
            "H1",
            mapOf("activity" to activity.javaClass.simpleName),
        )
        try {
            StreamCameraEngine.attachView(openGlView, activity)
            attached = true
            DebugTrace.log("CricrelayCameraPlatformView.init", "attachView ok", "H1")
            openGlView.addOnLayoutChangeListener(layoutListener)
            openGlView.post {
                if (openGlView.width > 64 && openGlView.height > 64) {
                    DebugTrace.log(
                        "CricrelayCameraPlatformView.layout",
                        "post layout sized",
                        "H2",
                        mapOf("w" to openGlView.width, "h" to openGlView.height),
                    )
                    StreamCameraEngine.onPreviewViewSized()
                }
            }
        } catch (e: Exception) {
            attached = false
            DebugTrace.log(
                "CricrelayCameraPlatformView.init",
                "attachView failed",
                "H1",
                mapOf("error" to (e.message ?: e.javaClass.simpleName)),
            )
        } catch (t: Throwable) {
            attached = false
            DebugTrace.log(
                "CricrelayCameraPlatformView.init",
                "attachView throwable",
                "H1",
                mapOf("error" to (t.message ?: t.javaClass.simpleName)),
            )
            throw t
        }
    }

    override fun getView(): View = openGlView

    override fun dispose() {
        openGlView.removeOnLayoutChangeListener(layoutListener)
        if (attached) {
            StreamCameraEngine.detachView(openGlView)
            attached = false
        }
    }
}
