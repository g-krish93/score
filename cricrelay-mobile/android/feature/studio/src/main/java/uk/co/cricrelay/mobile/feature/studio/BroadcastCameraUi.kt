package uk.co.cricrelay.mobile.feature.studio

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.PowerManager
import uk.co.cricrelay.mobile.ui.AppColors
import uk.co.cricrelay.mobile.ui.AppFonts
import uk.co.cricrelay.mobile.ui.AppTypography
import uk.co.cricrelay.mobile.ui.BroadcastGradientScrim
import uk.co.cricrelay.mobile.ui.CameraCircleButton
import uk.co.cricrelay.mobile.ui.ErrorBanner
import uk.co.cricrelay.mobile.ui.glassPill

/**
 * The studio surface (1b Checklist gate). Idle: top bar + glance rail + the checklist panel
 * feeding the segmented Go Live ring. Live: everything collapses to one broadcast bug and one
 * transport strip. PiP renders the bare camera only.
 */
@Composable
fun BroadcastCameraUi(
    state: StudioUiState,
    onBack: () -> Unit,
    onShutter: () -> Unit,
    onPause: () -> Unit,
    onCheckTap: (CheckKind) -> Unit,
    onBoard: () -> Unit,
    onCameraSettings: () -> Unit,
    onMenu: () -> Unit,
    onShare: (() -> Unit)?,
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

        // Scoreboard + watermark are burned into the camera GL surface during preview and
        // stream (parity with iOS) — no separate Compose overlay needed.

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

            if (state.streaming) {
                LiveChrome(
                    state = state,
                    landscape = landscape,
                    onPause = onPause,
                    onBoard = onBoard,
                    onToggleFocusLock = onToggleFocusLock,
                    onToggleMicMuted = onToggleMicMuted,
                    onShare = onShare,
                    onStop = onShutter,
                    onLowerQuality = onLowerQuality,
                )
            } else if (landscape) {
                IdleLandscapeChrome(
                    state = state,
                    onBack = onBack,
                    onCameraSettings = onCameraSettings,
                    onMenu = onMenu,
                    onCheckTap = onCheckTap,
                    onBoard = onBoard,
                    onToggleFocusLock = onToggleFocusLock,
                    onToggleMicMuted = onToggleMicMuted,
                    onGoLive = onShutter,
                    onLowerQuality = onLowerQuality,
                )
            } else {
                IdlePortraitChrome(
                    state = state,
                    onBack = onBack,
                    onCameraSettings = onCameraSettings,
                    onMenu = onMenu,
                    onCheckTap = onCheckTap,
                    onBoard = onBoard,
                    onToggleFocusLock = onToggleFocusLock,
                    onToggleMicMuted = onToggleMicMuted,
                    onGoLive = onShutter,
                    onLowerQuality = onLowerQuality,
                )
            }

            // Drawn last so a low tap's reticle sits above the scrim + controls, never behind them.
            state.focusX?.let { fx ->
                state.focusY?.let { fy ->
                    StudioFocusReticle(xPx = fx, yPx = fy, locked = state.focusLocked)
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

/** Idle top bar: back, the Studio wordmark, camera settings, and the broadcast menu. */
@Composable
private fun StudioTopBar(
    onBack: () -> Unit,
    onCameraSettings: () -> Unit,
    onMenu: () -> Unit,
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
        Spacer(Modifier.width(10.dp))
        Text(
            "Studio",
            fontFamily = AppFonts.Archivo,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 15.sp,
            letterSpacing = (-0.2).sp,
            color = Color.White,
        )
        Spacer(Modifier.weight(1f))
        CameraCircleButton(onClick = onCameraSettings) {
            Icon(Icons.Outlined.Tune, contentDescription = "Camera settings", tint = Color.White)
        }
        Spacer(Modifier.width(8.dp))
        CameraCircleButton(onClick = onMenu) {
            Icon(Icons.Outlined.MoreHoriz, contentDescription = "Menu", tint = Color.White)
        }
    }
}

@Composable
private fun IdlePortraitChrome(
    state: StudioUiState,
    onBack: () -> Unit,
    onCameraSettings: () -> Unit,
    onMenu: () -> Unit,
    onCheckTap: (CheckKind) -> Unit,
    onBoard: () -> Unit,
    onToggleFocusLock: () -> Unit,
    onToggleMicMuted: () -> Unit,
    onGoLive: () -> Unit,
    onLowerQuality: () -> Unit,
) {
    val checks = StudioChecklist.deriveChecks(state)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        StudioTopBar(onBack = onBack, onCameraSettings = onCameraSettings, onMenu = onMenu)

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            GlanceRail(
                focusLocked = state.focusLocked,
                micMuted = state.micMuted,
                onToggleFocusLock = onToggleFocusLock,
                onToggleMicMuted = onToggleMicMuted,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp),
            )
        }

        // Bottom stack sits over the BroadcastGradientScrim for the SPEC contrast floor.
        Column(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
            StudioStatusMessages(state, onLowerQuality)
            if (state.zoomLevel > 1.1f) {
                ZoomPill(state.zoomLevel)
                Spacer(Modifier.height(8.dp))
            }
            BoardChip(onClick = onBoard)
            Spacer(Modifier.height(10.dp))
            ChecklistPanel(
                checks = checks,
                onCheckTap = onCheckTap,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SegmentedGoLiveRing(checks = checks, busy = state.busy, onClick = onGoLive)
                Spacer(Modifier.height(8.dp))
                GoLiveRingCaption(checks)
            }
        }
    }
}

@Composable
private fun IdleLandscapeChrome(
    state: StudioUiState,
    onBack: () -> Unit,
    onCameraSettings: () -> Unit,
    onMenu: () -> Unit,
    onCheckTap: (CheckKind) -> Unit,
    onBoard: () -> Unit,
    onToggleFocusLock: () -> Unit,
    onToggleMicMuted: () -> Unit,
    onGoLive: () -> Unit,
    onLowerQuality: () -> Unit,
) {
    val checks = StudioChecklist.deriveChecks(state)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        StudioTopBar(
            onBack = onBack,
            onCameraSettings = onCameraSettings,
            onMenu = onMenu,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // Status / error column at the top-start, narrow so it doesn't cover the board
        // or the right-docked checklist.
        StudioStatusMessages(
            state = state,
            onLowerQuality = onLowerQuality,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 56.dp, start = 16.dp)
                .fillMaxWidth(0.45f),
        )

        // Glance pills hug the left edge, vertically centred.
        GlanceRail(
            focusLocked = state.focusLocked,
            micMuted = state.micMuted,
            onToggleFocusLock = onToggleFocusLock,
            onToggleMicMuted = onToggleMicMuted,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 12.dp),
        )

        // Zoom + board affordances stay bottom-start, clear of the checklist dock.
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.zoomLevel > 1.1f) ZoomPill(state.zoomLevel)
            BoardChip(onClick = onBoard)
        }

        // Checklist right-docked with the ring beneath it.
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp, top = 48.dp, bottom = 8.dp)
                .width(340.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ChecklistPanel(
                checks = checks,
                onCheckTap = onCheckTap,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            SegmentedGoLiveRing(checks = checks, busy = state.busy, onClick = onGoLive)
            Spacer(Modifier.height(6.dp))
            GoLiveRingCaption(checks)
        }
    }
}

/** Live chrome, both orientations: broadcast bug top-left, one transport strip at the bottom. */
@Composable
private fun LiveChrome(
    state: StudioUiState,
    landscape: Boolean,
    onPause: () -> Unit,
    onBoard: () -> Unit,
    onToggleFocusLock: () -> Unit,
    onToggleMicMuted: () -> Unit,
    onShare: (() -> Unit)?,
    onStop: () -> Unit,
    onLowerQuality: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 14.dp, end = 16.dp),
        ) {
            BroadcastBug(
                elapsedSeconds = state.liveElapsedSeconds,
                paused = state.paused,
                stats = state.streamStats,
            )
            Spacer(Modifier.height(8.dp))
            StudioStatusMessages(
                state = state,
                onLowerQuality = onLowerQuality,
                modifier = if (landscape) Modifier.fillMaxWidth(0.55f) else Modifier.fillMaxWidth(),
            )
        }

        LiveTransportStrip(
            focusLocked = state.focusLocked,
            micMuted = state.micMuted,
            paused = state.paused,
            onBoard = onBoard,
            onToggleFocusLock = onToggleFocusLock,
            onToggleMicMuted = onToggleMicMuted,
            onPause = onPause,
            onShare = onShare,
            onStop = onStop,
            compact = !landscape,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = if (landscape) 16.dp else 20.dp),
        )
    }
}

/** Transient status, reconnect, error, and thermal banners — glass surfaces per SPEC. */
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
                style = AppTypography.bodyMedium.copy(color = Color.White),
                modifier = Modifier
                    .padding(vertical = 6.dp)
                    .fillMaxWidth()
                    .glassPill(14.dp)
                    .padding(12.dp),
            )
        }
        if (state.reconnecting) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(vertical = 6.dp)
                    .fillMaxWidth()
                    .glassPill(14.dp)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                CircularProgressIndicator(
                    color = AppColors.Warning,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Connection lost — reconnecting…",
                    color = AppColors.Warning,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        state.error?.let {
            ErrorBanner(it, Modifier.padding(vertical = 4.dp))
        }
        if (state.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(vertical = 6.dp)
                    .fillMaxWidth()
                    .glassPill(14.dp)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
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
    }
}
