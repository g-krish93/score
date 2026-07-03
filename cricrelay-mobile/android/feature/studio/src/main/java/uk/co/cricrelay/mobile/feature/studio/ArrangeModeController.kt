package uk.co.cricrelay.mobile.feature.studio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import uk.co.cricrelay.shared.model.OverlayLayoutPrefs

// Vertical drag maps to the board's bottom margin (px, /720 in the engine). Allow lifting the
// board up to ~55% of the frame so the operator can place a lower-third or a mid-frame board.
private const val BOARD_DRAG_MARGIN_SPAN = 400.0
private const val BOARD_DRAG_MARGIN_MAX = 400.0

/**
 * Arrange mode: direct pinch/drag manipulation of the board + sponsor over the live composited
 * preview. Gestures push to the engine via preview (no network); commit persists once, on
 * "Done". Extracted from [StudioViewModel]; shares its UiState flow.
 */
class ArrangeModeController(
    private val uiState: MutableStateFlow<StudioUiState>,
    private val overlaySync: OverlaySyncController,
) {

    fun enterArrangeMode() {
        uiState.update {
            it.copy(arrangeMode = true, arrangeDraft = it.overlayPrefs, activeSheet = StudioSheet.None)
        }
    }

    fun cancelArrangeMode() {
        overlaySync.revertOverlayPreview()
        uiState.update { it.copy(arrangeMode = false, arrangeDraft = null) }
    }

    fun commitArrangeMode() {
        val draft = uiState.value.arrangeDraft
        uiState.update {
            it.copy(
                arrangeMode = false,
                arrangeDraft = null,
                // Completing Arrange advances the first-run precheck to its final step.
                precheckStep = if (it.precheckActive && it.precheckStep == PrecheckStep.Arrange) {
                    PrecheckStep.Ready
                } else {
                    it.precheckStep
                },
            )
        }
        if (draft != null) overlaySync.updateOverlayPrefs(draft)
    }

    fun setArrangeTarget(target: ArrangeTarget) {
        uiState.update { it.copy(arrangeTarget = target) }
    }

    /** Pinch: [zoom] is the incremental scale ratio (~1.0) from the transform gesture. */
    fun pinchBoard(zoom: Float) {
        if (zoom <= 0f) return
        mutateArrangeDraft { it.withBoardScale(it.boardScale() * zoom) }
    }

    /** Drag the active target by a fraction of the preview (dy<0 = up). */
    fun dragArrange(dxFraction: Float, dyFraction: Float) {
        mutateArrangeDraft { p ->
            when (uiState.value.arrangeTarget) {
                ArrangeTarget.Board -> p.copy(
                    anchorX = (p.anchorX + dxFraction).coerceIn(0.0, 1.0),
                    // Android's GL sprite reads bottomMargin (px/720) for vertical placement:
                    // dragging up (dy<0) lifts the board off the bottom edge.
                    bottomMargin = (p.bottomMargin - dyFraction * BOARD_DRAG_MARGIN_SPAN)
                        .coerceIn(0.0, BOARD_DRAG_MARGIN_MAX),
                )
                ArrangeTarget.Sponsor -> p.copy(
                    sponsorPositionX = (p.sponsorPositionX + dxFraction).coerceIn(0.0, 1.0),
                    sponsorPositionY = (p.sponsorPositionY + dyFraction).coerceIn(0.0, 1.0),
                )
            }
        }
    }

    private fun mutateArrangeDraft(block: (OverlayLayoutPrefs) -> OverlayLayoutPrefs) {
        val current = uiState.value.arrangeDraft ?: uiState.value.overlayPrefs
        val next = block(current)
        uiState.update { it.copy(arrangeDraft = next) }
        overlaySync.previewOverlayPrefs(next)
    }
}
