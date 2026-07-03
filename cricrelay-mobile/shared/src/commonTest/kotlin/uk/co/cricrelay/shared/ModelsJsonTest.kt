package uk.co.cricrelay.shared

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.cricrelay.shared.model.BroadcastStatus
import uk.co.cricrelay.shared.model.OverlayLayoutPrefs
import uk.co.cricrelay.shared.model.SponsorDisplayMode
import uk.co.cricrelay.shared.model.SponsorLayoutMode
import uk.co.cricrelay.shared.model.SponsorScrollDirection
import uk.co.cricrelay.shared.model.StabilizationLevel
import uk.co.cricrelay.shared.model.StreamMatch

/** JSON round-trips and wire-shape parsing for the models the studio persists and syncs. */
class ModelsJsonTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ── BroadcastStatus ─────────────────────────────────────────────────────

    @Test
    fun `broadcast status defaults to idle when json is missing`() {
        val status = BroadcastStatus.fromJson(null)
        assertEquals("idle", status.status)
        assertNull(status.platform)
        assertNull(status.watchUrl)
        assertFalse(status.isStreaming)
        assertFalse(status.isPaused)
    }

    @Test
    fun `broadcast status parses wire fields and state flags`() {
        val streaming = BroadcastStatus.fromJson(
            buildJsonObject {
                put("status", "streaming")
                put("platform", "youtube")
                put("watch_url", "https://youtu.be/x")
            },
        )
        assertTrue(streaming.isStreaming)
        assertFalse(streaming.isPaused)
        assertEquals("youtube", streaming.platform)
        assertEquals("https://youtu.be/x", streaming.watchUrl)

        val paused = BroadcastStatus.fromJson(buildJsonObject { put("status", "paused") })
        assertTrue(paused.isPaused)
        assertFalse(paused.isStreaming)
    }

    @Test
    fun `broadcast status kotlinx round-trip preserves all fields`() {
        val original = BroadcastStatus(status = "streaming", platform = "twitch", watchUrl = "https://t.tv/c")
        val decoded = json.decodeFromJsonElement<BroadcastStatus>(json.encodeToJsonElement(original))
        assertEquals(original, decoded)
    }

    // ── StreamMatch ─────────────────────────────────────────────────────────

    @Test
    fun `stream match parses a full server payload`() {
        val match = StreamMatch.fromJson(
            buildJsonObject {
                put("slug", "village-vs-town")
                put("label", "Village vs Town")
                put("overlay_embed_url", "/overlay/village-vs-town")
                put("relay_source", "play_cricket")
                put("relay_paused", true)
                put("scoring_mode", "auto")
                put("scoring_active", true)
                put("scoring_stale", true)
                put("is_live", true)
                putJsonObject("broadcast") {
                    put("status", "streaming")
                    put("platform", "youtube")
                }
            },
            baseUrl = "https://club.example.com",
        )

        assertEquals("village-vs-town", match.slug)
        assertEquals("Village vs Town", match.label)
        // Relative overlay URLs resolve against the club server base.
        assertEquals("https://club.example.com/overlay/village-vs-town", match.overlayEmbedUrl)
        assertEquals("play_cricket", match.relaySource)
        assertTrue(match.relayPaused)
        assertTrue(match.paused)
        assertEquals("auto", match.scoringMode)
        assertTrue(match.scoringActive)
        assertTrue(match.scoringStale)
        assertTrue(match.isLive)
        assertTrue(match.broadcast.isStreaming)
        assertEquals("youtube", match.broadcast.platform)
    }

    @Test
    fun `stream match accepts legacy field aliases`() {
        // Old servers write "paused" and "live" instead of relay_paused / is_live.
        val match = StreamMatch.fromJson(
            buildJsonObject {
                put("slug", "s")
                put("paused", true)
                put("live", true)
            },
            baseUrl = "https://club.example.com",
        )
        assertTrue(match.relayPaused)
        assertTrue(match.isLive)
        // Label falls back to the slug when absent.
        assertEquals("s", match.label)
    }

    @Test
    fun `stream match leaves absolute overlay urls untouched and defaults the rest`() {
        val match = StreamMatch.fromJson(
            buildJsonObject {
                put("slug", "s")
                put("overlay_embed_url", "https://cdn.example.com/overlay")
            },
            baseUrl = "https://club.example.com",
        )
        assertEquals("https://cdn.example.com/overlay", match.overlayEmbedUrl)
        assertEquals("scraper", match.relaySource)
        assertEquals("manual", match.scoringMode)
        assertFalse(match.relayPaused)
        assertFalse(match.isLive)
        assertEquals("idle", match.broadcast.status)
    }

    @Test
    fun `stream match kotlinx round-trip preserves all fields`() {
        val original = StreamMatch(
            slug = "village-vs-town",
            label = "Village vs Town",
            overlayEmbedUrl = "https://club.example.com/overlay/village-vs-town",
            relaySource = "play_cricket",
            relayPaused = true,
            scoringMode = "auto",
            scoringActive = true,
            scoringStale = true,
            isLive = true,
            broadcast = BroadcastStatus("streaming", "youtube", "https://youtu.be/x"),
        )
        val decoded = json.decodeFromJsonElement<StreamMatch>(json.encodeToJsonElement(original))
        assertEquals(original, decoded)
    }

    // ── OverlayLayoutPrefs ──────────────────────────────────────────────────

    /** Every field pushed off its default so a dropped key in either direction fails the test. */
    private fun nonDefaultPrefs() = OverlayLayoutPrefs(
        heightFraction = 0.2,
        widthFraction = 0.8,
        anchorX = 0.25,
        anchorY = 0.6,
        bottomMargin = 120.0,
        horizontalInset = 30.0,
        theme = "barlow",
        fontScale = 1.4,
        bgColor = "#102030",
        textColor = "#F0F0F0",
        opacity = 0.9,
        videoStabilization = true,
        stabilizationLevel = StabilizationLevel.CINEMATIC,
        keepScreenOn = false,
        watermarkEnabled = false,
        watermarkText = "Sunday League Live",
        sponsorEnabled = true,
        activeSponsorId = "sp-1",
        activeSponsorIds = listOf("sp-1", "sp-2"),
        sponsorLayoutMode = SponsorLayoutMode.CAROUSEL,
        sponsorCarouselIntervalSec = 9.0,
        sponsorDisplayMode = SponsorDisplayMode.SCROLL_BOTTOM,
        sponsorPositionX = 0.1,
        sponsorPositionY = 0.2,
        sponsorSizeScale = 1.5,
        sponsorOpacity = 0.7,
        sponsorScrollSpeed = 2.0,
        sponsorScrollDirection = SponsorScrollDirection.LTR,
    )

    @Test
    fun `overlay prefs toJson fromJson round-trip preserves every field`() {
        val original = nonDefaultPrefs()
        val decoded = OverlayLayoutPrefs.fromJson(original.toJson())
        assertEquals(original, decoded)
    }

    @Test
    fun `overlay prefs kotlinx round-trip matches the custom wire names`() {
        val original = nonDefaultPrefs()
        val element = json.encodeToJsonElement(original)
        // The @SerialName wire keys must line up with the hand-rolled toJson keys.
        val decodedViaCustom = OverlayLayoutPrefs.fromJson(
            json.decodeFromJsonElement<kotlinx.serialization.json.JsonObject>(element),
        )
        assertEquals(original, decodedViaCustom)
    }

    @Test
    fun `single active sponsor id is promoted into the id list`() {
        val prefs = OverlayLayoutPrefs.fromJson(
            buildJsonObject {
                put("sponsor_enabled", true)
                put("active_sponsor_id", "sp-7")
            },
        )
        assertEquals(listOf("sp-7"), prefs.activeSponsorIds)
        assertEquals(listOf("sp-7"), prefs.effectiveSponsorIds())
    }

    @Test
    fun `fromJson clamps out-of-range sponsor values and sanitizes modes`() {
        val prefs = OverlayLayoutPrefs.fromJson(
            buildJsonObject {
                put("sponsor_carousel_interval_sec", 500.0)
                put("sponsor_position_x", 4.2)
                put("sponsor_position_y", -1.0)
                put("sponsor_size_scale", 99.0)
                put("sponsor_opacity", 0.01)
                put("sponsor_scroll_speed", 100.0)
                put("sponsor_display_mode", "sideways")
                put("sponsor_layout_mode", "mosaic")
                put("sponsor_scroll_direction", "diagonal")
                put("theme", "comic-sans")
            },
        )
        assertEquals(30.0, prefs.sponsorCarouselIntervalSec, 0.0)
        assertEquals(1.0, prefs.sponsorPositionX, 0.0)
        assertEquals(0.0, prefs.sponsorPositionY, 0.0)
        assertEquals(3.0, prefs.sponsorSizeScale, 0.0)
        assertEquals(0.2, prefs.sponsorOpacity, 0.0)
        assertEquals(3.0, prefs.sponsorScrollSpeed, 0.0)
        assertEquals(SponsorDisplayMode.STATIC, prefs.sponsorDisplayMode)
        assertEquals(SponsorLayoutMode.SINGLE, prefs.sponsorLayoutMode)
        assertEquals(SponsorScrollDirection.RTL, prefs.sponsorScrollDirection)
        assertEquals("barlow", prefs.theme)
    }

    @Test
    fun `blank watermark text falls back to the default`() {
        val prefs = OverlayLayoutPrefs.fromJson(buildJsonObject { put("watermark_text", "  ") })
        assertEquals(OverlayLayoutPrefs.WATERMARK_DEFAULT_TEXT, prefs.watermarkText)
    }

    @Test
    fun `sponsor patch merge only touches sponsor fields`() {
        val base = nonDefaultPrefs()
        val patch = buildJsonObject {
            put("sponsor_enabled", false)
            put("sponsor_position_x", 0.5)
            putJsonArray("active_sponsor_ids") { add("sp-9") }
            // Board fields in the patch must be ignored by the sponsor merge.
            put("overlay_font_scale", 0.6)
            put("overlay_anchor_y", 0.33)
        }

        val merged = base.mergeSponsorPatch(patch)

        assertFalse(merged.sponsorEnabled)
        assertEquals(0.5, merged.sponsorPositionX, 0.0)
        assertEquals(listOf("sp-9"), merged.activeSponsorIds)
        // Non-sponsor fields survive untouched.
        assertEquals(base.fontScale, merged.fontScale, 0.0)
        assertEquals(base.anchorY, merged.anchorY, 0.0)
        assertEquals(base.watermarkText, merged.watermarkText)
    }

    @Test
    fun `sponsor patch json carries only sponsor keys`() {
        val patch = nonDefaultPrefs().sponsorPatchJson()
        assertTrue(patch.containsKey("sponsor_enabled"))
        assertTrue(patch.containsKey("active_sponsor_ids"))
        assertFalse(patch.containsKey("overlay_font_scale"))
        assertFalse(patch.containsKey("watermark_text"))
        assertFalse(patch.containsKey("stabilization_level"))
    }
}
