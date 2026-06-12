package uk.co.cricrelay.mobile.feature.studio

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.co.cricrelay.mobile.ui.AppColors
import uk.co.cricrelay.mobile.ui.AppMotion
import uk.co.cricrelay.mobile.ui.AppSpacing
import uk.co.cricrelay.mobile.ui.AppTypography
import uk.co.cricrelay.mobile.ui.GhostButton
import uk.co.cricrelay.mobile.ui.PrimaryButton

/**
 * Go Live cinema: full-screen 3-2-1 takeover with a haptic tick per digit.
 * Tapping anywhere aborts — the operator must always be able to bail out.
 */
@Composable
fun GoLiveCountdown(
    count: Int,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(count) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Background.copy(alpha = 0.9f))
            .clickable(interactionSource = interaction, indication = null, onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(280.dp)
                .background(
                    Brush.radialGradient(
                        listOf(AppColors.Primary.copy(alpha = 0.22f), Color.Transparent),
                    ),
                ),
        )
        AnimatedContent(
            targetState = count,
            transitionSpec = {
                (fadeIn(AppMotion.enterSpec(200)) + scaleIn(
                    initialScale = 1.12f,
                    animationSpec = AppMotion.enterSpec(220),
                )) togetherWith
                    (fadeOut(AppMotion.exitSpec(140)) + scaleOut(
                        targetScale = AppMotion.ExitScale,
                        animationSpec = AppMotion.exitSpec(140),
                    ))
            },
            label = "goLiveCount",
        ) { n ->
            Text(
                text = "$n",
                color = AppColors.Primary,
                fontSize = 132.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 72.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Going on air", style = AppTypography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("Tap anywhere to cancel", style = AppTypography.bodySmall)
        }
    }
}

/** End-of-broadcast recap: duration, destination, and a share affordance. */
@Composable
fun StreamRecapOverlay(
    recap: StreamRecap,
    onDismiss: () -> Unit,
    onShare: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Background.copy(alpha = 0.93f))
            .clickable(interactionSource = interaction, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(AppSpacing.lg)
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppSpacing.radiusXl))
                .background(AppColors.Surface)
                .border(1.dp, AppColors.Primary.copy(alpha = 0.35f), RoundedCornerShape(AppSpacing.radiusXl))
                .padding(AppSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(AppColors.Primary),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    "BROADCAST ENDED",
                    style = AppTypography.labelSmall.copy(color = AppColors.Primary),
                )
            }
            Spacer(Modifier.height(AppSpacing.md))
            Text(
                text = formatRecapDuration(recap.durationSeconds),
                color = AppColors.OnBackground,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(2.dp))
            Text("on air via ${recap.destinationLabel}", style = AppTypography.bodyMedium)
            Spacer(Modifier.height(AppSpacing.lg))
            PrimaryButton(text = "Done", onClick = onDismiss)
            if (onShare != null && recap.watchUrl.isNotBlank()) {
                Spacer(Modifier.height(AppSpacing.xs))
                GhostButton(text = "Share watch link", onClick = onShare)
            }
        }
    }
}

internal fun formatRecapDuration(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
