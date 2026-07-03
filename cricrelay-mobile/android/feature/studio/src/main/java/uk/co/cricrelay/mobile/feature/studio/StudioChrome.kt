package uk.co.cricrelay.mobile.feature.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Scoreboard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt
import uk.co.cricrelay.mobile.ui.AppColors
import uk.co.cricrelay.mobile.ui.AppFonts
import uk.co.cricrelay.mobile.ui.AppGradients
import uk.co.cricrelay.mobile.ui.GlancePill
import uk.co.cricrelay.mobile.ui.PressableScale
import uk.co.cricrelay.mobile.ui.TransportPill
import uk.co.cricrelay.mobile.ui.TransportPillStyle
import uk.co.cricrelay.mobile.ui.dockSurface
import uk.co.cricrelay.mobile.ui.glassPill
import uk.co.cricrelay.mobile.ui.rememberPulseAlpha
import uk.co.cricrelay.stream.StreamCameraEngine

// Mock geometry (1b Checklist gate): 98dp ring of 3×112° arcs with 8° gaps starting at −90°,
// ring band 7dp deep so the inner disc insets by the same amount.
private const val RING_SEGMENT_SWEEP = 112f
private const val RING_SEGMENT_STEP = 120f
private val RingSize = 98.dp
private val RingBand = 7.dp

/** Inner-disc ink while the ring is blocked — rgba(7,10,16,0.92) from the mock. */
private val RingDiscBlocked = Color(0xEB070A10)
private val ChecklistDivider = Color(0x12FFFFFF) // white 0.07
private val BugSegmentDivider = Color(0x1AFFFFFF) // white 0.10

/**
 * Segmented Go Live ring — the 1b gate itself. One arc per [StudioCheck] (gold when passed,
 * faint track when pending); the inner disc flips from blocked ink + coral "N to fix" to the
 * full gold CTA once all three pass. Always tappable: blocked taps route to the first
 * incomplete check's sheet (guidance, never a dead button).
 */
@Composable
fun SegmentedGoLiveRing(
    checks: List<StudioCheck>,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = StudioChecklist.firstIncomplete(checks) == null
    val fixLabel = StudioChecklist.fixLabel(checks)
    val caption = StudioChecklist.ringCaption(checks)
    val description = when {
        busy -> "Go live, connecting"
        ready -> "Go live — all checks passed"
        else -> "Go live blocked — $caption"
    }
    PressableScale(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = description },
    ) {
        Box(modifier = Modifier.size(RingSize), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val band = RingBand.toPx()
                val arcSize = Size(size.width - band, size.height - band)
                val topLeft = Offset(band / 2f, band / 2f)
                checks.forEachIndexed { index, check ->
                    drawArc(
                        color = if (check.complete) AppColors.Primary else AppColors.RingTrack,
                        // 8° gaps centred on the segment boundaries.
                        startAngle = -90f + index * RING_SEGMENT_STEP + 4f,
                        sweepAngle = RING_SEGMENT_SWEEP,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = band, cap = StrokeCap.Butt),
                    )
                }
            }
            val discShape = CircleShape
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(RingBand)
                    .clip(discShape)
                    .then(
                        if (ready && !busy) {
                            Modifier.background(AppGradients.PrimaryCta)
                        } else {
                            Modifier
                                .background(RingDiscBlocked)
                                .border(1.dp, Color.White.copy(alpha = 0.10f), discShape)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    busy -> CircularProgressIndicator(
                        color = AppColors.Primary,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(24.dp),
                    )
                    ready -> Text(
                        "GO LIVE",
                        fontFamily = AppFonts.Archivo,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp,
                        letterSpacing = 0.8.sp,
                        color = AppColors.OnPrimary,
                    )
                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "GO LIVE",
                            fontFamily = AppFonts.Archivo,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp,
                            letterSpacing = 0.8.sp,
                            color = Color.White.copy(alpha = 0.45f),
                        )
                        fixLabel?.let {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                it,
                                fontFamily = AppFonts.DmSans,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                color = AppColors.Warning,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Caption under the ring naming the missing check (or the all-clear). */
@Composable
fun GoLiveRingCaption(checks: List<StudioCheck>, modifier: Modifier = Modifier) {
    Text(
        StudioChecklist.ringCaption(checks),
        fontFamily = AppFonts.DmSans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.5.sp,
        color = AppColors.OnBackgroundMuted,
        modifier = modifier,
    )
}

/**
 * The checklist panel — each row is the entry point to its check's sheet. Complete rows get
 * the sky check disc; incomplete rows carry the coral blocked-as-guidance treatment with a
 * sky "Choose" chip.
 */
@Composable
fun ChecklistPanel(
    checks: List<StudioCheck>,
    onCheckTap: (CheckKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .dockSurface(18.dp)
            .padding(6.dp),
    ) {
        checks.forEachIndexed { index, check ->
            if (index > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .height(1.dp)
                        .background(ChecklistDivider),
                )
            }
            ChecklistRow(check = check, onClick = { onCheckTap(check.kind) })
        }
    }
}

@Composable
private fun ChecklistRow(check: StudioCheck, onClick: () -> Unit) {
    val accent = if (check.warning) AppColors.Warning else AppColors.Accent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (check.warning) AppColors.Warning.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.15f))
                .border(1.5.dp, accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (check.complete) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    tint = AppColors.Accent,
                    modifier = Modifier.size(13.dp),
                )
            } else {
                Text(
                    "!",
                    fontFamily = AppFonts.DmSans,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 13.sp,
                    color = AppColors.Warning,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                check.title,
                fontFamily = AppFonts.DmSans,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = Color.White,
            )
            Text(
                check.sublabel,
                fontFamily = AppFonts.DmSans,
                fontWeight = FontWeight.Medium,
                fontSize = 10.5.sp,
                color = if (check.warning) AppColors.Warning else AppColors.OnBackgroundDim,
            )
        }
        if (check.warning) {
            Box(
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(AppColors.Accent.copy(alpha = 0.15f))
                    .border(1.dp, AppColors.Accent.copy(alpha = 0.5f), RoundedCornerShape(15.dp))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Choose",
                    fontFamily = AppFonts.DmSans,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = AppColors.Accent,
                )
            }
        } else {
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = AppColors.OnBackgroundDim,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Broadcast bug — three fused segments: gold ON AIR (coral PAUSED) with a pulsing ink dot,
 * the Archivo timer, and the health segment (pulsing sky dot + quality + measured bitrate).
 * Replaces the old LiveTimerBadge + StreamStatsBadge pairing.
 */
@Composable
fun BroadcastBug(
    elapsedSeconds: Long,
    paused: Boolean,
    stats: StreamCameraEngine.StreamStats?,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    val statusColor = if (paused) AppColors.Warning else AppColors.Primary
    val statusBrush = if (paused) {
        Brush.verticalGradient(listOf(AppColors.Warning, AppColors.Warning.copy(alpha = 0.85f)))
    } else {
        AppGradients.PrimaryCta
    }
    // 1.6s full pulse cycle (tween is one leg of the reverse repeat); steady when paused.
    val dotAlpha = rememberPulseAlpha(
        active = !paused,
        min = 0.35f,
        max = 1f,
        durationMs = 800,
        label = "onAirDot",
    )
    val timer = formatBroadcastTimer(elapsedSeconds)
    val description = buildString {
        append(if (paused) "Broadcast paused" else "On air")
        append(", $timer")
        stats?.let {
            val mbps = it.sentBitrateBps / 1_000_000.0
            append(
                ", ${minOf(it.width, it.height)}p${it.fps} at " +
                    "${String.format(Locale.US, "%.1f", mbps)} megabits per second",
            )
        }
    }
    Row(
        modifier = modifier
            .clip(shape)
            .border(1.dp, AppColors.DockBorder, shape)
            .semantics { contentDescription = description },
    ) {
        Row(
            modifier = Modifier
                .height(40.dp)
                .background(statusBrush)
                .padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(AppColors.OnPrimary.copy(alpha = dotAlpha)),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                if (paused) "PAUSED" else "ON AIR",
                fontFamily = AppFonts.Archivo,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 12.5.sp,
                letterSpacing = 1.sp,
                color = AppColors.OnPrimary,
            )
        }
        Box(
            modifier = Modifier
                .height(40.dp)
                .background(AppColors.DockBg)
                .padding(horizontal = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                timer,
                fontFamily = AppFonts.Archivo,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 16.sp,
                letterSpacing = 0.5.sp,
                color = Color.White,
            )
        }
        stats?.let { s ->
            val adapting = s.targetBitrateBps < (s.maxBitrateBps * 0.9f).toInt()
            val healthColor = when {
                s.congested -> AppColors.Error
                adapting -> AppColors.Warning
                else -> AppColors.Accent
            }
            // 2.4s full pulse cycle for the health dot.
            val healthAlpha = rememberPulseAlpha(
                active = true,
                min = 0.45f,
                max = 1f,
                durationMs = 1200,
                label = "healthDot",
            )
            Row(
                modifier = Modifier
                    .height(40.dp)
                    .background(AppColors.DockBg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(BugSegmentDivider),
                )
                Spacer(Modifier.width(13.dp))
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(healthColor.copy(alpha = healthAlpha)),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    "${minOf(s.width, s.height)}p${s.fps}",
                    fontFamily = AppFonts.DmSans,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = Color.White,
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    String.format(Locale.US, "%.1f Mb/s", s.sentBitrateBps / 1_000_000.0),
                    fontFamily = AppFonts.DmSans,
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                    color = AppColors.OnBackgroundDim,
                )
                Spacer(Modifier.width(13.dp))
            }
        }
    }
}

internal fun formatBroadcastTimer(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}

/**
 * The single live control surface: BOARD · AF LOCK · MIC | PAUSE | SHARE · STOP.
 * [compact] drops the pill labels so the strip fits a portrait phone.
 */
@Composable
fun LiveTransportStrip(
    focusLocked: Boolean,
    micMuted: Boolean,
    paused: Boolean,
    onBoard: () -> Unit,
    onToggleFocusLock: () -> Unit,
    onToggleMicMuted: () -> Unit,
    onPause: () -> Unit,
    onShare: (() -> Unit)?,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Row(
        modifier = modifier
            .dockSurface(20.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StripPill(
            icon = Icons.Outlined.Scoreboard,
            label = "BOARD",
            style = TransportPillStyle.Default,
            compact = compact,
            onClick = onBoard,
        )
        StripPill(
            icon = if (focusLocked) Icons.Outlined.Lock else Icons.Outlined.CenterFocusStrong,
            label = "AF LOCK",
            style = if (focusLocked) TransportPillStyle.GoldActive else TransportPillStyle.Default,
            compact = compact,
            onClick = onToggleFocusLock,
        )
        StripPill(
            icon = if (micMuted) Icons.Outlined.MicOff else Icons.Outlined.Mic,
            label = if (micMuted) "MUTED" else "MIC",
            style = if (micMuted) TransportPillStyle.ErrorTint else TransportPillStyle.Default,
            compact = compact,
            onClick = onToggleMicMuted,
        )
        Spacer(Modifier.weight(1f))
        PressableScale(
            onClick = onPause,
            modifier = Modifier.semantics {
                contentDescription = if (paused) "Resume broadcast" else "Pause broadcast"
            },
        ) {
            Box(
                modifier = Modifier
                    .width(52.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (onShare != null) {
            StripPill(
                icon = Icons.Outlined.IosShare,
                label = "SHARE",
                style = TransportPillStyle.Default,
                compact = compact,
                onClick = onShare,
            )
        }
        StripPill(
            icon = Icons.Filled.Stop,
            label = "STOP",
            style = TransportPillStyle.ErrorTint,
            compact = compact,
            onClick = onStop,
        )
    }
}

/** Full [TransportPill] in landscape; icon-only 48dp square (same state colours) in portrait. */
@Composable
private fun StripPill(
    icon: ImageVector,
    label: String,
    style: TransportPillStyle,
    compact: Boolean,
    onClick: () -> Unit,
) {
    if (!compact) {
        TransportPill(icon = icon, label = label, style = style, onClick = onClick)
        return
    }
    val shape = RoundedCornerShape(14.dp)
    val (bg, borderColor, tint) = when (style) {
        TransportPillStyle.Default ->
            Triple(AppColors.GlassPillBg, AppColors.GlassBorder, Color.White)
        TransportPillStyle.GoldActive ->
            Triple(AppColors.Primary.copy(alpha = 0.14f), AppColors.Primary, AppColors.Primary)
        TransportPillStyle.ErrorTint ->
            Triple(AppColors.Error.copy(alpha = 0.16f), AppColors.Error, AppColors.Error)
    }
    PressableScale(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(shape)
                .background(bg)
                .border(1.dp, borderColor, shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

/** Gold "AE·AF LOCK" tag shown under the reticle while the pitch focus is held. */
@Composable
fun AeAfLockTag(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(Color(0xCC090D14))
            .border(1.dp, AppColors.Primary.copy(alpha = 0.5f), shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Lock,
            contentDescription = null,
            tint = AppColors.Primary,
            modifier = Modifier.size(10.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "AE·AF LOCK",
            fontFamily = AppFonts.DmSans,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 9.sp,
            letterSpacing = 0.5.sp,
            color = AppColors.Primary,
        )
    }
}

/**
 * Focus reticle at the tapped point: 62dp circle + centre dot, white while AF is free, gold
 * with the AE·AF LOCK tag while the pitch focus is held.
 */
@Composable
internal fun StudioFocusReticle(xPx: Float, yPx: Float, locked: Boolean) {
    val density = LocalDensity.current
    val ringSize = 62.dp
    val halfPx = with(density) { ringSize.toPx() / 2f }
    val color = if (locked) AppColors.Primary else Color.White.copy(alpha = 0.92f)
    Column(
        modifier = Modifier.offset { IntOffset((xPx - halfPx).roundToInt(), (yPx - halfPx).roundToInt()) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(ringSize)
                .border(2.dp, color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
        if (locked) {
            Spacer(Modifier.height(6.dp))
            AeAfLockTag()
        }
    }
}

/** Glass zoom readout — callers show it only past 1.1× per SPEC. */
@Composable
fun ZoomPill(zoomLevel: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .glassPill(14.dp)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            String.format(Locale.US, "%.1f×", zoomLevel),
            fontFamily = AppFonts.DmSans,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = Color.White,
        )
    }
}

/** Collapsed board affordance — opens the Board Edit sheet pre-live and mid-broadcast. */
@Composable
fun BoardChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    PressableScale(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = "Board style and sponsors" },
    ) {
        Row(
            modifier = Modifier
                .height(44.dp)
                .glassPill(14.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Scoreboard,
                contentDescription = null,
                tint = AppColors.Primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "BOARD",
                fontFamily = AppFonts.DmSans,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 0.4.sp,
                color = AppColors.OnBackgroundMuted,
            )
        }
    }
}

/** Right-rail glance pills: AF lock (gold when held) above MIC (error when muted). */
@Composable
fun GlanceRail(
    focusLocked: Boolean,
    micMuted: Boolean,
    onToggleFocusLock: () -> Unit,
    onToggleMicMuted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlancePill(
            icon = if (focusLocked) Icons.Outlined.Lock else Icons.Outlined.CenterFocusStrong,
            label = if (focusLocked) "AF LOCK" else "AF",
            active = focusLocked,
            activeColor = AppColors.Primary,
            onClick = onToggleFocusLock,
        )
        GlancePill(
            icon = if (micMuted) Icons.Outlined.MicOff else Icons.Outlined.Mic,
            label = if (micMuted) "MUTED" else "MIC",
            active = micMuted,
            activeColor = AppColors.Error,
            onClick = onToggleMicMuted,
            activeFillAlpha = 0.16f,
        )
    }
}
