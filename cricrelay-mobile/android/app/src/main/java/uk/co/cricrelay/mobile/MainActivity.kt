package uk.co.cricrelay.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dagger.hilt.android.AndroidEntryPoint
import uk.co.cricrelay.mobile.navigation.CricRelayNavHost
import uk.co.cricrelay.mobile.ui.CricRelayTheme
import uk.co.cricrelay.shared.repository.AuthRepository
import uk.co.cricrelay.stream.CameraPreviewHost
import uk.co.cricrelay.stream.StreamController
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var streamController: StreamController
    @Inject lateinit var authRepository: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        streamController.attachActivity(this)
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

    override fun onDestroy() {
        streamController.detachActivity()
        super.onDestroy()
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
