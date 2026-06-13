package uk.co.cricrelay.mobile.feature.studio

import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import uk.co.cricrelay.mobile.database.StreamDao
import uk.co.cricrelay.mobile.database.toDomain
import uk.co.cricrelay.shared.model.MatchDayStatus
import uk.co.cricrelay.shared.model.OverlayLayoutPrefs
import uk.co.cricrelay.shared.model.PlatformStatus
import uk.co.cricrelay.shared.model.ScoringConfig
import uk.co.cricrelay.shared.model.StreamMatch
import uk.co.cricrelay.shared.repository.ApiClientProvider
import uk.co.cricrelay.shared.repository.StreamRepository
import uk.co.cricrelay.stream.StreamController
import javax.inject.Inject

enum class StreamDestination(val platform: String?, val label: String) {
    Custom(null, "Custom RTMP"),
    YouTube("youtube", "YouTube"),
    Twitch("twitch", "Twitch"),
}

enum class StudioSheet {
    None,
    Destination,
    Overlay,
    Scoring,
    Preflight,
    Menu,
}

/** Shown after a broadcast ends — the shareable wrap-up. */
data class StreamRecap(
    val durationSeconds: Long,
    val destinationLabel: String,
    val watchUrl: String,
)

data class StudioUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val match: StreamMatch? = null,
    val previewReady: Boolean = false,
    val streaming: Boolean = false,
    val paused: Boolean = false,
    val watchUrl: String = "",
    val error: String? = null,
    val statusMessage: String = "",
    val destination: StreamDestination = StreamDestination.Custom,
    val destinationReady: Boolean = false,
    val customRtmpUrl: String = "",
    val customStreamKey: String = "",
    val customWatchUrl: String = "",
    val overlayPrefs: OverlayLayoutPrefs = OverlayLayoutPrefs(),
    val zoomLevel: Float = 1f,
    val matchDay: MatchDayStatus? = null,
    val scoring: ScoringConfig? = null,
    val activeSheet: StudioSheet = StudioSheet.None,
    val liveElapsedSeconds: Long = 0,
    val focusX: Float? = null,
    val focusY: Float? = null,
    val focusLocked: Boolean = false,
    val overlayPreview: androidx.compose.ui.graphics.ImageBitmap? = null,
    val goLiveCountdown: Int? = null,
    val recap: StreamRecap? = null,
    val inPip: Boolean = false,
) {
    val destinationLabel: String
        get() = when (destination) {
            StreamDestination.Custom -> if (destinationReady) "Custom RTMP" else "Set stream key"
            StreamDestination.YouTube -> "YouTube"
            StreamDestination.Twitch -> "Twitch"
        }
}

@HiltViewModel
class StudioViewModel @Inject constructor(
    private val streamRepository: StreamRepository,
    private val streamController: StreamController,
    private val apiClientProvider: ApiClientProvider,
    private val rtmpStore: RtmpCredentialsStore,
    private val streamDao: StreamDao,
) : ViewModel() {
    private val _uiState = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    private var matchDayJob: Job? = null
    private var liveTimerJob: Job? = null
    private var countdownJob: Job? = null
    private var focusReticleJob: Job? = null
    private var matchSlug: String = ""
    private var permissionsGranted = false
    private var previewSurfaceBound = false

    private fun cameraReadiness(): StudioCameraGate.Readiness = StudioCameraGate.Readiness(
        matchLoaded = _uiState.value.match != null,
        permissionsGranted = permissionsGranted,
        previewSurfaceBound = previewSurfaceBound,
    )

    private fun tryStartCamera() {
        if (!StudioCameraGate.canPrepareCamera(cameraReadiness())) return
        prepareCamera()
    }

    init {
        streamController.setPreviewOverlayListener { bytes, w, h ->
            val bitmap = runCatching {
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
            if (bitmap == null) {
                android.util.Log.w("Cricrelay", "overlay preview decode failed (${bytes.size} bytes)")
                return@setPreviewOverlayListener
            }
            android.util.Log.d(
                "Cricrelay",
                "overlay preview frame ${bitmap.width}x${bitmap.height} (push ${w}x$h)",
            )
            _uiState.update { it.copy(overlayPreview = bitmap.asImageBitmap()) }
        }
        viewModelScope.launch {
            streamController.status.collect { status ->
                _uiState.update {
                    it.copy(
                        previewReady = status.previewReady,
                        streaming = status.streaming,
                        paused = status.paused,
                        // Engine is the source of truth for the focus lock: a re-prepare (e.g.
                        // rotating before Go Live) clears it inside resetFocusState(), so mirror
                        // the real state here rather than letting the padlock drift out of sync.
                        focusLocked = streamController.isFocusLocked(),
                        statusMessage = when {
                            status.streaming -> it.statusMessage
                            status.previewReady && it.statusMessage == "Starting camera…" -> ""
                            else -> it.statusMessage
                        },
                    )
                }
                val event = status.lastEvent ?: return@collect
                if (event.event.contains("error", ignoreCase = true) ||
                    event.event.contains("fail", ignoreCase = true)
                ) {
                    _uiState.update {
                        it.copy(
                            error = event.message.ifBlank { event.event },
                            busy = false,
                            loading = false,
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            streamController.pipMode.collect { pip ->
                // In PiP the window is tiny: collapse to camera-only and dismiss any open sheet.
                _uiState.update {
                    it.copy(inPip = pip, activeSheet = if (pip) StudioSheet.None else it.activeSheet)
                }
            }
        }
    }

    fun load(slug: String) {
        if (slug == matchSlug && _uiState.value.match != null) {
            tryStartCamera()
            return
        }
        matchSlug = slug
        resetCameraGate()
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                val creds = rtmpStore.load(slug)
                val cachedMatch = streamDao.getBySlug(slug)?.toDomain()
                if (cachedMatch != null) {
                    revealStudio(cachedMatch, creds)
                }

                val match = try {
                    withTimeout(12_000) {
                        streamRepository.listStreams().firstOrNull { it.slug == slug }
                    }
                } catch (_: TimeoutCancellationException) {
                    null
                } ?: cachedMatch ?: error("Stream not found")

                revealStudio(match, creds)
                startMatchDayPolling(slug)
                loadStudioExtras(slug, match)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        error = e.message?.replace("Exception: ", "").orEmpty()
                            .ifBlank { "Failed to open studio" },
                    )
                }
            }
        }
    }

    private fun revealStudio(match: StreamMatch, creds: RtmpCredentials) {
        _uiState.update {
            it.copy(
                loading = false,
                match = match,
                customRtmpUrl = creds.rtmpUrl,
                customStreamKey = creds.streamKey,
                customWatchUrl = creds.watchUrl,
            )
        }
        // Start overlay WebView load immediately; don't wait for overlay prefs API.
        syncOverlay(match, _uiState.value.overlayPrefs)
    }

    private suspend fun loadStudioExtras(slug: String, match: StreamMatch) {
        val overlayPrefs = runCatching { streamRepository.getOverlayPrefs(slug) }
            .getOrDefault(OverlayLayoutPrefs())
        val scoring = runCatching { streamRepository.getScoring(slug) }.getOrNull()
        val youtube = runCatching { streamRepository.youtubePlatformStatus() }
            .getOrDefault(PlatformStatus())
        val twitch = runCatching { streamRepository.twitchPlatformStatus() }
            .getOrDefault(PlatformStatus())
        val destination = when {
            youtube.ready || youtube.connected -> StreamDestination.YouTube
            twitch.ready || twitch.connected -> StreamDestination.Twitch
            else -> StreamDestination.Custom
        }
        val creds = rtmpStore.load(slug)
        val destinationReady = when (destination) {
            StreamDestination.Custom ->
                creds.rtmpUrl.isNotBlank() && creds.streamKey.isNotBlank()
            StreamDestination.YouTube -> youtube.ready || youtube.connected
            StreamDestination.Twitch -> twitch.ready || twitch.connected
        }
        _uiState.update {
            it.copy(
                overlayPrefs = overlayPrefs,
                scoring = scoring,
                destination = destination,
                destinationReady = destinationReady,
                zoomLevel = streamController.currentZoom(),
            )
        }
        streamController.setVideoStabilization(overlayPrefs.videoStabilization)
        streamController.setKeepScreenOnDuringStream(overlayPrefs.keepScreenOn)
        syncOverlay(match, overlayPrefs)
    }

    private fun resetCameraGate() {
        permissionsGranted = false
        previewSurfaceBound = false
    }

    fun onStudioVisible() {
        streamController.refreshNativePreview()
    }

    fun onConfigurationChanged() {
        streamController.refreshNativePreview()
    }

    /** Live device rotation from the orientation sensor (Surface.ROTATION_* in degrees). */
    fun onDeviceOrientationChanged(surfaceRotationDegrees: Int) {
        streamController.setDeviceOrientation(surfaceRotationDegrees)
    }

    fun onPreviewSurfaceBound() {
        previewSurfaceBound = true
        tryStartCamera()
    }

    fun onCameraPermissionsGranted() {
        permissionsGranted = true
        tryStartCamera()
    }

    fun onCameraPermissionsDenied() {
        _uiState.update {
            it.copy(error = "Camera and microphone permission are required to broadcast")
        }
    }

    fun prepareCamera() {
        if (_uiState.value.match == null) return
        streamController.showNativePreview()
        val ready = streamController.preparePreview()
        _uiState.update {
            it.copy(
                previewReady = ready,
                statusMessage = if (ready) "Camera ready" else "Starting camera…",
            )
        }
        streamController.ensureComposeAboveCamera()
    }

    private fun syncOverlay(match: StreamMatch, prefs: OverlayLayoutPrefs) {
        if (match.overlayEmbedUrl.isBlank()) return
        streamController.updateOverlay(match.overlayEmbedUrl, prefs.toEngineLayout())
    }

    private fun startMatchDayPolling(slug: String) {
        matchDayJob?.cancel()
        matchDayJob = viewModelScope.launch {
            while (isActive) {
                runCatching { streamRepository.getMatchDayStatus(slug) }
                    .onSuccess { status -> _uiState.update { it.copy(matchDay = status) } }
                delay(8_000)
            }
        }
    }

    private fun startLiveTimer() {
        liveTimerJob?.cancel()
        liveTimerJob = viewModelScope.launch {
            var elapsed = 0L
            while (isActive) {
                _uiState.update { it.copy(liveElapsedSeconds = elapsed) }
                delay(1_000)
                elapsed++
            }
        }
    }

    fun openSheet(sheet: StudioSheet) = _uiState.update { it.copy(activeSheet = sheet, error = null) }

    fun closeSheet() = _uiState.update { it.copy(activeSheet = StudioSheet.None) }

    fun setDestination(destination: StreamDestination) {
        viewModelScope.launch {
            val ready = when (destination) {
                StreamDestination.Custom -> {
                    val s = _uiState.value
                    s.customRtmpUrl.isNotBlank() && s.customStreamKey.isNotBlank()
                }
                StreamDestination.YouTube -> {
                    runCatching { streamRepository.youtubePlatformStatus() }
                        .getOrDefault(PlatformStatus())
                        .let { it.ready || it.connected }
                }
                StreamDestination.Twitch -> {
                    runCatching { streamRepository.twitchPlatformStatus() }
                        .getOrDefault(PlatformStatus())
                        .let { it.ready || it.connected }
                }
            }
            _uiState.update { it.copy(destination = destination, destinationReady = ready) }
        }
    }

    fun updateCustomRtmp(url: String, key: String, watch: String) {
        val slug = matchSlug
        val creds = RtmpCredentials(url.trim(), key.trim(), watch.trim())
        rtmpStore.save(slug, creds)
        _uiState.update {
            it.copy(
                customRtmpUrl = creds.rtmpUrl,
                customStreamKey = creds.streamKey,
                customWatchUrl = creds.watchUrl,
                destinationReady = creds.rtmpUrl.isNotBlank() && creds.streamKey.isNotBlank(),
                destination = StreamDestination.Custom,
            )
        }
    }

    fun updateOverlayPrefs(prefs: OverlayLayoutPrefs) {
        val match = _uiState.value.match ?: return
        viewModelScope.launch {
            try {
                streamRepository.setOverlayPrefs(match.slug, prefs)
                streamController.setVideoStabilization(prefs.videoStabilization)
                streamController.setKeepScreenOnDuringStream(prefs.keepScreenOn)
                syncOverlay(match, prefs)
                _uiState.update { it.copy(overlayPrefs = prefs) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun setZoom(level: Float) {
        streamController.setZoom(level)
        _uiState.update { it.copy(zoomLevel = streamController.currentZoom()) }
    }

    fun onPreviewTap(x: Float, y: Float, width: Int, height: Int) {
        val result = streamController.tapToFocusAt(width, height, x, y)
        val locked = result["locked"] as? Boolean ?: false
        _uiState.update { it.copy(focusX = x, focusY = y, focusLocked = locked) }
        focusReticleJob?.cancel()
        focusReticleJob = viewModelScope.launch {
            delay(1_500)
            // Keep the reticle on screen while locked so the operator can see what's held.
            if (!_uiState.value.focusLocked) {
                _uiState.update { it.copy(focusX = null, focusY = null) }
            }
        }
    }

    /** Toggle the pitch focus lock: freeze AF on the strip, or hand it back to continuous AF. */
    fun onToggleFocusLock() {
        if (_uiState.value.focusLocked) {
            streamController.unlockFocus()
            focusReticleJob?.cancel()
            _uiState.update { it.copy(focusLocked = false, focusX = null, focusY = null) }
        } else {
            val ok = streamController.lockFocus()
            _uiState.update { it.copy(focusLocked = ok) }
        }
    }

    fun onPinchZoom(scale: Float) {
        val base = _uiState.value.zoomLevel
        setZoom(base * scale)
    }

    fun requestGoLive() {
        val state = _uiState.value
        if (!state.previewReady) return
        if (!state.streaming && !state.destinationReady) {
            openSheet(StudioSheet.Destination)
            return
        }
        openSheet(StudioSheet.Preflight)
    }

    /** Go Live cinema: 3-2-1 countdown takeover, then connect. Tap anywhere cancels. */
    fun confirmGoLive() {
        closeSheet()
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (n in 3 downTo 1) {
                _uiState.update { it.copy(goLiveCountdown = n) }
                delay(800)
            }
            _uiState.update { it.copy(goLiveCountdown = null) }
            goLive()
        }
    }

    fun cancelGoLiveCountdown() {
        countdownJob?.cancel()
        _uiState.update { it.copy(goLiveCountdown = null) }
    }

    fun dismissRecap() = _uiState.update { it.copy(recap = null) }

    fun goLive() {
        val match = _uiState.value.match ?: return
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null, statusMessage = "Connecting…") }
            try {
                val layout = state.overlayPrefs.toEngineLayout()
                val watchUrl: String
                when (state.destination) {
                    StreamDestination.Custom -> {
                        val endpoint = streamController.startStream(
                            rtmpUrl = state.customRtmpUrl,
                            streamKey = state.customStreamKey,
                            overlayUrl = match.overlayEmbedUrl,
                            layout = layout,
                        )
                        watchUrl = state.customWatchUrl
                        _uiState.update { it.copy(watchUrl = watchUrl) }
                        endpoint
                    }
                    StreamDestination.YouTube, StreamDestination.Twitch -> {
                        val result = streamRepository.goLive(match.slug, state.destination.platform!!)
                        streamController.startStream(
                            rtmpUrl = result.rtmpUrl,
                            streamKey = result.streamKey,
                            overlayUrl = match.overlayEmbedUrl.ifBlank { result.overlayEmbedUrl },
                            layout = layout,
                        )
                        streamRepository.updateBroadcastStatus(
                            matchSlug = match.slug,
                            status = "streaming",
                            platform = state.destination.platform,
                            watchUrl = result.watchUrl,
                        )
                        watchUrl = result.watchUrl
                        _uiState.update { it.copy(watchUrl = watchUrl) }
                        result.rtmpUrl
                    }
                }
                startLiveTimer()
                streamController.ensureComposeAboveCamera()
                _uiState.update {
                    it.copy(
                        busy = false,
                        streaming = true,
                        statusMessage = "Live",
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(busy = false, error = e.message ?: "Go Live failed", statusMessage = "")
                }
            }
        }
    }

    fun stopLive() {
        val match = _uiState.value.match ?: return
        val platform = _uiState.value.destination.platform
        val recap = StreamRecap(
            durationSeconds = _uiState.value.liveElapsedSeconds,
            destinationLabel = _uiState.value.destination.label,
            watchUrl = _uiState.value.watchUrl,
        )
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            streamController.stopStream()
            liveTimerJob?.cancel()
            try {
                if (platform != null) {
                    streamRepository.stopLive(platform)
                }
                streamRepository.updateBroadcastStatus(match.slug, status = "idle")
            } catch (_: Exception) {
            }
            prepareCamera()
            _uiState.update {
                it.copy(
                    busy = false,
                    streaming = false,
                    paused = false,
                    liveElapsedSeconds = 0,
                    statusMessage = "Stream stopped",
                    recap = recap.takeIf { r -> r.durationSeconds > 0 },
                )
            }
        }
    }

    fun togglePause() {
        val match = _uiState.value.match ?: return
        if (_uiState.value.paused) {
            streamController.resumeStream()
            viewModelScope.launch {
                runCatching {
                    streamRepository.updateBroadcastStatus(
                        match.slug,
                        status = "streaming",
                        platform = _uiState.value.destination.platform,
                        watchUrl = _uiState.value.watchUrl,
                    )
                }
            }
            _uiState.update { it.copy(paused = false, statusMessage = "Live") }
        } else {
            streamController.pauseStream()
            viewModelScope.launch {
                runCatching {
                    streamRepository.updateBroadcastStatus(match.slug, status = "paused")
                }
            }
            _uiState.update { it.copy(paused = true, statusMessage = "Paused") }
        }
    }

    fun setScoringMode(mode: String) {
        val match = _uiState.value.match ?: return
        viewModelScope.launch {
            try {
                val config = streamRepository.setScoring(match.slug, mode)
                _uiState.update { it.copy(scoring = config) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    override fun onCleared() {
        matchDayJob?.cancel()
        liveTimerJob?.cancel()
        countdownJob?.cancel()
        streamController.setPreviewOverlayListener(null)
        streamController.destroyOverlayCapture()
        streamController.hideNativePreview()
        super.onCleared()
    }

    fun onStudioHidden() {
        previewSurfaceBound = false
        _uiState.update {
            it.copy(
                previewReady = false,
                overlayPreview = null,
                statusMessage = if (it.streaming) it.statusMessage else "Starting camera…",
            )
        }
        streamController.hideNativePreview()
    }
}
