package uk.co.cricrelay.shared.session

import platform.Foundation.NSUserDefaults
import uk.co.cricrelay.shared.util.normalizeApiBaseUrl

actual class SessionStore {
    actual suspend fun readSession(defaultBaseUrl: String): SessionData {
        val defaults = NSUserDefaults.standardUserDefaults
        val base = normalizeApiBaseUrl(
            defaults.stringForKey(KEY_BASE) ?: defaultBaseUrl,
        )
        val token = defaults.stringForKey(SECURE_TOKEN_KEY)
        return SessionData(baseUrl = base, token = token)
    }

    actual suspend fun writeSession(baseUrl: String, token: String) {
        val defaults = NSUserDefaults.standardUserDefaults
        defaults.setObject(normalizeApiBaseUrl(baseUrl), KEY_BASE)
        defaults.setObject(token, SECURE_TOKEN_KEY)
    }

    actual suspend fun clearToken() {
        val defaults = NSUserDefaults.standardUserDefaults
        defaults.removeObjectForKey(SECURE_TOKEN_KEY)
    }

    actual suspend fun isOnboardingComplete(): Boolean =
        NSUserDefaults.standardUserDefaults.boolForKey(KEY_ONBOARDING)

    actual suspend fun markOnboardingComplete() {
        NSUserDefaults.standardUserDefaults.setBool(true, KEY_ONBOARDING)
    }

    private companion object {
        const val KEY_BASE = "stream_api_base"
        const val SECURE_TOKEN_KEY = "stream_api_token_secure"
        const val KEY_ONBOARDING = "stream_onboarding_complete_v1"
    }
}
