package uk.co.cricrelay.shared.session

data class SessionData(
    val baseUrl: String,
    val token: String?,
)

expect class SessionStore {
    suspend fun readSession(defaultBaseUrl: String = "https://cricrelay.co.uk"): SessionData
    suspend fun writeSession(baseUrl: String, token: String)
    suspend fun clearToken()
    suspend fun isOnboardingComplete(): Boolean
    suspend fun markOnboardingComplete()
}
