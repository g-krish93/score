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

    private val layoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
        val w = right - left
        val h = bottom - top
        if (w > 64 && h > 64) {
            StreamCameraEngine.onPreviewViewSized()
        }
    }

    init {
        StreamCameraEngine.attachView(openGlView, activity)
        openGlView.addOnLayoutChangeListener(layoutListener)
        openGlView.post {
            if (openGlView.width > 64 && openGlView.height > 64) {
                StreamCameraEngine.onPreviewViewSized()
            }
        }
    }

    override fun getView(): View = openGlView

    override fun dispose() {
        openGlView.removeOnLayoutChangeListener(layoutListener)
        StreamCameraEngine.detachView(openGlView)
    }
}
