package uk.co.cricrelay.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
    val ts: Double = 0.0,
) {
    companion object {
        fun fromJson(json: JsonObject): RemoteCommand = RemoteCommand(
            type = json.string("type").orEmpty(),
            command = json.string("command").orEmpty(),
            ts = (json["ts"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0,
        )
    }
}

data class PairRemoteResult(
    val pairToken: String,
    val expiresAt: String,
)

@Serializable
data class OverlayLayoutPrefs(
    // Scoreboard is a thin horizontal strip; height is a constant, font size is the
    // configurable readability lever instead of board height.
    @SerialName("overlay_height_fraction") val heightFraction: Double = 0.16,
    @SerialName("overlay_width_fraction") val widthFraction: Double = 1.0,
    @SerialName("overlay_anchor_x") val anchorX: Double = 0.5,
    @SerialName("overlay_anchor_y") val anchorY: Double = 0.85,
    @SerialName("overlay_bottom_margin") val bottomMargin: Double = 8.0,
    @SerialName("overlay_horizontal_inset") val horizontalInset: Double = 0.0,
    @SerialName("theme") val theme: String = "classic",
    // Configurable scoreboard appearance (Board Edit sheet).
    @SerialName("overlay_font_scale") val fontScale: Double = 1.0,
    @SerialName("overlay_bg_color") val bgColor: String = "",
    @SerialName("overlay_text_color") val textColor: String = "",
    @SerialName("overlay_opacity") val opacity: Double = 1.0,
    @SerialName("video_stabilization") val videoStabilization: Boolean = true,
    @SerialName("keep_screen_on") val keepScreenOn: Boolean = true,
    // Brand watermark burned into the stream; admin-configurable.
    @SerialName("watermark_enabled") val watermarkEnabled: Boolean = true,
    @SerialName("watermark_text") val watermarkText: String = WATERMARK_DEFAULT_TEXT,
    @SerialName("sponsor_enabled") val sponsorEnabled: Boolean = false,
    @SerialName("active_sponsor_id") val activeSponsorId: String? = null,
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

    companion object {
        const val REF_WIDTH_FRACTION = 1.0
        const val REF_HEIGHT_FRACTION = 0.16
        const val WIDTH_MIN = 0.25
        const val WIDTH_MAX = 0.98
        const val HEIGHT_MIN = 0.10
        const val HEIGHT_MAX = 0.28
        const val FONT_MIN = 0.6
        const val FONT_MAX = 2.0
        const val WATERMARK_DEFAULT_TEXT = "Visit cricrelay.co.uk"

        private val validThemes = setOf("classic", "neon", "minimal", "compact", "ai", "stadium")

        private fun sanitizeTheme(raw: String?): String {
            val t = raw?.trim()?.lowercase().orEmpty()
            if (t in validThemes) return t
            // Legacy mobile default before theme picker.
            if (t == "dark") return "classic"
            return "classic"
        }

        fun fromJson(json: JsonObject): OverlayLayoutPrefs = OverlayLayoutPrefs(
            heightFraction = json.string("overlay_height_fraction")?.toDoubleOrNull() ?: 0.16,
            widthFraction = json.string("overlay_width_fraction")?.toDoubleOrNull() ?: 1.0,
            anchorX = json.string("overlay_anchor_x")?.toDoubleOrNull() ?: 0.5,
            anchorY = json.string("overlay_anchor_y")?.toDoubleOrNull() ?: 0.85,
            bottomMargin = json.string("overlay_bottom_margin")?.toDoubleOrNull() ?: 8.0,
            horizontalInset = json.string("overlay_horizontal_inset")?.toDoubleOrNull() ?: 0.0,
            theme = sanitizeTheme(json.string("theme") ?: json.string("overlay_theme")),
            fontScale = json.string("overlay_font_scale")?.toDoubleOrNull() ?: 1.0,
            bgColor = json.string("overlay_bg_color") ?: "",
            textColor = json.string("overlay_text_color") ?: "",
            opacity = json.string("overlay_opacity")?.toDoubleOrNull() ?: 1.0,
            videoStabilization = json.bool("video_stabilization") != false,
            keepScreenOn = json.bool("keep_screen_on") != false,
            watermarkEnabled = json.bool("watermark_enabled") != false,
            watermarkText = json.string("watermark_text")?.takeIf { it.isNotBlank() }
                ?: WATERMARK_DEFAULT_TEXT,
            sponsorEnabled = json.bool("sponsor_enabled") == true,
            activeSponsorId = json.string("active_sponsor_id")?.takeIf { it.isNotBlank() },
        )
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
        put("video_stabilization", videoStabilization)
        put("keep_screen_on", keepScreenOn)
        put("watermark_enabled", watermarkEnabled)
        put("watermark_text", watermarkText)
        put("sponsor_enabled", sponsorEnabled)
        activeSponsorId?.let { put("active_sponsor_id", it) }
    }
}

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

fun JsonObject.array(key: String): List<JsonElement> =
    this[key]?.let { el ->
        when (el) {
            is kotlinx.serialization.json.JsonArray -> el
            else -> emptyList()
        }
    } ?: emptyList()
