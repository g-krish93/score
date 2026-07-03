import Foundation
import Shared

// The data-layer models (StreamMatch, BroadcastStatus, GoLiveResult, FixturesResponse,
// FixtureItem, ScoringConfig, MatchDayStatus, PlatformStatus, Sponsor, PairRemoteResult)
// live in the KMP Shared framework now (ADR-001 item 2) — see SharedModels+App.swift for
// their Swift-side conveniences. What remains here is iOS-only: the companion-session
// types, OverlayLayoutPrefs (pending migration — it has a local-only field, a legacy cache
// decoder, and the engine-layout mapping), and pure UI helpers.

struct CompanionSession: Codable {
    var companionToken: String
    var matchSlug: String

    enum CodingKeys: String, CodingKey {
        case companionToken = "companion_token"
        case matchSlug = "match_slug"
    }
}

// RemoteCommand and RemoteCompanionContext come from the KMP Shared framework now —
// RemoteCommand.mergeSponsorInto(base:) carries the sponsor-patch merge with it.

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

/// SwiftUI-editable value type for the studio's overlay setup. Serialization, sanitization
/// and merge semantics live in the KMP `Shared.OverlayLayoutPrefs` (ADR-001 item 2) —
/// everything crosses through `init(shared:)` / `toShared()` below, so a new field is a
/// compile error here until both mappings carry it.
struct OverlayLayoutPrefs {
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

    /// Boundary mapping from the KMP model (which did the tolerant/legacy-key parsing).
    init(shared: Shared.OverlayLayoutPrefs) {
        heightFraction = shared.heightFraction
        widthFraction = shared.widthFraction
        anchorX = shared.anchorX
        anchorY = shared.anchorY
        bottomMargin = shared.bottomMargin
        horizontalInset = shared.horizontalInset
        theme = shared.theme
        fontScale = shared.fontScale
        bgColor = shared.bgColor
        textColor = shared.textColor
        opacity = shared.opacity
        overlayEnabled = shared.overlayEnabled
        videoStabilization = shared.videoStabilization
        stabilizationLevel = Int(shared.stabilizationLevel)
        keepScreenOn = shared.keepScreenOn
        watermarkEnabled = shared.watermarkEnabled
        watermarkText = shared.watermarkText
        sponsorEnabled = shared.sponsorEnabled
        activeSponsorId = shared.activeSponsorId
        activeSponsorIds = shared.activeSponsorIds
        sponsorLayoutMode = shared.sponsorLayoutMode
        sponsorCarouselIntervalSec = shared.sponsorCarouselIntervalSec
        sponsorDisplayMode = shared.sponsorDisplayMode
        sponsorPositionX = shared.sponsorPositionX
        sponsorPositionY = shared.sponsorPositionY
        sponsorSizeScale = shared.sponsorSizeScale
        sponsorOpacity = shared.sponsorOpacity
        sponsorScrollSpeed = shared.sponsorScrollSpeed
        sponsorScrollDirection = shared.sponsorScrollDirection
    }

    /// Boundary mapping to the KMP model — its toJson()/setOverlayPrefs own the wire format.
    func toShared() -> Shared.OverlayLayoutPrefs {
        Shared.OverlayLayoutPrefs(
            heightFraction: heightFraction,
            widthFraction: widthFraction,
            anchorX: anchorX,
            anchorY: anchorY,
            bottomMargin: bottomMargin,
            horizontalInset: horizontalInset,
            theme: theme,
            fontScale: fontScale,
            bgColor: bgColor,
            textColor: textColor,
            opacity: opacity,
            overlayEnabled: overlayEnabled,
            videoStabilization: videoStabilization,
            stabilizationLevel: Int32(stabilizationLevel),
            keepScreenOn: keepScreenOn,
            watermarkEnabled: watermarkEnabled,
            watermarkText: watermarkText,
            sponsorEnabled: sponsorEnabled,
            activeSponsorId: activeSponsorId,
            activeSponsorIds: activeSponsorIds,
            sponsorLayoutMode: sponsorLayoutMode,
            sponsorCarouselIntervalSec: sponsorCarouselIntervalSec,
            sponsorDisplayMode: sponsorDisplayMode,
            sponsorPositionX: sponsorPositionX,
            sponsorPositionY: sponsorPositionY,
            sponsorSizeScale: sponsorSizeScale,
            sponsorOpacity: sponsorOpacity,
            sponsorScrollSpeed: sponsorScrollSpeed,
            sponsorScrollDirection: sponsorScrollDirection
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

    // Sponsor-patch merge and the patch wire format live on the shared model
    // (Shared.OverlayLayoutPrefs.mergeSponsorPatch / sponsorPatchJson) — the exact key
    // list is where the July 2026 overlay_ prefix bug lived, so it exists once now.
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
