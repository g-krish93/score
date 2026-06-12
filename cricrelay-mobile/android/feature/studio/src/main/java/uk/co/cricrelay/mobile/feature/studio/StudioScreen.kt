package uk.co.cricrelay.mobile.feature.studio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.view.OrientationEventListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import uk.co.cricrelay.mobile.ui.AppSpacing
import uk.co.cricrelay.mobile.ui.CricRelayBottomSheet
import uk.co.cricrelay.mobile.ui.ErrorBanner
import uk.co.cricrelay.mobile.ui.LoadingState
import uk.co.cricrelay.mobile.ui.PrimaryButton
import uk.co.cricrelay.mobile.ui.BackdropMood
import uk.co.cricrelay.mobile.ui.StudioBackdrop

@Composable
fun StudioScreen(
    matchSlug: String,
    onBack: () -> Unit,
    onOpenScoring: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StudioViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val view = LocalView.current
    val context = LocalContext.current
    val configuration = LocalConfiguration.current

    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val cameraGranted = grants[Manifest.permission.CAMERA] == true
        val audioGranted = grants[Manifest.permission.RECORD_AUDIO] == true
        if (StudioCameraGate.permissionsSatisfied(cameraGranted, audioGranted)) {
            viewModel.onCameraPermissionsGranted()
        } else {
            viewModel.onCameraPermissionsDenied()
        }
    }

    LaunchedEffect(matchSlug) { viewModel.load(matchSlug) }

    LaunchedEffect(state.match?.slug) {
        val slug = state.match?.slug ?: return@LaunchedEffect
        val cameraGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        val audioGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (StudioCameraGate.permissionsSatisfied(cameraGranted, audioGranted)) {
            viewModel.onCameraPermissionsGranted()
        } else {
            permissionsLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                ),
            )
        }
    }

    LaunchedEffect(Unit) {
        viewModel.onStudioVisible()
        repeat(4) {
            delay(150)
            viewModel.onStudioVisible()
        }
    }

    LaunchedEffect(state.streaming) {
        if (state.streaming) {
            repeat(4) {
                delay(200)
                viewModel.onStudioVisible()
            }
        }
    }

    LaunchedEffect(configuration.orientation, state.match?.slug) {
        if (state.match != null) {
            viewModel.onConfigurationChanged()
        }
    }

    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context, SensorManager.SENSOR_DELAY_NORMAL) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return
                val surfaceDeg = when {
                    orientation >= 45 && orientation < 135 -> 270
                    orientation >= 135 && orientation < 225 -> 180
                    orientation >= 225 && orientation < 315 -> 90
                    else -> 0
                }
                viewModel.onDeviceOrientationChanged(surfaceDeg)
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }

    DisposableEffect(Unit) {
        view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        onDispose { viewModel.onStudioHidden() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent),
    ) {
        when {
            StudioCameraGate.shouldShowOpaqueLoadingOverlay(
                loading = state.loading,
                matchLoaded = state.match != null,
                error = state.error,
            ) -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Transparent),
                ) {
                    LoadingState("Opening studio…")
                }
            }
            state.match == null -> {
                StudioBackdrop(mood = BackdropMood.Caution) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            modifier = Modifier.padding(AppSpacing.lg),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            ErrorBanner(state.error ?: "Could not open this stream")
                            Spacer(Modifier.height(AppSpacing.lg))
                            PrimaryButton(text = "Back", onClick = onBack)
                        }
                    }
                }
            }
            else -> {
                BroadcastCameraUi(
                    state = state,
                    onBack = onBack,
                    onShutter = {
                        if (state.streaming) viewModel.stopLive() else viewModel.requestGoLive()
                    },
                    onPause = viewModel::togglePause,
                    onDestination = { viewModel.openSheet(StudioSheet.Destination) },
                    onOverlay = { viewModel.openSheet(StudioSheet.Overlay) },
                    onScoring = { viewModel.openSheet(StudioSheet.Scoring) },
                    onMenu = { viewModel.openSheet(StudioSheet.Menu) },
                    onToggleStabilization = {
                        viewModel.updateOverlayPrefs(
                            state.overlayPrefs.copy(
                                videoStabilization = !state.overlayPrefs.videoStabilization,
                            ),
                        )
                    },
                    onToggleKeepScreenOn = {
                        viewModel.updateOverlayPrefs(
                            state.overlayPrefs.copy(
                                keepScreenOn = !state.overlayPrefs.keepScreenOn,
                            ),
                        )
                    },
                    onShare = state.watchUrl.takeIf { it.isNotBlank() }?.let { url ->
                        {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, url)
                            }, "Share watch link"))
                        }
                    },
                    onPreviewTap = viewModel::onPreviewTap,
                    onPinchZoom = viewModel::onPinchZoom,
                    onPreviewSurfaceBound = viewModel::onPreviewSurfaceBound,
                    onCancelCountdown = viewModel::cancelGoLiveCountdown,
                    onDismissRecap = viewModel::dismissRecap,
                )
            }
        }
    }

    CricRelayBottomSheet(
        visible = state.activeSheet == StudioSheet.Destination,
        onDismiss = viewModel::closeSheet,
    ) {
        DestinationSheet(
            state = state,
            onSaveCustom = viewModel::updateCustomRtmp,
            onSelect = viewModel::setDestination,
            onDismiss = viewModel::closeSheet,
        )
    }

    CricRelayBottomSheet(
        visible = state.activeSheet == StudioSheet.Overlay,
        onDismiss = viewModel::closeSheet,
    ) {
        OverlaySheet(
            prefs = state.overlayPrefs,
            onSave = viewModel::updateOverlayPrefs,
            onDismiss = viewModel::closeSheet,
        )
    }

    CricRelayBottomSheet(
        visible = state.activeSheet == StudioSheet.Scoring,
        onDismiss = viewModel::closeSheet,
    ) {
        ScoringSheet(
            scoring = state.scoring,
            onSelectMode = viewModel::setScoringMode,
            onOpenScorer = { state.match?.slug?.let(onOpenScoring) },
            onDismiss = viewModel::closeSheet,
        )
    }

    CricRelayBottomSheet(
        visible = state.activeSheet == StudioSheet.Preflight,
        onDismiss = viewModel::closeSheet,
    ) {
        PreflightSheet(
            state = state,
            onConfirm = viewModel::confirmGoLive,
            onDismiss = viewModel::closeSheet,
        )
    }

    CricRelayBottomSheet(
        visible = state.activeSheet == StudioSheet.Menu,
        onDismiss = viewModel::closeSheet,
    ) {
        StudioMenuSheet(
            onRestartPreview = viewModel::prepareCamera,
            onDismiss = viewModel::closeSheet,
        )
    }
}
