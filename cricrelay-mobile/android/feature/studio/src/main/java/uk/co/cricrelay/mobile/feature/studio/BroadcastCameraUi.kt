package uk.co.cricrelay.mobile.feature.studio

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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

import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Scoreboard
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import uk.co.cricrelay.shared.model.OverlayLayoutPrefs
import uk.co.cricrelay.mobile.ui.AppColors
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
    onPreviewTap: (Float, Float, Int, Int) -> Unit,
    onPinchZoom: (Float) -> Unit,
    onPreviewSurfaceBound: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val transformState = rememberTransformableState { zoomChange, _, _ ->
        if (zoomChange != 1f) onPinchZoom(zoomChange)
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
    ) {
        val previewWidth = constraints.maxWidth
        val previewHeight = constraints.maxHeight
        val landscape = previewWidth > previewHeight
        val density = LocalDensity.current
        // Board Edit "Position" slider — same bottom-margin math as the burned-in GL overlay.
        val positionLift = with(density) {
            state.overlayPrefs.bottomMarginPx(previewHeight).toDp()
        }
        val boardScaleX = state.overlayPrefs.boardDisplayScaleX()
        val boardScaleY = state.overlayPrefs.boardDisplayScaleY()

        CameraPreviewLayer(
            modifier = Modifier.fillMaxSize(),
            onPreviewSurfaceBound = onPreviewSurfaceBound,
        )

        // Thin scoreboard strip in the preview (before streaming). Portrait: above the
        // bottom controls. Landscape: full width along the bottom (controls are on the right).
        if (!state.streaming) {
            state.overlayPreview?.let { board ->
                val boardAlpha = state.overlayPrefs.opacity.toFloat().coerceIn(0.2f, 1f)
                val boardBaseModifier = Modifier
                    .fillMaxWidth(OverlayLayoutPrefs.REF_WIDTH_FRACTION.toFloat())
                    .graphicsLayer {
                        scaleX = boardScaleX
                        scaleY = boardScaleY
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                val boardModifier = if (landscape) {
                    // Sit centered along the bottom, clearing the left tool rail and the
                    // right Go Live rail.
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 96.dp, end = 110.dp, bottom = 14.dp + positionLift)
                        .then(boardBaseModifier)
                } else {
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 272.dp + positionLift)
                        .then(boardBaseModifier)
                }
                Image(
                    bitmap = board,
                    contentDescription = "Scoreboard preview",
                    // Fit shows the full captured board; FillWidth cropped the bottom when
                    // the rasterized strip was taller than the legacy 16% viewport.
                    contentScale = ContentScale.Fit,
                    alpha = boardAlpha,
                    modifier = boardModifier.clip(RoundedCornerShape(10.dp)),
                )
            }
        }

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
                )
            }
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
        CameraToolButton(label = "Style", active = false, onClick = onOverlay) {
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

/** Stabilize / Keep-screen-on quick toggles, surfaced next to Go Live (no menu dig). */
@Composable
private fun QuickToggles(
    state: StudioUiState,
    onToggleStabilization: () -> Unit,
    onToggleKeepScreenOn: () -> Unit,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
) {
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
    if (vertical) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            stabilize(); screenOn()
        }
    } else {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            stabilize(); screenOn()
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
            if (state.streaming) {
                LiveTimerBadge(state.liveElapsedSeconds, state.paused)
            } else {
                DestinationChip(
                    label = state.destinationLabel,
                    ready = state.destinationReady,
                    onClick = onDestination,
                )
            }
        }
        if (state.streaming && onShare != null) {
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

@Composable
private fun StudioStatusMessages(state: StudioUiState, modifier: Modifier = Modifier) {
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
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        StudioTopBar(state, onBack, onDestination, onMenu, onShare)

        Spacer(Modifier.weight(1f))

        Column(modifier = Modifier.padding(bottom = 12.dp)) {
            StudioStatusMessages(state)
            ToolButtons(state, onDestination, onOverlay, onScoring, vertical = false)
            Spacer(Modifier.height(14.dp))
            QuickToggles(
                state = state,
                onToggleStabilization = onToggleStabilization,
                onToggleKeepScreenOn = onToggleKeepScreenOn,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center,
            ) {
                Box(modifier = Modifier.size(72.dp)) {
                    if (state.streaming) {
                        CameraCircleButton(onClick = onPause, size = 48, modifier = Modifier.align(Alignment.Center)) {
                            Icon(
                                if (state.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                                contentDescription = "Pause",
                                tint = Color.White,
                            )
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CameraShutterButton(
                        live = state.streaming,
                        busy = state.busy,
                        enabled = state.previewReady || state.streaming,
                        onClick = onShutter,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        shutterLabel(state),
                        color = shutterLabelColor(state),
                        fontWeight = FontWeight.Bold,
                    )
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
                vertical = true,
            )
            Spacer(Modifier.height(16.dp))
            if (state.streaming) {
                CameraCircleButton(onClick = onPause, size = 44) {
                    Icon(
                        if (state.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                        contentDescription = "Pause",
                        tint = Color.White,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            CameraShutterButton(
                live = state.streaming,
                busy = state.busy,
                enabled = state.previewReady || state.streaming,
                onClick = onShutter,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                shutterLabel(state),
                color = shutterLabelColor(state),
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
