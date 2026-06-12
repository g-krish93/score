package uk.co.cricrelay.shared.session

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object KeychainTokenStore {
    private const val SERVICE = "uk.co.cricrelay"
    private const val ACCOUNT = "stream_api_token_secure"
    private const val LEGACY_UD_KEY = "stream_api_token_secure"

    fun read(): String? {
        migrateFromUserDefaultsIfNeeded()
        val query = mapOf<Any?, Any?>(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to SERVICE,
            kSecAttrAccount to ACCOUNT,
            kSecReturnData to true,
            kSecMatchLimit to kSecMatchLimitOne,
        )
        memScoped {
            val result = alloc<ObjCObjectVar<NSData?>>()
            val status = SecItemCopyMatching(query, result.ptr)
            if (status.toInt() != errSecSuccess.toInt()) return null
            val data = result.value ?: return null
            return NSString.create(data, NSUTF8StringEncoding) as String
        }
    }

    fun write(token: String) {
        clear()
        val payload = (token as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        val query = mapOf<Any?, Any?>(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to SERVICE,
            kSecAttrAccount to ACCOUNT,
            kSecValueData to payload,
            kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlock,
        )
        SecItemAdd(query, null)
    }

    fun clear() {
        val query = mapOf<Any?, Any?>(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to SERVICE,
            kSecAttrAccount to ACCOUNT,
        )
        SecItemDelete(query)
    }

    private fun migrateFromUserDefaultsIfNeeded() {
        val defaults = NSUserDefaults.standardUserDefaults
        val legacy = defaults.stringForKey(LEGACY_UD_KEY) ?: return
        if (legacy.isEmpty()) return
        write(legacy)
        defaults.removeObjectForKey(LEGACY_UD_KEY)
    }
}
