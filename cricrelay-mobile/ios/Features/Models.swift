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

struct Sponsor: Identifiable, Codable {
    var id: String
    var name: String
    var logoUrl: String?
    var linkUrl: String?
    var isActive: Bool

    enum CodingKeys: String, CodingKey {
        case id, name
        case logoUrl = "logo_url"
        case linkUrl = "link_url"
        case isActive = "is_active"
    }
}

struct PairRemoteResult: Codable {
    var pairToken: String
    var expiresAt: String?

    enum CodingKeys: String, CodingKey {
        case pairToken = "pair_token"
        case expiresAt = "expires_at"
    }
}

struct CompanionSession: Codable {
    var companionToken: String
    var matchSlug: String

    enum CodingKeys: String, CodingKey {
        case companionToken = "companion_token"
        case matchSlug = "match_slug"
    }
}

struct RemoteCommand {
    var type: String
    var command: String
    var ts: Double?
    var prefs: [String: Any]?

    static func from(_ dict: [String: Any]) -> RemoteCommand {
        RemoteCommand(
            type: dict["type"] as? String ?? "",
            command: dict["command"] as? String ?? "",
            ts: dict["ts"] as? Double,
            prefs: dict["prefs"] as? [String: Any]
        )
    }
}

struct RemoteCompanionContext {
    var sponsorPrefs: OverlayLayoutPrefs
    var sponsors: [Sponsor]
    var watchUrl: String
}

/// Video stabilization strength (parity with shared StabilizationLevel).
/// `standard` = today's behavior; `cinematic` = `.cinematicExtended`, which narrows the
/// field of view most, so the operator opts in.
enum StabilizationLevel: Int, Codable {
    case off = 0
    case standard = 1
    case cinematic = 2

    static func sanitize(_ v: Int?) -> Int {
        min(max(v ?? StabilizationLevel.standard.rawValue, 0), 2)
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
    /// Master switch for the score bar. Off for book-scored matches with no data feed —
    /// an empty scoreboard bar would just clutter the stream. Local-only pref (the server
    /// ignores unknown overlay keys; the per-slug cache is authoritative).
    var overlayEnabled: Bool
    /// Wire-compat boolean for old clients/servers; `stabilizationLevel` is the source of truth.
    var videoStabilization: Bool
    var stabilizationLevel: Int
    var keepScreenOn: Bool
    // Brand watermark burned into the stream; admin-configurable.
    var watermarkEnabled: Bool
    var watermarkText: String
    var sponsorEnabled: Bool
    var activeSponsorId: String?
    var activeSponsorIds: [String]
    var sponsorLayoutMode: String
    var sponsorCarouselIntervalSec: Double
    var sponsorDisplayMode: String
    var sponsorPositionX: Double
    var sponsorPositionY: Double
    var sponsorSizeScale: Double
    var sponsorOpacity: Double
    var sponsorScrollSpeed: Double
    var sponsorScrollDirection: String

    static let watermarkDefaultText = "Visit cricrelay.co.uk"

    init() {
        heightFraction = 0.16
        widthFraction = 1.0
        anchorX = 0.5
        anchorY = 0.85
        // 0 = board sits flush to the frame's bottom edge (operator can lift it in Arrange).
        bottomMargin = 0.0
        horizontalInset = 0.0
        theme = "barlow"
        fontScale = 1.0
        bgColor = ""
        textColor = ""
        opacity = 1.0
        overlayEnabled = true
        videoStabilization = true
        stabilizationLevel = StabilizationLevel.standard.rawValue
        keepScreenOn = true
        watermarkEnabled = true
        watermarkText = OverlayLayoutPrefs.watermarkDefaultText
        sponsorEnabled = false
        activeSponsorId = nil
        activeSponsorIds = []
        sponsorLayoutMode = SponsorLayoutMode.single
        sponsorCarouselIntervalSec = 6.0
        sponsorDisplayMode = SponsorDisplayMode.staticMode
        sponsorPositionX = 0.92
        sponsorPositionY = 0.88
        sponsorSizeScale = 1.0
        sponsorOpacity = 1.0
        sponsorScrollSpeed = 1.0
        sponsorScrollDirection = SponsorScrollDirection.rtl
    }

    // Board-geometry keys carry the overlay_ prefix on the wire — the server's
    // OVERLAY_LAYOUT_STATE_KEYS and the shared KMP model both use these names.
    enum CodingKeys: String, CodingKey {
        case heightFraction = "overlay_height_fraction"
        case widthFraction = "overlay_width_fraction"
        case anchorX = "overlay_anchor_x"
        case anchorY = "overlay_anchor_y"
        case bottomMargin = "overlay_bottom_margin"
        case horizontalInset = "overlay_horizontal_inset"
        case theme
        case fontScale = "overlay_font_scale"
        case bgColor = "overlay_bg_color"
        case textColor = "overlay_text_color"
        case opacity = "overlay_opacity"
        case overlayEnabled = "overlay_enabled"
        case videoStabilization = "video_stabilization"
        case stabilizationLevel = "stabilization_level"
        case keepScreenOn = "keep_screen_on"
        case watermarkEnabled = "watermark_enabled"
        case watermarkText = "watermark_text"
        case sponsorEnabled = "sponsor_enabled"
        case activeSponsorId = "active_sponsor_id"
        case activeSponsorIds = "active_sponsor_ids"
        case sponsorLayoutMode = "sponsor_layout_mode"
        case sponsorCarouselIntervalSec = "sponsor_carousel_interval_sec"
        case sponsorDisplayMode = "sponsor_display_mode"
        case sponsorPositionX = "sponsor_position_x"
        case sponsorPositionY = "sponsor_position_y"
        case sponsorSizeScale = "sponsor_size_scale"
        case sponsorOpacity = "sponsor_opacity"
        case sponsorScrollSpeed = "sponsor_scroll_speed"
        case sponsorScrollDirection = "sponsor_scroll_direction"
    }

    /// Board keys as older iOS builds wrote them into the local overlay_prefs cache
    /// (unprefixed). Read-only fallback so an existing device doesn't lose its arrangement.
    private enum LegacyBoardKeys: String, CodingKey {
        case heightFraction = "height_fraction"
        case widthFraction = "width_fraction"
        case anchorX = "anchor_x"
        case anchorY = "anchor_y"
        case bottomMargin = "bottom_margin"
        case horizontalInset = "horizontal_inset"
        case fontScale = "font_scale"
        case bgColor = "bg_color"
        case textColor = "text_color"
        case opacity
    }

    /// Tolerant decoder: any missing key falls back to its default so an older server
    /// (which may omit the watermark fields) never wipes the whole prefs object.
    init(from decoder: Decoder) throws {
        self.init()
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let legacy = try decoder.container(keyedBy: LegacyBoardKeys.self)
        heightFraction = try c.decodeIfPresent(Double.self, forKey: .heightFraction)
            ?? legacy.decodeIfPresent(Double.self, forKey: .heightFraction) ?? heightFraction
        widthFraction = try c.decodeIfPresent(Double.self, forKey: .widthFraction)
            ?? legacy.decodeIfPresent(Double.self, forKey: .widthFraction) ?? widthFraction
        anchorX = try c.decodeIfPresent(Double.self, forKey: .anchorX)
            ?? legacy.decodeIfPresent(Double.self, forKey: .anchorX) ?? anchorX
        anchorY = try c.decodeIfPresent(Double.self, forKey: .anchorY)
            ?? legacy.decodeIfPresent(Double.self, forKey: .anchorY) ?? anchorY
        bottomMargin = try c.decodeIfPresent(Double.self, forKey: .bottomMargin)
            ?? legacy.decodeIfPresent(Double.self, forKey: .bottomMargin) ?? bottomMargin
        horizontalInset = try c.decodeIfPresent(Double.self, forKey: .horizontalInset)
            ?? legacy.decodeIfPresent(Double.self, forKey: .horizontalInset) ?? horizontalInset
        theme = try c.decodeIfPresent(String.self, forKey: .theme) ?? theme
        fontScale = try c.decodeIfPresent(Double.self, forKey: .fontScale)
            ?? legacy.decodeIfPresent(Double.self, forKey: .fontScale) ?? fontScale
        bgColor = try c.decodeIfPresent(String.self, forKey: .bgColor)
            ?? legacy.decodeIfPresent(String.self, forKey: .bgColor) ?? bgColor
        textColor = try c.decodeIfPresent(String.self, forKey: .textColor)
            ?? legacy.decodeIfPresent(String.self, forKey: .textColor) ?? textColor
        opacity = try c.decodeIfPresent(Double.self, forKey: .opacity)
            ?? legacy.decodeIfPresent(Double.self, forKey: .opacity) ?? opacity
        overlayEnabled = try c.decodeIfPresent(Bool.self, forKey: .overlayEnabled) ?? overlayEnabled
        // Prefer the 3-level field; fall back to the legacy boolean from old writers.
        if let level = try c.decodeIfPresent(Int.self, forKey: .stabilizationLevel) {
            stabilizationLevel = StabilizationLevel.sanitize(level)
        } else if let on = try c.decodeIfPresent(Bool.self, forKey: .videoStabilization) {
            stabilizationLevel = on
                ? StabilizationLevel.standard.rawValue
                : StabilizationLevel.off.rawValue
        }
        videoStabilization = stabilizationLevel > StabilizationLevel.off.rawValue
        keepScreenOn = try c.decodeIfPresent(Bool.self, forKey: .keepScreenOn) ?? keepScreenOn
        watermarkEnabled = try c.decodeIfPresent(Bool.self, forKey: .watermarkEnabled) ?? watermarkEnabled
        let wm = try c.decodeIfPresent(String.self, forKey: .watermarkText) ?? watermarkText
        watermarkText = wm.isEmpty ? OverlayLayoutPrefs.watermarkDefaultText : wm
        sponsorEnabled = try c.decodeIfPresent(Bool.self, forKey: .sponsorEnabled) ?? sponsorEnabled
        activeSponsorId = try c.decodeIfPresent(String.self, forKey: .activeSponsorId)
        activeSponsorIds = try c.decodeIfPresent([String].self, forKey: .activeSponsorIds)
            ?? activeSponsorId.map { [$0] } ?? []
        sponsorLayoutMode = SponsorLayoutMode.sanitize(
            try c.decodeIfPresent(String.self, forKey: .sponsorLayoutMode)
        )
        sponsorCarouselIntervalSec = try c.decodeIfPresent(Double.self, forKey: .sponsorCarouselIntervalSec)
            ?? sponsorCarouselIntervalSec
        sponsorDisplayMode = SponsorDisplayMode.sanitize(
            try c.decodeIfPresent(String.self, forKey: .sponsorDisplayMode)
        )
        sponsorPositionX = try c.decodeIfPresent(Double.self, forKey: .sponsorPositionX) ?? sponsorPositionX
        sponsorPositionY = try c.decodeIfPresent(Double.self, forKey: .sponsorPositionY) ?? sponsorPositionY
        sponsorSizeScale = try c.decodeIfPresent(Double.self, forKey: .sponsorSizeScale) ?? sponsorSizeScale
        sponsorOpacity = try c.decodeIfPresent(Double.self, forKey: .sponsorOpacity) ?? sponsorOpacity
        sponsorScrollSpeed = try c.decodeIfPresent(Double.self, forKey: .sponsorScrollSpeed) ?? sponsorScrollSpeed
        sponsorScrollDirection = SponsorScrollDirection.sanitize(
            try c.decodeIfPresent(String.self, forKey: .sponsorScrollDirection)
        )
    }

    // Uniform pinch-scale bounds + drag range (parity with shared OverlayLayoutPrefs).
    static let refWidthFraction = 1.0
    static let refHeightFraction = 0.16
    static let widthMin = 0.25
    static let widthMax = 0.98
    static let heightMin = 0.10
    static let heightMax = 0.28
    static let boardScaleMin = 0.4
    static let boardScaleMax = 1.0
    static let anchorYMin = 0.30
    static let anchorYMax = 0.97

    /// Uniform board size multiplier (1.0 ≈ full-width lower-third). Drives both dimensions of the
    /// fixed-aspect strip, so internal proportions never distort — what the Arrange pinch controls.
    func boardScale() -> Double {
        let w = min(max(widthFraction, Self.widthMin), Self.widthMax)
        return min(max(w / Self.refWidthFraction, Self.boardScaleMin), Self.boardScaleMax)
    }

    /// Copy scaled uniformly to `scale`; width and height move together (aspect-locked).
    func withBoardScale(_ scale: Double) -> OverlayLayoutPrefs {
        let s = min(max(scale, Self.boardScaleMin), Self.boardScaleMax)
        var out = self
        out.widthFraction = min(max(Self.refWidthFraction * s, Self.widthMin), Self.widthMax)
        out.heightFraction = min(max(Self.refHeightFraction * s, Self.heightMin), Self.heightMax)
        return out
    }

    /// Copy at `level`, keeping the wire-compat boolean in sync.
    func withStabilizationLevel(_ level: Int) -> OverlayLayoutPrefs {
        var out = self
        out.stabilizationLevel = StabilizationLevel.sanitize(level)
        out.videoStabilization = out.stabilizationLevel > StabilizationLevel.off.rawValue
        return out
    }

    /// Copy re-anchored to a normalized preview point (centre of the board).
    func withAnchor(x: Double, y: Double) -> OverlayLayoutPrefs {
        var out = self
        out.anchorX = min(max(x, 0.0), 1.0)
        out.anchorY = min(max(y, Self.anchorYMin), Self.anchorYMax)
        out.bottomMargin = 0.0
        return out
    }

    func effectiveSponsorIds() -> [String] {
        if !activeSponsorIds.isEmpty { return activeSponsorIds }
        if let id = activeSponsorId, !id.isEmpty { return [id] }
        return []
    }

    func resolveSponsorLogoUrls(from sponsors: [Sponsor]) -> [String] {
        guard sponsorEnabled else { return [] }
        let fromIds = effectiveSponsorIds().compactMap { id in
            sponsors.first(where: { $0.id == id })?.logoUrl?.trimmingCharacters(in: .whitespacesAndNewlines)
        }.filter { !$0.isEmpty }
        if !fromIds.isEmpty { return Array(fromIds.prefix(6)) }
        if SponsorLayoutMode.sanitize(sponsorLayoutMode) == SponsorLayoutMode.single {
            if let logo = sponsors.first(where: { $0.isActive })?.logoUrl, !logo.isEmpty {
                return [logo]
            }
            return []
        }
        return sponsors.filter(\.isActive).compactMap(\.logoUrl).filter { !$0.isEmpty }.prefix(6).map { $0 }
    }

    func resolvedSponsorLogoUrl(from sponsors: [Sponsor]) -> String {
        resolveSponsorLogoUrls(from: sponsors).first ?? ""
    }

    func toEngineLayout(sponsorLogoUrls: [String] = []) -> StreamCameraEngine.OverlayLayout {
        let urls = sponsorLogoUrls.filter { !$0.isEmpty }
        return StreamCameraEngine.OverlayLayout(
            heightFraction: Float(heightFraction),
            widthFraction: Float(widthFraction),
            anchorX: Float(anchorX),
            anchorY: Float(anchorY),
            bottomMarginFraction: Float(bottomMargin / 720),
            horizontalInsetFraction: Float(horizontalInset / 400),
            fontScale: Float(fontScale),
            bgColor: bgColor,
            textColor: textColor,
            opacity: Float(max(0.2, min(1.0, opacity))),
            watermarkEnabled: watermarkEnabled,
            watermarkText: watermarkText,
            sponsorEnabled: sponsorEnabled,
            sponsorLogoUrl: urls.first ?? "",
            sponsorLogoUrls: urls,
            sponsorLayoutMode: sponsorLayoutMode,
            sponsorCarouselIntervalSec: Float(max(2, min(30, sponsorCarouselIntervalSec))),
            sponsorDisplayMode: sponsorDisplayMode,
            sponsorPositionX: Float(max(0, min(1, sponsorPositionX))),
            sponsorPositionY: Float(max(0, min(1, sponsorPositionY))),
            sponsorSizeScale: Float(max(0.3, min(3, sponsorSizeScale))),
            sponsorOpacity: Float(max(0.2, min(1, sponsorOpacity))),
            sponsorScrollSpeed: Float(max(0.3, min(3, sponsorScrollSpeed))),
            sponsorScrollDirection: SponsorScrollDirection.sanitize(sponsorScrollDirection),
            theme: theme.isEmpty ? "barlow" : theme,
            overlayEnabled: overlayEnabled
        )
    }

    func mergeSponsorPatch(_ patch: [String: Any]) -> OverlayLayoutPrefs {
        var merged = self
        if let v = patch["sponsor_enabled"] as? Bool { merged.sponsorEnabled = v }
        if let v = patch["active_sponsor_id"] as? String {
            merged.activeSponsorId = v.isEmpty ? nil : v
        } else if patch["active_sponsor_id"] is NSNull {
            merged.activeSponsorId = nil
        }
        if let arr = patch["active_sponsor_ids"] as? [String] {
            merged.activeSponsorIds = arr.filter { !$0.isEmpty }.prefix(6).map { $0 }
            merged.activeSponsorId = merged.activeSponsorIds.first
        }
        if let v = patch["sponsor_layout_mode"] as? String {
            merged.sponsorLayoutMode = SponsorLayoutMode.sanitize(v)
        }
        if let v = patch["sponsor_carousel_interval_sec"] as? Double {
            merged.sponsorCarouselIntervalSec = max(2, min(30, v))
        }
        if let v = patch["sponsor_display_mode"] as? String {
            merged.sponsorDisplayMode = SponsorDisplayMode.sanitize(v)
        }
        if let v = patch["sponsor_position_x"] as? Double {
            merged.sponsorPositionX = max(0, min(1, v))
        }
        if let v = patch["sponsor_position_x"] as? Int {
            merged.sponsorPositionX = max(0, min(1, Double(v)))
        }
        if let v = patch["sponsor_position_y"] as? Double {
            merged.sponsorPositionY = max(0, min(1, v))
        }
        if let v = patch["sponsor_position_y"] as? Int {
            merged.sponsorPositionY = max(0, min(1, Double(v)))
        }
        if let v = patch["sponsor_size_scale"] as? Double {
            merged.sponsorSizeScale = max(0.3, min(3, v))
        }
        if let v = patch["sponsor_opacity"] as? Double {
            merged.sponsorOpacity = max(0.2, min(1, v))
        }
        if let v = patch["sponsor_scroll_speed"] as? Double {
            merged.sponsorScrollSpeed = max(0.3, min(3, v))
        }
        if let v = patch["sponsor_scroll_direction"] as? String {
            merged.sponsorScrollDirection = SponsorScrollDirection.sanitize(v)
        }
        return merged
    }

    func sponsorPatchDictionary() -> [String: Any] {
        var out: [String: Any] = [
            "sponsor_enabled": sponsorEnabled,
            "sponsor_layout_mode": sponsorLayoutMode,
            "sponsor_carousel_interval_sec": sponsorCarouselIntervalSec,
            "sponsor_display_mode": sponsorDisplayMode,
            "sponsor_position_x": sponsorPositionX,
            "sponsor_position_y": sponsorPositionY,
            "sponsor_size_scale": sponsorSizeScale,
            "sponsor_opacity": sponsorOpacity,
            "sponsor_scroll_speed": sponsorScrollSpeed,
            "sponsor_scroll_direction": sponsorScrollDirection,
        ]
        if !activeSponsorIds.isEmpty {
            out["active_sponsor_ids"] = activeSponsorIds
            if let id = activeSponsorIds.first { out["active_sponsor_id"] = id }
        } else if let id = activeSponsorId {
            out["active_sponsor_id"] = id
        }
        return out
    }
}

enum SponsorLayoutMode {
    static let single = "single"
    static let multi = "multi"
    static let carousel = "carousel"

    static let modes: [(id: String, label: String)] = [
        (single, "One logo"),
        (multi, "All at once"),
        (carousel, "Carousel"),
    ]

    static func sanitize(_ raw: String?) -> String {
        let m = raw?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? single
        return modes.contains(where: { $0.id == m }) ? m : single
    }

    static func allowsMultiSelect(_ mode: String) -> Bool {
        let m = sanitize(mode)
        return m == multi || m == carousel
    }
}

enum SponsorScrollDirection {
    static let ltr = "ltr"
    static let rtl = "rtl"
    static let ttb = "ttb"
    static let btt = "btt"
    static let fixed = "fixed"

    static let directions: [(id: String, label: String)] = [
        (rtl, "Right → Left"),
        (ltr, "Left → Right"),
        (ttb, "Top → Bottom"),
        (btt, "Bottom → Top"),
        (fixed, "Fixed"),
    ]

    static func sanitize(_ raw: String?) -> String {
        let d = raw?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? rtl
        return directions.contains(where: { $0.id == d }) ? d : rtl
    }

    static func isHorizontal(_ dir: String) -> Bool {
        let d = sanitize(dir)
        return d == ltr || d == rtl
    }

    static func isVertical(_ dir: String) -> Bool {
        let d = sanitize(dir)
        return d == ttb || d == btt
    }
}

enum SponsorDisplayMode {
    static let staticMode = "static"
    static let scrollTop = "scroll_top"
    static let scrollBottom = "scroll_bottom"
    static let scrollAboveBoard = "scroll_above_board"
    static let scrollBelowBoard = "scroll_below_board"

    static let modes: [(id: String, label: String)] = [
        (staticMode, "Fixed"),
        (scrollTop, "Scroll top"),
        (scrollAboveBoard, "Above board"),
        (scrollBelowBoard, "Below board"),
        (scrollBottom, "Scroll bottom"),
    ]

    static func isScroll(_ mode: String) -> Bool {
        mode.hasPrefix("scroll")
    }

    static func sanitize(_ raw: String?) -> String {
        let m = raw?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? staticMode
        return modes.contains(where: { $0.id == m }) ? m : staticMode
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
