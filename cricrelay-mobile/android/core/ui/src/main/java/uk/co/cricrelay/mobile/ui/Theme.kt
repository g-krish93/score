package uk.co.cricrelay.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object AppColors {
    val Background = Color(0xFF07080C)
    val Canvas = Color(0xFF0A0B10)
    val Surface = Color(0xFF12151C)
    val SurfaceElevated = Color(0xFF1A1E28)
    val SurfaceSunken = Color(0xFF0D1016)
    val Primary = Color(0xFFFF3B47)
    val PrimaryBright = Color(0xFFFF5A64)
    val PrimaryDeep = Color(0xFFE0233D)
    val Accent = Color(0xFF00D4AA)
    val AccentBlue = Color(0xFF4DA3FF)
    val OnBackground = Color(0xFFF4F6FA)
    val OnBackgroundMuted = Color(0xFFB4BBC8)
    val OnBackgroundDim = Color(0xFF6B7380)
    val Border = Color(0xFF2E3440)
    val BorderSubtle = Color(0xFF1F2530)
    val Error = Color(0xFFFF5A52)
    val Live = Color(0xFFFF3B47)
    val Success = Color(0xFF34D399)
    val Warning = Color(0xFFFBBF24)
    val GlassBorder = Color(0x33FFFFFF)
    val YouTube = Color(0xFFFF0033)
    val Twitch = Color(0xFF9146FF)
}

/** Shared brand gradients so every screen lights CTAs and heroes the same way. */
object AppGradients {
    val PrimaryCta = Brush.linearGradient(
        listOf(AppColors.PrimaryBright, AppColors.PrimaryDeep),
    )
    val AccentSweep = Brush.linearGradient(
        listOf(AppColors.Accent, AppColors.AccentBlue),
    )
    val TitleShine = Brush.linearGradient(
        listOf(AppColors.OnBackground, AppColors.Accent),
    )
}

object AppSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val radiusSm = 10.dp
    val radiusMd = 14.dp
    val radiusLg = 18.dp
    val radiusXl = 24.dp

    /** Minimum comfortable touch target (Material guidance, Fitts's law). */
    val touchTarget = 48.dp
}

private val DarkScheme = darkColorScheme(
    primary = AppColors.Primary,
    onPrimary = Color.White,
    secondary = AppColors.Accent,
    background = AppColors.Background,
    surface = AppColors.Surface,
    surfaceContainer = AppColors.SurfaceElevated,
    surfaceContainerHigh = AppColors.SurfaceElevated,
    onBackground = AppColors.OnBackground,
    onSurface = AppColors.OnBackground,
    onSurfaceVariant = AppColors.OnBackgroundMuted,
    outline = AppColors.Border,
    outlineVariant = AppColors.BorderSubtle,
    error = AppColors.Error,
)

val AppTypography = androidx.compose.material3.Typography(
    headlineLarge = TextStyle(
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
        color = AppColors.OnBackground,
        fontFamily = FontFamily.SansSerif,
    ),
    headlineMedium = TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.3).sp,
        color = AppColors.OnBackground,
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        color = AppColors.OnBackground,
    ),
    titleMedium = TextStyle(
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        color = AppColors.OnBackground,
    ),
    titleSmall = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = AppColors.OnBackground,
    ),
    bodyMedium = TextStyle(
        fontSize = 15.sp,
        color = AppColors.OnBackgroundMuted,
        lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 13.sp,
        color = AppColors.OnBackgroundDim,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        color = AppColors.OnBackground,
    ),
    labelMedium = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp,
        color = AppColors.OnBackgroundMuted,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        color = AppColors.OnBackgroundDim,
    ),
)

@Composable
fun CricRelayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = AppTypography,
        content = content,
    )
}
