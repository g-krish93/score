import Foundation
import Shared

/// Proof of KMP interop for the ADR-001 spike: imports the shared framework, calls a
/// top-level Kotlin function and a shared model constructor across the boundary.
/// Delete once real call sites consume the shared data layer (ADR-001 items 2–4).
enum SharedKitProbe {
    /// Runs at bootstrap in DEBUG (see CricRelayApp). Release builds still compile and
    /// link this file, which is the part the spike needs to prove on CI.
    static func verify() -> Bool {
        // Top-level Kotlin functions in UrlValidator.kt surface as statics on UrlValidatorKt.
        let normalized = UrlValidatorKt.normalizeApiBaseUrl(raw: " https://cricrelay.co.uk/ ")
        let fixture = FixtureItem(matchId: "probe", title: "ADR-001 spike")
        return normalized == "https://cricrelay.co.uk" && fixture.matchId == "probe"
    }
}
