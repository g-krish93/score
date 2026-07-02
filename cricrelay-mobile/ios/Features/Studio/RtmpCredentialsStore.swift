import Foundation

struct RtmpCredentials {
    var rtmpUrl: String = ""
    var streamKey: String = ""
    var watchUrl: String = ""

    var isConfigured: Bool { !rtmpUrl.isEmpty && !streamKey.isEmpty }
}

/// Persists per-stream custom RTMP credentials across launches so a custom destination survives
/// relaunch. Mirrors the Android `RtmpCredentialsStore` — UserDefaults is app-sandboxed, the same
/// security posture as Android's private SharedPreferences (the stream key is not a long-lived
/// secret; the auth token still lives in the Keychain).
enum RtmpCredentialsStore {
    private static let defaults = UserDefaults.standard

    static func load(slug: String) -> RtmpCredentials {
        RtmpCredentials(
            rtmpUrl: defaults.string(forKey: key(slug, "rtmp_url")) ?? "",
            streamKey: defaults.string(forKey: key(slug, "stream_key")) ?? "",
            watchUrl: defaults.string(forKey: key(slug, "watch_url")) ?? ""
        )
    }

    static func save(slug: String, _ creds: RtmpCredentials) {
        defaults.set(creds.rtmpUrl, forKey: key(slug, "rtmp_url"))
        defaults.set(creds.streamKey, forKey: key(slug, "stream_key"))
        defaults.set(creds.watchUrl, forKey: key(slug, "watch_url"))
    }

    private static func key(_ slug: String, _ field: String) -> String {
        "rtmp_\(slug)_\(field)"
    }
}

/// Camera/device-scoped settings — they describe THIS phone, not the match.
struct DeviceStreamSettings {
    var stabilizationLevel: Int = StabilizationLevel.standard.rawValue
    var keepScreenOn: Bool = true

    /// Overlay a copy of `prefs` with this device's camera settings.
    func appliedTo(_ prefs: OverlayLayoutPrefs) -> OverlayLayoutPrefs {
        var out = prefs.withStabilizationLevel(stabilizationLevel)
        out.keepScreenOn = keepScreenOn
        return out
    }
}

/// Local-first persistence for the studio setup (parity with Android `StudioLocalPrefsStore`).
/// The phone owns its configuration:
/// - **Device settings** (stabilization level, keep-screen-on) are stored per device, never read
///   back from the server — a camera setting must not depend on a network round-trip.
/// - **Overlay prefs** (board layout, sponsor setup, watermark) are cached per match slug. The
///   cache is authoritative for the studio; the server copy is only a seed for a fresh install
///   and the mirror the web dashboard / remote companion read.
enum StudioLocalPrefsStore {
    private static let defaults = UserDefaults.standard
    private static let stabilizationKey = "device_stabilization_level"
    private static let keepScreenOnKey = "device_keep_screen_on"

    static func loadDeviceSettings() -> DeviceStreamSettings {
        DeviceStreamSettings(
            stabilizationLevel: StabilizationLevel.sanitize(
                defaults.object(forKey: stabilizationKey) as? Int
            ),
            keepScreenOn: defaults.object(forKey: keepScreenOnKey) as? Bool ?? true
        )
    }

    static func saveDeviceSettings(_ settings: DeviceStreamSettings) {
        defaults.set(StabilizationLevel.sanitize(settings.stabilizationLevel), forKey: stabilizationKey)
        defaults.set(settings.keepScreenOn, forKey: keepScreenOnKey)
    }

    static func loadOverlayPrefs(slug: String) -> OverlayLayoutPrefs? {
        guard let data = defaults.data(forKey: overlayKey(slug)) else { return nil }
        return try? JSONDecoder().decode(OverlayLayoutPrefs.self, from: data)
    }

    static func saveOverlayPrefs(slug: String, _ prefs: OverlayLayoutPrefs) {
        if let data = try? JSONEncoder().encode(prefs) {
            defaults.set(data, forKey: overlayKey(slug))
        }
    }

    private static func overlayKey(_ slug: String) -> String {
        "overlay_prefs_\(slug)"
    }
}
