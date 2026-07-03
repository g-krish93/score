package uk.co.cricrelay.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayThemeBridgeTest {

    @Test
    fun `cricketOverlayTheme always navy accent`() {
        assertEquals("navy", OverlayThemeBridge.cricketOverlayTheme("barlow"))
        assertEquals("navy", OverlayThemeBridge.cricketOverlayTheme("floodlight"))
    }

    @Test
    fun `sanitize keeps the six known ids and falls back to floodlight`() {
        listOf("barlow", "floodlight", "chalk", "club-green", "broadcast-blue", "mono")
            .forEach { id -> assertEquals(id, OverlayThemeBridge.sanitizeBoardStyle(id)) }
        assertEquals("chalk", OverlayThemeBridge.sanitizeBoardStyle("  CHALK "))
        assertEquals("floodlight", OverlayThemeBridge.sanitizeBoardStyle("classic"))
        assertEquals("floodlight", OverlayThemeBridge.sanitizeBoardStyle(""))
        assertEquals("floodlight", OverlayThemeBridge.sanitizeBoardStyle(null))
    }

    @Test
    fun `urlWithTheme keeps barlow url byte-identical to legacy builds`() {
        val base = "https://cricrelay.co.uk/m/demo/stream?embed=1"
        val themed = OverlayThemeBridge.urlWithTheme(base, "barlow")
        // Behavioral invariant: old theme -> exact same URL as pre-preset releases.
        assertEquals(
            "https://cricrelay.co.uk/m/demo/stream?embed=1&theme=navy&boardStyle=barlow",
            themed,
        )
        assertFalse(themed.contains("island="))
    }

    @Test
    fun `urlWithTheme emits preset boardStyle with island flag`() {
        val base = "https://cricrelay.co.uk/m/demo/stream?embed=1"
        val on = OverlayThemeBridge.urlWithTheme(base, "floodlight", bowlingIslandEnabled = true)
        assertTrue(on.contains("theme=navy"))
        assertTrue(on.contains("boardStyle=floodlight"))
        assertTrue(on.contains("island=1"))
        assertTrue(on.contains("embed=1"))
        val off = OverlayThemeBridge.urlWithTheme(base, "floodlight", bowlingIslandEnabled = false)
        assertTrue(off.contains("boardStyle=floodlight"))
        assertTrue(off.contains("island=0"))
    }

    @Test
    fun `urlWithTheme emits every preset id`() {
        listOf("chalk", "club-green", "broadcast-blue", "mono").forEach { id ->
            val themed = OverlayThemeBridge.urlWithTheme("https://x/y?embed=1", id)
            assertTrue("missing boardStyle=$id in $themed", themed.contains("boardStyle=$id"))
            assertTrue(themed.contains("island=1"))
        }
    }

    @Test
    fun `urlWithTheme sanitizes unknown theme to floodlight`() {
        val themed = OverlayThemeBridge.urlWithTheme("https://x/y", "stadium")
        assertTrue(themed.contains("boardStyle=floodlight"))
        assertTrue(themed.contains("island=1"))
    }

    @Test
    fun `urlWithTheme replaces existing theme boardStyle and island params`() {
        val base =
            "https://cricrelay.co.uk/m/demo/stream?embed=1&theme=gold&boardStyle=classic&island=0"
        val themed = OverlayThemeBridge.urlWithTheme(base, "mono", bowlingIslandEnabled = true)
        assertTrue(themed.contains("theme=navy"))
        assertTrue(themed.contains("boardStyle=mono"))
        assertTrue(themed.contains("island=1"))
        assertTrue(themed.contains("embed=1"))
        assertEquals(1, themed.split("theme=").size - 1)
        assertEquals(1, themed.split("boardStyle=").size - 1)
        assertEquals(1, themed.split("island=").size - 1)
    }

    @Test
    fun `applyThemeScript calls new board api when fl-root present`() {
        val script =
            OverlayThemeBridge.applyThemeScript("floodlight", bowlingIslandEnabled = true, compact = false)
        assertTrue(script.contains("document.getElementById('fl-root')"))
        assertTrue(script.contains("applyBoardStyle('floodlight',{island:true,compact:false})"))
    }

    @Test
    fun `applyThemeScript passes island and compact flags`() {
        val script =
            OverlayThemeBridge.applyThemeScript("chalk", bowlingIslandEnabled = false, compact = true)
        assertTrue(script.contains("applyBoardStyle('chalk',{island:false,compact:true})"))
    }

    @Test
    fun `applyThemeScript keeps legacy barlow fallback for old server pages`() {
        // Capability guard: new app + old server HTML (no #fl-root) must keep rendering the
        // barlow board exactly as today — classList add + no-arg applyBoardStyle.
        listOf("barlow", "floodlight", "mono").forEach { theme ->
            val script = OverlayThemeBridge.applyThemeScript(theme)
            assertTrue(script.contains("document.body.classList.add('board-barlow')"))
            assertTrue(script.contains("if(typeof applyBoardStyle==='function'){ applyBoardStyle(); }"))
        }
    }

    @Test
    fun `applyThemeScript sanitizes unknown theme`() {
        val script = OverlayThemeBridge.applyThemeScript("stadium")
        assertTrue(script.contains("applyBoardStyle('floodlight',{island:true,compact:false})"))
    }
}
