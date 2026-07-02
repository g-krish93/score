package uk.co.cricrelay.mobile.feature.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.cricrelay.shared.model.OverlayLayoutPrefs
import uk.co.cricrelay.stream.StreamCameraEngine
import uk.co.cricrelay.stream.StreamOverlayPolicy

/**
 * JVM runtime checks for overlay preview (session f8bbc4).
 */
class OverlayPreviewThemeTest {

    @Test
    fun `preview mode uses GL refresh before stream`() {
        val mode = StreamOverlayPolicy.refreshMode(
            isStreaming = false,
            hasPreviewListener = false,
            overlayUrlBlank = false,
        )
        assertEquals(StreamOverlayPolicy.RefreshMode.PreviewGlRefresh, mode)
    }

    @Test
    fun `stream mode uses StreamRefresh for GL burn-in`() {
        val mode = StreamOverlayPolicy.refreshMode(
            isStreaming = true,
            hasPreviewListener = false,
            overlayUrlBlank = false,
        )
        assertEquals(StreamOverlayPolicy.RefreshMode.StreamRefresh, mode)
    }

    @Test
    fun `toEngineLayout carries theme to capture engine`() {
        val prefs = OverlayLayoutPrefs(theme = "barlow", fontScale = 1.1)
        val layout = prefs.toEngineLayout()
        assertEquals("barlow", layout.theme)
        assertTrue(layout.fontScale == prefs.effectiveFontScale())
    }

    @Test
    fun `toEngineLayout carries the scoreboard master switch`() {
        assertTrue(OverlayLayoutPrefs().toEngineLayout().overlayEnabled)
        assertTrue(!OverlayLayoutPrefs(overlayEnabled = false).toEngineLayout().overlayEnabled)
    }

    @Test
    fun `sanitizeTheme maps legacy values to barlow`() {
        assertEquals("barlow", OverlayLayoutPrefs.sanitizeTheme("unknown"))
        assertEquals("barlow", OverlayLayoutPrefs.sanitizeTheme("classic"))
        assertEquals("barlow", OverlayLayoutPrefs.sanitizeTheme("barlow"))
    }
}
