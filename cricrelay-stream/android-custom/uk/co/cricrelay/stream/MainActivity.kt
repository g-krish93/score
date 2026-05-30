package uk.co.cricrelay.stream

import android.app.PictureInPictureParams
import android.content.ComponentCallbacks2
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.util.Rational
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        flutterEngine.plugins.add(StreamRtmpPlugin())
    }

    override fun onUserLeaveHint() {
        if (StreamRtmpPlugin.pipWhenLive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val w = StreamRtmpPlugin.pipAspectWidth.coerceAtLeast(1)
                val h = StreamRtmpPlugin.pipAspectHeight.coerceAtLeast(1)
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(w, h))
                    .build()
                enterPictureInPictureMode(params)
            } catch (_: Exception) {
            }
        }
        super.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        StreamRtmpPlugin.pipActive = isInPictureInPictureMode
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                StreamCameraEngine.onMemoryPressure()
            }
            level <= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                StreamCameraEngine.onMemoryRestored()
            }
        }
    }

    fun lockOrientation(mode: String) {
        requestedOrientation = when (mode) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}
