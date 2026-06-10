package uk.co.cricrelay.mobile.feature.auth

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.cricrelay.mobile.ui.AppColors
import uk.co.cricrelay.mobile.ui.AppSpacing
import uk.co.cricrelay.mobile.ui.AppTypography
import uk.co.cricrelay.mobile.ui.ErrorBanner
import uk.co.cricrelay.mobile.ui.GlassPanel
import uk.co.cricrelay.mobile.ui.PrimaryButton
import uk.co.cricrelay.mobile.ui.StudioBackdrop
import uk.co.cricrelay.mobile.ui.StudioTextField

@Composable
fun LoginScreen(
    onLoggedIn: (needsOnboarding: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    StudioBackdrop(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(AppSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            GlassPanel {
                Icon(
                    imageVector = Icons.Outlined.Sensors,
                    contentDescription = null,
                    tint = AppColors.Primary,
                    modifier = Modifier
                        .size(44.dp)
                        .align(Alignment.CenterHorizontally),
                )
            }
            Spacer(Modifier.height(AppSpacing.lg))
            Text("CricRelay Live", style = AppTypography.headlineLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(AppSpacing.sm))
            Text(
                "Professional cricket streaming with a live scoreboard burned into your broadcast.",
                style = AppTypography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(AppSpacing.xl))
            StudioTextField(
                value = state.baseUrl,
                onValueChange = viewModel::onBaseUrlChange,
                label = "Club server",
            )
            Spacer(Modifier.height(AppSpacing.md))
            StudioTextField(
                value = state.email,
                onValueChange = viewModel::onEmailChange,
                label = "Email",
            )
            Spacer(Modifier.height(AppSpacing.md))
            StudioTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = "Password",
            )
            state.error?.let {
                Spacer(Modifier.height(AppSpacing.md))
                ErrorBanner(it)
            }
            Spacer(Modifier.height(AppSpacing.lg))
            PrimaryButton(
                text = "Sign in to studio",
                onClick = { viewModel.login(onLoggedIn) },
                loading = state.loading,
            )
        }
    }
}

private data class OnboardingStep(
    val title: String,
    val body: String,
    val icon: @Composable () -> Unit,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val steps = listOf(
        OnboardingStep(
            "Paste your stream key",
            "On the broadcast screen, tap Destination and paste the RTMP URL and key from YouTube Studio or Twitch.",
        ) { Icon(Icons.Outlined.Settings, null, tint = AppColors.AccentBlue) },
        OnboardingStep(
            "Position & lock overlay",
            "Drag the scoreboard to the right spot, resize if needed, then lock it so touches do not move it while you film.",
        ) { Icon(Icons.Outlined.ViewInAr, null, tint = AppColors.Accent) },
        OnboardingStep(
            "Go live when ready",
            "Wait for the camera preview, run the pre-flight checklist, then tap Go Live. Keep the phone plugged in on a stable connection.",
        ) { Icon(Icons.Outlined.Sensors, null, tint = AppColors.Primary) },
    )
    val pagerState = rememberPagerState { steps.size }
    StudioBackdrop(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(AppSpacing.lg),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
                val step = steps[page]
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    GlassPanel { step.icon() }
                    Spacer(Modifier.height(AppSpacing.lg))
                    Text(step.title, style = AppTypography.titleMedium, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(AppSpacing.md))
                    Text(step.body, style = AppTypography.bodyMedium, textAlign = TextAlign.Center)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${pagerState.currentPage + 1} / ${steps.size}", style = AppTypography.bodySmall)
                if (pagerState.currentPage == steps.lastIndex) {
                    PrimaryButton(
                        text = "Enter studio",
                        onClick = { viewModel.complete(onComplete) },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    TextButton(onClick = { viewModel.complete(onComplete) }) {
                        Text("Skip")
                    }
                }
            }
        }
    }
}
