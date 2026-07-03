package uk.co.cricrelay.mobile.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Floodlight" palette — built for direct-sunlight legibility at the ground.
 * Deep ink base, stadium-gold hero (always carries ink text, never white),
 * sky for ready/info, coral for caution; red appears only for danger.
 * Contrast floors: 7:1 body, 4.5:1 interactive, ~10:1 over live video.
 */
object AppColors {
    val Background = Color(0xFF0A0E15)
    val Canvas = Color(0xFF0D1219)
    val Surface = Color(0xFF141A26)
    val SurfaceElevated = Color(0xFF1C2433)
    val SurfaceSunken = Color(0xFF070A10)
    val Primary = Color(0xFFFFC233)
    val PrimaryBright = Color(0xFFFFD15C)
    val PrimaryDeep = Color(0xFFE8A912)
    val OnPrimary = Color(0xFF1A1305)
    val Accent = Color(0xFF57C7FF)
    val AccentBlue = Color(0xFF4DA3FF)
    val OnBackground = Color(0xFFFFFFFF)
    val OnBackgroundMuted = Color(0xFFC7CDD9)
    val OnBackgroundDim = Color(0xFF98A1B3)
    val Border = Color(0xFF323B4D)
    val BorderSubtle = Color(0xFF222A3A)
    val Error = Color(0xFFFF5C7A)
    val Live = Color(0xFFFFC233)
    val Success = Color(0xFF57C7FF)
    val Warning = Color(0xFFFF9466)
    val GlassBorder = Color(0x33FFFFFF)
    val GlassPillBg = Color(0xC7090D14)
    val DockBg = Color(0xD9070A10)
    val DockBorder = Color(0x24FFFFFF)
    val RingTrack = Color(0x2EFFFFFF)
    val YouTube = Color(0xFFFF0033)
    val Twitch = Color(0xFF9146FF)
}

/**
 * Bundled brand type. Archivo ExtraBold is broadcast display only — wordmark, ON AIR,
 * timers, GO LIVE, scoreboard team+score; DM Sans carries every other piece of UI text.
 * Archivo ships one cut, registered at 800/900 so Black requests stay on-brand instead
 * of falling back to a synthetic system bold.
 */
object AppFonts {
    val Archivo = FontFamily(
        Font(R.font.archivo_extrabold, FontWeight.ExtraBold),
        Font(R.font.archivo_extrabold, FontWeight.Black),
    )
    val DmSans = FontFamily(
        Font(R.font.dm_sans_regular, FontWeight.Normal),
        Font(R.font.dm_sans_medium, FontWeight.Medium),
        Font(R.font.dm_sans_bold, FontWeight.Bold),
    )
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

/**
 * Shared motion tokens — strong ease-out for enters, faster exits, press feedback ≤160ms.
 * Nothing scales from zero; entrances start at [EnterScale] so elements feel physically present.
 */
object AppMotion {
    val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
    val EaseInOut = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)

    const val PressMs = 160
    const val ExitMs = 160
    const val EnterMs = 240
    const val SheetEnterMs = 260
    const val SheetExitMs = 180
    const val NavEnterMs = 260
    const val NavExitMs = 180
    const val MoodMs = 1200
    const val MoodReducedMs = 320

    /** Minimum visible scale for enter animations — never pop from nothing. */
    const val EnterScale = 0.95f
    const val ExitScale = 0.96f
    const val PressScale = 0.97f

    fun pressFloatSpec(): FiniteAnimationSpec<Float> = tween(PressMs, easing = EaseOut)

    fun enterSpec(durationMs: Int = EnterMs): FiniteAnimationSpec<Float> =
        tween(durationMs, easing = EaseOut)

    fun exitSpec(durationMs: Int = ExitMs): FiniteAnimationSpec<Float> =
        tween(durationMs, easing = EaseOut)

    fun colorSpec(durationMs: Int = EnterMs): FiniteAnimationSpec<Color> =
        tween(durationMs, easing = EaseOut)

    fun moodColorSpec(reducedMotion: Boolean): FiniteAnimationSpec<Color> =
        tween(if (reducedMotion) MoodReducedMs else MoodMs, easing = EaseOut)
}

private val DarkScheme = darkColorScheme(
    primary = AppColors.Primary,
    onPrimary = AppColors.OnPrimary,
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
        fontFamily = AppFonts.DmSans,
    ),
    headlineMedium = TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.3).sp,
        color = AppColors.OnBackground,
        fontFamily = AppFonts.DmSans,
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        color = AppColors.OnBackground,
        fontFamily = AppFonts.DmSans,
    ),
    titleMedium = TextStyle(
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        color = AppColors.OnBackground,
        fontFamily = AppFonts.DmSans,
    ),
    titleSmall = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = AppColors.OnBackground,
        fontFamily = AppFonts.DmSans,
    ),
    bodyMedium = TextStyle(
        fontSize = 15.sp,
        color = AppColors.OnBackgroundMuted,
        lineHeight = 22.sp,
        fontFamily = AppFonts.DmSans,
    ),
    bodySmall = TextStyle(
        fontSize = 13.sp,
        color = AppColors.OnBackgroundDim,
        lineHeight = 18.sp,
        fontFamily = AppFonts.DmSans,
    ),
    labelLarge = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        color = AppColors.OnBackground,
        fontFamily = AppFonts.DmSans,
    ),
    labelMedium = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp,
        color = AppColors.OnBackgroundMuted,
        fontFamily = AppFonts.DmSans,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        color = AppColors.OnBackgroundDim,
        fontFamily = AppFonts.DmSans,
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
