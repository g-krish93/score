package uk.co.cricrelay.mobile.feature.scoring

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.co.cricrelay.mobile.ui.AppColors
import uk.co.cricrelay.mobile.ui.AppSpacing
import uk.co.cricrelay.mobile.ui.AppTypography
import uk.co.cricrelay.mobile.ui.ErrorBanner
import uk.co.cricrelay.mobile.ui.LoadingState
import uk.co.cricrelay.mobile.ui.ScreenTopBar
import uk.co.cricrelay.mobile.ui.SecondaryButton
import uk.co.cricrelay.mobile.ui.StudioBackdrop
import uk.co.cricrelay.mobile.ui.encodeQrBitmap
import uk.co.cricrelay.shared.repository.StreamRepository
import javax.inject.Inject

data class ScorerQrUiState(
    val loading: Boolean = true,
    val qrBitmap: Bitmap? = null,
    val scorerUrl: String = "",
    val expiresAt: String = "",
    val error: String? = null,
)

@HiltViewModel
class ScorerQrViewModel @Inject constructor(
    private val streamRepository: StreamRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ScorerQrUiState())
    val uiState: StateFlow<ScorerQrUiState> = _uiState.asStateFlow()

    fun load(matchSlug: String) {
        viewModelScope.launch {
            _uiState.update { ScorerQrUiState(loading = true) }
            try {
                // Always mint fresh — links expire (~12h); never reuse a cached one.
                val link = streamRepository.getScorerLink(matchSlug)
                val bitmap = withContext(Dispatchers.Default) { encodeQrBitmap(link.scorerUrl, 512) }
                _uiState.update {
                    it.copy(
                        loading = false,
                        qrBitmap = bitmap,
                        scorerUrl = link.scorerUrl,
                        expiresAt = link.expiresAt,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(loading = false, error = e.message ?: "Failed to create scorer link")
                }
            }
        }
    }
}

@Composable
fun ScorerQrScreen(
    matchSlug: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ScorerQrViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(matchSlug) { viewModel.load(matchSlug) }

    StudioBackdrop(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            ScreenTopBar(title = "Scorer link", subtitle = matchSlug, onBack = onBack)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AppSpacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(AppSpacing.lg))
                Text(
                    "Scan with the scorer's phone camera — the scoring page opens in their browser. No app needed.",
                    style = AppTypography.bodyMedium,
                    color = AppColors.OnBackgroundMuted,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(AppSpacing.lg))
                when {
                    state.loading -> LoadingState("Creating scorer link…")
                    state.error != null -> ErrorBanner(state.error!!)
                    state.qrBitmap != null -> {
                        Image(
                            bitmap = state.qrBitmap!!.asImageBitmap(),
                            contentDescription = "Scorer page QR code",
                            modifier = Modifier
                                .size(260.dp)
                                .background(Color.White, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                        )
                        if (state.expiresAt.isNotBlank()) {
                            Spacer(Modifier.height(AppSpacing.md))
                            Text(
                                "Link lasts about 12 hours — reopen this screen for a fresh one.",
                                style = AppTypography.bodySmall,
                                color = AppColors.OnBackgroundDim,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Spacer(Modifier.height(AppSpacing.lg))
                        SecondaryButton(
                            text = "Share link instead",
                            onClick = {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, state.scorerUrl)
                                }
                                context.startActivity(Intent.createChooser(send, "Share scorer link"))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Spacer(Modifier.height(AppSpacing.lg))
            }
        }
    }
}
