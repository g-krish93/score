package uk.co.cricrelay.mobile.feature.scoring

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.cricrelay.mobile.ui.AppColors
import uk.co.cricrelay.mobile.ui.AppSpacing
import uk.co.cricrelay.mobile.ui.AppTypography
import uk.co.cricrelay.mobile.ui.ErrorBanner
import uk.co.cricrelay.mobile.ui.GlassPanel
import uk.co.cricrelay.mobile.ui.LoadingState
import uk.co.cricrelay.mobile.ui.PrimaryButton
import uk.co.cricrelay.mobile.ui.ScreenTopBar
import uk.co.cricrelay.mobile.ui.StatusChip
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
                    .statusBarsPadding(),
            ) {
                ScreenTopBar(title = "Manual scoring", subtitle = matchSlug, onBack = onBack)
                Column(modifier = Modifier.padding(AppSpacing.lg)) {
                    GlassPanel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(AppSpacing.radiusSm))
                                    .background(AppColors.Accent.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Outlined.Edit,
                                    contentDescription = null,
                                    tint = AppColors.Accent,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                            Spacer(Modifier.width(AppSpacing.md))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Web scorer", style = AppTypography.titleMedium)
                                Text(
                                    "Score the match from any browser — the overlay updates live.",
                                    style = AppTypography.bodySmall,
                                )
                            }
                        }
                        Spacer(Modifier.height(AppSpacing.md))
                        StatusChip(label = "Mode: ${state.mode}", ok = true)
                        Spacer(Modifier.height(AppSpacing.md))
                        PrimaryButton(
                            text = "Open scorer in browser",
                            enabled = state.scorerUrl.isNotBlank(),
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(state.scorerUrl))
                                context.startActivity(intent)
                            },
                        )
                    }
                    state.error?.let {
                        Spacer(Modifier.height(AppSpacing.md))
                        ErrorBanner(it)
                    }
                }
            }
        }
    }
}
