package uk.co.cricrelay.mobile.feature.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import uk.co.cricrelay.mobile.ui.AppColors
import uk.co.cricrelay.mobile.ui.AppSpacing
import uk.co.cricrelay.mobile.ui.AppTypography
import uk.co.cricrelay.mobile.ui.glassPill
import uk.co.cricrelay.shared.model.OverlayLayoutPrefs

/**
 * Full-screen direct-manipulation layer shown in Arrange mode over the live composited preview.
 * Pinch always scales the board (aspect-locked); one-finger drag moves the selected target
 * (Board or Sponsor). The board outline is gold dashed with a corner resize handle; the
 * sponsor's is sky dashed and drag-only (its size stays a sheet slider). Drags snap to the
 * centre lines + 16px safe margins — the gold guides and the monospace readout come from
 * [StudioUiState]. Transparent so the real camera + scoreboard sprite show through.
 */
@Composable
fun ArrangeOverlay(
    state: StudioUiState,
    onPinch: (Float) -> Unit,
    onDrag: (Float, Float, Float, Float) -> Unit,
    onResizeBoard: (Float) -> Unit,
    onDragEnded: () -> Unit,
    onTarget: (ArrangeTarget) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val prefs = state.arrangeDraft ?: state.overlayPrefs
    val density = LocalDensity.current

    val boardRect = boardRectPx(prefs, size)
    val sponsorRect = if (prefs.sponsorEnabled) {
        sponsorRectPx(prefs, size, with(density) { 120.dp.toPx() }, with(density) { 36.dp.toPx() })
    } else {
        null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                // detectTransformGestures has no end callback, and the snap guides/readout
                // must clear when the fingers lift — so run the transform loop by hand.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        // The corner handle consumes its own drag; don't double-handle it.
                        if (event.changes.any { it.isConsumed }) break
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (zoom != 1f) {
                            moved = true
                            onPinch(zoom)
                        }
                        if (pan.x != 0f || pan.y != 0f) {
                            moved = true
                            val w = if (size.width > 0) size.width.toFloat() else 1f
                            val h = if (size.height > 0) size.height.toFloat() else 1f
                            onDrag(pan.x / w, pan.y / h, w, h)
                        }
                        if (event.changes.none { it.pressed }) break
                    }
                    if (moved) onDragEnded()
                }
            },
    ) {
        // Outlines + snap guides under the controls so buttons stay tappable.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
            boardRect?.let { r ->
                drawRect(
                    color = AppColors.Primary,
                    topLeft = Offset(r.left, r.top),
                    size = Size(r.width, r.height),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = dash),
                )
            }
            sponsorRect?.let { r ->
                drawRect(
                    color = AppColors.Accent,
                    topLeft = Offset(r.left, r.top),
                    size = Size(r.width, r.height),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = dash),
                )
            }
            val guide = AppColors.Primary.copy(alpha = 0.6f)
            if (state.arrangeGuideV) {
                drawLine(
                    color = guide,
                    start = Offset(this.size.width / 2f, 0f),
                    end = Offset(this.size.width / 2f, this.size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            if (state.arrangeGuideH) {
                drawLine(
                    color = guide,
                    start = Offset(0f, this.size.height / 2f),
                    end = Offset(this.size.width, this.size.height / 2f),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }

        // 24dp gold corner handle on the board's top-right corner — horizontal drag resizes
        // (prototype feel: scale = start·(1 + dx/140)). Its own pointerInput so the main
        // surface's pan never fights it.
        boardRect?.let { r ->
            val handleHalfPx = with(density) { 12.dp.toPx() }
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (r.right - handleHalfPx).roundToInt(),
                            (r.top - handleHalfPx).roundToInt(),
                        )
                    }
                    .size(24.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(AppColors.Primary)
                    .pointerInput(Unit) {
                        var totalDx = 0f
                        detectDragGestures(
                            onDragStart = { totalDx = 0f },
                            onDragEnd = { onDragEnded() },
                            onDragCancel = { onDragEnded() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                totalDx += dragAmount.x
                                onResizeBoard(totalDx)
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(AppColors.OnPrimary),
                )
            }
        }

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
            state.arrangeReadout?.let { readout ->
                Spacer(Modifier.height(AppSpacing.sm))
                Text(
                    readout,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = Color.White,
                    modifier = Modifier
                        .glassPill(AppSpacing.radiusSm)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }

        // Persistent hint near the bottom, above the board it describes.
        Text(
            text = "Drag anywhere to move the ${targetLabel(state.arrangeTarget)} · " +
                "pinch or pull the gold handle to resize the scoreboard",
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

/**
 * The board's on-screen rect, mirroring the GL sprite maths: width/height fractions of the
 * frame, anchorX centring with edge clamping, and the bottomMargin (px/720) lift — see
 * OverlaySpriteLayout.computePosition in the streaming module.
 */
private fun boardRectPx(prefs: OverlayLayoutPrefs, size: IntSize): Rect? {
    if (size.width <= 0 || size.height <= 0) return null
    val w = size.width.toFloat()
    val h = size.height.toFloat()
    val boardW = (prefs.clampedWidthFraction() * w).toFloat()
    val boardH = (prefs.clampedHeightFraction() * h).toFloat()
    val left = (prefs.anchorX.toFloat() * w - boardW / 2f)
        .coerceIn(0f, (w - boardW).coerceAtLeast(0f))
    val bottom = h - prefs.bottomMarginPx(size.height)
    return Rect(left, bottom - boardH, left + boardW, bottom)
}

/** Sponsor outline centred on its normalized position (the engine centres the sprite too). */
private fun sponsorRectPx(
    prefs: OverlayLayoutPrefs,
    size: IntSize,
    baseWidthPx: Float,
    baseHeightPx: Float,
): Rect? {
    if (size.width <= 0 || size.height <= 0) return null
    val scale = prefs.sponsorSizeScale.toFloat().coerceIn(0.3f, 3f)
    val boxW = baseWidthPx * scale
    val boxH = baseHeightPx * scale
    val cx = prefs.sponsorPositionX.toFloat() * size.width
    val cy = prefs.sponsorPositionY.toFloat() * size.height
    return Rect(cx - boxW / 2f, cy - boxH / 2f, cx + boxW / 2f, cy + boxH / 2f)
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
