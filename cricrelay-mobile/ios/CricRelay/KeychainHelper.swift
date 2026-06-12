import Foundation
import Security

enum KeychainHelper {
    private static let service = "uk.co.cricrelay"
    private static let account = "stream_api_token_secure"
    private static let legacyDefaultsKey = "stream_api_token_secure"

    static func readToken() -> String? {
        migrateFromUserDefaultsIfNeeded()
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func saveToken(_ token: String) {
        deleteToken()
        guard let data = token.data(using: .utf8) else { return }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
        ]
        SecItemAdd(query as CFDictionary, nil)
    }

    static func deleteToken() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
    }

    private static func migrateFromUserDefaultsIfNeeded() {
        guard let legacy = UserDefaults.standard.string(forKey: legacyDefaultsKey), !legacy.isEmpty else {
            return
        }
        saveToken(legacy)
        UserDefaults.standard.removeObject(forKey: legacyDefaultsKey)
    }
}
