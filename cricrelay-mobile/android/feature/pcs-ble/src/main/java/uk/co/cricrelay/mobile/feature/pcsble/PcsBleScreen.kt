package uk.co.cricrelay.mobile.feature.pcsble

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.cricrelay.mobile.ui.AppSpacing
import uk.co.cricrelay.mobile.ui.AppTypography
import uk.co.cricrelay.mobile.ui.PrimaryButton
import uk.co.cricrelay.mobile.ui.StudioBackdrop
import uk.co.cricrelay.mobile.ui.StudioTextField

@Composable
fun PcsBleScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PcsBleViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var ingestUrl by remember(state.ingestUrl) { mutableStateOf(state.ingestUrl) }
    var bearerToken by remember(state.bearerToken) { mutableStateOf(state.bearerToken) }

    StudioBackdrop(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(AppSpacing.lg),
        ) {
            Text("PCS BLE relay", style = AppTypography.headlineLarge)
            Text(
                "Advertise as ${PcsBle.PRESET_ADVERTISE_NAME} so PCS can connect and send score lines.",
                style = AppTypography.bodyMedium,
            )
            Spacer(Modifier.height(AppSpacing.lg))
            StudioTextField(ingestUrl, { ingestUrl = it }, "Ingest URL")
            Spacer(Modifier.height(AppSpacing.sm))
            StudioTextField(bearerToken, { bearerToken = it }, "Bearer token")
            Spacer(Modifier.height(AppSpacing.md))
            PrimaryButton(
                text = "Save settings",
                onClick = { viewModel.updateSettings(ingestUrl, bearerToken) },
            )
            Spacer(Modifier.height(AppSpacing.lg))
            Text("Status: ${state.status}", style = AppTypography.bodyMedium)
            Text(
                "Packets ${state.packetCount} · OK ${state.postedOk} · Fail ${state.postFail}",
                style = AppTypography.bodySmall,
            )
            state.recentPackets.forEach { line ->
                Text(line, style = AppTypography.bodySmall)
            }
            Spacer(Modifier.height(AppSpacing.lg))
            PrimaryButton(
                text = if (state.advertising) "Stop relay" else "Start relay",
                onClick = viewModel::toggleRelay,
            )
            Spacer(Modifier.height(AppSpacing.sm))
            PrimaryButton("Back", onClick = onBack)
        }
    }
}
