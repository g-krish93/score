package uk.co.cricrelay.shared

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.cricrelay.shared.model.BoardPreset
import uk.co.cricrelay.shared.model.OverlayLayoutPrefs
import uk.co.cricrelay.shared.model.StabilizationLevel

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

    @Test
    fun `toJson writes both stabilization fields from the level`() {
        val json = OverlayLayoutPrefs().withStabilizationLevel(StabilizationLevel.CINEMATIC).toJson()
        assertEquals(JsonPrimitive(2), json["stabilization_level"])
        assertEquals(JsonPrimitive(true), json["video_stabilization"])

        val offJson = OverlayLayoutPrefs().withStabilizationLevel(StabilizationLevel.OFF).toJson()
        assertEquals(JsonPrimitive(0), offJson["stabilization_level"])
        assertEquals(JsonPrimitive(false), offJson["video_stabilization"])
    }

    @Test
    fun `fromJson prefers stabilization level over legacy boolean`() {
        val prefs = OverlayLayoutPrefs.fromJson(
            buildJsonObject {
                put("stabilization_level", 2)
                put("video_stabilization", false)
            },
        )
        assertEquals(StabilizationLevel.CINEMATIC, prefs.stabilizationLevel)
        assertTrue(prefs.videoStabilization)
    }

    @Test
    fun `fromJson falls back to legacy boolean when level absent`() {
        val off = OverlayLayoutPrefs.fromJson(buildJsonObject { put("video_stabilization", false) })
        assertEquals(StabilizationLevel.OFF, off.stabilizationLevel)
        assertFalse(off.videoStabilization)

        val on = OverlayLayoutPrefs.fromJson(buildJsonObject { put("video_stabilization", true) })
        assertEquals(StabilizationLevel.STANDARD, on.stabilizationLevel)
        assertTrue(on.videoStabilization)

        val absent = OverlayLayoutPrefs.fromJson(buildJsonObject { })
        assertEquals(StabilizationLevel.STANDARD, absent.stabilizationLevel)
        assertTrue(absent.videoStabilization)
    }

    @Test
    fun `withStabilizationLevel sanitizes and keeps the boolean in sync`() {
        assertEquals(
            StabilizationLevel.CINEMATIC,
            OverlayLayoutPrefs().withStabilizationLevel(7).stabilizationLevel,
        )
        assertEquals(
            StabilizationLevel.OFF,
            OverlayLayoutPrefs().withStabilizationLevel(-1).stabilizationLevel,
        )
        assertFalse(OverlayLayoutPrefs().withStabilizationLevel(0).videoStabilization)
        assertTrue(OverlayLayoutPrefs().withStabilizationLevel(1).videoStabilization)
    }

    @Test
    fun `fresh prefs default to the floodlight board with the island on`() {
        val prefs = OverlayLayoutPrefs()
        assertEquals("floodlight", prefs.theme)
        assertTrue(prefs.bowlingIslandEnabled)
    }

    @Test
    fun `sanitizeTheme accepts every preset id`() {
        for (id in listOf("barlow", "floodlight", "chalk", "club-green", "broadcast-blue", "mono")) {
            assertEquals(id, OverlayLayoutPrefs.sanitizeTheme(id))
        }
        // Case/whitespace tolerant, like the other string sanitizers.
        assertEquals("club-green", OverlayLayoutPrefs.sanitizeTheme("  Club-Green "))
    }

    @Test
    fun `sanitizeTheme falls back to floodlight for unknown null or blank ids`() {
        assertEquals("floodlight", OverlayLayoutPrefs.sanitizeTheme("comic-sans"))
        assertEquals("floodlight", OverlayLayoutPrefs.sanitizeTheme(null))
        assertEquals("floodlight", OverlayLayoutPrefs.sanitizeTheme("   "))
        // The legacy id is still explicitly valid — stored barlow boards keep their look.
        assertEquals("barlow", OverlayLayoutPrefs.sanitizeTheme("barlow"))
    }

    @Test
    fun `board preset catalogue matches the sanitizer and names the presets`() {
        // Every catalogued id must survive sanitization unchanged (single source of truth).
        for (preset in BoardPreset.ALL) {
            assertEquals(preset.id, OverlayLayoutPrefs.sanitizeTheme(preset.id))
        }
        assertEquals(
            listOf("Floodlight", "Chalk", "Club Green", "Broadcast Blue", "Mono", "Classic"),
            BoardPreset.ALL.map { it.displayName },
        )
        // Only the legacy Classic entry is flagged legacy, and it keeps the old wire id.
        val classic = BoardPreset.ALL.single { it.legacy }
        assertEquals("barlow", classic.id)
        assertEquals(BoardPreset.CLASSIC, classic)
    }

    @Test
    fun `board preset lookup resolves ids and defaults unknowns to floodlight`() {
        assertEquals(BoardPreset.CHALK, BoardPreset.byId("chalk"))
        assertEquals(BoardPreset.CLASSIC, BoardPreset.byId("barlow"))
        assertEquals(BoardPreset.FLOODLIGHT, BoardPreset.byId("comic-sans"))
        assertEquals(BoardPreset.FLOODLIGHT, BoardPreset.byId(null))
    }
}
