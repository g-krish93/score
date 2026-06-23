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
