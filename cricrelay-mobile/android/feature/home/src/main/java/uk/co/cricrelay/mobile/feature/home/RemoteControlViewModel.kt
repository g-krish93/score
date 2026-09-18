package uk.co.cricrelay.mobile.feature.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uk.co.cricrelay.shared.model.OverlayLayoutPrefs
import uk.co.cricrelay.shared.model.RemoteCameraIds
import uk.co.cricrelay.shared.model.RemoteCameraInfo
import uk.co.cricrelay.shared.model.RemoteCameraState
import uk.co.cricrelay.shared.model.Sponsor
import uk.co.cricrelay.shared.model.StabilizationLevel
import uk.co.cricrelay.shared.remote.RemoteControlMetrics
import uk.co.cricrelay.shared.remote.RemoteControlSessionMetrics
import uk.co.cricrelay.shared.repository.StreamRepository
import android.util.Log
import javax.inject.Inject

data class RemoteControlUiState(
    val paired: Boolean = false,
    val matchSlug: String = "",
    val busy: Boolean = false,
    val statusMessage: String = "",
    val error: String? = null,
    val contextLoading: Boolean = false,
    val sponsors: List<Sponsor> = emptyList(),
    val sponsorPrefs: OverlayLayoutPrefs = OverlayLayoutPrefs(),
    val watchUrl: String = "",
    val previewBitmap: Bitmap? = null,
    val previewStale: Boolean = true,
    val previewAgeSec: Int? = null,
    val camera: RemoteCameraState = RemoteCameraState(),
    /** Local zoom slider value while dragging (committed via set_zoom). */
    val zoomDraft: Float = 1f,
    /** Command awaiting tripod sidecar confirmation (e.g. set_mute). */
    val pendingAck: String? = null,
    val focusReticleNx: Float? = null,
    val focusReticleNy: Float? = null,
    /** Dual-end: which camera the companion is viewing / controlling. */
    val selectedCameraId: String = RemoteCameraIds.END_A,
    val liveCameraId: String? = null,
    val cameras: List<RemoteCameraInfo> = emptyList(),
    val thumbA: Bitmap? = null,
    val thumbB: Bitmap? = null,
    /** Dismissible stream-health banner (reconnect / thermal / offline / lost). */
    val healthAlert: String? = null,
)

@HiltViewModel
class RemoteControlViewModel @Inject constructor(
    private val streamRepository: StreamRepository,
    private val companionTokenStore: CompanionTokenStore,
    private val authRepository: uk.co.cricrelay.shared.repository.AuthRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(RemoteControlUiState())
    val uiState: StateFlow<RemoteControlUiState> = _uiState.asStateFlow()
    private var sponsorSendJob: Job? = null
    private var previewPollJob: Job? = null
    private var camerasPollJob: Job? = null
    private var zoomSendJob: Job? = null
    private var ackTimeoutJob: Job? = null
    private var reticleJob: Job? = null
    private var pendingMute: Boolean? = null
    private var pendingLock: Boolean? = null
    private var pendingPaused: Boolean? = null
    private var sawStreaming = false
    private val pendingMetricEvents = mutableListOf<RemoteControlMetrics.Event>()
    private var metricsFlushJob: Job? = null
    private val sessionMetrics = RemoteControlSessionMetrics { event ->
        Log.i(METRICS_TAG, "remote_metric name=${event.name} value=${event.value}")
        synchronized(pendingMetricEvents) { pendingMetricEvents += event }
        scheduleMetricsFlush()
    }

    init {
        companionTokenStore.load()?.let { session ->
            viewModelScope.launch {
                if (session.apiBase.isNotBlank()) {
                    authRepository.preferApiBase(session.apiBase)
                }
            }
            _uiState.update {
                it.copy(
                    paired = true,
                    matchSlug = session.matchSlug,
                    statusMessage = "Paired to ${session.matchSlug}",
                )
            }
            sessionMetrics.markPairSuccess()
            loadContext()
            startPreviewPolling()
            startCamerasPolling()
        }
    }

    fun onQrScanned(payload: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null, statusMessage = "Pairing…") }
            try {
                val link = uk.co.cricrelay.shared.util.parsePairDeepLink(payload)
                    ?: run {
                        error("Not a CricRelay pairing code")
                        return@launch
                    }
                authRepository.preferApiBase(link.apiBase)
                val companionToken = streamRepository.redeemPairToken(link.slug, link.token, link.apiBase)
                companionTokenStore.save(
                    CompanionSession(
                        matchSlug = link.slug,
                        companionToken = companionToken,
                        apiBase = link.apiBase,
                    ),
                )
                _uiState.update {
                    it.copy(
                        busy = false,
                        paired = true,
                        matchSlug = link.slug,
                        statusMessage = "Paired — ready to control",
                        error = null,
                    )
                }
                sessionMetrics.markPairSuccess()
                loadContext()
                startPreviewPolling()
                startCamerasPolling()
            } catch (e: Exception) {
                sessionMetrics.markPairFailure()
                error(e.message ?: "Pairing failed")
            }
        }
    }

    fun selectCamera(cameraId: String) {
        val id = RemoteCameraIds.sanitize(cameraId) ?: return
        _uiState.update { it.copy(selectedCameraId = id) }
    }

    fun takeLive(cameraId: String = _uiState.value.selectedCameraId) {
        val id = RemoteCameraIds.sanitize(cameraId) ?: return
        if (id == _uiState.value.liveCameraId) return
        sessionMetrics.markTakeLive()
        sendControl(
            "take_live",
            mapOf("camera_id" to id),
            ackLabel = "Switching to ${RemoteCameraIds.label(id)}…",
            trackAck = false,
        )
        _uiState.update {
            it.copy(selectedCameraId = id, statusMessage = "Take live → ${RemoteCameraIds.label(id)}")
        }
    }

    fun sendCommand(command: String) {
        sendControl(command, payload = null)
    }

    fun onZoomDraft(level: Float) {
        _uiState.update { it.copy(zoomDraft = level) }
        zoomSendJob?.cancel()
        zoomSendJob = viewModelScope.launch {
            delay(120)
            sendZoom(level)
        }
    }

    fun onZoomCommitted(level: Float) {
        zoomSendJob?.cancel()
        _uiState.update { it.copy(zoomDraft = level) }
        sendZoom(level)
    }

    private fun sendZoom(level: Float) {
        sendControl(
            "set_zoom",
            mapOf("level" to level.toDouble()),
            busyOverlay = false,
            trackAck = false,
        )
    }

    fun onPreviewTap(nx: Float, ny: Float) {
        val cx = nx.coerceIn(0f, 1f)
        val cy = ny.coerceIn(0f, 1f)
        _uiState.update { it.copy(focusReticleNx = cx, focusReticleNy = cy) }
        reticleJob?.cancel()
        reticleJob = viewModelScope.launch {
            delay(1_400)
            _uiState.update { it.copy(focusReticleNx = null, focusReticleNy = null) }
        }
        sendControl(
            "tap_focus",
            mapOf("nx" to cx.toDouble(), "ny" to cy.toDouble()),
            busyOverlay = false,
            trackAck = false,
        )
    }

    fun setStabilization(level: Int) {
        if (_uiState.value.camera.streaming) return
        val clamped = StabilizationLevel.sanitize(level)
        sendControl(
            "set_stabilization",
            mapOf("level" to clamped),
            trackAck = false,
        )
    }

    fun setMute(muted: Boolean) {
        if (_uiState.value.camera.muted == muted && pendingMute == null) return
        pendingMute = muted
        sendControl("set_mute", mapOf("muted" to muted), ackLabel = if (muted) "Muting…" else "Unmuting…")
    }

    fun setFocusLock(locked: Boolean) {
        if (_uiState.value.camera.locked == locked && pendingLock == null) return
        pendingLock = locked
        sendControl(
            "set_focus_lock",
            mapOf("locked" to locked),
            ackLabel = if (locked) "Locking focus…" else "Unlocking focus…",
        )
    }

    fun setPaused(paused: Boolean) {
        if (!_uiState.value.camera.streaming) return
        if (_uiState.value.camera.paused == paused && pendingPaused == null) return
        pendingPaused = paused
        sendControl(
            if (paused) "pause_broadcast" else "resume_broadcast",
            payload = null,
            ackLabel = if (paused) "Pausing…" else "Resuming…",
        )
    }

    private fun withCameraPayload(payload: Map<String, Any>?): Map<String, Any> {
        val base = payload?.toMutableMap() ?: mutableMapOf()
        base["camera_id"] = _uiState.value.selectedCameraId
        return base
    }

    private fun sendControl(
        command: String,
        payload: Map<String, Any>?,
        busyOverlay: Boolean = true,
        trackAck: Boolean = true,
        ackLabel: String? = null,
    ) {
        val session = companionTokenStore.load() ?: run {
            _uiState.update { it.copy(error = "Not paired") }
            return
        }
        viewModelScope.launch {
            if (busyOverlay) _uiState.update { it.copy(busy = true, error = null) }
            if (trackAck) {
                _uiState.update {
                    it.copy(pendingAck = ackLabel ?: "Sending…", statusMessage = ackLabel ?: "Sent")
                }
                sessionMetrics.markCommandSent()
                armAckTimeout()
            }
            try {
                streamRepository.sendRemoteCommand(
                    session.matchSlug,
                    session.companionToken,
                    command,
                    withCameraPayload(payload),
                )
                if (command == "toggle_sponsor") {
                    _uiState.update {
                        it.copy(sponsorPrefs = it.sponsorPrefs.copy(sponsorEnabled = !it.sponsorPrefs.sponsorEnabled))
                    }
                }
                if (command == "take_live") {
                    val cam = payload?.get("camera_id") as? String
                    if (!cam.isNullOrBlank()) {
                        _uiState.update { it.copy(liveCameraId = cam) }
                    }
                }
                _uiState.update {
                    it.copy(
                        busy = false,
                        statusMessage = if (trackAck) {
                            it.statusMessage.ifBlank { "Sent" }
                        } else {
                            "Sent: ${command.replace('_', ' ')}"
                        },
                    )
                }
            } catch (e: Exception) {
                clearPending()
                error(e.message ?: "Command failed")
            }
        }
    }

    private fun armAckTimeout() {
        ackTimeoutJob?.cancel()
        ackTimeoutJob = viewModelScope.launch {
            delay(3_500)
            if (_uiState.value.pendingAck != null) {
                sessionMetrics.markCommandTimeout()
                clearPending()
                _uiState.update {
                    it.copy(
                        pendingAck = null,
                        statusMessage = "Camera did not confirm — check the broadcast phone",
                    )
                }
            }
        }
    }

    private fun clearPending() {
        pendingMute = null
        pendingLock = null
        pendingPaused = null
        ackTimeoutJob?.cancel()
        _uiState.update { it.copy(pendingAck = null) }
    }

    private fun reconcileAck(camera: RemoteCameraState) {
        var cleared = false
        pendingMute?.let { want ->
            if (camera.muted == want) {
                pendingMute = null
                cleared = true
            }
        }
        pendingLock?.let { want ->
            if (camera.locked == want) {
                pendingLock = null
                cleared = true
            }
        }
        pendingPaused?.let { want ->
            if (camera.paused == want) {
                pendingPaused = null
                cleared = true
            }
        }
        if (cleared && pendingMute == null && pendingLock == null && pendingPaused == null) {
            ackTimeoutJob?.cancel()
            sessionMetrics.markCommandAcked()
            _uiState.update {
                it.copy(pendingAck = null, statusMessage = "Applied on camera")
            }
        }
    }

    fun updateSponsorPrefs(transform: (OverlayLayoutPrefs) -> OverlayLayoutPrefs) {
        val next = transform(_uiState.value.sponsorPrefs)
        _uiState.update { it.copy(sponsorPrefs = next) }
        scheduleSponsorSend(next)
    }

    fun refreshContext() = loadContext()

    private fun scheduleSponsorSend(prefs: OverlayLayoutPrefs) {
        sponsorSendJob?.cancel()
        sponsorSendJob = viewModelScope.launch {
            delay(120)
            val session = companionTokenStore.load() ?: return@launch
            try {
                streamRepository.sendRemoteOverlayPrefs(
                    session.matchSlug,
                    session.companionToken,
                    prefs,
                )
                _uiState.update {
                    it.copy(statusMessage = "Sponsor updated on broadcast phone")
                }
            } catch (e: Exception) {
                error(e.message ?: "Sponsor update failed")
            }
        }
    }

    private fun loadContext() {
        val session = companionTokenStore.load() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(contextLoading = true, error = null) }
            try {
                val ctx = streamRepository.getRemoteContext(session.matchSlug, session.companionToken)
                _uiState.update {
                    it.copy(
                        contextLoading = false,
                        sponsors = ctx.sponsors,
                        sponsorPrefs = ctx.sponsorPrefs,
                        watchUrl = ctx.watchUrl,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(contextLoading = false) }
                error(e.message ?: "Failed to load sponsor settings")
            }
        }
    }

    private fun startCamerasPolling() {
        camerasPollJob?.cancel()
        camerasPollJob = viewModelScope.launch {
            while (isActive && _uiState.value.paired) {
                val session = companionTokenStore.load() ?: break
                runCatching {
                    streamRepository.listRemoteCameras(session.matchSlug, session.companionToken)
                }.onSuccess { snap ->
                    _uiState.update {
                        it.copy(
                            cameras = snap.cameras,
                            liveCameraId = snap.liveCameraId ?: it.liveCameraId,
                        )
                    }
                }
                // Lightweight thumbs for the standby end (selected uses main preview pane).
                val selected = _uiState.value.selectedCameraId
                for (camId in RemoteCameraIds.ALL) {
                    if (camId == selected) {
                        _uiState.value.previewBitmap?.let { bmp ->
                            _uiState.update {
                                when (camId) {
                                    RemoteCameraIds.END_A -> it.copy(thumbA = bmp)
                                    RemoteCameraIds.END_B -> it.copy(thumbB = bmp)
                                    else -> it
                                }
                            }
                        }
                        continue
                    }
                    runCatching {
                        streamRepository.getRemotePreview(
                            session.matchSlug,
                            session.companionToken,
                            cameraId = camId,
                        )
                    }.onSuccess { frame ->
                        val bmp = frame.jpegB64
                            ?.takeIf { it.isNotBlank() }
                            ?.let { decodeJpegB64(it) }
                        if (bmp != null) {
                            _uiState.update {
                                when (camId) {
                                    RemoteCameraIds.END_A -> it.copy(thumbA = bmp)
                                    RemoteCameraIds.END_B -> it.copy(thumbB = bmp)
                                    else -> it
                                }
                            }
                        }
                    }
                }
                delay(1_200)
            }
        }
    }

    private fun startPreviewPolling() {
        previewPollJob?.cancel()
        previewPollJob = viewModelScope.launch {
            while (isActive && _uiState.value.paired) {
                val session = companionTokenStore.load() ?: break
                val cameraId = _uiState.value.selectedCameraId
                runCatching {
                    streamRepository.getRemotePreview(
                        session.matchSlug,
                        session.companionToken,
                        cameraId = cameraId,
                    )
                }.onSuccess { frame ->
                    val bitmap = frame.jpegB64
                        ?.takeIf { it.isNotBlank() }
                        ?.let { decodeJpegB64(it) }
                    val camera = frame.state ?: _uiState.value.camera
                    val ageSec = frame.ts?.let { ts ->
                        val age = ((System.currentTimeMillis() / 1000.0) - ts).toInt()
                        age.coerceAtLeast(0)
                    }
                    reconcileAck(camera)
                    if (bitmap != null || !frame.stale) {
                        sessionMetrics.onPreviewFrame()
                    }
                    val alert = deriveHealthAlert(
                        camera = camera,
                        stale = frame.stale || (bitmap == null && _uiState.value.previewBitmap == null),
                        previous = _uiState.value.camera,
                    )
                    if (camera.streaming) sawStreaming = true
                    _uiState.update {
                        it.copy(
                            previewBitmap = bitmap ?: it.previewBitmap,
                            previewStale = frame.stale || (bitmap == null && it.previewBitmap == null),
                            previewAgeSec = ageSec,
                            camera = camera,
                            liveCameraId = frame.liveCameraId ?: it.liveCameraId,
                            zoomDraft = if (zoomSendJob?.isActive == true) it.zoomDraft else camera.zoom,
                            healthAlert = alert ?: it.healthAlert,
                        )
                    }
                }.onFailure {
                    _uiState.update { it.copy(previewStale = true) }
                }
                delay(400)
            }
        }
    }

    private fun decodeJpegB64(b64: String): Bitmap? = try {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (_: Exception) {
        null
    }

    fun unpair() {
        sponsorSendJob?.cancel()
        zoomSendJob?.cancel()
        previewPollJob?.cancel()
        camerasPollJob?.cancel()
        ackTimeoutJob?.cancel()
        reticleJob?.cancel()
        clearPending()
        companionTokenStore.clear()
        _uiState.update {
            RemoteControlUiState(statusMessage = "Scan a pairing code from the broadcast phone")
        }
    }

    fun dismissHealthAlert() {
        _uiState.update { it.copy(healthAlert = null) }
    }

    fun onWatchShared() {
        sessionMetrics.markShareWatch()
    }

    private fun deriveHealthAlert(
        camera: RemoteCameraState,
        stale: Boolean,
        previous: RemoteCameraState,
    ): String? {
        return when {
            camera.reconnecting ->
                "Broadcast reconnecting — check signal on the camera phone"
            camera.thermal >= 3 ->
                "Camera phone is hot — bitrate may drop"
            stale && (camera.streaming || sawStreaming) ->
                "Camera offline — preview frozen"
            sawStreaming && previous.streaming && !camera.streaming && !camera.paused ->
                "Broadcast stopped or lost on the camera phone"
            else -> null
        }
    }

    private fun scheduleMetricsFlush() {
        if (metricsFlushJob?.isActive == true) return
        metricsFlushJob = viewModelScope.launch {
            delay(2_500)
            flushMetrics()
        }
    }

    private fun flushMetrics() {
        val session = companionTokenStore.load() ?: return
        val batch = synchronized(pendingMetricEvents) {
            if (pendingMetricEvents.isEmpty()) return
            val copy = pendingMetricEvents.toList()
            pendingMetricEvents.clear()
            copy
        }
        viewModelScope.launch {
            runCatching {
                streamRepository.postRemoteMetrics(
                    session.matchSlug,
                    session.companionToken,
                    batch,
                )
            }
        }
    }

    private fun error(message: String) {
        _uiState.update { it.copy(busy = false, contextLoading = false, error = message, pendingAck = null) }
    }

    companion object {
        private const val METRICS_TAG = "CricRelayRemote"
    }

    override fun onCleared() {
        previewPollJob?.cancel()
        camerasPollJob?.cancel()
        zoomSendJob?.cancel()
        sponsorSendJob?.cancel()
        ackTimeoutJob?.cancel()
        reticleJob?.cancel()
        metricsFlushJob?.cancel()
        flushMetrics()
        super.onCleared()
    }
}
