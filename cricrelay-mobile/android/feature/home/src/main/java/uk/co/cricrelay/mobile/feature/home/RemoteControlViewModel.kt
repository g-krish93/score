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
import uk.co.cricrelay.shared.model.RemoteCameraState
import uk.co.cricrelay.shared.model.Sponsor
import uk.co.cricrelay.shared.model.StabilizationLevel
import uk.co.cricrelay.shared.repository.StreamRepository
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
    val camera: RemoteCameraState = RemoteCameraState(),
    /** Local zoom slider value while dragging (committed via set_zoom). */
    val zoomDraft: Float = 1f,
)

@HiltViewModel
class RemoteControlViewModel @Inject constructor(
    private val streamRepository: StreamRepository,
    private val companionTokenStore: CompanionTokenStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(RemoteControlUiState())
    val uiState: StateFlow<RemoteControlUiState> = _uiState.asStateFlow()
    private var sponsorSendJob: Job? = null
    private var previewPollJob: Job? = null
    private var zoomSendJob: Job? = null

    init {
        companionTokenStore.load()?.let { session ->
            _uiState.update {
                it.copy(
                    paired = true,
                    matchSlug = session.matchSlug,
                    statusMessage = "Paired to ${session.matchSlug}",
                )
            }
            loadContext()
            startPreviewPolling()
        }
    }

    fun onQrScanned(payload: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            try {
                val uri = android.net.Uri.parse(payload)
                if (uri.scheme != "cricrelay" || uri.host != "pair") {
                    error("Not a CricRelay pairing code")
                    return@launch
                }
                val slug = uri.getQueryParameter("slug").orEmpty()
                val token = uri.getQueryParameter("token").orEmpty()
                val base = uri.getQueryParameter("base").orEmpty()
                if (slug.isBlank() || token.isBlank() || base.isBlank()) {
                    error("Pairing code is missing match details")
                    return@launch
                }
                val companionToken = streamRepository.redeemPairToken(slug, token, base)
                companionTokenStore.save(
                    CompanionSession(
                        matchSlug = slug,
                        companionToken = companionToken,
                        apiBase = base,
                    ),
                )
                _uiState.update {
                    it.copy(
                        busy = false,
                        paired = true,
                        matchSlug = slug,
                        statusMessage = "Paired — ready to control",
                        error = null,
                    )
                }
                loadContext()
                startPreviewPolling()
            } catch (e: Exception) {
                error(e.message ?: "Pairing failed")
            }
        }
    }

    fun sendCommand(command: String) {
        sendControl(command, payload = null)
    }

    fun onZoomDraft(level: Float) {
        _uiState.update { it.copy(zoomDraft = level) }
        zoomSendJob?.cancel()
        zoomSendJob = viewModelScope.launch {
            delay(200)
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
        )
    }

    fun onPreviewTap(nx: Float, ny: Float) {
        sendControl(
            "tap_focus",
            mapOf(
                "nx" to nx.toDouble().coerceIn(0.0, 1.0),
                "ny" to ny.toDouble().coerceIn(0.0, 1.0),
            ),
            busyOverlay = false,
        )
    }

    fun setStabilization(level: Int) {
        if (_uiState.value.camera.streaming) return
        val clamped = StabilizationLevel.sanitize(level)
        sendControl(
            "set_stabilization",
            mapOf("level" to clamped.toDouble()),
        )
        _uiState.update {
            it.copy(camera = it.camera.copy(stab = clamped))
        }
    }

    fun toggleMute() {
        // Tripod toggles on mute_mic; optimistically flip local state for the button label.
        sendCommand("mute_mic")
        _uiState.update {
            it.copy(camera = it.camera.copy(muted = !it.camera.muted))
        }
    }

    fun toggleFocusLock() {
        sendCommand("toggle_focus_lock")
        _uiState.update {
            it.copy(camera = it.camera.copy(locked = !it.camera.locked))
        }
    }

    fun togglePause() {
        val paused = _uiState.value.camera.paused
        sendCommand(if (paused) "resume_broadcast" else "pause_broadcast")
        _uiState.update {
            it.copy(camera = it.camera.copy(paused = !paused))
        }
    }

    private fun sendControl(
        command: String,
        payload: Map<String, Double>?,
        busyOverlay: Boolean = true,
    ) {
        val session = companionTokenStore.load() ?: run {
            _uiState.update { it.copy(error = "Not paired") }
            return
        }
        viewModelScope.launch {
            if (busyOverlay) _uiState.update { it.copy(busy = true, error = null) }
            try {
                streamRepository.sendRemoteCommand(
                    session.matchSlug,
                    session.companionToken,
                    command,
                    payload,
                )
                if (command == "toggle_sponsor") {
                    _uiState.update {
                        it.copy(sponsorPrefs = it.sponsorPrefs.copy(sponsorEnabled = !it.sponsorPrefs.sponsorEnabled))
                    }
                }
                _uiState.update {
                    it.copy(
                        busy = false,
                        statusMessage = "Sent: ${command.replace('_', ' ')}",
                    )
                }
            } catch (e: Exception) {
                error(e.message ?: "Command failed")
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

    private fun startPreviewPolling() {
        previewPollJob?.cancel()
        previewPollJob = viewModelScope.launch {
            while (isActive && _uiState.value.paired) {
                val session = companionTokenStore.load() ?: break
                runCatching {
                    streamRepository.getRemotePreview(session.matchSlug, session.companionToken)
                }.onSuccess { frame ->
                    val bitmap = frame.jpegB64
                        ?.takeIf { it.isNotBlank() }
                        ?.let { decodeJpegB64(it) }
                    val camera = frame.state ?: _uiState.value.camera
                    _uiState.update {
                        it.copy(
                            previewBitmap = bitmap ?: it.previewBitmap,
                            previewStale = frame.stale || bitmap == null,
                            camera = camera,
                            // Don't fight an in-flight drag.
                            zoomDraft = if (zoomSendJob?.isActive == true) it.zoomDraft else camera.zoom,
                        )
                    }
                }
                delay(750)
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
        companionTokenStore.clear()
        _uiState.update {
            RemoteControlUiState(statusMessage = "Scan a pairing code from the broadcast phone")
        }
    }

    private fun error(message: String) {
        _uiState.update { it.copy(busy = false, contextLoading = false, error = message) }
    }

    override fun onCleared() {
        previewPollJob?.cancel()
        zoomSendJob?.cancel()
        sponsorSendJob?.cancel()
        super.onCleared()
    }
}
