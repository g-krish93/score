package uk.co.cricrelay.shared.session

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.first
import uk.co.cricrelay.shared.util.normalizeApiBaseUrl

private val Context.dataStore by preferencesDataStore(name = "cricrelay_session")

actual class SessionStore(private val context: Context) {
    actual suspend fun readSession(defaultBaseUrl: String): SessionData {
        val prefs = context.dataStore.data.first()
        val base = normalizeApiBaseUrl(prefs[KEY_BASE] ?: defaultBaseUrl)
        val token = readSecureToken()
        return SessionData(baseUrl = base, token = token)
    }

    actual suspend fun writeSession(baseUrl: String, token: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BASE] = normalizeApiBaseUrl(baseUrl)
        }
        writeSecureToken(token)
    }

    actual suspend fun clearToken() {
        writeSecureToken("")
        context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(LEGACY_TOKEN_KEY)
            .apply()
    }

    actual suspend fun isOnboardingComplete(): Boolean {
        val prefs = context.dataStore.data.first()
        return prefs[KEY_ONBOARDING] == true
    }

    actual suspend fun markOnboardingComplete() {
        context.dataStore.edit { prefs ->
            prefs[KEY_ONBOARDING] = true
        }
    }

    private fun securePrefs() = EncryptedSharedPreferences.create(
        context,
        SECURE_PREFS,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private fun readSecureToken(): String? {
        val secure = securePrefs().getString(SECURE_TOKEN_KEY, null)
        if (!secure.isNullOrBlank()) return secure
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            .getString(LEGACY_TOKEN_KEY, null)
        if (!legacy.isNullOrBlank()) {
            writeSecureToken(legacy)
            legacyPrefs().edit().remove(LEGACY_TOKEN_KEY).apply()
            return legacy
        }
        return null
    }

    private fun writeSecureToken(token: String) {
        securePrefs().edit().putString(SECURE_TOKEN_KEY, token).apply()
    }

    private fun legacyPrefs() =
        context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)

    private companion object {
        val KEY_BASE = stringPreferencesKey("stream_api_base")
        val KEY_ONBOARDING = booleanPreferencesKey("stream_onboarding_complete_v1")
        const val SECURE_PREFS = "cricrelay_secure"
        const val SECURE_TOKEN_KEY = "stream_api_token_secure"
        const val LEGACY_PREFS = "FlutterSharedPreferences"
        const val LEGACY_TOKEN_KEY = "flutter.stream_api_token"
    }
}
