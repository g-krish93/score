package uk.co.cricrelay.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayThemeBridgeTest {

    @Test
    fun `cricketOverlayTheme always navy accent`() {
        assertEquals("navy", OverlayThemeBridge.cricketOverlayTheme("barlow"))
        assertEquals("navy", OverlayThemeBridge.cricketOverlayTheme("classic"))
    }

    @Test
    fun `urlWithTheme appends barlow boardStyle`() {
        val base = "https://cricrelay.co.uk/m/demo/stream?embed=1"
        val themed = OverlayThemeBridge.urlWithTheme(base, "barlow")
        assertTrue(themed.contains("theme=navy"))
        assertTrue(themed.contains("boardStyle=barlow"))
        assertTrue(themed.contains("embed=1"))
    }

    @Test
    fun `urlWithTheme replaces existing theme param`() {
        val base = "https://cricrelay.co.uk/m/demo/stream?embed=1&theme=gold&boardStyle=classic"
        val themed = OverlayThemeBridge.urlWithTheme(base, "stadium")
        assertTrue(themed.contains("theme=navy"))
        assertTrue(themed.contains("boardStyle=barlow"))
        assertEquals(1, themed.split("theme=").size - 1)
    }
}
