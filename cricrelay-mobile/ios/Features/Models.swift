import Foundation

struct BroadcastStatus: Codable {
    var status: String
    var platform: String?
    var watchUrl: String?

    var isStreaming: Bool { status == "streaming" }
    var isPaused: Bool { status == "paused" }

    enum CodingKeys: String, CodingKey {
        case status, platform
        case watchUrl = "watch_url"
    }
}

struct StreamMatch: Identifiable, Codable {
    var slug: String
    var label: String
    var overlayEmbedUrl: String
    var relaySource: String
    var relayPaused: Bool
    var scoringMode: String
    var scoringActive: Bool
    var scoringStale: Bool
    var isLive: Bool
    var broadcast: BroadcastStatus

    var id: String { slug }

    enum CodingKeys: String, CodingKey {
        case slug, label
        case overlayEmbedUrl = "overlay_embed_url"
        case relaySource = "relay_source"
        case relayPaused = "relay_paused"
        case scoringMode = "scoring_mode"
        case scoringActive = "scoring_active"
        case scoringStale = "scoring_stale"
        case isLive = "is_live"
        case broadcast
    }
}

struct GoLiveResult: Codable {
    var rtmpUrl: String
    var streamKey: String
    var watchUrl: String
    var overlayEmbedUrl: String

    enum CodingKeys: String, CodingKey {
        case rtmpUrl = "rtmp_url"
        case streamKey = "stream_key"
        case watchUrl = "watch_url"
        case overlayEmbedUrl = "overlay_embed_url"
    }
}

struct FixtureItem: Identifiable, Codable {
    var matchId: String
    var title: String

    var id: String { matchId }

    enum CodingKeys: String, CodingKey {
        case matchId = "match_id"
        case title
    }
}

struct FixturesResponse: Codable {
    var fixtures: [FixtureItem]
    var activeMatchIds: [String]
    var error: String?
    var slotsUsed: Int
    var slotsTotal: Int

    enum CodingKeys: String, CodingKey {
        case fixtures
        case activeMatchIds = "active_match_ids"
        case error
        case slotsUsed = "slots_used"
        case slotsTotal = "slots_total"
    }
}

struct ScoringConfig: Codable {
    var mode: String
    var manualInputUrl: String
    var manualScorerUrl: String
    var pcsIngestUrl: String
    var pcsIngestToken: String
    var pcsRelayApkUrl: String

    var scorerUrl: String {
        manualScorerUrl.isEmpty
            ? manualInputUrl.replacingOccurrences(of: "/input", with: "/score")
            : manualScorerUrl
    }

    enum CodingKeys: String, CodingKey {
        case mode
        case manualInputUrl = "manual_input_url"
        case manualScorerUrl = "manual_scorer_url"
        case pcsIngestUrl = "pcs_ingest_url"
        case pcsIngestToken = "pcs_ingest_token"
        case pcsRelayApkUrl = "pcs_relay_apk_url"
    }
}

struct PlatformStatus: Codable {
    var connected: Bool
    var ready: Bool
    var label: String

    init(connected: Bool = false, ready: Bool = false, label: String = "") {
        self.connected = connected
        self.ready = ready
        self.label = label
    }
}

struct OverlayLayoutPrefs: Codable {
    var heightFraction: Double
    var widthFraction: Double
    var anchorX: Double
    var anchorY: Double
    var bottomMargin: Double
    var horizontalInset: Double
    var theme: String
    var fontScale: Double
    var bgColor: String
    var textColor: String
    var opacity: Double
    var videoStabilization: Bool
    var keepScreenOn: Bool
    // Brand watermark burned into the stream; admin-configurable.
    var watermarkEnabled: Bool
    var watermarkText: String

    static let watermarkDefaultText = "Visit cricrelay.co.uk"

    init() {
        heightFraction = 0.16
        widthFraction = 1.0
        anchorX = 0.5
        anchorY = 0.85
        bottomMargin = 8.0
        horizontalInset = 0.0
        theme = "classic"
        fontScale = 1.0
        bgColor = ""
        textColor = ""
        opacity = 1.0
        videoStabilization = true
        keepScreenOn = true
        watermarkEnabled = true
        watermarkText = OverlayLayoutPrefs.watermarkDefaultText
    }

    enum CodingKeys: String, CodingKey {
        case heightFraction = "height_fraction"
        case widthFraction = "width_fraction"
        case anchorX = "anchor_x"
        case anchorY = "anchor_y"
        case bottomMargin = "bottom_margin"
        case horizontalInset = "horizontal_inset"
        case theme
        case fontScale = "font_scale"
        case bgColor = "bg_color"
        case textColor = "text_color"
        case opacity
        case videoStabilization = "video_stabilization"
        case keepScreenOn = "keep_screen_on"
        case watermarkEnabled = "watermark_enabled"
        case watermarkText = "watermark_text"
    }

    /// Tolerant decoder: any missing key falls back to its default so an older server
    /// (which may omit the watermark fields) never wipes the whole prefs object.
    init(from decoder: Decoder) throws {
        self.init()
        let c = try decoder.container(keyedBy: CodingKeys.self)
        heightFraction = try c.decodeIfPresent(Double.self, forKey: .heightFraction) ?? heightFraction
        widthFraction = try c.decodeIfPresent(Double.self, forKey: .widthFraction) ?? widthFraction
        anchorX = try c.decodeIfPresent(Double.self, forKey: .anchorX) ?? anchorX
        anchorY = try c.decodeIfPresent(Double.self, forKey: .anchorY) ?? anchorY
        bottomMargin = try c.decodeIfPresent(Double.self, forKey: .bottomMargin) ?? bottomMargin
        horizontalInset = try c.decodeIfPresent(Double.self, forKey: .horizontalInset) ?? horizontalInset
        theme = try c.decodeIfPresent(String.self, forKey: .theme) ?? theme
        fontScale = try c.decodeIfPresent(Double.self, forKey: .fontScale) ?? fontScale
        bgColor = try c.decodeIfPresent(String.self, forKey: .bgColor) ?? bgColor
        textColor = try c.decodeIfPresent(String.self, forKey: .textColor) ?? textColor
        opacity = try c.decodeIfPresent(Double.self, forKey: .opacity) ?? opacity
        videoStabilization = try c.decodeIfPresent(Bool.self, forKey: .videoStabilization) ?? videoStabilization
        keepScreenOn = try c.decodeIfPresent(Bool.self, forKey: .keepScreenOn) ?? keepScreenOn
        watermarkEnabled = try c.decodeIfPresent(Bool.self, forKey: .watermarkEnabled) ?? watermarkEnabled
        let wm = try c.decodeIfPresent(String.self, forKey: .watermarkText) ?? watermarkText
        watermarkText = wm.isEmpty ? OverlayLayoutPrefs.watermarkDefaultText : wm
    }

    func toEngineLayout() -> StreamCameraEngine.OverlayLayout {
        StreamCameraEngine.OverlayLayout(
            heightFraction: Float(heightFraction),
            widthFraction: Float(widthFraction),
            anchorX: Float(anchorX),
            anchorY: Float(anchorY),
            bottomMarginFraction: Float(bottomMargin / 400),
            horizontalInsetFraction: Float(horizontalInset / 400),
            fontScale: Float(fontScale),
            bgColor: bgColor,
            textColor: textColor,
            opacity: Float(max(0.2, min(1.0, opacity))),
            watermarkEnabled: watermarkEnabled,
            watermarkText: watermarkText
        )
    }
}

struct MatchDayStatus: Codable {
    var slug: String
    var label: String
    var scoringMode: String
    var scoringActive: Bool
    var scoringStale: Bool
    var relayPaused: Bool
    var broadcast: BroadcastStatus
    var manualScorerUrl: String

    enum CodingKeys: String, CodingKey {
        case slug, label
        case scoringMode = "scoring_mode"
        case scoringActive = "scoring_active"
        case scoringStale = "scoring_stale"
        case relayPaused = "relay_paused"
        case broadcast
        case manualScorerUrl = "manual_scorer_url"
    }
}

struct StreamRecap {
    let title: String
    let destinationLabel: String
    let durationSeconds: Int
    let watchUrl: String

    /// `m:ss` for a sub-hour broadcast, `h:mm:ss` once it crosses an hour.
    var durationText: String {
        let h = durationSeconds / 3600
        let m = (durationSeconds % 3600) / 60
        let s = durationSeconds % 60
        return h > 0
            ? String(format: "%d:%02d:%02d", h, m, s)
            : String(format: "%d:%02d", m, s)
    }
}
