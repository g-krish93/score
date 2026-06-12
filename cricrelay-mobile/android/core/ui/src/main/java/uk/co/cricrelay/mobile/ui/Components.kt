package uk.co.cricrelay.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Broadcast mood for the living backdrop — drives the aurora hue. */
enum class BackdropMood { Idle, OnAir, Caution }

/**
 * Living backdrop: two slow-drifting light pools over ink that shift hue with
 * broadcast state — sky while idle, gold while on air, coral for caution.
 * Aurora alpha stays ≤0.12 so text contrast (sunlight contract) is unaffected.
 */
@Composable
fun StudioBackdrop(
    modifier: Modifier = Modifier,
    mood: BackdropMood = BackdropMood.Idle,
    content: @Composable () -> Unit,
) {
    val reducedMotion = rememberReducedMotion()
    val auroraA by animateColorAsState(
        targetValue = when (mood) {
            BackdropMood.Idle -> AppColors.Accent
            BackdropMood.OnAir -> AppColors.Primary
            BackdropMood.Caution -> AppColors.Warning
        },
        animationSpec = AppMotion.moodColorSpec(reducedMotion),
        label = "auroraA",
    )
    val auroraB by animateColorAsState(
        targetValue = when (mood) {
            BackdropMood.Idle -> AppColors.AccentBlue
            BackdropMood.OnAir -> AppColors.PrimaryDeep
            BackdropMood.Caution -> AppColors.Error
        },
        animationSpec = AppMotion.moodColorSpec(reducedMotion),
        label = "auroraB",
    )
    val drift = rememberInfiniteTransition(label = "aurora")
    val driftA by drift.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Reverse),
        label = "auroraDriftA",
    )
    val driftB by drift.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(19000, easing = LinearEasing), RepeatMode.Reverse),
        label = "auroraDriftB",
    )
    val tA = if (reducedMotion) 0.35f else driftA
    val tB = if (reducedMotion) 0.35f else driftB
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(AppColors.Canvas, AppColors.Background, Color.Black),
                ),
            )
            .drawBehind {
                if (size.width <= 0f || size.height <= 0f) return@drawBehind
                drawRect(
                    Brush.radialGradient(
                        listOf(auroraA.copy(alpha = 0.12f), Color.Transparent),
                        center = Offset(
                            size.width * (0.18f + 0.5f * tA),
                            size.height * (0.04f + 0.14f * tB),
                        ),
                        radius = size.width * 0.95f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(auroraB.copy(alpha = 0.09f), Color.Transparent),
                        center = Offset(
                            size.width * (0.92f - 0.6f * tB),
                            size.height * (0.34f + 0.22f * tA),
                        ),
                        radius = size.width * 0.8f,
                    ),
                )
            },
    ) {
        content()
    }
}

@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(AppSpacing.radiusLg))
            .background(AppColors.Surface.copy(alpha = 0.85f))
            .border(1.dp, AppColors.GlassBorder, RoundedCornerShape(AppSpacing.radiusLg))
            .padding(AppSpacing.lg),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

@Composable
fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    Text(
        text = message,
        color = AppColors.Error,
        style = AppTypography.bodyMedium,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.radiusMd))
            .background(AppColors.Error.copy(alpha = 0.12f))
            .border(1.dp, AppColors.Error.copy(alpha = 0.3f), RoundedCornerShape(AppSpacing.radiusMd))
            .padding(AppSpacing.md),
    )
}

/**
 * Error banner that slides in instead of snapping. Pass the nullable error straight
 * from state — the last message is kept so the exit animation doesn't show a blank.
 */
@Composable
fun AnimatedErrorBanner(message: String?, modifier: Modifier = Modifier) {
    var lastMessage by remember { mutableStateOf(message ?: "") }
    if (message != null) lastMessage = message
    AnimatedVisibility(
        visible = message != null,
        enter = expandVertically(
            animationSpec = tween(AppMotion.SheetEnterMs, easing = AppMotion.EaseOut),
        ) +
            fadeIn(AppMotion.enterSpec(AppMotion.SheetEnterMs)) +
            slideInVertically(
                animationSpec = tween(AppMotion.SheetEnterMs, easing = AppMotion.EaseOut),
            ) { -it / 4 },
        exit = shrinkVertically(
            animationSpec = tween(AppMotion.SheetExitMs, easing = AppMotion.EaseOut),
        ) +
            fadeOut(AppMotion.exitSpec(AppMotion.SheetExitMs)),
    ) {
        ErrorBanner(lastMessage, modifier)
    }
}

/**
 * Primary call-to-action: gradient fill, generous touch target, press-scale feedback.
 * Only one of these should carry a screen's main intent (Hick's law).
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val active = enabled && !loading
    val scale by animateFloatAsState(
        targetValue = if (pressed && active) AppMotion.PressScale else 1f,
        animationSpec = AppMotion.pressFloatSpec(),
        label = "ctaScale",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .scale(scale)
            .then(
                if (active) {
                    Modifier.shadow(
                        elevation = 14.dp,
                        shape = RoundedCornerShape(AppSpacing.radiusMd),
                        spotColor = AppColors.Primary.copy(alpha = 0.55f),
                        ambientColor = AppColors.Primary.copy(alpha = 0.35f),
                    )
                } else {
                    Modifier
                },
            )
            .clip(RoundedCornerShape(AppSpacing.radiusMd))
            .background(AppGradients.PrimaryCta)
            .alpha(if (active) 1f else 0.45f)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = active,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = AppColors.OnPrimary,
                modifier = Modifier.size(22.dp),
            )
        } else {
            // Sunlight contract: gold CTAs carry ink text (~11:1), never white (would be ~1.6:1).
            Text(text, color = AppColors.OnPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Quiet tonal button for secondary actions so they never compete with the main CTA. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) AppMotion.PressScale else 1f,
        animationSpec = AppMotion.pressFloatSpec(),
        label = "secScale",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .scale(scale)
            .clip(RoundedCornerShape(AppSpacing.radiusMd))
            .background(AppColors.SurfaceElevated)
            .border(1.dp, AppColors.Border, RoundedCornerShape(AppSpacing.radiusMd))
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = AppColors.OnBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Destructive action — red outline, never filled, so it reads as deliberate. */
@Composable
fun DangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) AppMotion.PressScale else 1f,
        animationSpec = AppMotion.pressFloatSpec(),
        label = "dangerScale",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .scale(scale)
            .clip(RoundedCornerShape(AppSpacing.radiusMd))
            .background(AppColors.Error.copy(alpha = 0.10f))
            .border(1.dp, AppColors.Error.copy(alpha = 0.55f), RoundedCornerShape(AppSpacing.radiusMd))
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = AppColors.Error, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Icon button with press-scale feedback. Wraps Material [IconButton] so touch
 * targets and semantics stay correct.
 */
@Composable
fun PressableIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) AppMotion.PressScale else 1f,
        animationSpec = AppMotion.pressFloatSpec(),
        label = "iconBtnScale",
    )
    IconButton(
        onClick = onClick,
        modifier = modifier.scale(scale),
        enabled = enabled,
        interactionSource = interaction,
    ) {
        content()
    }
}

/** Text-only action with press-scale — replaces Material TextButton for consistency. */
@Composable
fun PressableTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) AppMotion.PressScale else 1f,
        animationSpec = AppMotion.pressFloatSpec(),
        label = "textBtnScale",
    )
    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(AppSpacing.radiusSm))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** Borderless low-emphasis action ("Cancel", "Back"). */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) AppMotion.PressScale else 1f,
        animationSpec = AppMotion.pressFloatSpec(),
        label = "ghostScale",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .scale(scale)
            .clip(RoundedCornerShape(AppSpacing.radiusMd))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = AppColors.OnBackgroundMuted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun StudioTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    keyboardOptions: KeyboardOptions? = null,
    leadingIcon: ImageVector? = null,
) {
    var revealed by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        modifier = modifier.fillMaxWidth(),
        visualTransformation = if (isPassword && !revealed) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        keyboardOptions = keyboardOptions ?: KeyboardOptions(
            keyboardType = if (isPassword) KeyboardType.Password else keyboardType,
        ),
        leadingIcon = leadingIcon?.let {
            { Icon(it, contentDescription = null, tint = AppColors.OnBackgroundDim) }
        },
        trailingIcon = if (isPassword) {
            {
                PressableIconButton(onClick = { revealed = !revealed }) {
                    Icon(
                        if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (revealed) "Hide password" else "Show password",
                        tint = AppColors.OnBackgroundDim,
                    )
                }
            }
        } else {
            null
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AppColors.Accent,
            unfocusedBorderColor = AppColors.Border,
            focusedLabelColor = AppColors.Accent,
            unfocusedLabelColor = AppColors.OnBackgroundDim,
            focusedTextColor = AppColors.OnBackground,
            unfocusedTextColor = AppColors.OnBackground,
            cursorColor = AppColors.Accent,
            focusedContainerColor = AppColors.SurfaceSunken,
            unfocusedContainerColor = AppColors.SurfaceSunken,
        ),
        shape = RoundedCornerShape(AppSpacing.radiusMd),
    )
}

@Composable
fun LoadingState(message: String = "Loading…") {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = AppColors.Accent, strokeWidth = 3.dp)
            Text(
                text = message,
                style = AppTypography.bodyMedium,
                modifier = Modifier.padding(top = AppSpacing.md),
            )
        }
    }
}

/** Standard screen header: 48dp back target on the left, title beside it. */
@Composable
fun ScreenTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PressableIconButton(onClick = onBack, modifier = Modifier.size(AppSpacing.touchTarget)) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Back",
                tint = AppColors.OnBackground,
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = AppSpacing.xs)) {
            Text(
                title,
                style = AppTypography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(it, style = AppTypography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        actions()
    }
}

/**
 * Tap-to-select option card used in sheets and pickers: whole card is the touch
 * target, selection shows as an accent ring plus check — no tiny radio circles.
 */
@Composable
fun SelectableOptionCard(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = AppColors.Accent,
    enabled: Boolean = true,
) {
    val borderColor by animateColorAsState(
        targetValue = if (selected) AppColors.Accent.copy(alpha = 0.8f) else AppColors.Border,
        animationSpec = AppMotion.colorSpec(),
        label = "optBorder",
    )
    val bgColor by animateColorAsState(
        targetValue = if (selected) AppColors.Accent.copy(alpha = 0.08f) else AppColors.SurfaceElevated.copy(alpha = 0.6f),
        animationSpec = AppMotion.colorSpec(),
        label = "optBg",
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) AppMotion.PressScale else 1f,
        animationSpec = AppMotion.pressFloatSpec(),
        label = "optScale",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(AppSpacing.radiusMd))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(AppSpacing.radiusMd))
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = AppSpacing.md, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(AppSpacing.radiusSm))
                    .background(iconTint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = AppTypography.titleSmall)
            if (description != null) {
                Spacer(Modifier.height(2.dp))
                Text(description, style = AppTypography.bodySmall)
            }
        }
        if (selected) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Selected",
                tint = AppColors.Accent,
                modifier = Modifier.size(22.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .border(1.5.dp, AppColors.Border, CircleShape),
            )
        }
    }
}

/** Labeled slider with a live value badge, themed to the accent color. */
@Composable
fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = AppTypography.titleSmall)
            Text(
                valueText,
                color = AppColors.Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppColors.Accent.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = AppColors.Accent,
                activeTrackColor = AppColors.Accent,
                inactiveTrackColor = AppColors.Border,
            ),
        )
    }
}

/** Settings-style row: icon tile, label, trailing content (e.g. a Switch). */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = AppColors.AccentBlue,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(AppSpacing.radiusSm))
                    .background(iconTint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(AppSpacing.md))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = AppTypography.titleSmall)
            subtitle?.let {
                Spacer(Modifier.height(1.dp))
                Text(it, style = AppTypography.bodySmall)
            }
        }
        trailing()
    }
}
