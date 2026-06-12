package uk.co.cricrelay.mobile.ui

import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

private const val REDUCE_MOTION_SETTING = "reduce_motion"

/** True when the user has asked the system to reduce motion (or disabled all animations). */
fun Context.isReducedMotionEnabled(): Boolean {
    val animatorOff = runCatching {
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)
    if (animatorOff) return true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        return runCatching {
            Settings.Secure.getInt(contentResolver, REDUCE_MOTION_SETTING, 0) == 1
        }.getOrDefault(false)
    }
    return false
}

/**
 * Observes system animation settings so decorative motion can be disabled at runtime.
 * Keeps opacity/color transitions elsewhere — only continuous movement should respect this.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    var reduced by remember(context) { mutableStateOf(context.isReducedMotionEnabled()) }
    DisposableEffect(context) {
        val resolver = context.contentResolver
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                reduced = context.isReducedMotionEnabled()
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(REDUCE_MOTION_SETTING),
                false,
                observer,
            )
        }
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}

/**
 * Decorative pulse (live badges, glows). Returns a steady "on" alpha when motion is reduced
 * so status remains visible without oscillating movement.
 */
@Composable
fun rememberPulseAlpha(
    active: Boolean,
    min: Float = 0.5f,
    max: Float = 1f,
    durationMs: Int = 900,
    label: String = "pulse",
): Float {
    val reducedMotion = rememberReducedMotion()
    if (!active) return 1f
    if (reducedMotion) return max
    val transition = rememberInfiniteTransition(label = label)
    return transition.animateFloat(
        initialValue = min,
        targetValue = max,
        animationSpec = infiniteRepeatable(tween(durationMs), RepeatMode.Reverse),
        label = "${label}Alpha",
    ).value
}
