package uk.co.cricrelay.mobile.feature.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import uk.co.cricrelay.mobile.ui.AppColors
import uk.co.cricrelay.mobile.ui.AppSpacing
import uk.co.cricrelay.mobile.ui.AppTypography

/**
 * Full-screen direct-manipulation layer shown in Arrange mode over the live composited preview.
 * Pinch always scales the board (aspect-locked); one-finger drag moves the selected target
 * (Board or Sponsor). Transparent so the real camera + scoreboard sprite show through.
 */
@Composable
fun ArrangeOverlay(
    state: StudioUiState,
    onPinch: (Float) -> Unit,
    onDrag: (Float, Float) -> Unit,
    onTarget: (ArrangeTarget) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (zoom != 1f) onPinch(zoom)
                    if (pan.x != 0f || pan.y != 0f) {
                        val w = if (size.width > 0) size.width.toFloat() else 1f
                        val h = if (size.height > 0) size.height.toFloat() else 1f
                        onDrag(pan.x / w, pan.y / h)
                    }
                }
            },
    ) {
        // Controls live at the TOP so the lower area (where the board sits) stays free to grab.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(AppSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
            ) {
                ArrangeButton("Cancel", filled = false, modifier = Modifier.weight(1f), onClick = onCancel)
                ArrangeButton("Done", filled = true, modifier = Modifier.weight(1f), onClick = onDone)
            }
            Spacer(Modifier.height(AppSpacing.md))
            Text("Move:", style = AppTypography.bodySmall, color = Color.White)
            Spacer(Modifier.height(AppSpacing.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                ArrangeChip("Scoreboard", state.arrangeTarget == ArrangeTarget.Board) {
                    onTarget(ArrangeTarget.Board)
                }
                ArrangeChip("Sponsor", state.arrangeTarget == ArrangeTarget.Sponsor) {
                    onTarget(ArrangeTarget.Sponsor)
                }
            }
        }

        // Persistent hint near the bottom, above the board it describes.
        Text(
            text = "Drag anywhere to move the ${targetLabel(state.arrangeTarget)} · pinch with two fingers to resize the scoreboard",
            style = AppTypography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 140.dp, start = 24.dp, end = 24.dp)
                .clip(RoundedCornerShape(AppSpacing.radiusSm))
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

private fun targetLabel(target: ArrangeTarget): String =
    when (target) {
        ArrangeTarget.Board -> "scoreboard"
        ArrangeTarget.Sponsor -> "sponsor"
    }

@Composable
private fun ArrangeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = AppTypography.bodySmall,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) AppColors.OnBackground else AppColors.OnBackgroundMuted,
        modifier = Modifier
            .clip(RoundedCornerShape(AppSpacing.radiusSm))
            .background(
                if (selected) AppColors.Accent.copy(alpha = 0.25f)
                else Color.Black.copy(alpha = 0.4f),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ArrangeButton(
    label: String,
    filled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(AppSpacing.radiusSm))
            .background(if (filled) AppColors.Accent else Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = AppTypography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (filled) Color.Black else Color.White,
        )
    }
}
