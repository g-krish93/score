package uk.co.cricrelay.mobile.feature.studio

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import uk.co.cricrelay.mobile.ui.AppColors
import uk.co.cricrelay.mobile.ui.AppSpacing
import uk.co.cricrelay.mobile.ui.AppTypography
import uk.co.cricrelay.mobile.ui.CameraCircleButton
import uk.co.cricrelay.mobile.ui.ErrorBanner
import uk.co.cricrelay.mobile.ui.LoadingState
import uk.co.cricrelay.mobile.ui.StudioBackdrop
import uk.co.cricrelay.mobile.ui.encodeQrBitmap
import java.time.Duration
import java.time.Instant

@Composable
fun PairRemoteScreen(
    onBack: () -> Unit,
    viewModel: StudioViewModel,
    modifier: Modifier = Modifier,
) {
    val studioState by viewModel.uiState.collectAsStateWithLifecycle()
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var expiresAtIso by remember { mutableStateOf("") }
    var secondsLeft by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        error = null
        runCatching {
            val (payload, expiry) = viewModel.createPairingCode()
            expiresAtIso = expiry
            qrBitmap = withContext(Dispatchers.Default) { encodeQrBitmap(payload, 512) }
        }.onFailure { e ->
            error = e.message ?: "Failed to create pairing code"
        }
        loading = false
    }

    LaunchedEffect(expiresAtIso) {
        if (expiresAtIso.isBlank()) {
            secondsLeft = null
            return@LaunchedEffect
        }
        while (isActive) {
            secondsLeft = secondsUntil(expiresAtIso)
            delay(1_000)
        }
    }

    StudioBackdrop(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = AppSpacing.lg),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                CameraCircleButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.TopStart),
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                    )
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = AppSpacing.md),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "Pair Remote",
                        style = AppTypography.headlineMedium,
                        color = AppColors.OnBackground,
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                    Text(
                        "Scan with the phone camera (or in-app scanner) to open companion controls — no club login needed on that phone.",
                        style = AppTypography.bodyMedium,
                        color = AppColors.OnBackgroundMuted,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.lg))
                    when {
                        loading -> LoadingState("Generating code…")
                        error != null -> ErrorBanner(error!!)
                        qrBitmap != null -> {
                            Image(
                                bitmap = qrBitmap!!.asImageBitmap(),
                                contentDescription = "Remote pairing QR code",
                                modifier = Modifier
                                    .size(260.dp)
                                    .background(Color.White, RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                            )
                            Spacer(modifier = Modifier.height(AppSpacing.md))
                            if (studioState.companionPaired) {
                                Text(
                                    "Companion connected",
                                    style = AppTypography.titleMedium,
                                    color = AppColors.Accent,
                                    textAlign = TextAlign.Center,
                                )
                                Spacer(modifier = Modifier.height(AppSpacing.xs))
                                Text(
                                    "You can close this screen — keep broadcasting on this phone.",
                                    style = AppTypography.bodySmall,
                                    color = AppColors.OnBackgroundDim,
                                    textAlign = TextAlign.Center,
                                )
                            } else {
                                Text(
                                    "Waiting for companion…",
                                    style = AppTypography.titleMedium,
                                    color = AppColors.OnBackground,
                                    textAlign = TextAlign.Center,
                                )
                                val left = secondsLeft
                                if (left != null) {
                                    Spacer(modifier = Modifier.height(AppSpacing.xs))
                                    Text(
                                        if (left <= 0L) {
                                            "Code expired — go back and generate a new one"
                                        } else {
                                            "Expires in ${formatCountdown(left)}"
                                        },
                                        style = AppTypography.bodySmall,
                                        color = if (left <= 30L) {
                                            AppColors.Warning
                                        } else {
                                            AppColors.OnBackgroundDim
                                        },
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun secondsUntil(iso: String): Long? =
    runCatching {
        val expiry = Instant.parse(iso)
        Duration.between(Instant.now(), expiry).seconds.coerceAtLeast(0)
    }.getOrNull()

private fun formatCountdown(totalSeconds: Long): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%d:%02d".format(m, s)
}
