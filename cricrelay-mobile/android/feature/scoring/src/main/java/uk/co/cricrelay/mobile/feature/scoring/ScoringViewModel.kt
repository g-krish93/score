package uk.co.cricrelay.mobile.feature.scoring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uk.co.cricrelay.shared.repository.ApiClientProvider
import javax.inject.Inject

data class ScoringUiState(
    val loading: Boolean = true,
    val scorerUrl: String = "",
    val mode: String = "manual",
    val error: String? = null,
)

@HiltViewModel
class ScoringViewModel @Inject constructor(
    private val apiClientProvider: ApiClientProvider,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ScoringUiState())
    val uiState: StateFlow<ScoringUiState> = _uiState.asStateFlow()

    fun load(matchSlug: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                val config = apiClientProvider.get().getScoring(matchSlug)
                _uiState.update {
                    it.copy(
                        loading = false,
                        scorerUrl = config.scorerUrl,
                        mode = config.mode,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, error = e.message) }
            }
        }
    }
}
