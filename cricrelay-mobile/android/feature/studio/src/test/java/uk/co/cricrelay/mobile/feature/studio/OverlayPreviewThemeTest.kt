package uk.co.cricrelay.mobile.feature.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.cricrelay.shared.model.BoardPreset
import uk.co.cricrelay.shared.model.OverlayLayoutPrefs
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
    fun `toEngineLayout carries every preset id and the island flag`() {
        for (preset in BoardPreset.ALL) {
            val layout = OverlayLayoutPrefs(theme = preset.id, bowlingIslandEnabled = false)
                .toEngineLayout()
            assertEquals(preset.id, layout.theme)
            assertEquals(false, layout.bowlingIslandEnabled)
        }
    }

    @Test
    fun `sanitizeTheme keeps known ids and falls back to floodlight`() {
        assertEquals("floodlight", OverlayLayoutPrefs.sanitizeTheme("unknown"))
        assertEquals("floodlight", OverlayLayoutPrefs.sanitizeTheme("classic"))
        // Legacy boards stored explicitly as barlow keep their exact look.
        assertEquals("barlow", OverlayLayoutPrefs.sanitizeTheme("barlow"))
        assertEquals("club-green", OverlayLayoutPrefs.sanitizeTheme("club-green"))
    }

    @Test
    fun `preset catalogue resolves ids with a floodlight fallback`() {
        assertEquals("Classic", BoardPreset.byId("barlow").displayName)
        assertEquals("floodlight", BoardPreset.byId("not-a-preset").id)
    }
}
