package uk.co.cricrelay.shared.repository

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import uk.co.cricrelay.shared.api.CricRelayApiClient
import uk.co.cricrelay.shared.model.FixturesResponse
import uk.co.cricrelay.shared.model.MatchDayStatus
import uk.co.cricrelay.shared.model.OverlayLayoutPrefs
import uk.co.cricrelay.shared.model.PlatformStatus
import uk.co.cricrelay.shared.model.ScoringConfig
import uk.co.cricrelay.shared.model.StreamMatch
import uk.co.cricrelay.shared.session.SessionData
import uk.co.cricrelay.shared.session.SessionStore
import uk.co.cricrelay.shared.util.isAllowedApiBaseUrl
import uk.co.cricrelay.shared.util.normalizeApiBaseUrl

class AuthRepository(
    private val sessionStore: SessionStore,
    private val httpClientFactory: () -> HttpClient = { defaultHttpClient() },
) {
    suspend fun loadApiClient(): CricRelayApiClient {
        val session = sessionStore.readSession()
        return CricRelayApiClient(httpClientFactory(), session.baseUrl, session.token)
    }

    suspend fun login(baseUrl: String, email: String, password: String): CricRelayApiClient {
        val normalized = normalizeApiBaseUrl(baseUrl)
        if (!isAllowedApiBaseUrl(normalized)) {
            throw IllegalArgumentException("Use HTTPS for your club server (http only for local testing).")
        }
        val client = CricRelayApiClient(httpClientFactory(), normalized)
        client.login(email, password)
        sessionStore.writeSession(client.baseUrl, client.token.orEmpty())
        return client
    }

    suspend fun logout() {
        sessionStore.clearToken()
    }

    suspend fun currentSession(): SessionData = sessionStore.readSession()

    suspend fun isOnboardingComplete(): Boolean = sessionStore.isOnboardingComplete()

    suspend fun markOnboardingComplete() = sessionStore.markOnboardingComplete()
}

class StreamRepository(
    private val apiClientProvider: ApiClientProvider,
) {
    suspend fun listStreams(): List<StreamMatch> = apiClientProvider.get().listStreams()

    suspend fun listFixtures(): FixturesResponse = apiClientProvider.get().listFixtures()

    suspend fun createPlayCricketStream(matchId: String, label: String): StreamMatch =
        apiClientProvider.get().createPlayCricketStream(matchId = matchId, label = label)

    suspend fun createPcsBleStream(label: String): StreamMatch =
        apiClientProvider.get().createPcsBleStream(label)

    suspend fun deleteStream(matchSlug: String) = apiClientProvider.get().deleteStream(matchSlug)

    suspend fun renameStream(matchSlug: String, label: String) =
        apiClientProvider.get().renameStream(matchSlug, label)

    suspend fun getScoring(matchSlug: String): ScoringConfig =
        apiClientProvider.get().getScoring(matchSlug)

    suspend fun setScoring(matchSlug: String, mode: String): ScoringConfig =
        apiClientProvider.get().setScoring(matchSlug, mode)

    suspend fun getMatchDayStatus(matchSlug: String): MatchDayStatus =
        apiClientProvider.get().getMatchDayStatus(matchSlug)

    suspend fun setRelayPause(matchSlug: String, paused: Boolean) =
        apiClientProvider.get().setRelayPause(matchSlug, paused)

    suspend fun goLive(matchSlug: String, platform: String = "youtube") =
        apiClientProvider.get().goLive(matchSlug, platform)

    suspend fun stopLive(platform: String? = null) = apiClientProvider.get().stopLive(platform)

    suspend fun updateBroadcastStatus(
        matchSlug: String,
        status: String,
        platform: String? = null,
        watchUrl: String? = null,
    ) = apiClientProvider.get().updateBroadcastStatus(matchSlug, status, platform, watchUrl)

    suspend fun youtubeStatus(): JsonObject = apiClientProvider.get().youtubeStatus()

    suspend fun twitchStatus(): JsonObject = apiClientProvider.get().twitchStatus()

    suspend fun youtubePlatformStatus(): PlatformStatus =
        PlatformStatus.fromYoutube(youtubeStatus())

    suspend fun twitchPlatformStatus(): PlatformStatus =
        PlatformStatus.fromTwitch(twitchStatus())

    suspend fun youtubeAuthorizeUrl(): String = apiClientProvider.get().youtubeAuthorizeUrl()

    suspend fun twitchAuthorizeUrl(): String = apiClientProvider.get().twitchAuthorizeUrl()

    suspend fun youtubeDisconnect() = apiClientProvider.get().youtubeDisconnect()

    suspend fun twitchDisconnect() = apiClientProvider.get().twitchDisconnect()

    suspend fun getOverlayPrefs(matchSlug: String): OverlayLayoutPrefs =
        apiClientProvider.get().getOverlayPrefs(matchSlug)

    suspend fun setOverlayPrefs(matchSlug: String, prefs: OverlayLayoutPrefs) =
        apiClientProvider.get().setOverlayPrefs(matchSlug, prefs)

    suspend fun getAppBuilds(): JsonObject = apiClientProvider.get().getAppBuilds()
}

fun defaultHttpClient(): HttpClient = HttpClient {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            },
        )
    }
}
