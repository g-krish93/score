package uk.co.cricrelay.mobile.feature.studio

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import uk.co.cricrelay.shared.model.OverlayLayoutPrefs

// Vertical drag maps to the board's bottom margin (px, /720 in the engine). Allow lifting the
// board up to ~55% of the frame so the operator can place a lower-third or a mid-frame board.
private const val BOARD_DRAG_MARGIN_SPAN = 400.0
private const val BOARD_DRAG_MARGIN_MAX = 400.0

// The engine expresses the board's vertical lift as bottomMargin/720 of the frame height.
private const val ENGINE_MARGIN_REF = 720.0

// Snap feel from the arrange prototype: centre lines catch within 7px, the 16px safe margins
// within 8px, and the corner handle scales the board by start·(1 + dx/140).
private const val CENTRE_SNAP_PX = 7f
private const val EDGE_SNAP_PX = 8f
private const val SAFE_MARGIN_PX = 16f
private const val RESIZE_HANDLE_SPAN_PX = 140f

/**
 * Arrange mode: direct pinch/drag manipulation of the board + sponsor over the live composited
 * preview. Gestures push to the engine via preview (no network); commit persists once, on
 * "Done". Drags snap to the centre lines and 16px safe margins, publishing the gold guide
 * flags + monospace readout that [ArrangeOverlay] renders. Extracted from [StudioViewModel];
 * shares its UiState flow.
 */
class ArrangeModeController(
    private val uiState: MutableStateFlow<StudioUiState>,
    private val overlaySync: OverlaySyncController,
) {

    /** Board scale at the start of a corner-handle drag; cleared on [dragEnded]. */
    private var resizeStartScale: Double? = null

    private data class Snap(val value: Double, val centred: Boolean = false)

    fun enterArrangeMode() {
        uiState.update {
            it.copy(arrangeMode = true, arrangeDraft = it.overlayPrefs, activeSheet = StudioSheet.None)
        }
    }

    fun cancelArrangeMode() {
        overlaySync.revertOverlayPreview()
        uiState.update {
            it.copy(
                arrangeMode = false,
                arrangeDraft = null,
                arrangeGuideV = false,
                arrangeGuideH = false,
                arrangeReadout = null,
            )
        }
        resizeStartScale = null
    }

    fun commitArrangeMode() {
        val draft = uiState.value.arrangeDraft
        uiState.update {
            it.copy(
                arrangeMode = false,
                arrangeDraft = null,
                arrangeGuideV = false,
                arrangeGuideH = false,
                arrangeReadout = null,
            )
        }
        resizeStartScale = null
        if (draft != null) overlaySync.updateOverlayPrefs(draft)
    }

    fun setArrangeTarget(target: ArrangeTarget) {
        uiState.update { it.copy(arrangeTarget = target) }
    }

    /** Pinch: [zoom] is the incremental scale ratio (~1.0) from the transform gesture. */
    fun pinchBoard(zoom: Float) {
        if (zoom <= 0f) return
        mutateArrangeDraft { it.withBoardScale(it.boardScale() * zoom) }
        publishFeedback(readout = widthReadout(uiState.value.arrangeDraft ?: return))
    }

    /**
     * Corner-handle resize (prototype feel: scale = start·(1 + dx/140)). [totalDxPx] is the
     * cumulative horizontal drag since the handle was grabbed; the start scale is captured on
     * the first call and held until [dragEnded].
     */
    fun resizeBoardHandle(totalDxPx: Float) {
        val start = resizeStartScale
            ?: (uiState.value.arrangeDraft ?: uiState.value.overlayPrefs).boardScale()
                .also { resizeStartScale = it }
        // withBoardScale clamps to the existing BOARD_SCALE bounds — those stay authoritative.
        mutateArrangeDraft { it.withBoardScale(start * (1.0 + totalDxPx / RESIZE_HANDLE_SPAN_PX)) }
        publishFeedback(readout = widthReadout(uiState.value.arrangeDraft ?: return))
    }

    /**
     * Drag the active target by a fraction of the preview (dy<0 = up). The preview's pixel
     * size turns the fractional positions back into px so the snap thresholds feel identical
     * on every screen; pass zeros to skip snapping (defensive, e.g. before first layout).
     */
    fun dragArrange(
        dxFraction: Float,
        dyFraction: Float,
        previewWidthPx: Float = 0f,
        previewHeightPx: Float = 0f,
    ) {
        val canSnap = previewWidthPx > 0f && previewHeightPx > 0f
        var guideV = false
        var guideH = false
        var readout: String? = null
        mutateArrangeDraft { p ->
            when (uiState.value.arrangeTarget) {
                ArrangeTarget.Board -> {
                    var anchorX = (p.anchorX + dxFraction).coerceIn(0.0, 1.0)
                    // Android's GL sprite reads bottomMargin (px/720) for vertical placement:
                    // dragging up (dy<0) lifts the board off the bottom edge.
                    var bottomMargin = (p.bottomMargin - dyFraction * BOARD_DRAG_MARGIN_SPAN)
                        .coerceIn(0.0, BOARD_DRAG_MARGIN_MAX)
                    if (canSnap) {
                        val snapX = snapBoardX(anchorX, p.clampedWidthFraction(), previewWidthPx)
                        anchorX = snapX.value
                        guideV = snapX.centred
                        val snapY =
                            snapBoardMargin(bottomMargin, p.clampedHeightFraction(), previewHeightPx)
                        bottomMargin = snapY.value
                        guideH = snapY.centred
                    }
                    readout = "BOARD ${(anchorX * 100).roundToInt()}% · " +
                        "${(bottomMargin / ENGINE_MARGIN_REF * 100).roundToInt()}%"
                    p.copy(anchorX = anchorX, bottomMargin = bottomMargin)
                }
                ArrangeTarget.Sponsor -> {
                    var x = (p.sponsorPositionX + dxFraction).coerceIn(0.0, 1.0)
                    var y = (p.sponsorPositionY + dyFraction).coerceIn(0.0, 1.0)
                    if (canSnap) {
                        val snapX = snapAxis(x, previewWidthPx)
                        x = snapX.value
                        guideV = snapX.centred
                        val snapY = snapAxis(y, previewHeightPx)
                        y = snapY.value
                        guideH = snapY.centred
                    }
                    readout = "SPONSOR ${(x * 100).roundToInt()}% · ${(y * 100).roundToInt()}%"
                    p.copy(sponsorPositionX = x, sponsorPositionY = y)
                }
            }
        }
        publishFeedback(guideV = guideV, guideH = guideH, readout = readout)
    }

    /** Fingers lifted — clear guides, the readout, and the corner-handle scale baseline. */
    fun dragEnded() {
        resizeStartScale = null
        publishFeedback()
    }

    // ── snapping ────────────────────────────────────────────────────────────

    /** Board horizontal snap: vertical centre line, then the 16px safe margins on both edges. */
    private fun snapBoardX(anchorX: Double, widthFraction: Double, previewWidthPx: Float): Snap {
        if (abs(anchorX - 0.5) * previewWidthPx < CENTRE_SNAP_PX) return Snap(0.5, centred = true)
        val safe = (SAFE_MARGIN_PX / previewWidthPx).toDouble()
        val half = widthFraction / 2.0
        val leftEdge = anchorX - half
        if (abs(leftEdge - safe) * previewWidthPx < EDGE_SNAP_PX) {
            return Snap((safe + half).coerceIn(0.0, 1.0))
        }
        val rightEdge = anchorX + half
        if (abs((1.0 - safe) - rightEdge) * previewWidthPx < EDGE_SNAP_PX) {
            return Snap((1.0 - safe - half).coerceIn(0.0, 1.0))
        }
        return Snap(anchorX)
    }

    /**
     * Board vertical snap, expressed on bottomMargin (px/720): the horizontal centre line via
     * the board's mid-height, then the 16px safe margins for the bottom and top edges.
     */
    private fun snapBoardMargin(
        bottomMargin: Double,
        heightFraction: Double,
        previewHeightPx: Float,
    ): Snap {
        val marginFraction = bottomMargin / ENGINE_MARGIN_REF
        val centreFromBottom = marginFraction + heightFraction / 2.0
        if (abs(centreFromBottom - 0.5) * previewHeightPx < CENTRE_SNAP_PX) {
            val snapped = (0.5 - heightFraction / 2.0) * ENGINE_MARGIN_REF
            return Snap(snapped.coerceIn(0.0, BOARD_DRAG_MARGIN_MAX), centred = true)
        }
        val safe = (SAFE_MARGIN_PX / previewHeightPx).toDouble()
        if (abs(marginFraction - safe) * previewHeightPx < EDGE_SNAP_PX) {
            return Snap((safe * ENGINE_MARGIN_REF).coerceIn(0.0, BOARD_DRAG_MARGIN_MAX))
        }
        val topEdgeFromBottom = marginFraction + heightFraction
        if (abs((1.0 - safe) - topEdgeFromBottom) * previewHeightPx < EDGE_SNAP_PX) {
            val snapped = (1.0 - safe - heightFraction) * ENGINE_MARGIN_REF
            return Snap(snapped.coerceIn(0.0, BOARD_DRAG_MARGIN_MAX))
        }
        return Snap(bottomMargin)
    }

    /** Sponsor snap on one normalized axis: centre line, then both 16px safe margins. */
    private fun snapAxis(value: Double, previewPx: Float): Snap {
        if (abs(value - 0.5) * previewPx < CENTRE_SNAP_PX) return Snap(0.5, centred = true)
        val safe = (SAFE_MARGIN_PX / previewPx).toDouble()
        if (abs(value - safe) * previewPx < EDGE_SNAP_PX) return Snap(safe)
        if (abs(value - (1.0 - safe)) * previewPx < EDGE_SNAP_PX) return Snap(1.0 - safe)
        return Snap(value)
    }

    private fun widthReadout(prefs: OverlayLayoutPrefs): String =
        "BOARD WIDTH ${(prefs.clampedWidthFraction() * 100).roundToInt()}%"

    private fun publishFeedback(
        guideV: Boolean = false,
        guideH: Boolean = false,
        readout: String? = null,
    ) {
        uiState.update {
            it.copy(arrangeGuideV = guideV, arrangeGuideH = guideH, arrangeReadout = readout)
        }
    }

    private fun mutateArrangeDraft(block: (OverlayLayoutPrefs) -> OverlayLayoutPrefs) {
        val current = uiState.value.arrangeDraft ?: uiState.value.overlayPrefs
        val next = block(current)
        uiState.update { it.copy(arrangeDraft = next) }
        overlaySync.previewOverlayPrefs(next)
    }
}
