package uk.co.cricrelay.mobile.feature.home

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class CompanionSession(
    val matchSlug: String = "",
    val companionToken: String = "",
    val apiBase: String = "",
)

@Singleton
class CompanionTokenStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(session: CompanionSession) {
        prefs.edit()
            .putString(KEY_SLUG, session.matchSlug)
            .putString(KEY_TOKEN, session.companionToken)
            .putString(KEY_BASE, session.apiBase)
            .apply()
    }

    fun load(): CompanionSession? {
        val slug = prefs.getString(KEY_SLUG, "").orEmpty()
        val token = prefs.getString(KEY_TOKEN, "").orEmpty()
        val base = prefs.getString(KEY_BASE, "").orEmpty()
        if (slug.isBlank() || token.isBlank()) return null
        return CompanionSession(matchSlug = slug, companionToken = token, apiBase = base)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS = "cricrelay_companion_session"
        const val KEY_SLUG = "match_slug"
        const val KEY_TOKEN = "companion_token"
        const val KEY_BASE = "api_base"
    }
}
