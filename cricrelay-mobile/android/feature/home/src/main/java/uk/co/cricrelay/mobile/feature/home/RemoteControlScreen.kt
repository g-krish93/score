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
import androidx.compose.foundation.layout.padding
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
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp
import uk.co.cricrelay.mobile.ui.AppColors
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import uk.co.cricrelay.mobile.ui.LabeledSlider
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

    LaunchedEffect(Unit) {
        if (!cameraGranted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
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
                if (state.paired) "Control the broadcast on ${state.matchSlug}" else "Scan the QR from Broadcast menu → Pair Remote",
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
                    PrimaryButton(
                        text = "Start broadcast",
                        enabled = !state.busy,
                        onClick = { viewModel.sendCommand("start_broadcast") },
                    )
                    PrimaryButton(
                        text = "Stop broadcast",
                        enabled = !state.busy,
                        onClick = { viewModel.sendCommand("stop_broadcast") },
                    )
                    SecondaryButton(
                        text = "Mute mic",
                        enabled = !state.busy,
                        onClick = { viewModel.sendCommand("mute_mic") },
                    )
                    SecondaryButton(
                        text = "Toggle focus lock",
                        enabled = !state.busy,
                        onClick = { viewModel.sendCommand("toggle_focus_lock") },
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
        "Changes apply on the broadcast phone — camera preview is not shown here.",
        style = AppTypography.bodySmall,
        color = AppColors.OnBackgroundDim,
    )
    if (state.watchUrl.isNotBlank()) {
        Text(
            "Watch live: ${state.watchUrl}",
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
