package uk.co.cricrelay.shared

import org.junit.Assert.assertEquals
import org.junit.Test
import uk.co.cricrelay.shared.model.OverlayLayoutPrefs

class OverlayLayoutPrefsTest {

    @Test
    fun `effective font is independent of board size`() {
        val full = OverlayLayoutPrefs(fontScale = 1.2)
        val small = OverlayLayoutPrefs(
            fontScale = 1.2,
            widthFraction = 0.46,
            heightFraction = 0.08,
        )
        assertEquals(full.effectiveFontScale(), small.effectiveFontScale(), 0.001f)
    }

    @Test
    fun `effective font stays at reference for default board size`() {
        val prefs = OverlayLayoutPrefs()
        assertEquals(1.0f, prefs.effectiveFontScale(), 0.001f)
    }

    @Test
    fun `board display scale tracks width and height sliders`() {
        val prefs = OverlayLayoutPrefs(
            widthFraction = 0.5,
            heightFraction = 0.12,
        )
        assertEquals(0.5f, prefs.boardDisplayScaleX(), 0.001f)
        assertEquals(0.75f, prefs.boardDisplayScaleY(), 0.001f)
    }
}
