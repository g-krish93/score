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

    init {
        StreamCameraEngine.attachView(openGlView, activity)
        // Wait until the PlatformView is laid out before opening the camera.
        openGlView.post {
            StreamCameraEngine.preparePreview(1280, 720, 30)
        }
    }

    override fun getView(): View = openGlView

    override fun dispose() {
        StreamCameraEngine.detachView(openGlView)
    }
}
