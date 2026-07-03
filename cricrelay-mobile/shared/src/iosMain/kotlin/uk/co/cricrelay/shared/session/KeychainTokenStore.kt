package uk.co.cricrelay.shared.session

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
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
        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = withQuery(extraCapacity = 2) { query ->
                CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
                CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
                SecItemCopyMatching(query, result.ptr)
            }
            if (status.toInt() != errSecSuccess.toInt()) return null
            // SecItemCopyMatching hands back a +1 "Copy" ref; CFBridgingRelease moves it to ARC.
            val data = CFBridgingRelease(result.value) as? NSData ?: return null
            return NSString.create(data, NSUTF8StringEncoding)?.toString()
        }
    }

    fun write(token: String) {
        clear()
        val payload = NSString.create(string = token).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        val payloadRef = CFBridgingRetain(payload)
        try {
            withQuery(extraCapacity = 2) { query ->
                CFDictionaryAddValue(query, kSecValueData, payloadRef)
                CFDictionaryAddValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlock)
                SecItemAdd(query, null)
            }
        } finally {
            CFRelease(payloadRef)
        }
    }

    fun clear() {
        withQuery(extraCapacity = 0) { query ->
            SecItemDelete(query)
        }
    }

    /**
     * Build the service/account query as a real CFDictionary — the Security framework takes
     * CFDictionaryRef, and Kotlin Maps don't bridge across the C boundary (the original
     * Map-based version of this file never compiled; Apple targets only build on macOS CI).
     * Created with no retain callbacks, so every bridged ref must outlive the block: the
     * service/account strings are retained here and released after, the kSec* constants
     * have process lifetime.
     */
    private inline fun <T> withQuery(extraCapacity: Int, block: (CFMutableDictionaryRef?) -> T): T {
        val service = CFBridgingRetain(SERVICE)
        val account = CFBridgingRetain(ACCOUNT)
        val query = CFDictionaryCreateMutable(null, (3 + extraCapacity).toLong(), null, null)
        try {
            CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(query, kSecAttrService, service)
            CFDictionaryAddValue(query, kSecAttrAccount, account)
            return block(query)
        } finally {
            CFRelease(query)
            CFRelease(service)
            CFRelease(account)
        }
    }

    private fun migrateFromUserDefaultsIfNeeded() {
        val defaults = NSUserDefaults.standardUserDefaults
        val legacy = defaults.stringForKey(LEGACY_UD_KEY) ?: return
        if (legacy.isEmpty()) return
        write(legacy)
        defaults.removeObjectForKey(LEGACY_UD_KEY)
    }
}
