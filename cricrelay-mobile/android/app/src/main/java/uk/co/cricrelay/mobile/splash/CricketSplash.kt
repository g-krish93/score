package uk.co.cricrelay.mobile.splash

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.delay
import uk.co.cricrelay.mobile.ui.R
import uk.co.cricrelay.mobile.ui.rememberReducedMotion
import java.util.Random
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Cold-start cinematic splash — a port of the design handoff renderer
 * (docs/design_handoff_cricrelay_splash/"Splash Animation 1e v2.dc.html"), plus
 * three production upgrades on top of the spec (the "v3" pass):
 *  - lights-down handover: after the lockup settles, the cream surface dims into
 *    the app background so the splash→home transition is seamless, not a pop
 *  - a "tap to skip" hint over the bottom letterbox bar during the shot
 *  - a haptic tick at stump impact
 *
 * Every frame is a pure function of the internal timeline t (0..5.8s), played at
 * 160/63 ≈ 2.54× (~2.3s wall clock): fade in on the leather ball, pull back to the
 * umpire's view, in-swinging delivery with a broadcast speed ramp, middle stump goes
 * back, the impact glow blooms into the 1e logo lockup, then the lights come down.
 */
@Composable
fun CricketSplash(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val reducedMotion = rememberReducedMotion()
    var t by remember { mutableFloatStateOf(0f) }
    val renderer = remember {
        SplashRenderer(
            noise = makeNoiseTile(),
            wordmarkFont = runCatching {
                ResourcesCompat.getFont(context, R.font.archivo_extrabold)
            }.getOrNull(),
            taglineFont = runCatching {
                ResourcesCompat.getFont(context, R.font.dm_sans_medium)
            }.getOrNull(),
        )
    }
    LaunchedEffect(reducedMotion) {
        // Reduced motion: no camera fly-through — hold the (dark) lockup, then hand over.
        if (reducedMotion) t = DUR
        var last = 0L
        while (t < DUR) {
            androidx.compose.runtime.withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1e9f).coerceAtMost(0.05f)
                    t = (t + dt * SPEED).coerceAtMost(DUR)
                }
                last = now
            }
        }
        delay(350) // let the lockup land before the app fades in underneath
        onFinished()
    }
    // Haptic tick when the ball takes the stumps. Skipping past the impact (or
    // reduced motion) leaves t well beyond the window, so it stays silent.
    val view = LocalView.current
    LaunchedEffect(Unit) {
        delay((T_HIT / SPEED * 1000f).toLong())
        if (t >= T_HIT - 0.05f && t < T_HIT + 0.6f) {
            val fx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.KEYBOARD_TAP
            }
            view.performHapticFeedback(fx)
        }
    }
    val density = LocalDensity.current.density
    ComposeCanvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Skip: jump to the settled lockup and let the lights-down play out.
                detectTapGestures { if (t < T_DARK) t = T_DARK }
            },
    ) {
        drawIntoCanvas { renderer.draw(it.nativeCanvas, size.width, size.height, density, t) }
    }
}

// Internal timeline keys from the handoff spec (the shot itself ends at 5.2;
// 5.2..5.8 is the added lights-down handover into the app surface).
private const val DUR = 5.8f
private const val SPEED = 160f / 63f // ≈2.54× → ~2.3s wall clock
private const val T_REL = 1.45f // ball release — overlaps the pull-back, no dead beat
private const val T_HIT = 2.8f // stump impact
private const val T_LOGO = 3.5f
private const val T_DARK = 5.2f // lockup dims from cream to the app background
private const val PI = Math.PI.toFloat()

private data class Cam(var x: Float, var y: Float, var z: Float, var tilt: Float)
private data class Proj(val x: Float, val y: Float, val d: Float)

private fun ease(x: Float): Float =
    if (x <= 0f) 0f else if (x >= 1f) 1f else x * x * (3f - 2f * x)

private fun lerpColor(a: Int, b: Int, k: Float): Int {
    val kk = k.coerceIn(0f, 1f)
    fun ch(x: Int, y: Int) = (x + (y - x) * kk).toInt()
    return Color.argb(
        ch(Color.alpha(a), Color.alpha(b)),
        ch(Color.red(a), Color.red(b)),
        ch(Color.green(a), Color.green(b)),
        ch(Color.blue(a), Color.blue(b)),
    )
}

/** Premium camera easing — long elegant settle (ease-in-out quartic). */
private fun easeIO(x: Float): Float = when {
    x <= 0f -> 0f
    x >= 1f -> 1f
    x < 0.5f -> 8f * x * x * x * x
    else -> 1f - (-2f * x + 2f).pow(4) / 2f
}

private fun easeOutBack(x: Float): Float {
    if (x <= 0f) return 0f
    if (x >= 1f) return 1f
    val c = 1.20158f
    return 1f + (c + 1f) * (x - 1f).pow(3) + c * (x - 1f).pow(2)
}

/** Broadcast speed ramp: quick off the hand, slow-mo through the swing, snap at the stumps. */
private fun pMap(u: Float): Float = when {
    u < 0.55f -> u * (0.75f / 0.55f)
    u < 0.9f -> 0.75f + (u - 0.55f) * (0.15f / 0.35f)
    else -> 0.9f + (u - 0.9f)
}

/** World position across the delivery, p 0..1. In-swing, pitching at 66% of the length. */
private fun ballAt(p: Float): FloatArray {
    val pitchP = 0.66f
    val z = 0.6f + p * 19.5f
    val y: Float
    val x: Float
    if (p < pitchP) {
        val q = p / pitchP
        y = 0.05f + 1.95f * (1f - q * q)
        x = -0.38f * (1f - sin(q * PI / 2f)) // smooth inswing curve
    } else {
        val q = (p - pitchP) / (1f - pitchP)
        y = 0.05f + 1.35f * q * (1.25f - q) // bounce up toward stump height
        x = 0.0f - 0.04f * q // slight jag after pitching
    }
    return floatArrayOf(x, y, z)
}

private fun camera(t: Float): Cam {
    // Extreme close-up on the ball -> pull back to the umpire's view.
    val k = easeIO((t - 0.6f) / 1.1f)
    fun lerp(a: Float, b: Float) = a + (b - a) * k
    val cam = Cam(lerp(0f, -0.30f), lerp(0.55f, 1.9f), lerp(0.6f, -2.6f), lerp(0f, 0.10f))
    // Tracking shot: the camera chases the ball, settling ~3/4 down the pitch at impact.
    if (t > T_REL) {
        val kd = easeIO(min(1f, (t - T_REL) / (T_HIT - T_REL)))
        val p = min(1f, (t - T_REL) / (T_HIT - T_REL))
        // Blend from wherever the pull-back currently is — no hitch between the two moves.
        cam.z += (14.6f - cam.z) * kd
        cam.y += (1.35f - cam.y) * kd
        cam.tilt += (0.13f - cam.tilt) * kd
        cam.x += (ballAt(pMap(p))[0] * 0.5f - cam.x) * kd
    }
    // Handheld drift — subtle organic float on top of everything.
    cam.x += sin(t * 1.7f) * 0.014f + sin(t * 3.9f + 1.2f) * 0.007f
    cam.y += sin(t * 2.3f + 0.5f) * 0.010f
    // Impact shake, decaying over 0.45s.
    val imp = t - T_HIT
    if (imp > 0f && imp < 0.45f) {
        val s = (0.45f - imp) * 0.10f
        cam.x += sin(t * 90f) * s
        cam.y += cos(t * 77f) * s
    }
    return cam
}

private fun project(px: Float, py: Float, pz: Float, cam: Cam, w: Float, h: Float, f: Float): Proj? {
    val dx = px - cam.x
    val dy = py - cam.y
    val dz = pz - cam.z
    val c = cos(cam.tilt)
    val s = sin(cam.tilt)
    val zv = -dy * s + dz * c
    val yv = dy * c + dz * s
    if (zv < 0.12f) return null
    return Proj(w / 2f + f * dx / zv, h / 2f - f * yv / zv, zv)
}

/** 128px film-grain tile (~7% alpha per pixel, drawn at ~55% layer alpha). */
private fun makeNoiseTile(): Bitmap {
    val bmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
    val px = IntArray(128 * 128)
    val rnd = Random()
    for (i in px.indices) {
        val v = rnd.nextInt(256)
        px[i] = Color.argb(18, v, v, v)
    }
    bmp.setPixels(px, 0, 128, 0, 0, 128, 128)
    return bmp
}

/**
 * The frame renderer. Draws in density-independent units (canvas pre-scaled by
 * [draw]'s density) so every constant matches the handoff HTML 1:1.
 */
private class SplashRenderer(
    private val noise: Bitmap,
    private val wordmarkFont: Typeface?,
    private val taglineFont: Typeface?,
) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val noisePaint = Paint().apply { isFilterBitmap = false }
    private val path = Path()
    private val rect = RectF()

    fun draw(canvas: Canvas, widthPx: Float, heightPx: Float, density: Float, t: Float) {
        canvas.save()
        canvas.scale(density, density)
        val w = widthPx / density
        val h = heightPx / density
        val f = h * 0.95f
        val cam = camera(t)
        fun p(x: Float, y: Float, z: Float): Proj? = project(x, y, z, cam, w, h, f)

        // ---- sky + ground: graded night atmosphere (teal & tungsten) ----
        val horP = p(0f, 0f, 120f)
        val horY = if (horP != null) horP.y.coerceIn(h * 0.10f, h * 0.62f) else h * 0.30f
        fill.alpha = 255 // shader fills must not inherit leftover paint alpha
        fill.shader = LinearGradient(
            0f, 0f, 0f, horY,
            intArrayOf(0xFF08111A.toInt(), 0xFF0E141A.toInt(), 0xFF191A11.toInt()),
            floatArrayOf(0f, 0.7f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, horY + 1f, fill)
        fill.alpha = 255 // shader fills must not inherit leftover paint alpha
        fill.shader = LinearGradient(
            0f, horY, 0f, h,
            intArrayOf(0xFF191A11.toInt(), 0xFF12150D.toInt(), 0xFF080B07.toInt()),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, horY, w, h, fill)
        fill.shader = null
        // Warm light pools spilling onto the outfield.
        for (pp in arrayOf(floatArrayOf(-5.5f, 0f, 9f), floatArrayOf(5.5f, 0f, 9f))) {
            val lp = p(pp[0], pp[1], pp[2]) ?: continue
            val pr = maxOf(40f, f * 5.5f / lp.d)
            fill.alpha = 255 // shader fills must not inherit leftover paint alpha
            fill.shader = RadialGradient(
                lp.x, lp.y, pr,
                intArrayOf(Color.argb(13, 255, 235, 195), Color.argb(0, 255, 235, 195)),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
            rect.set(lp.x - pr, lp.y - pr * 0.4f, lp.x + pr, lp.y + pr * 0.4f)
            canvas.drawOval(rect, fill)
            fill.shader = null
        }

        // ---- night-stadium floodlights ----
        for (fp in arrayOf(
            floatArrayOf(-9f, 6.5f, 26f), floatArrayOf(9f, 6.5f, 26f),
            floatArrayOf(-11f, 5.5f, 4f), floatArrayOf(11f, 5.5f, 4f),
        )) {
            val lp = p(fp[0], fp[1], fp[2]) ?: continue
            val lr = maxOf(14f, f * 2.3f / lp.d)
            fill.alpha = 255 // shader fills must not inherit leftover paint alpha
            fill.shader = RadialGradient(
                lp.x, lp.y, lr,
                intArrayOf(
                    Color.argb(87, 255, 241, 214), // warm tungsten core
                    Color.argb(13, 235, 215, 180),
                    Color.argb(0, 235, 215, 180),
                ),
                floatArrayOf(0f, 0.3f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(lp.x, lp.y, lr, fill)
            fill.shader = null
            fill.color = Color.argb(217, 238, 242, 250)
            val bw = maxOf(4f, f * 0.5f / lp.d)
            canvas.drawRect(lp.x - bw / 2f, lp.y - bw / 8f, lp.x + bw / 2f, lp.y - bw / 8f + bw / 4f, fill)
        }

        // ---- pitch strip ----
        val corners = arrayOf(p(-1.55f, 0f, -1f), p(1.55f, 0f, -1f), p(1.55f, 0f, 21.2f), p(-1.55f, 0f, 21.2f))
        if (corners.all { it != null }) {
            val c0 = corners[0]!!; val c2 = corners[2]!!
            path.reset()
            path.moveTo(c0.x, c0.y)
            for (i in 1 until 4) path.lineTo(corners[i]!!.x, corners[i]!!.y)
            path.close()
            fill.alpha = 255 // shader fills must not inherit leftover paint alpha
            fill.shader = LinearGradient(
                0f, c2.y, 0f, c0.y,
                intArrayOf(0xFF2A2415.toInt(), 0xFF463C24.toInt(), 0xFF3C3420.toInt()),
                floatArrayOf(0f, 0.55f, 1f), // far end sinks into haze; warm amber under the lights
                Shader.TileMode.CLAMP,
            )
            canvas.drawPath(path, fill)
            fill.shader = null
            stroke.color = Color.argb(20, 255, 255, 255)
            stroke.strokeWidth = 1f
            canvas.drawPath(path, stroke)
            // Mowing stripes.
            var z0 = 0f
            while (z0 < 20.4f) {
                if (Math.round(z0 / 2.55f) % 2 == 0) {
                    val q = arrayOf(
                        p(-1.55f, 0f, z0), p(1.55f, 0f, z0),
                        p(1.55f, 0f, minOf(21.2f, z0 + 2.55f)), p(-1.55f, 0f, minOf(21.2f, z0 + 2.55f)),
                    )
                    if (q.all { it != null }) {
                        path.reset()
                        path.moveTo(q[0]!!.x, q[0]!!.y)
                        for (i in 1 until 4) path.lineTo(q[i]!!.x, q[i]!!.y)
                        path.close()
                        fill.color = Color.argb(7, 255, 255, 255)
                        canvas.drawPath(path, fill)
                    }
                }
                z0 += 2.55f
            }
        }
        // Crease lines (both ends).
        fun line(ax: Float, az: Float, bx: Float, bz: Float, alpha: Float, lw: Float) {
            val p1 = p(ax, 0f, az) ?: return
            val p2 = p(bx, 0f, bz) ?: return
            stroke.color = Color.argb((alpha * 0.85f * 255f).toInt(), 238, 238, 228)
            stroke.strokeWidth = lw
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, stroke)
        }
        line(-1.3f, 18.9f, 1.3f, 18.9f, 0.55f, 2f) // popping crease (batting end)
        line(-1.3f, 20.1f, 1.3f, 20.1f, 0.35f, 1.5f) // bowling crease far
        line(-1.3f, 1.2f, 1.3f, 1.2f, 0.55f, 2f) // near crease
        // Atmospheric haze hanging over the far end.
        fill.alpha = 255 // shader fills must not inherit leftover paint alpha
        fill.shader = LinearGradient(
            0f, horY - h * 0.05f, 0f, horY + h * 0.12f,
            intArrayOf(
                Color.argb(0, 185, 175, 150),
                Color.argb(20, 185, 175, 150),
                Color.argb(0, 185, 175, 150),
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, horY - h * 0.05f, w, horY - h * 0.05f + h * 0.17f, fill)
        fill.shader = null

        // ---- stumps at the far end ----
        val hitP = ((t - T_HIT) * 2.6f).coerceIn(0f, 1f)
        val sb = p(0f, 0f, 20.1f)
        if (sb != null) {
            fill.color = Color.argb(77, 0, 0, 0) // soft contact shadow under the set
            rect.set(
                sb.x - maxOf(6f, f * 0.26f / sb.d), sb.y + 1f - maxOf(2f, f * 0.05f / sb.d),
                sb.x + maxOf(6f, f * 0.26f / sb.d), sb.y + 1f + maxOf(2f, f * 0.05f / sb.d),
            )
            canvas.drawOval(rect, fill)
        }
        stroke.strokeCap = Paint.Cap.ROUND
        for (i in -1..1) {
            val sx = i * 0.14f
            var topY = 0.72f
            var topZ = 20.1f
            if (i == 0 && hitP > 0f) { // middle stump knocked back
                val ang = hitP * 1.1f
                topY = 0.72f * cos(ang)
                topZ = 20.1f + 0.72f * sin(ang)
            }
            val b = p(sx, 0f, 20.1f)
            val tp = p(sx, topY, topZ)
            if (b != null && tp != null) {
                val lw = maxOf(2f, f * 0.045f / b.d)
                stroke.color = 0xFFE0D3AE.toInt() // pale willow
                stroke.strokeWidth = lw
                canvas.drawLine(b.x, b.y, tp.x, tp.y, stroke)
                // Shaded edge for roundness.
                stroke.color = Color.argb(140, 80, 66, 42)
                stroke.strokeWidth = lw * 0.32f
                canvas.drawLine(b.x + lw * 0.28f, b.y, tp.x + lw * 0.28f, tp.y, stroke)
                // Specular sliver from the floodlights.
                stroke.color = Color.argb(128, 255, 252, 240)
                stroke.strokeWidth = lw * 0.18f
                canvas.drawLine(b.x - lw * 0.24f, b.y, tp.x - lw * 0.24f, tp.y, stroke)
            }
        }
        // Bails.
        if (hitP <= 0f) {
            val b1 = p(-0.07f, 0.745f, 20.1f)
            val b2 = p(0.07f, 0.745f, 20.1f)
            if (b1 != null && b2 != null) {
                stroke.color = 0xFFE0D3AE.toInt()
                stroke.strokeWidth = maxOf(1.5f, f * 0.03f / b1.d)
                canvas.drawLine(b1.x - 4f, b1.y, b1.x + 4f, b1.y, stroke)
                canvas.drawLine(b2.x - 4f, b2.y, b2.x + 4f, b2.y, stroke)
            }
        } else {
            // Flying bails — ballistic, spinning ~9 rad/s.
            val bt = t - T_HIT
            for (b in arrayOf(
                floatArrayOf(-0.07f, -0.9f, 2.4f, 1.6f),
                floatArrayOf(0.07f, 0.7f, 3.1f, 2.0f),
            )) {
                val bx = b[0] + b[1] * bt * 0.4f
                var by = 0.745f + b[2] * bt - 4.9f * bt * bt
                val bz = 20.1f + b[3] * bt * 0.5f
                if (by < 0.02f) by = 0.02f
                val pp = p(bx, by, bz) ?: continue
                canvas.save()
                canvas.translate(pp.x, pp.y)
                canvas.rotate(Math.toDegrees((bt * 9f * (if (b[0] < 0f) -1f else 1f)).toDouble()).toFloat())
                stroke.color = 0xFFE0D3AE.toInt()
                stroke.strokeWidth = 2f
                canvas.drawLine(-5f, 0f, 5f, 0f, stroke)
                canvas.restore()
            }
        }

        // ---- ball ----
        var ball: FloatArray? = null
        val spin = if (t < 1.3f) 3.6f * t else 3.6f * 1.3f + 16f * (t - 1.3f)
        if (t < T_REL) {
            // One continuous ball: the close-up position glides into the release point
            // (left of the stumps, right-arm over) as the camera pulls back.
            val k2 = ease((t - 0.5f) / 0.95f)
            ball = floatArrayOf(
                -0.38f * k2,
                0.55f + sin(t * 2.2f) * 0.012f * (1f - k2) + 1.45f * k2,
                1.0f - 0.4f * k2,
            )
        } else if (t < T_HIT) {
            val prog = pMap((t - T_REL) / (T_HIT - T_REL))
            ball = ballAt(prog)
            // Faint motion smear, not a comet.
            for (k in 1..7) {
                val tp2 = prog - k * 0.035f
                if (tp2 <= 0f) break
                val wpos = ballAt(tp2)
                val sp = p(wpos[0], wpos[1], wpos[2]) ?: continue
                val r = maxOf(1f, f * 0.075f / sp.d) * (1f - k * 0.11f)
                fill.color = Color.argb((0.10f * (1f - k / 8f) * 255f).toInt(), 226, 110, 80)
                canvas.drawCircle(sp.x, sp.y, r, fill)
            }
            // Dust kicked off the deck where it pitches (66% of the length).
            val pitchT = T_REL + 0.66f * (T_HIT - T_REL)
            val bf = t - pitchT
            if (bf > 0f && bf < 0.35f) {
                val wpos = ballAt(0.66f)
                val sp = p(wpos[0], 0f, wpos[2])
                if (sp != null) {
                    val da = 1f - bf / 0.35f
                    for (di in 0..2) {
                        val dr = (8f + di * 9f) * (bf / 0.35f + 0.3f) * maxOf(0.5f, f * 0.02f / sp.d) * 8f
                        fill.color = Color.argb((0.10f * da / (di + 1) * 255f).toInt(), 190, 172, 135)
                        rect.set(
                            sp.x - di * dr * 0.15f - dr, sp.y - dr * 0.12f - dr * 0.38f,
                            sp.x - di * dr * 0.15f + dr, sp.y - dr * 0.12f + dr * 0.38f,
                        )
                        canvas.drawOval(rect, fill)
                    }
                }
            }
        }
        if (ball != null) {
            // Grounded contact shadow under the ball.
            val gs = p(ball[0], 0f, ball[2])
            val sp = p(ball[0], ball[1], ball[2])
            if (gs != null && sp != null) {
                val r = maxOf(3f, f * 0.075f / sp.d)
                val shA = maxOf(0f, 0.32f - ball[1] * 0.12f)
                fill.color = Color.argb((shA * 255f).toInt(), 0, 0, 0)
                rect.set(
                    gs.x - r * (1.15f - ball[1] * 0.25f), gs.y - r * 0.32f,
                    gs.x + r * (1.15f - ball[1] * 0.25f), gs.y + r * 0.32f,
                )
                canvas.drawOval(rect, fill)
            }
            if (sp != null) {
                val r = maxOf(3f, f * 0.075f / sp.d)
                // Leather sphere lit top-left by the floodlights. The HTML uses an
                // off-centre two-point radial; a radial centred on the highlight
                // reads identically at this size.
                fill.alpha = 255 // shader fills must not inherit leftover paint alpha
                fill.shader = RadialGradient(
                    sp.x - r * 0.38f, sp.y - r * 0.42f, r * 1.65f,
                    intArrayOf(0xFFF0855A.toInt(), 0xFFB93A1E.toInt(), 0xFF450E06.toInt()),
                    floatArrayOf(0f, 0.42f, 1f), // tungsten highlight → rich leather → core shadow
                    Shader.TileMode.CLAMP,
                )
                canvas.drawCircle(sp.x, sp.y, r, fill)
                fill.shader = null
                // Stitched seam band, rotating with the ball.
                canvas.save()
                path.reset()
                path.addCircle(sp.x, sp.y, r, Path.Direction.CW)
                canvas.clipPath(path)
                canvas.translate(sp.x, sp.y)
                canvas.rotate(Math.toDegrees(((spin * 0.6f) % (2f * PI)).toDouble()).toFloat())
                stroke.color = Color.argb(230, 242, 228, 200)
                stroke.strokeWidth = maxOf(1f, r * 0.055f)
                rect.set(-r * 0.30f, -r * 0.97f, r * 0.30f, r * 0.97f)
                canvas.drawOval(rect, stroke)
                if (r > 10f) {
                    // Cross-stitches across the band.
                    stroke.strokeWidth = maxOf(1f, r * 0.035f)
                    stroke.color = Color.argb(191, 242, 228, 200)
                    var a = -0.75f
                    while (a <= 0.75f) {
                        val yy = a * r * 0.92f
                        canvas.drawLine(-r * 0.10f, yy, r * 0.10f, yy, stroke)
                        a += 0.25f
                    }
                }
                canvas.restore()
                // Rim light.
                stroke.color = Color.argb(89, 255, 240, 220)
                stroke.strokeWidth = maxOf(1f, r * 0.06f)
                rect.set(sp.x - r * 0.93f, sp.y - r * 0.93f, sp.x + r * 0.93f, sp.y + r * 0.93f)
                canvas.drawArc(rect, 171f, 108f, false, stroke)
            }
        }

        // ---- impact flash: brief exposure kick, not a cartoon flash ----
        val fl = t - T_HIT
        if (fl > 0f && fl < 0.09f) {
            fill.color = Color.argb((0.16f * (1f - fl / 0.09f) * 255f).toInt(), 255, 255, 255)
            canvas.drawRect(0f, 0f, w, h, fill)
        }
        if (fl > 0f && fl < 0.5f) {
            val sp = p(0f, 0.45f, 20.0f)
            if (sp != null) {
                val a = 1f - fl / 0.5f
                fill.alpha = 255 // shader fills must not inherit leftover paint alpha
                fill.shader = RadialGradient(
                    sp.x, sp.y, 64f,
                    intArrayOf(Color.argb((0.30f * a * 255f).toInt(), 255, 230, 190), Color.argb(0, 255, 230, 190)),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP,
                )
                canvas.drawCircle(sp.x, sp.y, 64f, fill)
                fill.shader = null
            }
        }
        // Light-bloom match cut: the warm impact glow swells until it becomes the lockup screen.
        val laB = ease((t - T_LOGO) / 0.8f)
        if (fl > 0.10f && laB < 1f) {
            val bp = p(0f, 0.45f, 20.0f)
            if (bp != null) {
                val bloomK = easeIO((fl - 0.10f) / (T_LOGO - T_HIT + 0.15f))
                val bigR = 30f + bloomK * hypot(w, h) * 1.05f
                fill.alpha = 255 // shader fills must not inherit leftover paint alpha
                fill.shader = RadialGradient(
                    bp.x, bp.y, bigR,
                    intArrayOf(
                        Color.argb((min(1f, bloomK * 1.6f) * 255f).toInt(), 247, 245, 238),
                        Color.argb((min(1f, bloomK * 1.25f) * 0.85f * 255f).toInt(), 247, 245, 238),
                        Color.argb(0, 247, 245, 238),
                    ),
                    floatArrayOf(0f, 0.75f, 1f),
                    Shader.TileMode.CLAMP,
                )
                canvas.drawCircle(bp.x, bp.y, bigR, fill)
                fill.shader = null
            }
        }

        // ---- cinematic grade: vignette + letterbox + grain + fade-in ----
        val laPre = ease((t - T_LOGO) / 0.8f)
        fill.alpha = 255 // shader fills must not inherit leftover paint alpha
        fill.shader = RadialGradient(
            w / 2f, h / 2f, h * 0.72f,
            intArrayOf(Color.argb(0, 4, 9, 14), Color.argb(0, 4, 9, 14), Color.argb(140, 4, 9, 14)),
            floatArrayOf(0f, 0.25f / 0.72f, 1f), // teal-leaning corners
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        val barH = h * 0.08f * (1f - laPre) // letterbox retracts at the lockup
        if (barH > 0.5f) {
            fill.color = Color.BLACK
            canvas.drawRect(0f, 0f, w, barH, fill)
            canvas.drawRect(0f, h - barH, w, h, fill)
        }
        // Animated film grain.
        if (laPre < 1f) {
            val ox = ((t * 6.1f) % 1f) * 128f
            val oy = ((t * 4.7f) % 1f) * 128f
            noisePaint.alpha = (0.55f * (1f - laPre) * 255f).toInt()
            var gx = -ox
            while (gx < w) {
                var gy = -oy
                while (gy < h) {
                    canvas.drawBitmap(noise, gx, gy, noisePaint)
                    gy += 128f
                }
                gx += 128f
            }
        }
        if (t < 0.4f) {
            fill.color = Color.argb(((1f - t / 0.4f) * 255f).toInt(), 0, 0, 0)
            canvas.drawRect(0f, 0f, w, h, fill)
        }
        // "tap to skip" hint, sitting just above the bottom letterbox bar.
        val la = ease((t - T_LOGO) / 0.8f)
        val hintA = ease((t - 0.8f) / 0.4f) * (1f - la)
        if (hintA > 0.01f) {
            textPaint.typeface = taglineFont ?: Typeface.SANS_SERIF
            textPaint.textSize = 13f
            textPaint.color = Color.argb((0.4f * hintA * 255f).toInt(), 238, 238, 228)
            canvas.drawText("tap to skip", w / 2f, h - h * 0.08f - 16f, textPaint)
        }

        // ---- logo lockup (the landing / transition frame) ----
        if (la > 0f) {
            // Lights-down: past T_DARK the cream surface eases into the app
            // background (and the type goes cream) so the handover to home is
            // invisible rather than a bright-to-dark pop.
            val dk = ease((t - T_DARK) / 0.6f)
            val darkBg = 0xFF0A0E15.toInt() // AppColors.Background
            canvas.saveLayerAlpha(0f, 0f, w, h, (la * 255f).toInt())
            fill.alpha = 255 // shader fills must not inherit leftover paint alpha
            fill.shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(
                    lerpColor(0xFFF8F6EF.toInt(), darkBg, dk),
                    lerpColor(0xFFEDEAE0.toInt(), darkBg, dk),
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, h, fill)
            fill.shader = null
            // Whisper of green at the frame edges (fades with the lights).
            fill.alpha = 255 // shader fills must not inherit leftover paint alpha
            fill.shader = RadialGradient(
                w / 2f, h * 0.46f, h * 0.8f,
                intArrayOf(
                    Color.argb(0, 46, 94, 50),
                    Color.argb(0, 46, 94, 50),
                    Color.argb((20 * (1f - dk)).toInt(), 46, 94, 50),
                ),
                floatArrayOf(0f, 0.25f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, h, fill)
            fill.shader = null
            // Zoom in from 72% with a refined overshoot settle.
            val zs = 0.72f + 0.28f * easeOutBack(la)
            canvas.translate(w / 2f, h / 2f)
            canvas.scale(zs, zs)
            canvas.translate(-w / 2f, -h / 2f)
            val cy = h * 0.44f
            // 1e pitch-mark: green rounded square, cream pitch, crease ticks.
            val s = 84f
            val lx = w / 2f - s / 2f
            val ly = cy - 120f
            fill.color = 0xFF2E5E32.toInt()
            rect.set(lx, ly, lx + s, ly + s)
            canvas.drawRoundRect(rect, 19f, 19f, fill)
            fill.color = 0xFFD8C9A3.toInt()
            rect.set(w / 2f - 13f, ly + 15f, w / 2f + 13f, ly + 69f)
            canvas.drawRoundRect(rect, 7f, 7f, fill)
            fill.color = 0xFF2E5E32.toInt()
            canvas.drawRect(w / 2f - 8f, ly + 22f, w / 2f + 8f, ly + 24.6f, fill)
            canvas.drawRect(w / 2f - 8f, ly + 59.4f, w / 2f + 8f, ly + 62f, fill)
            // Wordmark + tagline — green-on-cream by day, cream-on-ink after the
            // lights come down (per the handoff's dark lockup tokens).
            textPaint.typeface = wordmarkFont ?: Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textPaint.textSize = 42f
            textPaint.color = lerpColor(0xFF2E5E32.toInt(), 0xFFE0D3AE.toInt(), dk)
            canvas.drawText("cricrelay", w / 2f, cy + 32f, textPaint)
            textPaint.typeface = taglineFont ?: Typeface.SANS_SERIF
            textPaint.textSize = 16f
            textPaint.color = lerpColor(
                Color.argb(153, 47, 42, 36),
                Color.argb(140, 255, 255, 255),
                dk,
            )
            canvas.drawText("your club's home ground", w / 2f, cy + 64f, textPaint)
            canvas.restore()
        }
        canvas.restore()
    }
}
