package uk.co.cricrelay.mobile.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uk.co.cricrelay.shared.repository.AuthRepository
import javax.inject.Inject

data class LoginUiState(
    val baseUrl: String = "https://cricrelay.co.uk",
    val email: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val session = authRepository.currentSession()
            _uiState.update { it.copy(baseUrl = session.baseUrl) }
        }
    }

    fun onBaseUrlChange(value: String) = _uiState.update { it.copy(baseUrl = value, error = null) }
    fun onEmailChange(value: String) = _uiState.update { it.copy(email = value, error = null) }
    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, error = null) }

    fun login(onSuccess: suspend (needsOnboarding: Boolean) -> Unit) {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                authRepository.login(state.baseUrl, state.email.trim(), state.password)
                val needsOnboarding = !authRepository.isOnboardingComplete()
                onSuccess(needsOnboarding)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = e.message?.replace("Exception: ", "").orEmpty().ifBlank { "Sign in failed" })
                }
            } finally {
                _uiState.update { it.copy(loading = false) }
            }
        }
    }
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {
    fun complete(onDone: () -> Unit) {
        viewModelScope.launch {
            authRepository.markOnboardingComplete()
            onDone()
        }
    }
}
