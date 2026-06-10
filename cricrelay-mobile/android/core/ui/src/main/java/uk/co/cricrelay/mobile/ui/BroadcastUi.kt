package uk.co.cricrelay.mobile.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PressableScale(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = modifier
            .scale(if (pressed && enabled) 0.97f else 1f)
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
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.radiusLg))
            .background(AppColors.Surface.copy(alpha = if (highlighted) 0.92f else 0.85f))
            .border(1.dp, borderColor, RoundedCornerShape(AppSpacing.radiusLg))
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier.clickable(onClick = onClick)
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
    val alpha = if (pulse) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val anim by transition.animateFloat(
            initialValue = 0.6f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "pulseAlpha",
        )
        anim
    } else {
        1f
    }
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
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
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
    val label = if (paused) "PAUSED" else "LIVE · %02d:%02d".format(mins, secs)
    Text(
        text = label,
        color = if (paused) AppColors.Warning else AppColors.Live,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background((if (paused) AppColors.Warning else AppColors.Live).copy(alpha = 0.18f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
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
    val scale by animateFloatAsState(if (pressed && enabled) 0.9f else 1f, label = "circleScale")
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
    val scale by animateFloatAsState(if (pressed && enabled && !busy) 0.9f else 1f, label = "shutterScale")

    val glowAlpha = if (enabled && !live && !busy) {
        val transition = rememberInfiniteTransition(label = "shutterGlow")
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 0.6f,
            animationSpec = infiniteRepeatable(tween(1300), RepeatMode.Reverse),
            label = "glow",
        ).value
    } else if (live) 0.55f else 0.18f

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
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, label = "toolScale")
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
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "quickToggleScale")
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
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onClick)
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
