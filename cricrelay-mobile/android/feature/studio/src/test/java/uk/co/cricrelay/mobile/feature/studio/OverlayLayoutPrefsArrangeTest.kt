package uk.co.cricrelay.mobile.feature.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.cricrelay.shared.model.OverlayLayoutPrefs
import uk.co.cricrelay.shared.model.SponsorScrollDirection

/**
 * Arrange-mode model behaviour: uniform pinch scale, drag anchoring, sponsor scroll direction,
 * and the flush-bottom default (session: pre-live arrange flow).
 */
class OverlayLayoutPrefsArrangeTest {

    @Test
    fun `default board sits flush to the bottom`() {
        assertEquals(0.0, OverlayLayoutPrefs().bottomMargin, 0.0)
    }

    @Test
    fun `withBoardScale drives widthFraction and round-trips through boardScale`() {
        // The engine sizes the board from widthFraction + the bitmap's native aspect (uniform),
        // so boardScale must round-trip through widthFraction.
        val small = OverlayLayoutPrefs().withBoardScale(0.5)
        val big = OverlayLayoutPrefs().withBoardScale(0.9)
        assertTrue("bigger scale => wider board", big.widthFraction > small.widthFraction)
        assertEquals(0.5, small.boardScale(), 0.02)
        assertEquals(0.9, big.boardScale(), 0.02)
    }

    @Test
    fun `board scale clamps to configured bounds`() {
        val tooBig = OverlayLayoutPrefs().withBoardScale(5.0).boardScale()
        val tooSmall = OverlayLayoutPrefs().withBoardScale(0.01).boardScale()
        assertTrue(tooBig <= OverlayLayoutPrefs.BOARD_SCALE_MAX + 1e-6)
        assertTrue(tooSmall >= OverlayLayoutPrefs.BOARD_SCALE_MIN - 1e-6)
    }

    @Test
    fun `withAnchor clears bottom margin and clamps vertical range`() {
        val moved = OverlayLayoutPrefs(bottomMargin = 20.0).withAnchor(0.5, 0.1)
        assertEquals(0.0, moved.bottomMargin, 0.0)
        assertEquals(OverlayLayoutPrefs.ANCHOR_Y_MIN, moved.anchorY, 1e-6)
    }

    @Test
    fun `scroll direction sanitizes and classifies axis`() {
        assertEquals(SponsorScrollDirection.RTL, SponsorScrollDirection.sanitize("nonsense"))
        assertTrue(SponsorScrollDirection.isVertical(SponsorScrollDirection.TTB))
        assertTrue(SponsorScrollDirection.isVertical(SponsorScrollDirection.BTT))
        assertTrue(SponsorScrollDirection.isHorizontal(SponsorScrollDirection.LTR))
        assertTrue(SponsorScrollDirection.isHorizontal(SponsorScrollDirection.RTL))
    }
}
