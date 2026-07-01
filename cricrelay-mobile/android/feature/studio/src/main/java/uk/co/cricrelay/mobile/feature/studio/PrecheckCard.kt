package uk.co.cricrelay.mobile.feature.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import uk.co.cricrelay.mobile.ui.AppColors
import uk.co.cricrelay.mobile.ui.AppSpacing
import uk.co.cricrelay.mobile.ui.AppTypography

/**
 * First-run guided precheck (Camera → Arrange → Ready) shown over the studio preview before the
 * first Go Live. Mirrors the iOS PrecheckCard. Dismissable via Skip; persisted once finished.
 */
@Composable
fun PrecheckCard(
    state: StudioUiState,
    onStartArrange: () -> Unit,
    onFinish: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 24.dp)
                .padding(bottom = 120.dp)
                .clip(RoundedCornerShape(AppSpacing.radiusMd))
                .background(Color.Black.copy(alpha = 0.75f))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Quick setup before you go live",
                style = AppTypography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )

            PrecheckRow(
                index = 1,
                title = "Camera",
                done = state.previewReady,
                active = state.precheckStep == PrecheckStep.Camera,
                subtitle = if (state.previewReady) "Preview is running" else "Waiting for the camera…",
            )
            PrecheckRow(
                index = 2,
                title = "Arrange board & sponsor",
                done = state.precheckStep == PrecheckStep.Ready,
                active = state.precheckStep == PrecheckStep.Arrange,
                subtitle = "Pinch to resize, drag to place them on the frame",
            )
            PrecheckRow(
                index = 3,
                title = "Ready",
                done = false,
                active = state.precheckStep == PrecheckStep.Ready,
                subtitle = "You can rearrange any time from the Board menu",
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Skip",
                    style = AppTypography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(AppSpacing.radiusSm))
                        .clickable(onClick = onFinish)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
                Spacer(Modifier.weight(1f))
                when (state.precheckStep) {
                    PrecheckStep.Camera -> Unit
                    PrecheckStep.Arrange -> PrecheckActionButton("Arrange now", onStartArrange)
                    PrecheckStep.Ready -> PrecheckActionButton("All set", onFinish)
                }
            }
        }
    }
}

@Composable
private fun PrecheckRow(
    index: Int,
    title: String,
    done: Boolean,
    active: Boolean,
    subtitle: String,
) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .width(22.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        when {
                            done -> Color(0xFF22C55E)
                            active -> AppColors.Accent
                            else -> Color.White.copy(alpha = 0.25f)
                        },
                    )
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text(
                    if (done) "✓" else "$index",
                    style = AppTypography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (active && !done) Color.Black else Color.White,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                title,
                style = AppTypography.bodySmall,
                fontWeight = if (active || done) FontWeight.Bold else FontWeight.Normal,
                color = Color.White,
            )
            Text(
                subtitle,
                style = AppTypography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun PrecheckActionButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = AppTypography.bodySmall,
        fontWeight = FontWeight.Bold,
        color = Color.Black,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(AppSpacing.radiusSm))
            .background(AppColors.Accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}
