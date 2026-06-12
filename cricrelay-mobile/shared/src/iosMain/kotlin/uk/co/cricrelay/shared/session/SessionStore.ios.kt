package uk.co.cricrelay.shared.session

import platform.Foundation.NSUserDefaults
import uk.co.cricrelay.shared.util.normalizeApiBaseUrl

actual class SessionStore {
    actual suspend fun readSession(defaultBaseUrl: String): SessionData {
        val defaults = NSUserDefaults.standardUserDefaults
        val base = normalizeApiBaseUrl(
            defaults.stringForKey(KEY_BASE) ?: defaultBaseUrl,
        )
        val token = KeychainTokenStore.read()
        return SessionData(baseUrl = base, token = token)
    }

    actual suspend fun writeSession(baseUrl: String, token: String) {
        val defaults = NSUserDefaults.standardUserDefaults
        defaults.setObject(normalizeApiBaseUrl(baseUrl), KEY_BASE)
        KeychainTokenStore.write(token)
    }

    actual suspend fun clearToken() {
        KeychainTokenStore.clear()
    }

    actual suspend fun isOnboardingComplete(): Boolean =
        NSUserDefaults.standardUserDefaults.boolForKey(KEY_ONBOARDING)

    actual suspend fun markOnboardingComplete() {
        NSUserDefaults.standardUserDefaults.setBool(true, KEY_ONBOARDING)
    }

    private companion object {
        const val KEY_BASE = "stream_api_base"
        const val KEY_ONBOARDING = "stream_onboarding_complete_v1"
    }
}
