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
import uk.co.cricrelay.shared.model.RemoteCommand
import uk.co.cricrelay.shared.model.ScoringConfig
import uk.co.cricrelay.shared.model.Sponsor
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

/** What the Arrange-mode drag gesture moves (pinch always scales the board). */
enum class ArrangeTarget { Board, Sponsor }

/** Steps of the first-run guided precheck shown before the first Go Live. */
enum class PrecheckStep { Camera, Arrange, Ready }

/**
 * Studio orientation control. Auto follows the physical sensor (system auto-rotate willing);
 * the lock modes force the activity orientation so an operator with the system rotation lock
 * on can still get a landscape studio + landscape stream.
 */
enum class OrientationMode { Auto, Landscape, Portrait }

// Vertical drag maps to the board's bottom margin (px, /720 in the engine). Allow lifting the
// board up to ~55% of the frame so the operator can place a lower-third or a mid-frame board.
private const val BOARD_DRAG_MARGIN_SPAN = 400.0
private const val BOARD_DRAG_MARGIN_MAX = 400.0

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
    val micMuted: Boolean = false,
    val thermalStatus: Int = android.os.PowerManager.THERMAL_STATUS_NONE,
    val sponsors: List<Sponsor> = emptyList(),
    val goLiveCountdown: Int? = null,
    val recap: StreamRecap? = null,
    val inPip: Boolean = false,
    // Pre-live "Arrange" mode: direct pinch/drag of the board + sponsor over the live preview.
    val arrangeMode: Boolean = false,
    val arrangeTarget: ArrangeTarget = ArrangeTarget.Board,
    val arrangeDraft: OverlayLayoutPrefs? = null,
    // First-run guided precheck (Camera → Arrange → Ready), gating the first Go Live.
    val precheckActive: Boolean = false,
    val precheckStep: PrecheckStep = PrecheckStep.Camera,
    val orientationMode: OrientationMode = OrientationMode.Auto,
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
    private var remotePollJob: Job? = null
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
        viewModelScope.launch {
            streamController.status.collect { status ->
                _uiState.update {
                    it.copy(
                        // Camera step of the first-run precheck auto-completes when the preview
                        // comes up; an already-live broadcast dismisses the precheck entirely.
                        precheckActive = it.precheckActive && !status.streaming,
                        precheckStep = if (
                            it.precheckActive && it.precheckStep == PrecheckStep.Camera &&
                            status.previewReady
                        ) PrecheckStep.Arrange else it.precheckStep,
                        previewReady = status.previewReady,
                        streaming = status.streaming,
                        paused = status.paused,
                        // Engine is the source of truth for the focus lock: a re-prepare (e.g.
                        // rotating before Go Live) clears it inside resetFocusState(), so mirror
                        // the real state here rather than letting the padlock drift out of sync.
                        focusLocked = streamController.isFocusLocked(),
                        thermalStatus = status.thermalStatus,
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
                startRemoteCommandPolling(slug)
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
                // Guided first-run precheck (Camera → Arrange → Ready) — never mid-broadcast.
                precheckActive = !it.streaming && !rtmpStore.isPrecheckDone(),
                precheckStep = if (it.previewReady) PrecheckStep.Arrange else PrecheckStep.Camera,
            )
        }
        // Start overlay WebView load immediately; don't wait for overlay prefs API.
        syncOverlay(match, _uiState.value.overlayPrefs)
    }

    private suspend fun loadStudioExtras(slug: String, match: StreamMatch) {
        val overlayPrefs = runCatching { streamRepository.getOverlayPrefs(slug) }
            .getOrDefault(OverlayLayoutPrefs())
        val sponsors = runCatching { streamRepository.listSponsors() }.getOrDefault(emptyList())
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
                sponsors = sponsors,
                scoring = scoring,
                destination = destination,
                destinationReady = destinationReady,
                zoomLevel = streamController.currentZoom(),
            )
        }
        streamController.setVideoStabilization(overlayPrefs.videoStabilization)
        streamController.setKeepScreenOnDuringStream(overlayPrefs.keepScreenOn)
        syncOverlay(match, overlayPrefs)
        syncSponsorLayer(overlayPrefs, sponsors)
    }

    private fun startRemoteCommandPolling(slug: String) {
        remotePollJob?.cancel()
        remotePollJob = viewModelScope.launch {
            while (isActive) {
                runCatching { streamRepository.pollRemoteCommands(slug) }
                    .onSuccess { commands -> handleRemoteCommands(commands) }
                delay(1_500)
            }
        }
    }

    private fun handleRemoteCommands(commands: List<RemoteCommand>) {
        if (commands.isEmpty()) return
        for (cmd in commands) {
            when (cmd.type) {
                "control" -> when (cmd.command) {
                    "start_broadcast" -> {
                        if (!_uiState.value.streaming && _uiState.value.destinationReady) {
                            goLive()
                        }
                    }
                    "stop_broadcast" -> {
                        if (_uiState.value.streaming) stopLive()
                    }
                    "mute_mic" -> onToggleMicMuted()
                    "toggle_focus_lock" -> onToggleFocusLock()
                    "toggle_sponsor" -> {
                        val prefs = _uiState.value.overlayPrefs.copy(
                            sponsorEnabled = !_uiState.value.overlayPrefs.sponsorEnabled,
                        )
                        updateOverlayPrefs(prefs)
                    }
                }
                "overlay" -> {
                    cmd.mergeSponsorInto(_uiState.value.overlayPrefs)?.let { updateOverlayPrefs(it) }
                }
            }
        }
    }

    private fun syncSponsorLayer(prefs: OverlayLayoutPrefs, sponsors: List<Sponsor> = _uiState.value.sponsors) {
        val match = _uiState.value.match ?: return
        val logoUrls = prefs.resolveSponsorLogoUrls(sponsors)
        if (match.overlayEmbedUrl.isBlank()) {
            streamController.setSponsorLayer(prefs.sponsorEnabled, logoUrls)
            return
        }
        streamController.updateOverlay(match.overlayEmbedUrl, prefs.toEngineLayout(logoUrls))
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
        // Only feed the sensor in Auto — under a lock the activity orientation is the truth,
        // otherwise the stream would follow the phone while the UI stays locked.
        if (_uiState.value.orientationMode != OrientationMode.Auto) return
        streamController.setDeviceOrientation(surfaceRotationDegrees)
    }

    /**
     * Flip the studio between portrait and landscape: one tap goes to the opposite of what's
     * on screen now, the next tap comes back. (A three-state Auto/lock cycle proved invisible
     * in the field — operators couldn't tell which mode they were in.) Physical auto-rotate
     * still works until the first tap. Locked out while live (RTMP is fixed).
     */
    fun toggleOrientation(currentlyLandscape: Boolean) {
        if (_uiState.value.streaming) return
        val next = if (currentlyLandscape) OrientationMode.Portrait else OrientationMode.Landscape
        // Clear the sensor override so the engine re-derives rotation from the
        // (about to be locked) display instead of how the phone happens to be held.
        streamController.clearDeviceOrientation()
        _uiState.update {
            it.copy(
                orientationMode = next,
                statusMessage = if (next == OrientationMode.Landscape) {
                    "Landscape — tap again for portrait"
                } else {
                    "Portrait — tap again for landscape"
                },
            )
        }
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
        val logoUrls = prefs.resolveSponsorLogoUrls(_uiState.value.sponsors)
        streamController.updateOverlay(match.overlayEmbedUrl, prefs.toEngineLayout(logoUrls))
    }

    /** Push overlay/sponsor prefs to the camera preview without persisting to the server. */
    fun previewOverlayPrefs(prefs: OverlayLayoutPrefs) {
        val match = _uiState.value.match ?: return
        syncOverlay(match, prefs)
        syncSponsorLayer(prefs)
    }

    /** Restore the last saved overlay on the preview after cancel/dismiss without save. */
    fun revertOverlayPreview() {
        previewOverlayPrefs(_uiState.value.overlayPrefs)
    }

    // ── Arrange mode ────────────────────────────────────────────────────────────
    // Direct manipulation of the board + sponsor on the live composited preview. Gestures push
    // to the engine via previewOverlayPrefs (no network); commit persists once, on "Done".

    fun enterArrangeMode() {
        _uiState.update {
            it.copy(arrangeMode = true, arrangeDraft = it.overlayPrefs, activeSheet = StudioSheet.None)
        }
    }

    fun cancelArrangeMode() {
        revertOverlayPreview()
        _uiState.update { it.copy(arrangeMode = false, arrangeDraft = null) }
    }

    fun commitArrangeMode() {
        val draft = _uiState.value.arrangeDraft
        _uiState.update {
            it.copy(
                arrangeMode = false,
                arrangeDraft = null,
                // Completing Arrange advances the first-run precheck to its final step.
                precheckStep = if (it.precheckActive && it.precheckStep == PrecheckStep.Arrange) {
                    PrecheckStep.Ready
                } else {
                    it.precheckStep
                },
            )
        }
        if (draft != null) updateOverlayPrefs(draft)
    }

    fun setArrangeTarget(target: ArrangeTarget) {
        _uiState.update { it.copy(arrangeTarget = target) }
    }

    // ── First-run precheck ──────────────────────────────────────────────────────

    fun precheckStartArrange() {
        _uiState.update { it.copy(precheckStep = PrecheckStep.Arrange) }
        enterArrangeMode()
    }

    fun finishPrecheck() {
        rtmpStore.setPrecheckDone()
        _uiState.update { it.copy(precheckActive = false) }
    }

    private fun mutateArrangeDraft(block: (OverlayLayoutPrefs) -> OverlayLayoutPrefs) {
        val current = _uiState.value.arrangeDraft ?: _uiState.value.overlayPrefs
        val next = block(current)
        _uiState.update { it.copy(arrangeDraft = next) }
        previewOverlayPrefs(next)
    }

    /** Pinch: [zoom] is the incremental scale ratio (~1.0) from the transform gesture. */
    fun pinchBoard(zoom: Float) {
        if (zoom <= 0f) return
        mutateArrangeDraft { it.withBoardScale(it.boardScale() * zoom) }
    }

    /** Drag the active target by a fraction of the preview (dy<0 = up). */
    fun dragArrange(dxFraction: Float, dyFraction: Float) {
        mutateArrangeDraft { p ->
            when (_uiState.value.arrangeTarget) {
                ArrangeTarget.Board -> p.copy(
                    anchorX = (p.anchorX + dxFraction).coerceIn(0.0, 1.0),
                    // Android's GL sprite reads bottomMargin (px/720) for vertical placement:
                    // dragging up (dy<0) lifts the board off the bottom edge.
                    bottomMargin = (p.bottomMargin - dyFraction * BOARD_DRAG_MARGIN_SPAN)
                        .coerceIn(0.0, BOARD_DRAG_MARGIN_MAX),
                )
                ArrangeTarget.Sponsor -> p.copy(
                    sponsorPositionX = (p.sponsorPositionX + dxFraction).coerceIn(0.0, 1.0),
                    sponsorPositionY = (p.sponsorPositionY + dyFraction).coerceIn(0.0, 1.0),
                )
            }
        }
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

    fun openSheet(sheet: StudioSheet) {
        if (sheet == StudioSheet.Overlay) {
            viewModelScope.launch {
                val sponsors = runCatching { streamRepository.listSponsors() }.getOrDefault(emptyList())
                _uiState.update { it.copy(activeSheet = sheet, error = null, sponsors = sponsors) }
            }
            return
        }
        _uiState.update { it.copy(activeSheet = sheet, error = null) }
    }

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
                syncSponsorLayer(prefs)
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

    fun onToggleMicMuted() {
        val next = !_uiState.value.micMuted
        streamController.setMicMuted(next)
        _uiState.update { it.copy(micMuted = next) }
    }

    suspend fun createPairingCode(): Pair<String, String> {
        val result = streamRepository.pairRemote(matchSlug)
        val base = apiClientProvider.get().baseUrl
        val payload = buildString {
            append("cricrelay://pair?slug=")
            append(java.net.URLEncoder.encode(matchSlug, Charsets.UTF_8.name()))
            append("&token=")
            append(java.net.URLEncoder.encode(result.pairToken, Charsets.UTF_8.name()))
            append("&base=")
            append(java.net.URLEncoder.encode(base, Charsets.UTF_8.name()))
        }
        return payload to result.expiresAt
    }

    fun onLowerQuality() {
        streamController.stepDownQuality()
    }

    fun onPinchZoom(scale: Float) {
        val base = _uiState.value.zoomLevel
        setZoom(base * scale)
    }

    fun requestGoLive() {
        val state = _uiState.value
        if (!state.previewReady) return
        // First session: finish the guided precheck before going live.
        if (state.precheckActive) return
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
                val logoUrls = state.overlayPrefs.resolveSponsorLogoUrls(state.sponsors)
                val layout = state.overlayPrefs.toEngineLayout(logoUrls)
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
                val (apiMode, provider) = when {
                    mode.startsWith("auto:") -> "auto" to mode.substringAfter(":")
                    else -> mode to null
                }
                val config = streamRepository.setScoring(match.slug, apiMode, provider)
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
        remotePollJob?.cancel()
        streamController.destroyOverlayCapture()
        streamController.hideNativePreview()
        super.onCleared()
    }

    fun onStudioHidden() {
        previewSurfaceBound = false
        _uiState.update {
            it.copy(
                previewReady = false,
                statusMessage = if (it.streaming) it.statusMessage else "Starting camera…",
            )
        }
        streamController.hideNativePreview()
    }
}
