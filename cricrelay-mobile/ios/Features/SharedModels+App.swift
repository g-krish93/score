import Foundation
import Shared

// Swift-side ergonomics for the KMP shared models (ADR-001 item 2). `FixtureItem` and
// `PlatformStatus` are Kotlin classes from the Shared framework; their Swift duplicates
// were deleted from Models.swift.

// NOTE on Identifiable: Kotlin classes arrive as NSObject subclasses, and Apple's overlay
// already declares `extension NSObject: Identifiable` (identity-based ObjectIdentifier id).
// Re-declaring the conformance here would be a "redundant conformance" compile error, and
// the inherited id churns on every reload (fresh Kotlin objects each fetch) — so list sites
// pass an explicit key instead: ForEach(streams, id: \.slug), ForEach(fixtures, id: \.matchId).

// Kotlin default arguments don't cross the Obj-C boundary — the framework exports only
// the full initializer — so restore the zero-arg "disconnected" default used app-wide.
extension PlatformStatus {
    convenience init() {
        self.init(connected: false, ready: false, label: "")
    }
}
