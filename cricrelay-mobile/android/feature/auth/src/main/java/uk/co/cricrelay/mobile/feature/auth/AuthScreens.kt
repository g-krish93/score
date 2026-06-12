package uk.co.cricrelay.mobile.feature.auth

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import uk.co.cricrelay.mobile.ui.AppColors
import uk.co.cricrelay.mobile.ui.AppGradients
import uk.co.cricrelay.mobile.ui.AppSpacing
import uk.co.cricrelay.mobile.ui.AppTypography
import uk.co.cricrelay.mobile.ui.ErrorBanner
import uk.co.cricrelay.mobile.ui.GhostButton
import uk.co.cricrelay.mobile.ui.PrimaryButton
import uk.co.cricrelay.mobile.ui.StudioBackdrop
import uk.co.cricrelay.mobile.ui.StudioTextField

/** Glowing brand mark shared by login and onboarding. */
@Composable
private fun BrandMark(modifier: Modifier = Modifier, size: Int = 72) {
    Box(
        modifier = modifier
            .size(size.dp)
            .shadow(
                elevation = 22.dp,
                shape = RoundedCornerShape(AppSpacing.radiusLg),
                spotColor = AppColors.Primary.copy(alpha = 0.6f),
                ambientColor = AppColors.Accent.copy(alpha = 0.4f),
            )
            .clip(RoundedCornerShape(AppSpacing.radiusLg))
            .background(
                Brush.linearGradient(listOf(AppColors.Primary, AppColors.Accent)),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Sensors,
            contentDescription = null,
            tint = AppColors.OnPrimary,
            modifier = Modifier.size((size * 0.5).dp),
        )
    }
}

@Composable
fun LoginScreen(
    onLoggedIn: (needsOnboarding: Boolean) -> Unit,
    onSignUp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    StudioBackdrop(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(AppSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(AppSpacing.xl))
            BrandMark()
            Spacer(Modifier.height(AppSpacing.lg))
            Text(
                "CricRelay Live",
                style = AppTypography.headlineLarge.copy(brush = AppGradients.TitleShine),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(AppSpacing.sm))
            Text(
                "Broadcast cricket like a pro — live scoreboard burned into every stream.",
                style = AppTypography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = AppSpacing.md),
            )
            Spacer(Modifier.height(AppSpacing.xl))
            StudioTextField(
                value = state.baseUrl,
                onValueChange = viewModel::onBaseUrlChange,
                label = "Club server",
                keyboardType = KeyboardType.Uri,
                leadingIcon = Icons.Outlined.Language,
            )
            Spacer(Modifier.height(AppSpacing.md))
            StudioTextField(
                value = state.email,
                onValueChange = viewModel::onEmailChange,
                label = "Email",
                keyboardType = KeyboardType.Email,
                leadingIcon = Icons.Outlined.Email,
            )
            Spacer(Modifier.height(AppSpacing.md))
            StudioTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = "Password",
                isPassword = true,
                leadingIcon = Icons.Outlined.Lock,
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
            Spacer(Modifier.height(AppSpacing.md))
            TextButton(onClick = onSignUp) {
                Text(
                    "Don't have an account? Sign up",
                    style = AppTypography.bodyMedium,
                    color = AppColors.Accent,
                )
            }
            Spacer(Modifier.height(AppSpacing.xl))
        }
    }
}

@Composable
fun RegisterScreen(
    onRegistered: (needsOnboarding: Boolean) -> Unit,
    onBackToLogin: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RegisterViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val privacyUrl = "${state.baseUrl.trimEnd('/')}/privacy"
    StudioBackdrop(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(AppSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(AppSpacing.xl))
            BrandMark()
            Spacer(Modifier.height(AppSpacing.lg))
            Text(
                "Create account",
                style = AppTypography.headlineLarge.copy(brush = AppGradients.TitleShine),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(AppSpacing.sm))
            Text(
                "Set up your CricRelay account to start broadcasting.",
                style = AppTypography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = AppSpacing.md),
            )
            Spacer(Modifier.height(AppSpacing.xl))
            StudioTextField(
                value = state.name,
                onValueChange = viewModel::onNameChange,
                label = "Club or your name",
                leadingIcon = Icons.Outlined.Person,
            )
            Spacer(Modifier.height(AppSpacing.md))
            StudioTextField(
                value = state.email,
                onValueChange = viewModel::onEmailChange,
                label = "Email",
                keyboardType = KeyboardType.Email,
                leadingIcon = Icons.Outlined.Email,
            )
            Spacer(Modifier.height(AppSpacing.md))
            StudioTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = "Password (min 8 characters)",
                isPassword = true,
                leadingIcon = Icons.Outlined.Lock,
            )
            Spacer(Modifier.height(AppSpacing.md))
            StudioTextField(
                value = state.confirmPassword,
                onValueChange = viewModel::onConfirmPasswordChange,
                label = "Confirm password",
                isPassword = true,
                leadingIcon = Icons.Outlined.Lock,
            )
            state.error?.let {
                Spacer(Modifier.height(AppSpacing.md))
                ErrorBanner(it)
            }
            Spacer(Modifier.height(AppSpacing.md))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Checkbox(
                    checked = state.consent,
                    onCheckedChange = viewModel::onConsentChange,
                    colors = CheckboxDefaults.colors(
                        checkedColor = AppColors.Accent,
                        uncheckedColor = AppColors.OnBackgroundDim,
                        checkmarkColor = AppColors.Surface,
                    ),
                )
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Text(
                        "I agree to the Privacy Policy and consent to CricRelay processing my data to provide the service.",
                        style = AppTypography.bodySmall,
                        color = AppColors.OnBackgroundMuted,
                    )
                    TextButton(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(privacyUrl)))
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text(
                            "Read Privacy Policy",
                            style = AppTypography.bodySmall,
                            color = AppColors.Accent,
                        )
                    }
                }
            }
            Spacer(Modifier.height(AppSpacing.lg))
            PrimaryButton(
                text = "Create account",
                onClick = { viewModel.register(onRegistered) },
                loading = state.loading,
            )
            Spacer(Modifier.height(AppSpacing.md))
            TextButton(onClick = onBackToLogin) {
                Text(
                    "Already have an account? Sign in",
                    style = AppTypography.bodyMedium,
                    color = AppColors.Accent,
                )
            }
            Spacer(Modifier.height(AppSpacing.xl))
        }
    }
}

private data class OnboardingStep(
    val title: String,
    val body: String,
    val icon: ImageVector,
    val tint: Color,
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
            Icons.Outlined.Settings,
            AppColors.AccentBlue,
        ),
        OnboardingStep(
            "Position & lock overlay",
            "Drag the scoreboard to the right spot, resize if needed, then lock it so touches do not move it while you film.",
            Icons.Outlined.ViewInAr,
            AppColors.Accent,
        ),
        OnboardingStep(
            "Go live when ready",
            "Wait for the camera preview, run the pre-flight checklist, then tap Go Live. Keep the phone plugged in on a stable connection.",
            Icons.Outlined.Sensors,
            AppColors.Primary,
        ),
    )
    val pagerState = rememberPagerState { steps.size }
    val scope = rememberCoroutineScope()
    val onLastPage = pagerState.currentPage == steps.lastIndex

    StudioBackdrop(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(AppSpacing.lg),
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
                val step = steps[page]
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .shadow(
                                elevation = 20.dp,
                                shape = RoundedCornerShape(AppSpacing.radiusXl),
                                spotColor = step.tint.copy(alpha = 0.55f),
                            )
                            .clip(RoundedCornerShape(AppSpacing.radiusXl))
                            .background(step.tint.copy(alpha = 0.14f))
                            .border(
                                1.dp,
                                step.tint.copy(alpha = 0.45f),
                                RoundedCornerShape(AppSpacing.radiusXl),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(step.icon, contentDescription = null, tint = step.tint, modifier = Modifier.size(42.dp))
                    }
                    Spacer(Modifier.height(AppSpacing.xl))
                    Text(step.title, style = AppTypography.headlineMedium, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(AppSpacing.md))
                    Text(
                        step.body,
                        style = AppTypography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = AppSpacing.md),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = AppSpacing.md),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                steps.indices.forEach { index ->
                    val selected = pagerState.currentPage == index
                    val dotWidth by animateDpAsState(if (selected) 24.dp else 8.dp, label = "dotW")
                    val dotColor by animateColorAsState(
                        if (selected) AppColors.Accent else AppColors.Border,
                        label = "dotC",
                    )
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .height(8.dp)
                            .width(dotWidth)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                }
            }

            if (onLastPage) {
                PrimaryButton(
                    text = "Enter studio",
                    onClick = { viewModel.complete(onComplete) },
                )
            } else {
                PrimaryButton(
                    text = "Next",
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    },
                )
            }
            Spacer(Modifier.height(AppSpacing.xs))
            if (onLastPage) {
                Spacer(Modifier.height(48.dp))
            } else {
                GhostButton(text = "Skip", onClick = { viewModel.complete(onComplete) })
            }
        }
    }
}
