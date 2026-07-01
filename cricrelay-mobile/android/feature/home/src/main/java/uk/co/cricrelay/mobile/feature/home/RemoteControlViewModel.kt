package uk.co.cricrelay.mobile.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uk.co.cricrelay.shared.repository.StreamRepository
import javax.inject.Inject

data class RemoteControlUiState(
    val paired: Boolean = false,
    val matchSlug: String = "",
    val busy: Boolean = false,
    val statusMessage: String = "",
    val error: String? = null,
)

@HiltViewModel
class RemoteControlViewModel @Inject constructor(
    private val streamRepository: StreamRepository,
    private val companionTokenStore: CompanionTokenStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(RemoteControlUiState())
    val uiState: StateFlow<RemoteControlUiState> = _uiState.asStateFlow()

    init {
        companionTokenStore.load()?.let { session ->
            _uiState.update {
                it.copy(
                    paired = true,
                    matchSlug = session.matchSlug,
                    statusMessage = "Paired to ${session.matchSlug}",
                )
            }
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
                _uiState.update {
                    it.copy(
                        busy = false,
                        statusMessage = "Sent: $command",
                    )
                }
            } catch (e: Exception) {
                error(e.message ?: "Command failed")
            }
        }
    }

    fun unpair() {
        companionTokenStore.clear()
        _uiState.update {
            RemoteControlUiState(statusMessage = "Scan a pairing code from the broadcast phone")
        }
    }

    private fun error(message: String) {
        _uiState.update { it.copy(busy = false, error = message) }
    }
}
