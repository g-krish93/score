import Foundation

/// Pending `cricrelay://pair?…` URI from the system Camera QR banner until Remote Control redeems it.
enum PairDeepLinkStore {
    private static let key = "cricrelay.pending_pair_uri"

    static var pendingUri: String? {
        get { UserDefaults.standard.string(forKey: key) }
        set {
            if let newValue, !newValue.isEmpty {
                UserDefaults.standard.set(newValue, forKey: key)
            } else {
                UserDefaults.standard.removeObject(forKey: key)
            }
        }
    }

    static func consume() -> String? {
        let value = pendingUri
        pendingUri = nil
        return value
    }
}
