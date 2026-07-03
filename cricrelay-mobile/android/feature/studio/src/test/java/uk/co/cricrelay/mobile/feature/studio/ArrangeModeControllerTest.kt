package uk.co.cricrelay.mobile.feature.studio

import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uk.co.cricrelay.shared.model.OverlayLayoutPrefs

class ArrangeModeControllerTest {

    private lateinit var state: MutableStateFlow<StudioUiState>
    private lateinit var overlaySync: OverlaySyncController
    private lateinit var arrange: ArrangeModeController

    @Before
    fun setUp() {
        state = MutableStateFlow(StudioUiState(loading = false))
        overlaySync = mockk(relaxed = true)
        arrange = ArrangeModeController(state, overlaySync)
    }

    @Test
    fun `entering arrange mode snapshots the committed prefs and closes any sheet`() {
        val committed = OverlayLayoutPrefs(fontScale = 1.3)
        state.value = state.value.copy(overlayPrefs = committed, activeSheet = StudioSheet.Overlay)

        arrange.enterArrangeMode()

        val s = state.value
        assertTrue(s.arrangeMode)
        assertEquals(committed, s.arrangeDraft)
        assertEquals(StudioSheet.None, s.activeSheet)
    }

    @Test
    fun `cancel reverts the preview and discards the draft`() {
        arrange.enterArrangeMode()
        arrange.pinchBoard(0.8f)

        arrange.cancelArrangeMode()

        assertFalse(state.value.arrangeMode)
        assertNull(state.value.arrangeDraft)
        verify { overlaySync.revertOverlayPreview() }
        verify(exactly = 0) { overlaySync.updateOverlayPrefs(any()) }
    }

    @Test
    fun `commit persists the draft once and leaves arrange mode`() {
        arrange.enterArrangeMode()
        arrange.pinchBoard(0.8f)
        val draft = state.value.arrangeDraft!!

        arrange.commitArrangeMode()

        assertFalse(state.value.arrangeMode)
        assertNull(state.value.arrangeDraft)
        verify(exactly = 1) { overlaySync.updateOverlayPrefs(draft) }
    }

    @Test
    fun `commit without ever entering does not persist`() {
        arrange.commitArrangeMode()
        verify(exactly = 0) { overlaySync.updateOverlayPrefs(any()) }
    }

    @Test
    fun `commit advances the first-run precheck from arrange to ready`() {
        state.value = state.value.copy(precheckActive = true, precheckStep = PrecheckStep.Arrange)
        arrange.enterArrangeMode()

        arrange.commitArrangeMode()

        assertEquals(PrecheckStep.Ready, state.value.precheckStep)
    }

    @Test
    fun `commit outside the precheck leaves the step alone`() {
        state.value = state.value.copy(precheckActive = false, precheckStep = PrecheckStep.Camera)
        arrange.enterArrangeMode()

        arrange.commitArrangeMode()

        assertEquals(PrecheckStep.Camera, state.value.precheckStep)
    }

    // ── pinch ───────────────────────────────────────────────────────────────

    @Test
    fun `pinch scales the board draft and previews it`() {
        state.value = state.value.copy(overlayPrefs = OverlayLayoutPrefs().withBoardScale(0.8))
        arrange.enterArrangeMode()

        arrange.pinchBoard(0.75f)

        val draft = state.value.arrangeDraft!!
        assertEquals(0.6, draft.boardScale(), 1e-6)
        verify { overlaySync.previewOverlayPrefs(draft) }
    }

    @Test
    fun `pinch clamps to the board scale bounds`() {
        arrange.enterArrangeMode()

        // Full-width board tops out at WIDTH_MAX, so the max reachable scale is 0.98, not 1.0.
        arrange.pinchBoard(100f)
        assertEquals(OverlayLayoutPrefs.WIDTH_MAX, state.value.arrangeDraft!!.boardScale(), 1e-6)

        arrange.pinchBoard(0.001f)
        assertEquals(OverlayLayoutPrefs.BOARD_SCALE_MIN, state.value.arrangeDraft!!.boardScale(), 1e-6)
    }

    @Test
    fun `zero or negative pinch ratios are ignored`() {
        arrange.enterArrangeMode()
        val before = state.value.arrangeDraft

        arrange.pinchBoard(0f)
        arrange.pinchBoard(-1f)

        assertEquals(before, state.value.arrangeDraft)
        verify(exactly = 0) { overlaySync.previewOverlayPrefs(any()) }
    }

    // ── drag ────────────────────────────────────────────────────────────────

    @Test
    fun `dragging the board moves the anchor and lifts the bottom margin`() {
        arrange.enterArrangeMode()

        // dy < 0 = drag up: bottomMargin grows by fraction * 400.
        arrange.dragArrange(dxFraction = 0.1f, dyFraction = -0.1f)

        val draft = state.value.arrangeDraft!!
        assertEquals(0.6, draft.anchorX, 1e-6)
        assertEquals(40.0, draft.bottomMargin, 1e-4)
    }

    @Test
    fun `board drag clamps at the frame edges`() {
        arrange.enterArrangeMode()

        arrange.dragArrange(dxFraction = 5f, dyFraction = 5f)
        var draft = state.value.arrangeDraft!!
        assertEquals(1.0, draft.anchorX, 1e-6)
        assertEquals(0.0, draft.bottomMargin, 1e-6)

        arrange.dragArrange(dxFraction = -5f, dyFraction = -5f)
        draft = state.value.arrangeDraft!!
        assertEquals(0.0, draft.anchorX, 1e-6)
        assertEquals(400.0, draft.bottomMargin, 1e-6)
    }

    @Test
    fun `dragging the sponsor moves and clamps its normalized position`() {
        arrange.enterArrangeMode()
        arrange.setArrangeTarget(ArrangeTarget.Sponsor)

        arrange.dragArrange(dxFraction = -0.5f, dyFraction = 0.05f)

        val draft = state.value.arrangeDraft!!
        assertEquals(0.42, draft.sponsorPositionX, 1e-6)
        assertEquals(0.93, draft.sponsorPositionY, 1e-6)
        // The board is untouched when the sponsor is the drag target.
        assertEquals(0.5, draft.anchorX, 1e-6)

        arrange.dragArrange(dxFraction = -5f, dyFraction = 5f)
        val clamped = state.value.arrangeDraft!!
        assertEquals(0.0, clamped.sponsorPositionX, 1e-6)
        assertEquals(1.0, clamped.sponsorPositionY, 1e-6)
    }

    @Test
    fun `gestures without entering arrange mode still draft from committed prefs`() {
        // Defensive path: mutateArrangeDraft falls back to the committed prefs as the base.
        state.value = state.value.copy(overlayPrefs = OverlayLayoutPrefs(anchorX = 0.4))

        arrange.dragArrange(dxFraction = 0.1f, dyFraction = 0f)

        assertEquals(0.5, state.value.arrangeDraft!!.anchorX, 1e-6)
    }
}
