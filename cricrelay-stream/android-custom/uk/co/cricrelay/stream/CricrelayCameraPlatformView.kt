package uk.co.cricrelay.stream

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.SurfaceHolder
import com.pedro.encoder.utils.gl.AspectRatioMode
import com.pedro.library.view.OpenGlView
import io.flutter.plugin.platform.PlatformView

class CricrelayCameraPlatformView(
    context: Context,
    private val activity: Activity,
) : PlatformView {

    private val openGlView = OpenGlView(context).apply {
        setKeepScreenOn(false)
        setBackgroundColor(Color.BLACK)
        setAspectRatioMode(AspectRatioMode.Fill)
    }

    private var attached = false

    /** RootEncoder requires surfaceChanged before prepareVideo/startPreview (see CI_PITFALLS). */
    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {}

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (width > 64 && height > 64 && holder.surface.isValid) {
                StreamCameraEngine.onPreviewSurfaceReady()
            }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            StreamCameraEngine.onPreviewSurfaceLost()
        }
    }

    init {
        try {
            StreamCameraEngine.attachView(openGlView, activity)
            attached = true
            openGlView.holder.addCallback(surfaceCallback)
        } catch (e: Exception) {
            attached = false
        } catch (t: Throwable) {
            attached = false
            throw t
        }
    }

    override fun getView() = openGlView

    override fun dispose() {
        openGlView.holder.removeCallback(surfaceCallback)
        if (attached) {
            StreamCameraEngine.detachView(openGlView)
            attached = false
        }
    }
}
