package uk.co.cricrelay.mobile.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uk.co.cricrelay.shared.model.OverlayLayoutPrefs
import uk.co.cricrelay.shared.model.Sponsor
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
)

@HiltViewModel
class RemoteControlViewModel @Inject constructor(
    private val streamRepository: StreamRepository,
    private val companionTokenStore: CompanionTokenStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(RemoteControlUiState())
    val uiState: StateFlow<RemoteControlUiState> = _uiState.asStateFlow()
    private var sponsorSendJob: Job? = null

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
            } catch (e: Exception) {
                error(e.message ?: "Pairing failed")
            }
        }
    }

    fun sendCommand(command: String) {
        val session = companionTokenStore.load() ?: run {
            _uiState.update { it.copy(error = "Not paired") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            try {
                streamRepository.sendRemoteCommand(session.matchSlug, session.companionToken, command)
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

    fun unpair() {
        sponsorSendJob?.cancel()
        companionTokenStore.clear()
        _uiState.update {
            RemoteControlUiState(statusMessage = "Scan a pairing code from the broadcast phone")
        }
    }

    private fun error(message: String) {
        _uiState.update { it.copy(busy = false, contextLoading = false, error = message) }
    }
}
