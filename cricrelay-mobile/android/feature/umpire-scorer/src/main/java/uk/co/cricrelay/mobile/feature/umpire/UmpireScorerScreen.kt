package uk.co.cricrelay.mobile.feature.umpire

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.cricrelay.mobile.ui.AppColors
import uk.co.cricrelay.mobile.ui.AppTypography
import uk.co.cricrelay.mobile.ui.PressableTextButton
import uk.co.cricrelay.mobile.ui.ScreenTopBar
import uk.co.cricrelay.mobile.ui.StudioBackdrop
import uk.co.cricrelay.shared.model.UmpireBallEvent
import uk.co.cricrelay.shared.model.UmpireDeliveryMode
import uk.co.cricrelay.shared.model.UmpireScorerState

// Scoring button colours — cricket-green palette distinct from the main app gold/blue.
private val RunButtonColor = Color(0xFF1565C0)
private val ExtraButtonColor = Color(0xFFBF360C)
private val ByeButtonColor = Color(0xFFE65100)
private val WicketButtonColor = Color(0xFFB71C1C)
private val ActiveWideColor = Color(0xFFFF6D00)
private val ActiveByeColor = Color(0xFFFF8F00)
private val ActiveWicketColor = Color(0xFFFF1744)

// Chip colours for the over summary row.
private val DotChipColor = Color(0xFF33691E)
private val RunChipColor = Color(0xFF1565C0)
private val BoundaryChipColor = Color(0xFF0D47A1)
private val WideChipColor = Color(0xFFBF360C)
private val NoBallChipColor = Color(0xFFE65100)
private val ByeChipColor = Color(0xFFF57F17)
private val WicketChipColor = Color(0xFFB71C1C)

@Composable
fun UmpireScorerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: UmpireScorerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showNewOverDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.newOverEvent.collect { showNewOverDialog = true }
    }

    StudioBackdrop(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            ScreenTopBar(title = "Umpire Scorer", onBack = onBack)

            // Score banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F2B11))
                    .padding(vertical = 12.dp, horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${state.totalRuns}/${state.totalWickets}",
                        color = Color.White,
                        fontSize = 52.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                    )
                    Text(
                        text = "Overs: ${state.completedOvers}.${state.legalBallCount}",
                        color = Color(0xFFA5D6A7),
                        fontSize = 16.sp,
                    )
                }
            }

            // Over chips
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1B5E20))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(state.currentOverBalls) { ball -> OverChip(ball) }
                val emptySlots = maxOf(0, 6 - state.legalBallCount)
                items(emptySlots) { EmptyChip() }
            }

            // Mode indicator
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1B5E20))
                    .padding(vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(modeIndicatorText(state), color = Color(0xFF81C784), fontSize = 13.sp)
            }

            // Scoring buttons — fill remaining height
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppColors.Background),
            ) {
                // Run row 1: 0, 1, 2
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(4.dp, 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(0, 1, 2).forEach { runs ->
                        ScoringButton(
                            text = "$runs",
                            color = RunButtonColor,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onClick = { viewModel.onRunsPressed(runs) },
                        )
                    }
                }
                // Run row 2: 3, 4, 6
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(4.dp, 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(3, 4, 6).forEach { runs ->
                        ScoringButton(
                            text = "$runs",
                            color = RunButtonColor,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onClick = { viewModel.onRunsPressed(runs) },
                        )
                    }
                }
                // Extras row 1: Wide, No Ball
                Row(
                    modifier = Modifier.fillMaxWidth().weight(0.8f).padding(4.dp, 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ScoringButton(
                        text = "Wide",
                        color = if (state.deliveryMode == UmpireDeliveryMode.WIDE) ActiveWideColor else ExtraButtonColor,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onClick = viewModel::onWidePressed,
                    )
                    ScoringButton(
                        text = "No Ball",
                        color = if (state.deliveryMode == UmpireDeliveryMode.NOBALL) ActiveWideColor else ExtraButtonColor,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onClick = viewModel::onNoBallPressed,
                    )
                }
                // Extras row 2: Bye, Leg Bye
                Row(
                    modifier = Modifier.fillMaxWidth().weight(0.8f).padding(4.dp, 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ScoringButton(
                        text = "Bye",
                        color = if (state.deliveryMode == UmpireDeliveryMode.BYE) ActiveByeColor else ByeButtonColor,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onClick = viewModel::onByePressed,
                    )
                    ScoringButton(
                        text = "Leg Bye",
                        color = if (state.deliveryMode == UmpireDeliveryMode.LEGBYE) ActiveByeColor else ByeButtonColor,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onClick = viewModel::onLegByePressed,
                    )
                }
                // Wicket
                ScoringButton(
                    text = if (state.pendingWicket) "◆ WICKET" else "WICKET",
                    color = if (state.pendingWicket) ActiveWicketColor else WicketButtonColor,
                    fontSize = 18.sp,
                    letterSpacing = 2.sp,
                    modifier = Modifier.fillMaxWidth().weight(0.8f).padding(horizontal = 4.dp, vertical = 2.dp),
                    onClick = viewModel::onWicketToggle,
                )
                // Controls row
                Row(
                    modifier = Modifier.fillMaxWidth().weight(0.7f).padding(4.dp, 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ScoringButton(
                        text = "Undo",
                        color = Color(0xFF546E7A),
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onClick = viewModel::onUndo,
                    )
                    ScoringButton(
                        text = "New Over",
                        color = Color(0xFF37474F),
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1.3f).fillMaxHeight(),
                        onClick = { showNewOverDialog = true },
                    )
                    ScoringButton(
                        text = "Reset",
                        color = Color(0xFF37474F),
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onClick = viewModel::onReset,
                    )
                }
            }
        }
    }

    if (showNewOverDialog) {
        AlertDialog(
            onDismissRequest = { showNewOverDialog = false },
            containerColor = AppColors.SurfaceElevated,
            titleContentColor = AppColors.OnBackground,
            textContentColor = AppColors.OnBackgroundMuted,
            title = { Text("End over?") },
            text = {
                Text("Over ${state.completedOvers + 1} complete. Start over ${state.completedOvers + 2}?")
            },
            confirmButton = {
                PressableTextButton(onClick = {
                    showNewOverDialog = false
                    viewModel.onEndOver()
                }) {
                    Text("New Over", color = AppColors.Primary, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                PressableTextButton(onClick = { showNewOverDialog = false }) {
                    Text("Cancel", color = AppColors.OnBackgroundMuted)
                }
            },
        )
    }
}

@Composable
private fun ScoringButton(
    text: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 22.sp,
    letterSpacing: TextUnit = TextUnit.Unspecified,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = rememberRipple(color = Color.White.copy(alpha = 0.2f)),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            letterSpacing = letterSpacing,
        )
    }
}

@Composable
private fun OverChip(ball: UmpireBallEvent) {
    val chipColor = when {
        ball.isWicket -> WicketChipColor
        ball.label.startsWith("Wd") -> WideChipColor
        ball.label.startsWith("Nb") -> NoBallChipColor
        ball.label.startsWith("Lb") -> ByeChipColor
        ball.label.startsWith("B") -> ByeChipColor
        ball.totalRuns >= 4 -> BoundaryChipColor
        ball.totalRuns > 0 -> RunChipColor
        else -> DotChipColor
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(chipColor)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = ball.label,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun EmptyChip() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF81C784), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text = "○", color = Color(0xFF81C784), fontSize = 13.sp)
    }
}

private fun modeIndicatorText(state: UmpireScorerState): String = when {
    state.deliveryMode != UmpireDeliveryMode.NORMAL && state.pendingWicket ->
        "${state.deliveryMode.name} + WICKET — tap runs"
    state.deliveryMode != UmpireDeliveryMode.NORMAL ->
        "${state.deliveryMode.name} — tap runs"
    state.pendingWicket -> "WICKET pending — tap runs"
    else -> "Tap runs — or pick type first"
}
