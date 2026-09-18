package uk.co.cricrelay.mobile.feature.home

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import android.graphics.Bitmap
import android.content.Intent
import uk.co.cricrelay.mobile.ui.AppColors
import uk.co.cricrelay.mobile.ui.LabeledSlider
import uk.co.cricrelay.shared.model.RemoteCameraIds
import uk.co.cricrelay.shared.model.SponsorDisplayMode
import uk.co.cricrelay.shared.model.SponsorLayoutMode
import uk.co.cricrelay.mobile.ui.AppSpacing
import uk.co.cricrelay.mobile.ui.AppTypography
import uk.co.cricrelay.mobile.ui.CameraCircleButton
import uk.co.cricrelay.mobile.ui.ErrorBanner
import uk.co.cricrelay.mobile.ui.GhostButton
import uk.co.cricrelay.mobile.ui.PrimaryButton
import uk.co.cricrelay.mobile.ui.SecondaryButton
import uk.co.cricrelay.mobile.ui.StudioBackdrop
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

@Composable
fun RemoteControlScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RemoteControlViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> cameraGranted = granted }
    var confirmStop by remember { mutableStateOf(false) }
    var confirmStart by remember { mutableStateOf(false) }

    fun shareWatchUrl(url: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Watch live on CricRelay")
            putExtra(Intent.EXTRA_TEXT, url)
        }
        context.startActivity(Intent.createChooser(intent, "Share watch link"))
        viewModel.onWatchShared()
    }

    LaunchedEffect(Unit) {
        if (!cameraGranted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Redeem a system-camera / Lens deep link (`cricrelay://pair?…`) when present.
    val pendingPairUri by PairDeepLinkBus.pendingUri.collectAsStateWithLifecycle()
    LaunchedEffect(pendingPairUri) {
        val uri = PairDeepLinkBus.consume() ?: return@LaunchedEffect
        viewModel.onQrScanned(uri)
    }

    if (confirmStop) {
        ConfirmRemoteActionDialog(
            title = "Stop broadcast?",
            body = "Viewers will lose the live feed until you go live again.",
            confirmLabel = "Stop",
            onConfirm = {
                confirmStop = false
                viewModel.sendCommand("stop_broadcast")
            },
            onDismiss = { confirmStop = false },
        )
    }
    if (confirmStart) {
        ConfirmRemoteActionDialog(
            title = "Start broadcast?",
            body = "The tripod phone will go live to the configured destination.",
            confirmLabel = "Go live",
            onConfirm = {
                confirmStart = false
                viewModel.sendCommand("start_broadcast")
            },
            onDismiss = { confirmStart = false },
        )
    }
    var confirmTakeLive by remember { mutableStateOf(false) }
    if (confirmTakeLive) {
        val label = uk.co.cricrelay.shared.model.RemoteCameraIds.label(state.selectedCameraId)
        ConfirmRemoteActionDialog(
            title = "Take live on $label?",
            body = "The other end soft-stops. This phone publishes on the same YouTube/RTMP feed — viewers keep one watch URL.",
            confirmLabel = "Take live",
            onConfirm = {
                confirmTakeLive = false
                viewModel.takeLive()
            },
            onDismiss = { confirmTakeLive = false },
        )
    }

    StudioBackdrop(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = AppSpacing.lg),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                CameraCircleButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                    )
                }
            }
            Spacer(Modifier.height(AppSpacing.md))
            Text(
                "Remote Control",
                style = AppTypography.headlineMedium,
                color = AppColors.OnBackground,
            )
            Text(
                if (state.paired) "Control the broadcast on ${state.matchSlug}" else "Scan the QR from Broadcast menu → Pair Remote — or open a pair link from the phone camera. No club login needed.",
                style = AppTypography.bodyMedium,
                color = AppColors.OnBackgroundMuted,
            )
            Spacer(Modifier.height(AppSpacing.md))
            state.error?.let { ErrorBanner(it) }
            if (state.statusMessage.isNotBlank()) {
                Text(
                    state.statusMessage,
                    style = AppTypography.bodySmall,
                    color = AppColors.OnBackgroundDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(AppSpacing.md))
            if (!state.paired) {
                if (cameraGranted) {
                    QrScanner(
                        onCode = viewModel::onQrScanned,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Camera permission is required to scan the pairing code.",
                            style = AppTypography.bodyMedium,
                            color = AppColors.OnBackgroundMuted,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                ) {
                    DualEndCameraStrip(
                        selectedCameraId = state.selectedCameraId,
                        liveCameraId = state.liveCameraId,
                        cameras = state.cameras,
                        thumbA = state.thumbA,
                        thumbB = state.thumbB,
                        onSelect = viewModel::selectCamera,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    state.healthAlert?.let { alert ->
                        HealthAlertBanner(
                            message = alert,
                            onDismiss = viewModel::dismissHealthAlert,
                        )
                    }
                    RemotePreviewPane(
                        bitmap = state.previewBitmap,
                        stale = state.previewStale,
                        ageSec = state.previewAgeSec,
                        camera = state.camera,
                        pendingAck = state.pendingAck,
                        focusNx = state.focusReticleNx,
                        focusNy = state.focusReticleNy,
                        onTapNormalized = viewModel::onPreviewTap,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .semantics {
                                contentDescription = buildString {
                                    append("Camera preview. ")
                                    append(
                                        when {
                                            state.camera.reconnecting -> "Reconnecting. "
                                            state.camera.paused -> "Paused. "
                                            state.camera.streaming -> "Live. "
                                            else -> "Idle. "
                                        },
                                    )
                                    if (state.previewStale) append("Preview stale. ")
                                    append("Double tap to focus.")
                                }
                            },
                    )
                    if (state.watchUrl.isNotBlank()) {
                        SecondaryButton(
                            text = "Share watch link",
                            enabled = !state.busy,
                            onClick = { shareWatchUrl(state.watchUrl) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    contentDescription = "Share watch party link with viewers"
                                    role = Role.Button
                                },
                        )
                    }
                    val zoomMin = state.camera.zoomMin.coerceAtMost(state.camera.zoomMax)
                    val zoomMax = state.camera.zoomMax.coerceAtLeast(zoomMin + 0.01f)
                    LabeledSlider(
                        label = "Zoom",
                        valueText = String.format("%.1f×", state.zoomDraft),
                        value = state.zoomDraft.coerceIn(zoomMin, zoomMax),
                        onValueChange = viewModel::onZoomDraft,
                        valueRange = zoomMin..zoomMax,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                    ) {
                        listOf(
                            0 to "Off",
                            1 to "Standard",
                            2 to "Cinematic",
                        ).forEach { (level, label) ->
                            val selected = state.camera.stab == level
                            Text(
                                label,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(AppSpacing.radiusSm))
                                    .clickable(enabled = !state.camera.streaming && !state.busy) {
                                        viewModel.setStabilization(level)
                                    }
                                    .background(
                                        if (selected) AppColors.Primary.copy(alpha = 0.35f)
                                        else AppColors.SurfaceElevated.copy(alpha = 0.7f),
                                    )
                                    .padding(vertical = 10.dp),
                                style = AppTypography.labelMedium,
                                color = if (state.camera.streaming) {
                                    AppColors.OnBackgroundDim
                                } else {
                                    AppColors.OnBackground
                                },
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    if (state.camera.streaming) {
                        Text(
                            "Stabilization is locked while live",
                            style = AppTypography.bodySmall,
                            color = AppColors.OnBackgroundDim,
                        )
                    }
                    val canTakeLive = state.selectedCameraId != state.liveCameraId &&
                        (
                            state.cameras.any { it.cameraId == state.selectedCameraId && !it.stale } ||
                                state.liveCameraId != null
                            )
                    PrimaryButton(
                        text = "Take live",
                        enabled = !state.busy && canTakeLive,
                        onClick = { confirmTakeLive = true },
                    )
                    PrimaryButton(
                        text = "Start broadcast",
                        enabled = !state.busy && !state.camera.streaming,
                        onClick = { confirmStart = true },
                    )
                    PrimaryButton(
                        text = "Stop broadcast",
                        enabled = !state.busy && state.camera.streaming,
                        onClick = { confirmStop = true },
                    )
                    SecondaryButton(
                        text = if (state.camera.paused) "Resume" else "Pause",
                        enabled = !state.busy && state.camera.streaming && state.pendingAck == null,
                        onClick = { viewModel.setPaused(!state.camera.paused) },
                    )
                    SecondaryButton(
                        text = if (state.camera.muted) "Unmute mic" else "Mute mic",
                        enabled = !state.busy && state.pendingAck == null,
                        onClick = { viewModel.setMute(!state.camera.muted) },
                    )
                    SecondaryButton(
                        text = if (state.camera.locked) "Unlock focus" else "Lock focus",
                        enabled = !state.busy && state.pendingAck == null,
                        onClick = { viewModel.setFocusLock(!state.camera.locked) },
                    )
                    Spacer(Modifier.height(AppSpacing.sm))
                    RemoteSponsorSection(
                        state = state,
                        onRefresh = viewModel::refreshContext,
                        onPrefsChange = viewModel::updateSponsorPrefs,
                    )
                    Spacer(Modifier.height(AppSpacing.sm))
                    GhostButton(text = "Unpair", onClick = viewModel::unpair)
                }
            }
        }
    }
}

@Composable
private fun HealthAlertBanner(
    message: String,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.radiusSm))
            .background(AppColors.Warning.copy(alpha = 0.22f))
            .border(1.dp, AppColors.Warning.copy(alpha = 0.55f), RoundedCornerShape(AppSpacing.radiusSm))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics { contentDescription = "Stream health alert: $message" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        Text(
            message,
            style = AppTypography.bodySmall,
            color = AppColors.OnBackground,
            modifier = Modifier.weight(1f),
        )
        Text(
            "Dismiss",
            style = AppTypography.labelMedium,
            color = AppColors.Warning,
            modifier = Modifier
                .heightIn(min = AppSpacing.touchTarget)
                .clickable(onClick = onDismiss)
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .semantics {
                    contentDescription = "Dismiss health alert"
                    role = Role.Button
                },
        )
    }
}

@Composable
private fun DualEndCameraStrip(
    selectedCameraId: String,
    liveCameraId: String?,
    cameras: List<uk.co.cricrelay.shared.model.RemoteCameraInfo>,
    thumbA: Bitmap?,
    thumbB: Bitmap?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val byId = cameras.associateBy { it.cameraId }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        listOf(
            uk.co.cricrelay.shared.model.RemoteCameraIds.END_A to thumbA,
            uk.co.cricrelay.shared.model.RemoteCameraIds.END_B to thumbB,
        ).forEach { (id, thumb) ->
            val info = byId[id]
            val selected = selectedCameraId == id
            val isLive = liveCameraId == id
            val online = info != null && !info.stale
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = AppSpacing.touchTarget)
                    .clip(RoundedCornerShape(AppSpacing.radiusSm))
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = when {
                            selected -> AppColors.Primary
                            isLive -> AppColors.Error.copy(alpha = 0.7f)
                            else -> AppColors.Border
                        },
                        shape = RoundedCornerShape(AppSpacing.radiusSm),
                    )
                    .clickable { onSelect(id) }
                    .background(AppColors.SurfaceElevated.copy(alpha = 0.85f))
                    .padding(6.dp)
                    .semantics {
                        val label = RemoteCameraIds.label(id)
                        contentDescription = buildString {
                            append(label)
                            if (selected) append(", selected")
                            if (isLive) append(", live")
                            if (!online) append(", offline")
                            append(". Double tap to control this end.")
                        }
                        role = Role.Button
                    },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (thumb != null) {
                        Image(
                            bitmap = thumb.asImageBitmap(),
                            contentDescription = uk.co.cricrelay.shared.model.RemoteCameraIds.label(id),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(
                            if (online) "…" else "Offline",
                            style = AppTypography.bodySmall,
                            color = AppColors.OnBackgroundDim,
                        )
                    }
                    if (isLive) {
                        Text(
                            "LIVE",
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .background(AppColors.Error, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            style = AppTypography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Text(
                    uk.co.cricrelay.shared.model.RemoteCameraIds.label(id),
                    style = AppTypography.labelMedium,
                    color = AppColors.OnBackground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ConfirmRemoteActionDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = AppColors.Error)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun RemotePreviewPane(
    bitmap: Bitmap?,
    stale: Boolean,
    ageSec: Int?,
    camera: uk.co.cricrelay.shared.model.RemoteCameraState,
    pendingAck: String?,
    focusNx: Float?,
    focusNy: Float?,
    onTapNormalized: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(AppSpacing.radiusMd))
            .background(AppColors.SurfaceElevated)
            .onSizeChanged { size = it }
            .pointerInput(size) {
                detectTapGestures { offset ->
                    if (size.width > 0 && size.height > 0) {
                        onTapNormalized(
                            (offset.x / size.width).coerceIn(0f, 1f),
                            (offset.y / size.height).coerceIn(0f, 1f),
                        )
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Camera preview",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                "Waiting for camera preview…",
                style = AppTypography.bodyMedium,
                color = AppColors.OnBackgroundMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(AppSpacing.md),
            )
        }
        // HUD strip
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val phase = when {
                camera.reconnecting -> "RECONNECTING"
                camera.paused -> "PAUSED"
                camera.streaming -> "LIVE"
                else -> "IDLE"
            }
            Text(
                phase,
                style = AppTypography.labelMedium,
                color = when {
                    camera.reconnecting -> AppColors.Warning
                    camera.streaming && !camera.paused -> AppColors.Error
                    else -> Color.White
                },
                fontWeight = FontWeight.Bold,
            )
            camera.bitrateKbps?.takeIf { it > 0 }?.let {
                Text("${it} kbps", style = AppTypography.labelSmall, color = Color.White.copy(alpha = 0.85f))
            }
            if (camera.thermal >= 3) {
                Text("HOT", style = AppTypography.labelSmall, color = AppColors.Warning, fontWeight = FontWeight.Bold)
            }
            Text(
                when (camera.stab) {
                    0 -> "Stab off"
                    2 -> "Cinematic"
                    else -> "Stab on"
                },
                style = AppTypography.labelSmall,
                color = Color.White.copy(alpha = 0.75f),
            )
            Spacer(modifier = Modifier.weight(1f))
            if (camera.muted) {
                Text("MUTED", style = AppTypography.labelSmall, color = AppColors.Warning)
            }
            if (camera.locked) {
                Text("AF LOCK", style = AppTypography.labelSmall, color = AppColors.Accent)
            }
        }
        Text(
            "YouTube is ~15–30s behind this preview",
            style = AppTypography.labelSmall,
            color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
        if (focusNx != null && focusNy != null && size.width > 0) {
            Box(
                modifier = Modifier
                    .offset {
                        androidx.compose.ui.unit.IntOffset(
                            x = ((size.width * focusNx) - 18f).toInt().coerceAtLeast(0),
                            y = ((size.height * focusNy) - 18f).toInt().coerceAtLeast(0),
                        )
                    }
                    .size(36.dp)
                    .border(2.dp, AppColors.Accent, RoundedCornerShape(4.dp)),
            )
        }
        if (stale) {
            val age = ageSec?.let { " · ${it}s ago" }.orEmpty()
            Text(
                "Camera offline$age",
                style = AppTypography.labelMedium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 36.dp)
                    .clip(RoundedCornerShape(AppSpacing.radiusSm))
                    .background(AppColors.Error.copy(alpha = 0.85f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        if (!pendingAck.isNullOrBlank()) {
            Text(
                pendingAck,
                style = AppTypography.labelMedium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(AppSpacing.radiusSm))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun RemoteSponsorSection(
    state: RemoteControlUiState,
    onRefresh: () -> Unit,
    onPrefsChange: ((uk.co.cricrelay.shared.model.OverlayLayoutPrefs) -> uk.co.cricrelay.shared.model.OverlayLayoutPrefs) -> Unit,
) {
    val prefs = state.sponsorPrefs
    val scrollMode = SponsorDisplayMode.isScroll(prefs.sponsorDisplayMode)
    val activeSponsors = state.sponsors.filter { it.isActive }

    Text("Sponsor overlay", style = AppTypography.titleSmall, color = AppColors.OnBackground)
    Text(
        "Changes apply on the broadcast phone.",
        style = AppTypography.bodySmall,
        color = AppColors.OnBackgroundDim,
    )
    if (state.watchUrl.isNotBlank()) {
        Text(
            "Watch link ready — use Share watch link above for the watch party.",
            style = AppTypography.bodySmall,
            color = AppColors.Accent,
        )
    }
    if (state.contextLoading) {
        Text("Loading sponsor settings…", style = AppTypography.bodySmall, color = AppColors.OnBackgroundMuted)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Sponsor logo", style = AppTypography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = prefs.sponsorEnabled,
            onCheckedChange = { enabled ->
                onPrefsChange { it.copy(sponsorEnabled = enabled) }
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = AppColors.OnPrimary,
                checkedTrackColor = AppColors.Primary,
            ),
        )
    }
    if (prefs.sponsorEnabled && activeSponsors.isNotEmpty()) {
        Text("How to show", style = AppTypography.bodySmall, color = AppColors.OnBackgroundMuted)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            items(
                listOf(
                    SponsorLayoutMode.SINGLE to "One logo",
                    SponsorLayoutMode.MULTI to "All at once",
                    SponsorLayoutMode.CAROUSEL to "Carousel",
                ),
            ) { (id, label) ->
                val selected = prefs.sponsorLayoutMode == id
                Text(
                    label,
                    modifier = Modifier
                        .clip(RoundedCornerShape(AppSpacing.radiusSm))
                        .clickable {
                            onPrefsChange {
                                var next = it.copy(sponsorLayoutMode = id)
                                if (!SponsorLayoutMode.allowsMultiSelect(id) && next.activeSponsorIds.size > 1) {
                                    next = next.copy(
                                        activeSponsorIds = next.activeSponsorIds.take(1),
                                        activeSponsorId = next.activeSponsorIds.firstOrNull(),
                                    )
                                }
                                next
                            }
                        }
                        .background(
                            if (selected) AppColors.Primary.copy(alpha = 0.25f)
                            else AppColors.SurfaceElevated.copy(alpha = 0.7f),
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    style = AppTypography.bodySmall,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
        Text("Select sponsor(s)", style = AppTypography.bodySmall, color = AppColors.OnBackgroundMuted)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            items(activeSponsors) { sponsor ->
                val multiPick = SponsorLayoutMode.allowsMultiSelect(prefs.sponsorLayoutMode)
                val selected = if (multiPick) {
                    sponsor.id in prefs.activeSponsorIds
                } else {
                    sponsor.id in prefs.activeSponsorIds ||
                        (prefs.activeSponsorIds.isEmpty() && sponsor.id == activeSponsors.firstOrNull()?.id)
                }
                Text(
                    sponsor.name,
                    modifier = Modifier
                        .clip(RoundedCornerShape(AppSpacing.radiusSm))
                        .background(
                            if (selected) AppColors.Primary.copy(alpha = 0.25f)
                            else AppColors.SurfaceElevated.copy(alpha = 0.7f),
                        )
                        .border(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) AppColors.Primary else AppColors.Border,
                            shape = RoundedCornerShape(AppSpacing.radiusSm),
                        )
                        .clickable {
                            onPrefsChange { prefsIn ->
                                if (multiPick) {
                                    val ids = if (sponsor.id in prefsIn.activeSponsorIds) {
                                        prefsIn.activeSponsorIds.filter { it != sponsor.id }
                                    } else {
                                        (prefsIn.activeSponsorIds + sponsor.id).take(6)
                                    }
                                    prefsIn.copy(
                                        activeSponsorIds = ids,
                                        activeSponsorId = ids.firstOrNull(),
                                    )
                                } else {
                                    prefsIn.copy(
                                        activeSponsorIds = listOf(sponsor.id),
                                        activeSponsorId = sponsor.id,
                                    )
                                }
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    style = AppTypography.bodySmall,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
        if (prefs.sponsorLayoutMode == SponsorLayoutMode.CAROUSEL) {
            LabeledSlider(
                label = "Carousel interval",
                valueText = "${prefs.sponsorCarouselIntervalSec.toInt()}s",
                value = prefs.sponsorCarouselIntervalSec.toFloat(),
                onValueChange = { v -> onPrefsChange { it.copy(sponsorCarouselIntervalSec = v.toDouble()) } },
                valueRange = 2f..30f,
            )
        }
    }
    if (prefs.sponsorEnabled) {
        Text("Display mode", style = AppTypography.bodySmall, color = AppColors.OnBackgroundMuted)
        val modes = listOf(
            SponsorDisplayMode.STATIC to "Fixed",
            SponsorDisplayMode.SCROLL_TOP to "Scroll top",
            SponsorDisplayMode.SCROLL_ABOVE_BOARD to "Above board",
            SponsorDisplayMode.SCROLL_BELOW_BOARD to "Below board",
            SponsorDisplayMode.SCROLL_BOTTOM to "Scroll bottom",
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            items(modes) { (id, label) ->
                val selected = prefs.sponsorDisplayMode == id
                Text(
                    label,
                    modifier = Modifier
                        .clip(RoundedCornerShape(AppSpacing.radiusSm))
                        .clickable { onPrefsChange { it.copy(sponsorDisplayMode = id) } }
                        .background(
                            if (selected) AppColors.Accent.copy(alpha = 0.2f)
                            else AppColors.SurfaceElevated.copy(alpha = 0.7f),
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    style = AppTypography.bodySmall,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
        LabeledSlider(
            label = "Logo size",
            valueText = "${(prefs.sponsorSizeScale * 100).toInt()}%",
            value = prefs.sponsorSizeScale.toFloat(),
            onValueChange = { v -> onPrefsChange { it.copy(sponsorSizeScale = v.toDouble()) } },
            valueRange = 0.3f..3f,
        )
        LabeledSlider(
            label = "Logo opacity",
            valueText = "${(prefs.sponsorOpacity * 100).toInt()}%",
            value = prefs.sponsorOpacity.toFloat(),
            onValueChange = { v -> onPrefsChange { it.copy(sponsorOpacity = v.toDouble()) } },
            valueRange = 0.2f..1f,
        )
        if (!scrollMode) {
            LabeledSlider(
                label = "Horizontal position",
                valueText = "${(prefs.sponsorPositionX * 100).toInt()}%",
                value = prefs.sponsorPositionX.toFloat(),
                onValueChange = { v -> onPrefsChange { it.copy(sponsorPositionX = v.toDouble()) } },
                valueRange = 0f..1f,
            )
            LabeledSlider(
                label = "Vertical position",
                valueText = "${(prefs.sponsorPositionY * 100).toInt()}%",
                value = prefs.sponsorPositionY.toFloat(),
                onValueChange = { v -> onPrefsChange { it.copy(sponsorPositionY = v.toDouble()) } },
                valueRange = 0f..1f,
            )
        } else {
            LabeledSlider(
                label = "Scroll speed",
                valueText = String.format("%.1f×", prefs.sponsorScrollSpeed),
                value = prefs.sponsorScrollSpeed.toFloat(),
                onValueChange = { v -> onPrefsChange { it.copy(sponsorScrollSpeed = v.toDouble()) } },
                valueRange = 0.3f..3f,
            )
        }
    }
    GhostButton(text = "Refresh from broadcast", onClick = onRefresh)
}

@Composable
private fun QrScanner(
    onCode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient() }
    var handled by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            scanner.close()
            executor.shutdown()
        }
    }

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        },
        modifier = modifier.height(320.dp),
        update = { previewView ->
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { imageProxy ->
                    val mediaImage = imageProxy.image
                    if (mediaImage == null || handled) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                    scanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            val raw = barcodes.firstOrNull { it.rawValue?.startsWith("cricrelay://") == true }
                                ?.rawValue
                            if (!raw.isNullOrBlank() && !handled) {
                                handled = true
                                onCode(raw)
                            }
                        }
                        .addOnCompleteListener { imageProxy.close() }
                }
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                } catch (_: Exception) {
                }
            }, ContextCompat.getMainExecutor(context))
        },
    )
}
