import Foundation

// MARK: - 1b Checklist gate — the go-live gate IS the setup UI.
//
// Three checks (camera / destination / scoreboard source) derived from published
// StudioViewModel state. The segmented Go Live ring shows N/3, the checklist rows are
// the sheet entry points, and a blocked ring routes the operator to the first
// incomplete check instead of going dead. Mirrors Android's StudioChecklist.kt.

/// Which of the three readiness checks a row represents; tapping opens its sheet.
enum CheckKind: Hashable {
    case camera
    case destination
    case scoring
}

struct StudioCheck: Identifiable, Equatable {
    let kind: CheckKind
    let complete: Bool
    let title: String
    let sublabel: String
    /// Paint the sublabel coral (guidance/stale states) instead of the dim body colour.
    let warning: Bool

    var id: CheckKind { kind }
}

enum StudioChecklist {
    /// Pure derivation so it stays unit-testable — every input is plain state.
    static func build(
        previewReady: Bool,
        qualityLabel: String,
        stabilizationLevel: Int,
        destinationReady: Bool,
        destinationLabel: String,
        scoringMode: String,
        overlayEmbedUrl: String,
        matchDay: MatchDayStatus?
    ) -> [StudioCheck] {
        [
            cameraCheck(
                previewReady: previewReady,
                qualityLabel: qualityLabel,
                stabilizationLevel: stabilizationLevel
            ),
            destinationCheck(ready: destinationReady, label: destinationLabel),
            scoringCheck(mode: scoringMode, overlayEmbedUrl: overlayEmbedUrl, matchDay: matchDay),
        ]
    }

    static func completedCount(_ checks: [StudioCheck]) -> Int {
        checks.filter(\.complete).count
    }

    static func firstIncomplete(_ checks: [StudioCheck]) -> StudioCheck? {
        checks.first { !$0.complete }
    }

    /// One line under the ring naming the missing check ("blocked = guidance, never dead").
    static func ringCaption(_ checks: [StudioCheck]) -> String {
        guard let first = firstIncomplete(checks) else {
            return "All checks passed — you're good to go"
        }
        switch first.kind {
        case .camera: return "Waiting for the camera to be ready"
        case .destination: return "Choose a destination to unlock"
        case .scoring: return "Choose a scoring source to unlock"
        }
    }

    /// Coral "N to fix" inside the blocked ring; nil once every check passes.
    static func fixLabel(_ checks: [StudioCheck]) -> String? {
        let missing = checks.count - completedCount(checks)
        return missing == 0 ? nil : "\(missing) to fix"
    }

    // MARK: - Individual checks

    private static func cameraCheck(
        previewReady: Bool,
        qualityLabel: String,
        stabilizationLevel: Int
    ) -> StudioCheck {
        let stabName: String
        switch stabilizationLevel {
        case StabilizationLevel.cinematic.rawValue: stabName = "cinematic"
        case StabilizationLevel.off.rawValue: stabName = "off"
        default: stabName = "standard"
        }
        return StudioCheck(
            kind: .camera,
            complete: previewReady,
            title: previewReady ? "Camera ready" : "Camera",
            sublabel: previewReady
                ? "\(qualityLabel) · stabilization \(stabName)"
                : "Waiting for the camera…",
            warning: false
        )
    }

    private static func destinationCheck(ready: Bool, label: String) -> StudioCheck {
        StudioCheck(
            kind: .destination,
            complete: ready,
            title: ready ? "\(label) connected" : "Destination",
            sublabel: ready ? "Ready to stream" : "Choose where to stream",
            warning: !ready
        )
    }

    private static func scoringCheck(
        mode: String,
        overlayEmbedUrl: String,
        matchDay: MatchDayStatus?
    ) -> StudioCheck {
        let modeSet = !mode.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        let overlayLinked = !overlayEmbedUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        // Both halves preserve the old preflight's coverage: a scoring mode without an overlay
        // embed URL means going live silently drops the board from the stream.
        let complete = modeSet && overlayLinked

        if !complete {
            return StudioCheck(
                kind: .scoring,
                complete: false,
                title: "Scoreboard source",
                sublabel: modeSet
                    ? "Overlay link missing — re-link the match"
                    : "Nothing linked yet",
                warning: true
            )
        }

        // Sublabel from the live MatchDayStatus poll (~5 s cadence).
        let effectiveMode = matchDay?.scoringMode ?? mode
        if effectiveMode == "manual" {
            let sublabel: String
            var warning = false
            if matchDay?.scoringStale == true {
                sublabel = "Manual scorer · updates stalled"; warning = true
            } else if matchDay?.scoringActive == true {
                sublabel = "Manual scorer · updating"
            } else {
                sublabel = "Manual scorer · waiting for scorer"
            }
            return StudioCheck(
                kind: .scoring, complete: true,
                title: "Scoreboard linked", sublabel: sublabel, warning: warning
            )
        }
        if let day = matchDay, day.scoringStale {
            return StudioCheck(
                kind: .scoring, complete: true,
                title: "Scoreboard linked", sublabel: "Play-Cricket · feed stale", warning: true
            )
        }
        let live = matchDay?.scoringActive == true
        return StudioCheck(
            kind: .scoring, complete: true,
            title: "Scoreboard linked",
            sublabel: live ? "Play-Cricket · live" : "Play-Cricket · waiting for feed",
            warning: false
        )
    }
}

// MARK: - View-model derivation

extension StudioViewModel {
    /// The three readiness checks the 1b gate renders, derived from published state so
    /// SwiftUI recomputes them on any relevant change.
    var checks: [StudioCheck] {
        StudioChecklist.build(
            previewReady: previewReady,
            qualityLabel: Self.streamQualityLabel,
            stabilizationLevel: overlayPrefs.stabilizationLevel,
            destinationReady: destinationReady,
            destinationLabel: destinationLabel,
            scoringMode: scoringConfig?.mode ?? "",
            overlayEmbedUrl: match?.overlayEmbedUrl ?? "",
            matchDay: matchDay
        )
    }

    var completedChecksCount: Int { StudioChecklist.completedCount(checks) }

    var firstIncompleteCheck: StudioCheck? { StudioChecklist.firstIncomplete(checks) }

    var ringCaption: String { StudioChecklist.ringCaption(checks) }

    var goLiveFixLabel: String? { StudioChecklist.fixLabel(checks) }

    /// Configured stream quality ("1080p30"). The iOS engine publishes no measured bitrate,
    /// so the health pill shows this configured quality only.
    /// TODO: surface measured bitrate when the engine publishes stats.
    static var streamQualityLabel: String {
        "\(min(StreamCameraEngine.defaultStreamWidth, StreamCameraEngine.defaultStreamHeight))p30"
    }

    /// Route a checklist row (or a blocked ring tap) to the sheet that fixes it.
    func openSheet(for kind: CheckKind) {
        switch kind {
        case .camera: activeSheet = .cameraSettings
        case .destination: activeSheet = .destination
        case .scoring: activeSheet = .scoring
        }
    }
}
