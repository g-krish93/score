package uk.co.cricrelay.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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
    val Primary = Color(0xFFFF3B47)
    val Accent = Color(0xFF00D4AA)
    val AccentBlue = Color(0xFF4DA3FF)
    val OnBackground = Color(0xFFF4F6FA)
    val OnBackgroundMuted = Color(0xFFB4BBC8)
    val OnBackgroundDim = Color(0xFF6B7380)
    val Border = Color(0xFF2E3440)
    val Error = Color(0xFFFF5A52)
    val Live = Color(0xFFFF3B47)
    val Success = Color(0xFF34D399)
    val Warning = Color(0xFFFBBF24)
    val GlassBorder = Color(0x33FFFFFF)
}

object AppSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val radiusMd = 14.dp
    val radiusLg = 18.dp
    val radiusXl = 24.dp
}

private val DarkScheme = darkColorScheme(
    primary = AppColors.Primary,
    onPrimary = Color.White,
    secondary = AppColors.Accent,
    background = AppColors.Background,
    surface = AppColors.Surface,
    onBackground = AppColors.OnBackground,
    onSurface = AppColors.OnBackground,
    error = AppColors.Error,
)

val AppTypography = androidx.compose.material3.Typography(
    headlineLarge = TextStyle(
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
        color = AppColors.OnBackground,
        fontFamily = FontFamily.SansSerif,
    ),
    titleMedium = TextStyle(
        fontSize = 18.sp,
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
)

@Composable
fun CricRelayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = AppTypography,
        content = content,
    )
}
