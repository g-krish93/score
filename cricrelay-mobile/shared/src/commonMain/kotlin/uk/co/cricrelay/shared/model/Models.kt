package uk.co.cricrelay.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import uk.co.cricrelay.shared.util.resolveAbsoluteUrl

@Serializable
data class BroadcastStatus(
    val status: String = "idle",
    val platform: String? = null,
    @SerialName("watch_url") val watchUrl: String? = null,
) {
    val isStreaming: Boolean get() = status == "streaming"
    val isPaused: Boolean get() = status == "paused"

    companion object {
        fun fromJson(json: JsonObject?): BroadcastStatus {
            if (json == null) return BroadcastStatus()
            return BroadcastStatus(
                status = json.string("status") ?: "idle",
                platform = json.string("platform"),
                watchUrl = json.string("watch_url"),
            )
        }
    }
}

@Serializable
data class StreamMatch(
    val slug: String,
    val label: String,
    @SerialName("overlay_embed_url") val overlayEmbedUrl: String = "",
    @SerialName("relay_source") val relaySource: String = "scraper",
    @SerialName("relay_paused") val relayPaused: Boolean = false,
    @SerialName("scoring_mode") val scoringMode: String = "manual",
    @SerialName("scoring_active") val scoringActive: Boolean = false,
    @SerialName("scoring_stale") val scoringStale: Boolean = false,
    @SerialName("is_live") val isLive: Boolean = false,
    val broadcast: BroadcastStatus = BroadcastStatus(),
) {
    val paused: Boolean get() = relayPaused

    companion object {
        fun fromJson(json: JsonObject, baseUrl: String): StreamMatch {
            var overlay = json.string("overlay_embed_url").orEmpty()
            if (overlay.startsWith("/")) {
                overlay = resolveAbsoluteUrl(baseUrl, overlay)
            }
            val broadcastRaw = json["broadcast"] as? JsonObject
            return StreamMatch(
                slug = json.string("slug").orEmpty(),
                label = json.string("label") ?: json.string("slug").orEmpty(),
                overlayEmbedUrl = overlay,
                relaySource = json.string("relay_source") ?: "scraper",
                relayPaused = json.bool("paused") == true || json.bool("relay_paused") == true,
                scoringMode = json.string("scoring_mode") ?: "manual",
                scoringActive = json.bool("scoring_active") == true,
                scoringStale = json.bool("scoring_stale") == true,
                isLive = json.bool("is_live") == true || json.bool("live") == true,
                broadcast = BroadcastStatus.fromJson(broadcastRaw),
            )
        }
    }
}

@Serializable
data class GoLiveResult(
    @SerialName("rtmp_url") val rtmpUrl: String = "",
    @SerialName("stream_key") val streamKey: String = "",
    @SerialName("watch_url") val watchUrl: String = "",
    @SerialName("overlay_embed_url") val overlayEmbedUrl: String = "",
) {
    companion object {
        fun fromJson(json: JsonObject): GoLiveResult = GoLiveResult(
            rtmpUrl = json.string("rtmp_url").orEmpty(),
            streamKey = json.string("stream_key").orEmpty(),
            watchUrl = json.string("watch_url").orEmpty(),
            overlayEmbedUrl = json.string("overlay_embed_url").orEmpty(),
        )
    }
}

@Serializable
data class FixtureItem(
    @SerialName("match_id") val matchId: String,
    val title: String,
) {
    companion object {
        fun fromJson(json: JsonObject): FixtureItem = FixtureItem(
            matchId = json.string("match_id").orEmpty(),
            title = json.string("title").orEmpty(),
        )
    }
}

data class FixturesResponse(
    val fixtures: List<FixtureItem>,
    val activeMatchIds: Set<String>,
    val error: String? = null,
    val slotsUsed: Int = 0,
    val slotsTotal: Int = 6,
) {
    companion object {
        fun fromJson(json: JsonObject): FixturesResponse {
            val rows = json.array("fixtures").mapNotNull { el ->
                (el as? JsonObject)?.let { FixtureItem.fromJson(it) }
            }
            val active = json.array("active_match_ids")
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .toSet()
            return FixturesResponse(
                fixtures = rows,
                activeMatchIds = active,
                error = json.string("error"),
                slotsUsed = json.string("slots_used")?.toIntOrNull() ?: 0,
                slotsTotal = json.string("slots_total")?.toIntOrNull() ?: 6,
            )
        }
    }
}

data class ScoringConfig(
    val mode: String,
    val manualInputUrl: String,
    val manualScorerUrl: String,
    val pcsIngestUrl: String,
    val pcsIngestToken: String,
    val pcsRelayApkUrl: String,
) {
    val scorerUrl: String
        get() = manualScorerUrl.ifEmpty {
            manualInputUrl.replace("/input", "/score")
        }

    companion object {
        fun localFallback(baseUrl: String, matchSlug: String, mode: String): ScoringConfig {
            val base = baseUrl.trimEnd('/')
            val slug = matchSlug.trim()
            return ScoringConfig(
                mode = mode,
                manualInputUrl = "$base/m/$slug/input",
                manualScorerUrl = "$base/m/$slug/score",
                pcsIngestUrl = "$base/relay/pcs-ingest?match=$slug",
                pcsIngestToken = "",
                pcsRelayApkUrl = "$base/download/pcs-relay.apk",
            )
        }

        fun fromJson(json: JsonObject, baseUrl: String): ScoringConfig {
            fun abs(path: String): String = resolveAbsoluteUrl(baseUrl, path)
            val inputUrl = abs(json.string("manual_input_url").orEmpty())
            val scorerUrl = abs(json.string("manual_scorer_url").orEmpty())
            return ScoringConfig(
                mode = json.string("mode") ?: "manual",
                manualInputUrl = inputUrl,
                manualScorerUrl = scorerUrl.ifEmpty { inputUrl.replace("/input", "/score") },
                pcsIngestUrl = abs(json.string("pcs_ingest_url").orEmpty()),
                pcsIngestToken = json.string("pcs_ingest_token").orEmpty(),
                pcsRelayApkUrl = abs(json.string("pcs_relay_apk_url").orEmpty()),
            )
        }
    }
}

data class MatchDayStatus(
    val slug: String,
    val label: String,
    val scoringMode: String,
    val scoringActive: Boolean,
    val scoringStale: Boolean,
    val relayPaused: Boolean,
    val broadcast: BroadcastStatus,
    val manualScorerUrl: String = "",
) {
    companion object {
        fun fromJson(json: JsonObject): MatchDayStatus {
            val broadcastRaw = json["broadcast"] as? JsonObject
            return MatchDayStatus(
                slug = json.string("slug").orEmpty(),
                label = json.string("label").orEmpty(),
                scoringMode = json.string("scoring_mode") ?: "manual",
                scoringActive = json.bool("scoring_active") == true,
                scoringStale = json.bool("scoring_stale") == true,
                relayPaused = json.bool("relay_paused") == true || json.bool("paused") == true,
                broadcast = BroadcastStatus.fromJson(broadcastRaw),
                manualScorerUrl = json.string("manual_scorer_url").orEmpty(),
            )
        }
    }
}

data class PlatformStatus(
    val connected: Boolean = false,
    val ready: Boolean = false,
    val label: String = "",
) {
    companion object {
        fun fromYoutube(json: JsonObject): PlatformStatus = PlatformStatus(
            connected = json.bool("connected") == true,
            ready = json.bool("ready") == true || json.bool("live_streaming_enabled") == true,
            label = json.string("channel_title").orEmpty(),
        )

        fun fromTwitch(json: JsonObject): PlatformStatus = PlatformStatus(
            connected = json.bool("connected") == true,
            ready = json.bool("ready") == true,
            label = json.string("display_name").orEmpty(),
        )
    }
}

data class Sponsor(
    val id: String = "",
    val name: String = "",
    val logoUrl: String? = null,
    val linkUrl: String? = null,
    val isActive: Boolean = true,
) {
    companion object {
        fun fromJson(json: JsonObject): Sponsor = Sponsor(
            id = json.string("id").orEmpty(),
            name = json.string("name").orEmpty(),
            logoUrl = json.string("logo_url"),
            linkUrl = json.string("link_url"),
            isActive = json.bool("is_active") != false,
        )
    }
}

data class RemoteCommand(
    val type: String = "",
    val command: String = "",
    val prefs: JsonObject? = null,
    val ts: Double = 0.0,
) {
    companion object {
        fun fromJson(json: JsonObject): RemoteCommand = RemoteCommand(
            type = json.string("type").orEmpty(),
            command = json.string("command").orEmpty(),
            prefs = json["prefs"] as? JsonObject,
            ts = (json["ts"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0,
        )
    }

    fun mergeSponsorInto(base: OverlayLayoutPrefs): OverlayLayoutPrefs? {
        val patch = prefs ?: return null
        return base.mergeSponsorPatch(patch)
    }
}

data class RemoteCompanionContext(
    val sponsorPrefs: OverlayLayoutPrefs,
    val sponsors: List<Sponsor>,
    val watchUrl: String = "",
) {
    companion object {
        fun fromJson(json: JsonObject): RemoteCompanionContext {
            val prefsObj = (json["sponsor_prefs"] as? JsonObject) ?: buildJsonObject { }
            val sponsors = json.array("sponsors").mapNotNull { el ->
                (el as? JsonObject)?.let { Sponsor.fromJson(it) }
            }
            return RemoteCompanionContext(
                sponsorPrefs = OverlayLayoutPrefs.fromJson(prefsObj),
                sponsors = sponsors,
                watchUrl = json.string("watch_url").orEmpty(),
            )
        }
    }
}

data class PairRemoteResult(
    val pairToken: String,
    val expiresAt: String,
)

object SponsorDisplayMode {
    const val STATIC = "static"
    const val SCROLL_TOP = "scroll_top"
    const val SCROLL_BOTTOM = "scroll_bottom"
    const val SCROLL_ABOVE_BOARD = "scroll_above_board"
    const val SCROLL_BELOW_BOARD = "scroll_below_board"

    val ALL = setOf(STATIC, SCROLL_TOP, SCROLL_BOTTOM, SCROLL_ABOVE_BOARD, SCROLL_BELOW_BOARD)

    fun sanitize(raw: String?): String {
        val m = raw?.trim()?.lowercase().orEmpty()
        return if (m in ALL) m else STATIC
    }

    fun isScroll(mode: String): Boolean = mode.startsWith("scroll")
}

/**
 * Direction the sponsor strip travels when [SponsorDisplayMode.isScroll]. `fixed` pins the
 * strip at its dragged position (no motion). Horizontal (`ltr`/`rtl`) and vertical
 * (`ttb`/`btt`) are driven natively on the GL layer (see StreamCameraEngine scroll animator).
 */
object SponsorScrollDirection {
    const val LTR = "ltr"
    const val RTL = "rtl"
    const val TTB = "ttb"
    const val BTT = "btt"
    const val FIXED = "fixed"

    val ALL = setOf(LTR, RTL, TTB, BTT, FIXED)

    fun sanitize(raw: String?): String {
        val d = raw?.trim()?.lowercase().orEmpty()
        return if (d in ALL) d else RTL
    }

    fun isHorizontal(dir: String): Boolean = sanitize(dir).let { it == LTR || it == RTL }
    fun isVertical(dir: String): Boolean = sanitize(dir).let { it == TTB || it == BTT }
}

/** How many sponsor logos to show: one pick, all at once, or rotating carousel. */
object SponsorLayoutMode {
    const val SINGLE = "single"
    const val MULTI = "multi"
    const val CAROUSEL = "carousel"

    val ALL = setOf(SINGLE, MULTI, CAROUSEL)

    fun sanitize(raw: String?): String {
        val m = raw?.trim()?.lowercase().orEmpty()
        return if (m in ALL) m else SINGLE
    }

    fun allowsMultiSelect(mode: String): Boolean =
        sanitize(mode) == MULTI || sanitize(mode) == CAROUSEL
}

/**
 * Video stabilization strength. `STANDARD` = EIS ON + OIS (today's behavior plus optical),
 * `CINEMATIC` = the strongest EIS grade (Camera2 PREVIEW_STABILIZATION / iOS cinematicExtended)
 * + OIS — smoother but narrows the field of view, so the operator opts in.
 */
object StabilizationLevel {
    const val OFF = 0
    const val STANDARD = 1
    const val CINEMATIC = 2

    fun sanitize(v: Int?): Int = (v ?: STANDARD).coerceIn(OFF, CINEMATIC)
}

@Serializable
data class OverlayLayoutPrefs(
    // Scoreboard is a thin horizontal strip; height is a constant, font size is the
    // configurable readability lever instead of board height.
    @SerialName("overlay_height_fraction") val heightFraction: Double = 0.16,
    @SerialName("overlay_width_fraction") val widthFraction: Double = 1.0,
    @SerialName("overlay_anchor_x") val anchorX: Double = 0.5,
    @SerialName("overlay_anchor_y") val anchorY: Double = 0.85,
    // 0 = board sits flush to the frame's bottom edge (operator can lift it by dragging in Arrange).
    @SerialName("overlay_bottom_margin") val bottomMargin: Double = 0.0,
    @SerialName("overlay_horizontal_inset") val horizontalInset: Double = 0.0,
    @SerialName("theme") val theme: String = "barlow",
    // Configurable scoreboard appearance (Board Edit sheet).
    @SerialName("overlay_font_scale") val fontScale: Double = 1.0,
    @SerialName("overlay_bg_color") val bgColor: String = "",
    @SerialName("overlay_text_color") val textColor: String = "",
    @SerialName("overlay_opacity") val opacity: Double = 1.0,
    // Wire-compat boolean for old clients/servers; [stabilizationLevel] is the source of truth.
    @SerialName("video_stabilization") val videoStabilization: Boolean = true,
    @SerialName("stabilization_level") val stabilizationLevel: Int = StabilizationLevel.STANDARD,
    @SerialName("keep_screen_on") val keepScreenOn: Boolean = true,
    // Master switch for the score bar. Off for book-scored matches with no data feed —
    // an empty scoreboard bar would just clutter the stream. Local-only pref (the server
    // ignores unknown overlay keys; the per-slug cache is authoritative).
    @SerialName("overlay_enabled") val overlayEnabled: Boolean = true,
    // Brand watermark burned into the stream; admin-configurable.
    @SerialName("watermark_enabled") val watermarkEnabled: Boolean = true,
    @SerialName("watermark_text") val watermarkText: String = WATERMARK_DEFAULT_TEXT,
    @SerialName("sponsor_enabled") val sponsorEnabled: Boolean = false,
    @SerialName("active_sponsor_id") val activeSponsorId: String? = null,
    @SerialName("active_sponsor_ids") val activeSponsorIds: List<String> = emptyList(),
    /** single | multi | carousel */
    @SerialName("sponsor_layout_mode") val sponsorLayoutMode: String = SponsorLayoutMode.SINGLE,
    @SerialName("sponsor_carousel_interval_sec") val sponsorCarouselIntervalSec: Double = 6.0,
    /** static | scroll_top | scroll_bottom | scroll_above_board | scroll_below_board */
    @SerialName("sponsor_display_mode") val sponsorDisplayMode: String = SponsorDisplayMode.STATIC,
    @SerialName("sponsor_position_x") val sponsorPositionX: Double = 0.92,
    @SerialName("sponsor_position_y") val sponsorPositionY: Double = 0.88,
    @SerialName("sponsor_size_scale") val sponsorSizeScale: Double = 1.0,
    @SerialName("sponsor_opacity") val sponsorOpacity: Double = 1.0,
    @SerialName("sponsor_scroll_speed") val sponsorScrollSpeed: Double = 1.0,
    /** ltr | rtl | ttb | btt | fixed — travel direction for scroll display modes. */
    @SerialName("sponsor_scroll_direction") val sponsorScrollDirection: String = SponsorScrollDirection.RTL,
) {
    /** Reference board size used when tuning default typography. */
    fun clampedWidthFraction(): Double = widthFraction.coerceIn(WIDTH_MIN, WIDTH_MAX)

    fun clampedHeightFraction(): Double = heightFraction.coerceIn(HEIGHT_MIN, HEIGHT_MAX)

    /** Display scale vs reference board size (width/height sliders scale the whole strip). */
    fun boardDisplayScaleX(): Float =
        (clampedWidthFraction() / REF_WIDTH_FRACTION).toFloat()

    fun boardDisplayScaleY(): Float =
        (clampedHeightFraction() / REF_HEIGHT_FRACTION).toFloat()

    /** User font slider only; board width/height scale the rendered bitmap, not typography. */
    fun effectiveFontScale(): Float =
        fontScale.toFloat().coerceIn(FONT_MIN.toFloat(), FONT_MAX.toFloat())

    /**
     * Uniform board size multiplier (1.0 ≈ default full-width lower-third). The board is a
     * fixed-aspect rasterized strip, so a single scale drives both dimensions and internal
     * proportions never distort — this is what the Arrange pinch gesture manipulates.
     */
    fun boardScale(): Double =
        (clampedWidthFraction() / REF_WIDTH_FRACTION).coerceIn(BOARD_SCALE_MIN, BOARD_SCALE_MAX)

    /** Return a copy scaled uniformly to [scale]; width and height move together (aspect-locked). */
    fun withBoardScale(scale: Double): OverlayLayoutPrefs {
        val s = scale.coerceIn(BOARD_SCALE_MIN, BOARD_SCALE_MAX)
        return copy(
            widthFraction = (REF_WIDTH_FRACTION * s).coerceIn(WIDTH_MIN, WIDTH_MAX),
            heightFraction = (REF_HEIGHT_FRACTION * s).coerceIn(HEIGHT_MIN, HEIGHT_MAX),
        )
    }

    /** Return a copy at [level], keeping the wire-compat boolean in sync. */
    fun withStabilizationLevel(level: Int): OverlayLayoutPrefs {
        val sanitized = StabilizationLevel.sanitize(level)
        return copy(
            stabilizationLevel = sanitized,
            videoStabilization = sanitized > StabilizationLevel.OFF,
        )
    }

    /** Return a copy re-anchored to a normalized preview point (centre of the board). */
    fun withAnchor(x: Double, y: Double): OverlayLayoutPrefs = copy(
        anchorX = x.coerceIn(0.0, 1.0),
        anchorY = y.coerceIn(ANCHOR_Y_MIN, ANCHOR_Y_MAX),
        // Dragging takes over vertical placement; the flush bottom margin no longer applies.
        bottomMargin = 0.0,
    )

    companion object {
        const val REF_WIDTH_FRACTION = 1.0
        const val REF_HEIGHT_FRACTION = 0.16
        const val WIDTH_MIN = 0.25
        const val WIDTH_MAX = 0.98
        const val HEIGHT_MIN = 0.10
        const val HEIGHT_MAX = 0.28
        const val FONT_MIN = 0.6
        const val FONT_MAX = 2.0
        // Uniform pinch-scale bounds. Max 1.0 = full-width; the strip can shrink to ~40%.
        const val BOARD_SCALE_MIN = 0.4
        const val BOARD_SCALE_MAX = 1.0
        // How far up the frame the board can be dragged (anchorY = fraction of frame height).
        const val ANCHOR_Y_MIN = 0.30
        const val ANCHOR_Y_MAX = 0.97
        const val WATERMARK_DEFAULT_TEXT = "Visit cricrelay.co.uk"

        private val validThemes = setOf("barlow")

        fun sanitizeTheme(raw: String?): String {
            val t = raw?.trim()?.lowercase().orEmpty()
            if (t in validThemes) return t
            return "barlow"
        }

        fun fromJson(json: JsonObject): OverlayLayoutPrefs {
            // Prefer the 3-level field; fall back to the legacy boolean from old writers.
            val stabilizationLevel = StabilizationLevel.sanitize(
                json.int("stabilization_level")
                    ?: if (json.bool("video_stabilization") != false) {
                        StabilizationLevel.STANDARD
                    } else {
                        StabilizationLevel.OFF
                    },
            )
            return OverlayLayoutPrefs(
                heightFraction = json.string("overlay_height_fraction")?.toDoubleOrNull() ?: 0.16,
                widthFraction = json.string("overlay_width_fraction")?.toDoubleOrNull() ?: 1.0,
                anchorX = json.string("overlay_anchor_x")?.toDoubleOrNull() ?: 0.5,
                anchorY = json.string("overlay_anchor_y")?.toDoubleOrNull() ?: 0.85,
                bottomMargin = json.string("overlay_bottom_margin")?.toDoubleOrNull() ?: 0.0,
                horizontalInset = json.string("overlay_horizontal_inset")?.toDoubleOrNull() ?: 0.0,
                theme = sanitizeTheme(json.string("theme") ?: json.string("overlay_theme")),
                fontScale = json.string("overlay_font_scale")?.toDoubleOrNull() ?: 1.0,
                bgColor = json.string("overlay_bg_color") ?: "",
                textColor = json.string("overlay_text_color") ?: "",
                opacity = json.string("overlay_opacity")?.toDoubleOrNull() ?: 1.0,
                videoStabilization = stabilizationLevel > StabilizationLevel.OFF,
                stabilizationLevel = stabilizationLevel,
                keepScreenOn = json.bool("keep_screen_on") != false,
                overlayEnabled = json.bool("overlay_enabled") != false,
                watermarkEnabled = json.bool("watermark_enabled") != false,
                watermarkText = json.string("watermark_text")?.takeIf { it.isNotBlank() }
                    ?: WATERMARK_DEFAULT_TEXT,
                sponsorEnabled = json.bool("sponsor_enabled") == true,
                activeSponsorId = json.string("active_sponsor_id")?.takeIf { it.isNotBlank() },
                activeSponsorIds = json.stringList("active_sponsor_ids").ifEmpty {
                    json.string("active_sponsor_id")?.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
                },
                sponsorLayoutMode = SponsorLayoutMode.sanitize(json.string("sponsor_layout_mode")),
                sponsorCarouselIntervalSec = json.double("sponsor_carousel_interval_sec")?.coerceIn(2.0, 30.0) ?: 6.0,
                sponsorDisplayMode = SponsorDisplayMode.sanitize(json.string("sponsor_display_mode")),
                sponsorPositionX = json.double("sponsor_position_x")?.coerceIn(0.0, 1.0) ?: 0.92,
                sponsorPositionY = json.double("sponsor_position_y")?.coerceIn(0.0, 1.0) ?: 0.88,
                sponsorSizeScale = json.double("sponsor_size_scale")?.coerceIn(0.3, 3.0) ?: 1.0,
                sponsorOpacity = json.double("sponsor_opacity")?.coerceIn(0.2, 1.0) ?: 1.0,
                sponsorScrollSpeed = json.double("sponsor_scroll_speed")?.coerceIn(0.3, 3.0) ?: 1.0,
                sponsorScrollDirection = SponsorScrollDirection.sanitize(json.string("sponsor_scroll_direction")),
            )
        }
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("overlay_height_fraction", heightFraction)
        put("overlay_width_fraction", widthFraction)
        put("overlay_anchor_x", anchorX)
        put("overlay_anchor_y", anchorY)
        put("overlay_bottom_margin", bottomMargin)
        put("overlay_horizontal_inset", horizontalInset)
        put("theme", theme)
        put("overlay_font_scale", fontScale)
        put("overlay_bg_color", bgColor)
        put("overlay_text_color", textColor)
        put("overlay_opacity", opacity)
        // Both stabilization fields derive from the level so old readers stay consistent.
        put("video_stabilization", stabilizationLevel > StabilizationLevel.OFF)
        put("stabilization_level", stabilizationLevel)
        put("keep_screen_on", keepScreenOn)
        put("overlay_enabled", overlayEnabled)
        put("watermark_enabled", watermarkEnabled)
        put("watermark_text", watermarkText)
        put("sponsor_enabled", sponsorEnabled)
        if (activeSponsorIds.isNotEmpty()) {
            put("active_sponsor_ids", JsonArray(activeSponsorIds.map { JsonPrimitive(it) }))
            activeSponsorIds.firstOrNull()?.let { put("active_sponsor_id", it) }
        } else {
            activeSponsorId?.let { put("active_sponsor_id", it) }
        }
        put("sponsor_layout_mode", sponsorLayoutMode)
        put("sponsor_carousel_interval_sec", sponsorCarouselIntervalSec)
        put("sponsor_display_mode", sponsorDisplayMode)
        put("sponsor_position_x", sponsorPositionX)
        put("sponsor_position_y", sponsorPositionY)
        put("sponsor_size_scale", sponsorSizeScale)
        put("sponsor_opacity", sponsorOpacity)
        put("sponsor_scroll_speed", sponsorScrollSpeed)
        put("sponsor_scroll_direction", sponsorScrollDirection)
    }

    /** Merge sponsor-only fields from a remote companion patch onto existing prefs. */
    fun mergeSponsorPatch(patch: JsonObject): OverlayLayoutPrefs {
        val base = toJson().toMutableMap()
        for (key in SPONSOR_PATCH_KEYS) {
            if (patch[key] != null) {
                base[key] = patch[key]!!
            }
        }
        return OverlayLayoutPrefs.fromJson(JsonObject(base))
    }

    fun sponsorPatchJson(): JsonObject = buildJsonObject {
        put("sponsor_enabled", sponsorEnabled)
        if (activeSponsorIds.isNotEmpty()) {
            put("active_sponsor_ids", JsonArray(activeSponsorIds.map { JsonPrimitive(it) }))
            activeSponsorIds.firstOrNull()?.let { put("active_sponsor_id", it) }
        } else {
            activeSponsorId?.let { put("active_sponsor_id", it) }
        }
        put("sponsor_layout_mode", sponsorLayoutMode)
        put("sponsor_carousel_interval_sec", sponsorCarouselIntervalSec)
        put("sponsor_display_mode", sponsorDisplayMode)
        put("sponsor_position_x", sponsorPositionX)
        put("sponsor_position_y", sponsorPositionY)
        put("sponsor_size_scale", sponsorSizeScale)
        put("sponsor_opacity", sponsorOpacity)
        put("sponsor_scroll_speed", sponsorScrollSpeed)
        put("sponsor_scroll_direction", sponsorScrollDirection)
    }

    fun effectiveSponsorIds(): List<String> = when {
        activeSponsorIds.isNotEmpty() -> activeSponsorIds
        !activeSponsorId.isNullOrBlank() -> listOf(activeSponsorId!!)
        else -> emptyList()
    }

    fun resolveSponsorLogoUrls(sponsors: List<Sponsor>): List<String> {
        if (!sponsorEnabled) return emptyList()
        val fromIds = effectiveSponsorIds().mapNotNull { id ->
            sponsors.find { it.id == id }?.logoUrl?.takeIf { it.isNotBlank() }
        }
        if (fromIds.isNotEmpty()) return fromIds.take(6)
        return when (SponsorLayoutMode.sanitize(sponsorLayoutMode)) {
            SponsorLayoutMode.SINGLE ->
                sponsors.firstOrNull { it.isActive }?.logoUrl?.takeIf { it.isNotBlank() }?.let { listOf(it) }
                    ?: emptyList()
            else ->
                sponsors.filter { it.isActive }.mapNotNull { it.logoUrl?.takeIf { u -> u.isNotBlank() } }.take(6)
        }
    }
}

private val SPONSOR_PATCH_KEYS = setOf(
    "sponsor_enabled",
    "active_sponsor_id",
    "active_sponsor_ids",
    "sponsor_layout_mode",
    "sponsor_carousel_interval_sec",
    "sponsor_display_mode",
    "sponsor_position_x",
    "sponsor_position_y",
    "sponsor_size_scale",
    "sponsor_opacity",
    "sponsor_scroll_speed",
    "sponsor_scroll_direction",
)

private fun JsonObject.stringList(key: String): List<String> =
    array(key).mapNotNull { el -> (el as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.double(key: String): Double? =
    (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

internal fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.content?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }

fun JsonObject.array(key: String): List<JsonElement> =
    this[key]?.let { el ->
        when (el) {
            is kotlinx.serialization.json.JsonArray -> el
            else -> emptyList()
        }
    } ?: emptyList()
