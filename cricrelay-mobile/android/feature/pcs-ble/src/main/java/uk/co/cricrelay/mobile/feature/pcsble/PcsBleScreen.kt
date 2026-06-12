package uk.co.cricrelay.mobile.feature.pcsble

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.cricrelay.mobile.ui.AppColors
import uk.co.cricrelay.mobile.ui.AppSpacing
import uk.co.cricrelay.mobile.ui.AppTypography
import uk.co.cricrelay.mobile.ui.GlassPanel
import uk.co.cricrelay.mobile.ui.PrimaryButton
import uk.co.cricrelay.mobile.ui.ScreenTopBar
import uk.co.cricrelay.mobile.ui.SecondaryButton
import uk.co.cricrelay.mobile.ui.SectionLabel
import uk.co.cricrelay.mobile.ui.StatusChip
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
                .statusBarsPadding(),
        ) {
            ScreenTopBar(title = "PCS BLE relay", onBack = onBack)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AppSpacing.lg),
            ) {
                Text(
                    "Advertise as ${PcsBle.PRESET_ADVERTISE_NAME} so PCS can connect and send score lines.",
                    style = AppTypography.bodyMedium,
                )
                Spacer(Modifier.height(AppSpacing.lg))

                SectionLabel("Relay settings")
                StudioTextField(ingestUrl, { ingestUrl = it }, "Ingest URL")
                Spacer(Modifier.height(AppSpacing.sm))
                StudioTextField(bearerToken, { bearerToken = it }, "Bearer token", isPassword = true)
                Spacer(Modifier.height(AppSpacing.md))
                SecondaryButton(
                    text = "Save settings",
                    onClick = { viewModel.updateSettings(ingestUrl, bearerToken) },
                )

                Spacer(Modifier.height(AppSpacing.lg))
                SectionLabel("Relay status")
                GlassPanel {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        StatusChip(
                            label = if (state.advertising) "Advertising" else "Stopped",
                            ok = state.advertising,
                            pulse = state.advertising,
                            color = if (state.advertising) AppColors.Success else AppColors.OnBackgroundDim,
                        )
                        Text(state.status, style = AppTypography.bodySmall)
                    }
                    Spacer(Modifier.height(AppSpacing.md))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.lg),
                    ) {
                        RelayStat("Packets", state.packetCount.toString())
                        RelayStat("Posted OK", state.postedOk.toString())
                        RelayStat("Failed", state.postFail.toString())
                    }
                }

                if (state.recentPackets.isNotEmpty()) {
                    Spacer(Modifier.height(AppSpacing.md))
                    SectionLabel("Recent packets")
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(AppSpacing.radiusMd))
                            .background(AppColors.SurfaceSunken)
                            .padding(AppSpacing.md),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        state.recentPackets.forEach { line ->
                            Text(
                                line,
                                style = AppTypography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    color = AppColors.Accent.copy(alpha = 0.9f),
                                ),
                                maxLines = 1,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(AppSpacing.lg))
                PrimaryButton(
                    text = if (state.advertising) "Stop relay" else "Start relay",
                    onClick = viewModel::toggleRelay,
                )
                Spacer(Modifier.height(AppSpacing.xl))
            }
        }
    }
}

@Composable
private fun RelayStat(label: String, value: String) {
    Column {
        Text(
            value,
            color = AppColors.OnBackground,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(label.uppercase(), style = AppTypography.labelSmall)
    }
}
