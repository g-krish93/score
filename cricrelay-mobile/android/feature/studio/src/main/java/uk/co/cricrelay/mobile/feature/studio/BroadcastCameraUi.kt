package uk.co.cricrelay.mobile.feature.studio

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset

import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Scoreboard
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import android.os.PowerManager
import kotlin.math.roundToInt
import uk.co.cricrelay.shared.model.OverlayLayoutPrefs
import uk.co.cricrelay.mobile.ui.AppColors
import uk.co.cricrelay.mobile.ui.AppMotion
import uk.co.cricrelay.mobile.ui.BroadcastGradientScrim
import uk.co.cricrelay.mobile.ui.CameraCircleButton
import uk.co.cricrelay.mobile.ui.CameraQuickToggle
import uk.co.cricrelay.mobile.ui.CameraShutterButton
import uk.co.cricrelay.mobile.ui.CameraToolButton
import uk.co.cricrelay.mobile.ui.DestinationChip
import uk.co.cricrelay.mobile.ui.ErrorBanner
import uk.co.cricrelay.mobile.ui.LiveTimerBadge

@Composable
fun BroadcastCameraUi(
    state: StudioUiState,
    onBack: () -> Unit,
    onShutter: () -> Unit,
    onPause: () -> Unit,
    onDestination: () -> Unit,
    onOverlay: () -> Unit,
    onScoring: () -> Unit,
    onMenu: () -> Unit,
    onShare: (() -> Unit)?,
    onToggleStabilization: () -> Unit,
    onToggleKeepScreenOn: () -> Unit,
    onToggleFocusLock: () -> Unit,
    onToggleMicMuted: () -> Unit,
    onLowerQuality: () -> Unit,
    onPreviewTap: (Float, Float, Int, Int) -> Unit,
    onPinchZoom: (Float) -> Unit,
    onPreviewSurfaceBound: () -> Unit = {},
    onCancelCountdown: () -> Unit = {},
    onDismissRecap: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val transformState = rememberTransformableState { zoomChange, _, _ ->
        if (zoomChange != 1f) onPinchZoom(zoomChange)
    }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(state.streaming) {
        if (state.streaming) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
    ) {
        val previewWidth = constraints.maxWidth
        val previewHeight = constraints.maxHeight
        val landscape = previewWidth > previewHeight
        CameraPreviewLayer(
            modifier = Modifier.fillMaxSize(),
            onPreviewSurfaceBound = onPreviewSurfaceBound,
        )

        if (state.inPip) {
            // Picture-in-Picture: the floating window is too small for any chrome. Show only the
            // camera — the scoreboard + watermark are already burned into the GL frame.
            return@BoxWithConstraints
        }

        // Scoreboard is burned into the camera GL surface during preview and stream (parity with iOS).

        // Watermark is burned into the camera GL surface (see StreamCameraEngine), so it's
        // already visible on this preview — no separate Compose overlay needed.

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(previewWidth, previewHeight) {
                    detectTapGestures { offset ->
                        onPreviewTap(offset.x, offset.y, previewWidth, previewHeight)
                    }
                }
                .transformable(state = transformState),
        ) {
            BroadcastGradientScrim(top = true, modifier = Modifier.align(Alignment.TopCenter))
            BroadcastGradientScrim(top = false, modifier = Modifier.align(Alignment.BottomCenter))

            if (landscape) {
                LandscapeControls(
                    state = state,
                    onBack = onBack,
                    onShutter = onShutter,
                    onPause = onPause,
                    onDestination = onDestination,
                    onOverlay = onOverlay,
                    onScoring = onScoring,
                    onMenu = onMenu,
                    onShare = onShare,
                    onToggleStabilization = onToggleStabilization,
                    onToggleKeepScreenOn = onToggleKeepScreenOn,
                    onToggleFocusLock = onToggleFocusLock,
                    onToggleMicMuted = onToggleMicMuted,
                    onLowerQuality = onLowerQuality,
                )
            } else {
                PortraitControls(
                    state = state,
                    onBack = onBack,
                    onShutter = onShutter,
                    onPause = onPause,
                    onDestination = onDestination,
                    onOverlay = onOverlay,
                    onScoring = onScoring,
                    onMenu = onMenu,
                    onShare = onShare,
                    onToggleStabilization = onToggleStabilization,
                    onToggleKeepScreenOn = onToggleKeepScreenOn,
                    onToggleFocusLock = onToggleFocusLock,
                    onToggleMicMuted = onToggleMicMuted,
                    onLowerQuality = onLowerQuality,
                )
            }

            // Drawn last so a low tap's reticle sits above the scrim + controls, never behind them.
            state.focusX?.let { fx ->
                state.focusY?.let { fy ->
                    FocusReticle(xPx = fx, yPx = fy, locked = state.focusLocked)
                }
            }
        }

        state.goLiveCountdown?.let { n ->
            GoLiveCountdown(count = n, onCancel = onCancelCountdown)
        }
        state.recap?.let { recap ->
            StreamRecapOverlay(recap = recap, onDismiss = onDismissRecap, onShare = onShare)
        }
    }
}

/**
 * Pause/resume button that scales + fades in when streaming starts. Extracted to its own
 * composable so the call sites' Row/Column scope doesn't shadow the plain AnimatedVisibility
 * overload (the scoped overloads would otherwise win and fail to resolve).
 */
@Composable
private fun PauseReveal(
    visible: Boolean,
    paused: Boolean,
    size: Int,
    onPause: () -> Unit,
    modifier: Modifier = Modifier,
    belowSpacing: Dp = 0.dp,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(AppMotion.enterSpec()) +
            scaleIn(initialScale = AppMotion.EnterScale, animationSpec = AppMotion.enterSpec()),
        exit = fadeOut(AppMotion.exitSpec()) +
            scaleOut(targetScale = AppMotion.ExitScale, animationSpec = AppMotion.exitSpec()),
        modifier = modifier,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CameraCircleButton(onClick = onPause, size = size) {
                Icon(
                    if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                    contentDescription = "Pause",
                    tint = Color.White,
                )
            }
            if (belowSpacing > 0.dp) Spacer(Modifier.height(belowSpacing))
        }
    }
}

/** Dest / Style / Score tool buttons, laid out as a Row (portrait) or Column (landscape). */
@Composable
private fun ToolButtons(
    state: StudioUiState,
    onDestination: () -> Unit,
    onOverlay: () -> Unit,
    onScoring: () -> Unit,
    vertical: Boolean,
) {
    val dest: @Composable () -> Unit = {
        CameraToolButton(label = "Dest", active = state.destinationReady, onClick = onDestination) {
            Icon(Icons.Outlined.Cast, contentDescription = null, tint = Color.White)
        }
    }
    val style: @Composable () -> Unit = {
        CameraToolButton(
            label = "Board",
            active = state.overlayPrefs.sponsorEnabled,
            onClick = onOverlay,
        ) {
            Icon(Icons.Outlined.Layers, contentDescription = null, tint = Color.White)
        }
    }
    val score: @Composable () -> Unit = {
        CameraToolButton(label = "Score", active = false, onClick = onScoring) {
            Icon(Icons.Outlined.Scoreboard, contentDescription = null, tint = Color.White)
        }
    }
    if (vertical) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            dest(); style(); score()
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            dest(); style(); score()
        }
    }
}

/** Focus lock / Stabilize / Keep-screen-on quick toggles, surfaced next to Go Live (no menu dig). */
@Composable
private fun QuickToggles(
    state: StudioUiState,
    onToggleStabilization: () -> Unit,
    onToggleKeepScreenOn: () -> Unit,
    onToggleFocusLock: () -> Unit,
    onToggleMicMuted: () -> Unit,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
) {
    val focusLock: @Composable () -> Unit = {
        CameraQuickToggle(
            label = if (state.focusLocked) "Locked" else "Focus",
            active = state.focusLocked,
            icon = if (state.focusLocked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
            onClick = onToggleFocusLock,
        )
    }
    val stabilize: @Composable () -> Unit = {
        CameraQuickToggle(
            label = "Stabilize",
            active = state.overlayPrefs.videoStabilization,
            icon = Icons.Outlined.Vibration,
            onClick = onToggleStabilization,
        )
    }
    val screenOn: @Composable () -> Unit = {
        CameraQuickToggle(
            label = "Screen on",
            active = state.overlayPrefs.keepScreenOn,
            icon = Icons.Outlined.LightMode,
            onClick = onToggleKeepScreenOn,
        )
    }
    val micMute: @Composable () -> Unit = {
        CameraQuickToggle(
            label = if (state.micMuted) "Muted" else "Mic",
            active = state.micMuted,
            icon = if (state.micMuted) Icons.Outlined.MicOff else Icons.Outlined.Mic,
            onClick = onToggleMicMuted,
        )
    }
    if (vertical) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            focusLock(); stabilize(); screenOn(); micMute()
        }
    } else {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            focusLock(); stabilize(); screenOn(); micMute()
        }
    }
}

/**
 * Square focus reticle drawn at the tapped point. Turns gold with a padlock badge when the
 * pitch focus is locked, so the operator can see at a glance that AF is held on the strip.
 */
@Composable
private fun FocusReticle(xPx: Float, yPx: Float, locked: Boolean) {
    val density = LocalDensity.current
    val ringSize = 76.dp
    val halfPx = with(density) { ringSize.toPx() / 2f }
    val color = if (locked) AppColors.Accent else Color.White
    Box(
        modifier = Modifier
            .offset { IntOffset((xPx - halfPx).roundToInt(), (yPx - halfPx).roundToInt()) }
            .size(ringSize)
            .border(1.5.dp, color, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (locked) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = "Focus locked",
                tint = color,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun StudioTopBar(
    state: StudioUiState,
    onBack: () -> Unit,
    onDestination: () -> Unit,
    onMenu: () -> Unit,
    onShare: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CameraCircleButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = state.streaming,
                transitionSpec = {
                    (fadeIn(AppMotion.enterSpec()) + scaleIn(
                        initialScale = AppMotion.EnterScale,
                        animationSpec = AppMotion.enterSpec(),
                    )) togetherWith
                        (fadeOut(AppMotion.exitSpec()) + scaleOut(
                            targetScale = AppMotion.ExitScale,
                            animationSpec = AppMotion.exitSpec(),
                        ))
                },
                label = "topBarStatus",
            ) { streaming ->
                if (streaming) {
                    LiveTimerBadge(state.liveElapsedSeconds, state.paused)
                } else {
                    DestinationChip(
                        label = state.destinationLabel,
                        ready = state.destinationReady,
                        onClick = onDestination,
                    )
                }
            }
        }
        AnimatedContent(
            targetState = state.streaming && onShare != null,
            transitionSpec = {
                (fadeIn(AppMotion.enterSpec()) + scaleIn(
                    initialScale = AppMotion.EnterScale,
                    animationSpec = AppMotion.enterSpec(),
                )) togetherWith
                    (fadeOut(AppMotion.exitSpec()) + scaleOut(
                        targetScale = AppMotion.ExitScale,
                        animationSpec = AppMotion.exitSpec(),
                    ))
            },
            label = "topBarAction",
        ) { shareMode ->
            if (shareMode && onShare != null) {
                CameraCircleButton(onClick = onShare) {
                    Icon(Icons.Outlined.IosShare, contentDescription = "Share", tint = Color.White)
                }
            } else {
                CameraCircleButton(onClick = onMenu) {
                    Icon(Icons.Outlined.MoreHoriz, contentDescription = "Menu", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun StudioStatusMessages(
    state: StudioUiState,
    onLowerQuality: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        state.statusMessage.takeIf { it.isNotBlank() && !state.streaming }?.let { msg ->
            Text(
                msg,
                color = Color.White,
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    .padding(12.dp),
            )
        }
        state.error?.let {
            ErrorBanner(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        if (state.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(
                    Icons.Outlined.Whatshot,
                    contentDescription = null,
                    tint = AppColors.Warning,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Phone is overheating — quality may drop automatically soon.",
                    color = AppColors.Warning,
                    modifier = Modifier.weight(1f),
                )
                if (state.thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onLowerQuality) {
                        Text("Lower quality", color = AppColors.Warning, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (!state.streaming && !state.destinationReady) {
            Text(
                text = "Tap Dest to set YouTube, Twitch, or a stream key before Go Live",
                color = AppColors.Warning,
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    .padding(12.dp),
            )
        } else if (!state.streaming && !state.previewReady) {
            Text(
                text = "Preparing camera…",
                color = AppColors.Warning,
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                    .padding(12.dp),
            )
        }
    }
}

/** Shutter caption that crossfades between states instead of snapping. */
@Composable
private fun ShutterLabel(state: StudioUiState) {
    AnimatedContent(
        targetState = shutterLabel(state),
        transitionSpec = {
            fadeIn(AppMotion.enterSpec(250)) togetherWith fadeOut(AppMotion.exitSpec(150))
        },
        label = "shutterLabel",
    ) { label ->
        Text(
            label,
            color = shutterLabelColor(state),
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun shutterLabel(state: StudioUiState): String = when {
    state.streaming -> "STOP"
    state.busy -> "CONNECTING…"
    state.previewReady -> "GO LIVE"
    else -> "PREPARING…"
}

@Composable
private fun shutterLabelColor(state: StudioUiState): Color = when {
    state.streaming -> AppColors.Live
    state.previewReady && state.destinationReady -> Color.White
    else -> Color.White.copy(alpha = 0.5f)
}

@Composable
private fun PortraitControls(
    state: StudioUiState,
    onBack: () -> Unit,
    onShutter: () -> Unit,
    onPause: () -> Unit,
    onDestination: () -> Unit,
    onOverlay: () -> Unit,
    onScoring: () -> Unit,
    onMenu: () -> Unit,
    onShare: (() -> Unit)?,
    onToggleStabilization: () -> Unit,
    onToggleKeepScreenOn: () -> Unit,
    onToggleFocusLock: () -> Unit,
    onToggleMicMuted: () -> Unit,
    onLowerQuality: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        StudioTopBar(state, onBack, onDestination, onMenu, onShare)

        Spacer(Modifier.weight(1f))

        Column(modifier = Modifier.padding(bottom = 12.dp)) {
            StudioStatusMessages(state, onLowerQuality)
            ToolButtons(state, onDestination, onOverlay, onScoring, vertical = false)
            Spacer(Modifier.height(14.dp))
            QuickToggles(
                state = state,
                onToggleStabilization = onToggleStabilization,
                onToggleKeepScreenOn = onToggleKeepScreenOn,
                onToggleFocusLock = onToggleFocusLock,
                onToggleMicMuted = onToggleMicMuted,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center,
            ) {
                Box(modifier = Modifier.size(72.dp)) {
                    PauseReveal(
                        visible = state.streaming,
                        paused = state.paused,
                        size = 48,
                        onPause = onPause,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CameraShutterButton(
                        live = state.streaming,
                        busy = state.busy,
                        enabled = state.previewReady || state.streaming,
                        onClick = onShutter,
                    )
                    Spacer(Modifier.height(8.dp))
                    ShutterLabel(state)
                }
                Spacer(Modifier.size(72.dp))
            }
        }
    }
}

@Composable
private fun LandscapeControls(
    state: StudioUiState,
    onBack: () -> Unit,
    onShutter: () -> Unit,
    onPause: () -> Unit,
    onDestination: () -> Unit,
    onOverlay: () -> Unit,
    onScoring: () -> Unit,
    onMenu: () -> Unit,
    onShare: (() -> Unit)?,
    onToggleStabilization: () -> Unit,
    onToggleKeepScreenOn: () -> Unit,
    onToggleFocusLock: () -> Unit,
    onToggleMicMuted: () -> Unit,
    onLowerQuality: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        // Top bar across the top.
        StudioTopBar(
            state = state,
            onBack = onBack,
            onDestination = onDestination,
            onMenu = onMenu,
            onShare = onShare,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // Status / error column at the top-start, narrow so it doesn't cover the board
        // or the side rails.
        StudioStatusMessages(
            state = state,
            onLowerQuality = onLowerQuality,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 56.dp, start = 88.dp)
                .fillMaxWidth(0.5f),
        )

        // Left rail: Dest / Style / Score tools, vertically centered so they never push
        // the Go Live button off-screen.
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .padding(start = 10.dp, top = 56.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ToolButtons(state, onDestination, onOverlay, onScoring, vertical = true)
        }

        // Right rail: quick toggles directly above the Go Live shutter, the whole group
        // vertically centered on the right edge — Go Live is always visible and reachable.
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(end = 10.dp, top = 56.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            QuickToggles(
                state = state,
                onToggleStabilization = onToggleStabilization,
                onToggleKeepScreenOn = onToggleKeepScreenOn,
                onToggleFocusLock = onToggleFocusLock,
                onToggleMicMuted = onToggleMicMuted,
                vertical = true,
            )
            Spacer(Modifier.height(16.dp))
            PauseReveal(
                visible = state.streaming,
                paused = state.paused,
                size = 44,
                onPause = onPause,
                belowSpacing = 12.dp,
            )
            CameraShutterButton(
                live = state.streaming,
                busy = state.busy,
                enabled = state.previewReady || state.streaming,
                onClick = onShutter,
            )
            Spacer(Modifier.height(6.dp))
            ShutterLabel(state)
        }
    }
}
