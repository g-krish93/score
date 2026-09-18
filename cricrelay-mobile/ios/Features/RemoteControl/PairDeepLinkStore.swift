import Foundation

/// Pending `https://…/pair` or `cricrelay://pair` URI from system Camera / Universal Links
/// until Remote Control redeems it.
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

struct ParsedPairDeepLink {
    let slug: String
    let token: String
    let apiBase: String
}

enum PairDeepLinkParser {
    /// Accepts `cricrelay://pair?…` and `https://host/pair?…`.
    static func parse(_ raw: String) -> ParsedPairDeepLink? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty,
              let components = URLComponents(string: trimmed),
              let scheme = components.scheme?.lowercased() else { return nil }

        let host = (components.host ?? "").lowercased()
        if scheme == "cricrelay" {
            guard host == "pair" else { return nil }
        } else if scheme == "https" || scheme == "http" {
            let path = components.path.lowercased()
            let allowed =
                host == "cricrelay.co.uk" ||
                host == "www.cricrelay.co.uk" ||
                host == "localhost" ||
                host.hasSuffix(".cricrelay.co.uk")
            guard allowed else { return nil }
            guard path == "/pair" || path.hasSuffix("/pair") else { return nil }
        } else {
            return nil
        }

        let items = Dictionary(
            (components.queryItems ?? []).map { ($0.name, $0.value ?? "") },
            uniquingKeysWith: { first, _ in first }
        )
        guard let slug = items["slug"], !slug.isEmpty,
              let token = items["token"], !token.isEmpty else { return nil }
        let base = (items["base"] ?? "").trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        return ParsedPairDeepLink(
            slug: slug,
            token: token,
            apiBase: base.isEmpty ? "https://cricrelay.co.uk" : base
        )
    }
}
