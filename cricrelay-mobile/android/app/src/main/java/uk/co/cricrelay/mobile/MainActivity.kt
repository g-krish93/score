package uk.co.cricrelay.mobile

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import uk.co.cricrelay.mobile.navigation.CricRelayNavHost
import uk.co.cricrelay.mobile.ui.CricRelayTheme
import uk.co.cricrelay.shared.repository.AuthRepository
import uk.co.cricrelay.stream.CameraPreviewHost
import uk.co.cricrelay.stream.StreamController
import uk.co.cricrelay.stream.StreamLifecyclePolicy
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var streamController: StreamController
    @Inject lateinit var authRepository: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        streamController.attachActivity(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && pipSupported()) {
            // Keep auto-enter PiP params current so the home gesture floats the live camera even
            // when onUserLeaveHint isn't delivered (gesture nav). autoEnter tracks streaming state.
            lifecycleScope.launch {
                streamController.status.collect {
                    runCatching { setPictureInPictureParams(buildPipParams()) }
                }
            }
        }
        setContent {
            CricRelayTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Transparent),
                ) {
                    BootstrapNavHost(authRepository = authRepository)
                }
            }
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        CameraPreviewHost.elevateComposeUi(this)
        if (CameraPreviewHost.isShowing) {
            streamController.refreshNativePreview()
        }
    }

    override fun onStart() {
        super.onStart()
        // Foreground / unlock: swap the live encoder back to the on-screen preview.
        streamController.onExitBackground()
    }

    override fun onStop() {
        super.onStop()
        // Manual lock or Home without PiP: keep broadcasting from the offscreen GL interface
        // instead of freezing. PiP keeps a real surface, so onStop doesn't fire there.
        if (!isInPipModeCompat() && streamController.isStreaming) {
            streamController.onEnterBackground()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (StreamLifecyclePolicy.shouldEnterPipOnLeave(streamController.isStreaming, pipSupported())) {
            enterPipSafely()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        streamController.setPipMode(isInPictureInPictureMode)
        if (isInPictureInPictureMode) {
            // PiP keeps a real, visible surface — make sure we render to the view, not offscreen.
            streamController.onExitBackground()
        } else {
            CameraPreviewHost.elevateComposeUi(this)
            streamController.refreshNativePreview()
        }
    }

    override fun onDestroy() {
        streamController.detachActivity()
        super.onDestroy()
    }

    private fun pipSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun isInPipModeCompat(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode

    private fun enterPipSafely() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching { enterPictureInPictureMode(buildPipParams()) }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(pipAspect())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Float the camera automatically on the home gesture, but only while live.
            builder.setAutoEnterEnabled(streamController.isStreaming)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    /** Encoded aspect ratio, clamped to the PiP-allowed range (~[0.418, 2.39]) so enter never throws. */
    private fun pipAspect(): Rational {
        val (w, h) = streamController.currentStreamAspect()
        val clamped = (w.toFloat() / h.toFloat()).coerceIn(0.42f, 2.38f)
        return Rational((clamped * 1000).toInt(), 1000)
    }
}

@Composable
private fun BootstrapNavHost(authRepository: AuthRepository) {
    val startDestination = androidx.compose.runtime.produceState<String?>(initialValue = null) {
        val session = authRepository.currentSession()
        value = when {
            session.token.isNullOrBlank() -> "login"
            !authRepository.isOnboardingComplete() -> "onboarding"
            else -> "home"
        }
    }.value

    if (startDestination != null) {
        CricRelayNavHost(startDestination = startDestination)
    }
}
