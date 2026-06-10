package uk.co.cricrelay.mobile.feature.scoring

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.cricrelay.mobile.ui.AppSpacing
import uk.co.cricrelay.mobile.ui.AppTypography
import uk.co.cricrelay.mobile.ui.ErrorBanner
import uk.co.cricrelay.mobile.ui.LoadingState
import uk.co.cricrelay.mobile.ui.PrimaryButton
import uk.co.cricrelay.mobile.ui.StudioBackdrop

@Composable
fun ScoringScreen(
    matchSlug: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ScoringViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(matchSlug) { viewModel.load(matchSlug) }

    StudioBackdrop(modifier = modifier) {
        when {
            state.loading -> LoadingState()
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(AppSpacing.lg),
            ) {
                Text("Manual scoring", style = AppTypography.headlineLarge)
                Text("Mode: ${state.mode}", style = AppTypography.bodyMedium)
                Spacer(Modifier.height(AppSpacing.lg))
                state.error?.let { ErrorBanner(it) }
                PrimaryButton(
                    text = "Open scorer in browser",
                    enabled = state.scorerUrl.isNotBlank(),
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(state.scorerUrl))
                        context.startActivity(intent)
                    },
                )
                Spacer(Modifier.height(AppSpacing.sm))
                PrimaryButton(text = "Back", onClick = onBack)
            }
        }
    }
}
