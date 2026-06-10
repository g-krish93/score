package uk.co.cricrelay.mobile.feature.pcsble

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class PcsBleViewModel @Inject constructor(
    private val relayManager: PcsBleRelayManager,
) : ViewModel() {
    val uiState: StateFlow<PcsBleUiState> = relayManager.state

    fun updateSettings(ingestUrl: String, bearerToken: String) =
        relayManager.updateSettings(ingestUrl, bearerToken)

    fun toggleRelay() = relayManager.toggleAdvertise()

    override fun onCleared() {
        relayManager.stopAdvertise()
        super.onCleared()
    }
}
