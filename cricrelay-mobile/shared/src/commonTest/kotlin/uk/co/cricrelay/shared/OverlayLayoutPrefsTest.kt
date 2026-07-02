package uk.co.cricrelay.shared

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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
}
