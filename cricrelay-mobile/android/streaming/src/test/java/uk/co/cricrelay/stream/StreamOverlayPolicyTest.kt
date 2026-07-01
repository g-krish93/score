package uk.co.cricrelay.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamOverlayPolicyTest {

    @Test
    fun `preview without streaming refreshes GL overlay`() {
        assertEquals(
            StreamOverlayPolicy.RefreshMode.PreviewGlRefresh,
            StreamOverlayPolicy.refreshMode(
                isStreaming = false,
                hasPreviewListener = false,
                overlayUrlBlank = false,
            ),
        )
    }

    @Test
    fun `live stream refreshes overlay on GL`() {
        assertEquals(
            StreamOverlayPolicy.RefreshMode.StreamRefresh,
            StreamOverlayPolicy.refreshMode(
                isStreaming = true,
                hasPreviewListener = false,
                overlayUrlBlank = false,
            ),
        )
    }

    @Test
    fun `blank overlay url never refreshes`() {
        assertEquals(
            StreamOverlayPolicy.RefreshMode.None,
            StreamOverlayPolicy.refreshMode(
                isStreaming = true,
                hasPreviewListener = true,
                overlayUrlBlank = true,
            ),
        )
    }

    @Test
    fun `GL overlay attaches during preview`() {
        assertTrue(StreamOverlayPolicy.shouldAttachGlOverlayOnPreview(isStreaming = false))
        assertFalse(StreamOverlayPolicy.shouldAttachGlOverlayOnPreview(isStreaming = true))
    }

    @Test
    fun `GL overlay attaches only while streaming with overlay url`() {
        assertTrue(
            StreamOverlayPolicy.shouldAttachGlOverlayOnStream(
                isStreaming = true,
                overlayUrlBlank = false,
            ),
        )
        assertFalse(
            StreamOverlayPolicy.shouldAttachGlOverlayOnStream(
                isStreaming = false,
                overlayUrlBlank = false,
            ),
        )
    }
}
