package uk.co.cricrelay.mobile.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Glass pill surface — the SPEC "surface over live video" treatment for standalone pills:
 * translucent ink fill with a hairline glass border (~10:1 contrast floor over footage).
 * Radius runs 14–18dp depending on the pill's context.
 */
fun Modifier.glassPill(radius: Dp = 16.dp): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .clip(shape)
        .background(AppColors.GlassPillBg)
        .border(1.dp, AppColors.GlassBorder, shape)
}

/**
 * Dock/panel surface — deeper, more opaque ink for grouped controls (checklist panel,
 * transport strip) with a softer border than a lone glass pill, per SPEC surfaces.
 */
fun Modifier.dockSurface(radius: Dp = 24.dp): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .clip(shape)
        .background(AppColors.DockBg)
        .border(1.dp, AppColors.DockBorder, shape)
}

@Composable
fun PressableScale(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) AppMotion.PressScale else 1f,
        animationSpec = AppMotion.pressFloatSpec(),
        label = "pressableScale",
    )
    Box(
        modifier = modifier
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
    ) {
        content()
    }
}

@Composable
fun StudioHero(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.radiusXl))
            .background(
                Brush.linearGradient(
                    listOf(
                        AppColors.Primary.copy(alpha = 0.28f),
                        AppColors.SurfaceElevated.copy(alpha = 0.9f),
                        AppColors.Accent.copy(alpha = 0.16f),
                    ),
                ),
            )
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(AppColors.Primary.copy(alpha = 0.5f), AppColors.Accent.copy(alpha = 0.4f)),
                ),
                RoundedCornerShape(AppSpacing.radiusXl),
            )
            .padding(AppSpacing.lg),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .shadow(14.dp, RoundedCornerShape(AppSpacing.radiusMd), clip = false)
                        .clip(RoundedCornerShape(AppSpacing.radiusMd))
                        .background(
                            Brush.linearGradient(
                                listOf(AppColors.Primary, AppColors.Accent),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.LiveTv,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.width(AppSpacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Match-day streaming", style = AppTypography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Go live to YouTube or Twitch with live scoreboard overlays — no laptop, no OBS.",
                        style = AppTypography.bodySmall,
                    )
                }
            }
        }
    }
}

data class StreamStatusChip(
    val label: String,
    val color: Color,
    val pulse: Boolean = false,
)

@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun StreamTile(
    title: String,
    subtitle: String,
    chips: List<StreamStatusChip>,
    highlighted: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (highlighted) AppColors.Live.copy(alpha = 0.5f) else AppColors.GlassBorder
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        targetValue = if (pressed) AppMotion.PressScale else 1f,
        animationSpec = AppMotion.pressFloatSpec(),
        label = "tileScale",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(AppSpacing.radiusLg))
            .background(AppColors.Surface.copy(alpha = if (highlighted) 0.92f else 0.85f))
            .border(1.dp, borderColor, RoundedCornerShape(AppSpacing.radiusLg))
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongClick()
                        },
                    )
                } else {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                    )
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.lg),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = AppTypography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = AppTypography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (chips.isNotEmpty()) {
                    Spacer(Modifier.height(AppSpacing.sm))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        chips.forEach { chip ->
                            StatusChip(label = chip.label, ok = !chip.pulse, color = chip.color, pulse = chip.pulse)
                        }
                    }
                }
            }
            Icon(Icons.Outlined.PlayArrow, contentDescription = null, tint = AppColors.Accent)
        }
    }
}

@Composable
fun StatusChip(
    label: String,
    ok: Boolean,
    modifier: Modifier = Modifier,
    color: Color = if (ok) AppColors.Success else AppColors.Warning,
    pulse: Boolean = false,
) {
    val alpha = rememberPulseAlpha(active = pulse, min = 0.6f, max = 1f, durationMs = 900, label = "statusChip")
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.15f * alpha))
            .border(1.dp, color.copy(alpha = 0.45f * alpha), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (ok && !pulse) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = color.copy(alpha = alpha),
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(label, color = color.copy(alpha = alpha), fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun InfoBanner(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    accentColor: Color = AppColors.Accent,
    onDismiss: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.radiusMd))
            .background(accentColor.copy(alpha = 0.08f))
            .border(1.dp, accentColor.copy(alpha = 0.25f), RoundedCornerShape(AppSpacing.radiusMd))
            .padding(AppSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = accentColor, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = AppColors.OnBackground, modifier = Modifier.weight(1f))
            if (onDismiss != null) {
                PressableIconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "Dismiss", modifier = Modifier.size(18.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(body, style = AppTypography.bodyMedium)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = AppTypography.bodySmall.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            color = AppColors.OnBackgroundDim,
        ),
        modifier = modifier.padding(vertical = AppSpacing.sm),
    )
}

@Composable
fun LiveTimerBadge(elapsedSeconds: Long, paused: Boolean, modifier: Modifier = Modifier) {
    val mins = elapsedSeconds / 60
    val secs = elapsedSeconds % 60
    val tint = if (paused) AppColors.Warning else AppColors.Live
    val livePulse = rememberPulseAlpha(
        active = !paused,
        min = 0.35f,
        max = 1f,
        durationMs = 700,
        label = "liveDot",
    )
    val dotAlpha = if (paused) 1f else livePulse
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .border(1.dp, tint.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = dotAlpha)),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = if (paused) "PAUSED" else "ON AIR",
            color = tint,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 1.sp,
        )
        if (!paused) {
            Spacer(Modifier.width(7.dp))
            Text(
                text = "%02d:%02d".format(mins, secs),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
fun CameraCircleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Int = 44,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.94f else 1f,
        animationSpec = AppMotion.pressFloatSpec(),
        label = "circleScale",
    )
    Box(
        modifier = modifier
            .size(size.dp)
            .scale(scale)
            .shadow(12.dp, CircleShape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.28f),
                        Color.White.copy(alpha = 0.06f),
                        Color.Black.copy(alpha = 0.6f),
                    ),
                ),
            )
            .border(
                1.dp,
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.06f)),
                ),
                CircleShape,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.3f),
                        0.45f to Color.Transparent,
                        1f to Color.Transparent,
                    ),
                ),
        )
        content()
    }
}

@Composable
fun CameraShutterButton(
    live: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled && !busy) 0.94f else 1f,
        animationSpec = AppMotion.pressFloatSpec(),
        label = "shutterScale",
    )

    val pulseGlow = rememberPulseAlpha(
        active = enabled && !live && !busy,
        min = 0.25f,
        max = 0.6f,
        durationMs = 1300,
        label = "shutterGlow",
    )
    val glowAlpha = when {
        live -> 0.55f
        busy || !enabled -> 0.18f
        else -> pulseGlow
    }

    val haloColor = if (live) AppColors.Live else AppColors.Accent
    val ringColor = when {
        live -> AppColors.Live
        busy -> Color.White.copy(alpha = 0.5f)
        enabled -> Color.White
        else -> Color.White.copy(alpha = 0.4f)
    }

    Box(
        modifier = modifier
            .size(92.dp)
            .scale(scale)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled && !busy,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Soft glow halo for depth.
        Box(
            modifier = Modifier
                .size(92.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(haloColor.copy(alpha = glowAlpha), Color.Transparent),
                    ),
                ),
        )
        // Outer ring with a metallic top-light gradient.
        Box(
            modifier = Modifier
                .size(76.dp)
                .shadow(12.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        listOf(ringColor, ringColor.copy(alpha = 0.65f)),
                    ),
                )
                .border(1.dp, Color.White.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            // Inner core: red rounded square when live, glossy white dome otherwise.
            Box(
                modifier = Modifier
                    .size(if (live) 30.dp else 62.dp)
                    .clip(if (live) RoundedCornerShape(8.dp) else CircleShape)
                    .background(
                        if (live) {
                            Brush.verticalGradient(
                                listOf(AppColors.Live, AppColors.Live.copy(alpha = 0.8f)),
                            )
                        } else {
                            Brush.verticalGradient(
                                listOf(Color.White, Color.White.copy(alpha = 0.82f)),
                            )
                        },
                    ),
            )
        }
    }
}

@Composable
fun CameraToolButton(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = AppMotion.pressFloatSpec(),
        label = "toolScale",
    )
    Column(
        modifier = modifier
            .scale(scale)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .shadow(if (active) 16.dp else 11.dp, CircleShape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(CircleShape)
                .background(
                    if (active) {
                        Brush.verticalGradient(
                            listOf(
                                AppColors.Accent.copy(alpha = 0.85f),
                                AppColors.Accent.copy(alpha = 0.45f),
                                AppColors.Accent.copy(alpha = 0.2f),
                            ),
                        )
                    } else {
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.26f),
                                Color.White.copy(alpha = 0.08f),
                                Color.Black.copy(alpha = 0.62f),
                            ),
                        )
                    },
                )
                .border(
                    1.dp,
                    Brush.verticalGradient(
                        if (active) {
                            listOf(Color.White.copy(alpha = 0.6f), AppColors.Accent.copy(alpha = 0.5f))
                        } else {
                            listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.06f))
                        },
                    ),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            // Specular top-light highlight for a raised, glassy 3D read.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = 0.30f),
                            0.45f to Color.Transparent,
                            1f to Color.Transparent,
                        ),
                    ),
            )
            content()
        }
        Spacer(Modifier.height(5.dp))
        Text(
            label,
            color = if (active) AppColors.Accent else Color.White.copy(alpha = 0.85f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Compact pill toggle for quick on/off camera options (stabilization, keep screen on). */
@Composable
fun CameraQuickToggle(
    label: String,
    active: Boolean,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = AppMotion.pressFloatSpec(),
        label = "quickToggleScale",
    )
    val tint = if (active) AppColors.Accent else Color.White.copy(alpha = 0.9f)
    Row(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(22.dp))
            .background(
                if (active) AppColors.Accent.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.42f),
            )
            .border(
                1.dp,
                if (active) AppColors.Accent.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.20f),
                RoundedCornerShape(22.dp),
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun DestinationChip(
    label: String,
    ready: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) AppMotion.PressScale else 1f,
        animationSpec = AppMotion.pressFloatSpec(),
        label = "destChipScale",
    )
    Row(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (ready) AppColors.Success else AppColors.Warning),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun BroadcastGradientScrim(
    top: Boolean,
    modifier: Modifier = Modifier,
    heightFraction: Float = 0.22f,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(
                Brush.verticalGradient(
                    colors = if (top) {
                        listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent)
                    } else {
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))
                    },
                ),
            ),
    )
}

/**
 * Vertical at-a-glance pill for the studio edge rail (AF lock, mic). Default is glass +
 * white; active swaps to an [activeColor] border over a translucent fill of the same hue —
 * gold for AF-lock, [AppColors.Error] with [activeFillAlpha] 0.16f for a muted mic.
 */
@Composable
fun GlancePill(
    icon: ImageVector,
    label: String,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    activeFillAlpha: Float = 0.14f,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) AppMotion.PressScale else 1f,
        animationSpec = AppMotion.pressFloatSpec(),
        label = "glancePillScale",
    )
    val shape = RoundedCornerShape(16.dp)
    val tint = if (active) activeColor else Color.White
    Column(
        modifier = modifier
            .scale(scale)
            .width(64.dp)
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(if (active) activeColor.copy(alpha = activeFillAlpha) else AppColors.GlassPillBg)
            .border(1.dp, if (active) activeColor else AppColors.GlassBorder, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            label.uppercase(),
            color = tint,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            maxLines = 1,
        )
    }
}

/** Visual treatments for [TransportPill] — glass default, gold latched toggle, error-tinted STOP. */
enum class TransportPillStyle { Default, GoldActive, ErrorTint }

/**
 * Horizontal control pill for the live transport strip — 48dp tall (full touch target),
 * radius 14 per SPEC control-pill states.
 */
@Composable
fun TransportPill(
    icon: ImageVector,
    label: String,
    style: TransportPillStyle = TransportPillStyle.Default,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) AppMotion.PressScale else 1f,
        animationSpec = AppMotion.pressFloatSpec(),
        label = "transportPillScale",
    )
    val shape = RoundedCornerShape(14.dp)
    val (bg, borderColor, tint) = when (style) {
        TransportPillStyle.Default ->
            Triple(AppColors.GlassPillBg, AppColors.GlassBorder, Color.White)
        TransportPillStyle.GoldActive ->
            Triple(AppColors.Primary.copy(alpha = 0.14f), AppColors.Primary, AppColors.Primary)
        TransportPillStyle.ErrorTint ->
            Triple(AppColors.Error.copy(alpha = 0.16f), AppColors.Error, AppColors.Error)
    }
    Row(
        modifier = modifier
            .scale(scale)
            .height(48.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, borderColor, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(
            label,
            color = tint,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
