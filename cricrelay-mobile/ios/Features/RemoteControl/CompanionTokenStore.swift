import Foundation

enum CompanionTokenStore {
    private static let tokenKey = "cricrelay_companion_token"
    private static let slugKey = "cricrelay_companion_slug"

    static func save(token: String, slug: String) {
        UserDefaults.standard.set(token, forKey: tokenKey)
        UserDefaults.standard.set(slug, forKey: slugKey)
    }

    static func load() -> (token: String, slug: String)? {
        guard let token = UserDefaults.standard.string(forKey: tokenKey),
              let slug = UserDefaults.standard.string(forKey: slugKey),
              !token.isEmpty, !slug.isEmpty else { return nil }
        return (token, slug)
    }

    static func clear() {
        UserDefaults.standard.removeObject(forKey: tokenKey)
        UserDefaults.standard.removeObject(forKey: slugKey)
    }
}
