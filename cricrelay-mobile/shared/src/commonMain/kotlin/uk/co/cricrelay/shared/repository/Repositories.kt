package uk.co.cricrelay.shared.repository

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.withTimeoutOrNull
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

    suspend fun register(
        baseUrl: String,
        name: String,
        email: String,
        password: String,
        consent: Boolean = false,
    ): CricRelayApiClient {
        val normalized = normalizeApiBaseUrl(baseUrl)
        if (!isAllowedApiBaseUrl(normalized)) {
            throw IllegalArgumentException("Use HTTPS for your club server (http only for local testing).")
        }
        val client = CricRelayApiClient(httpClientFactory(), normalized)
        client.register(name, email, password, consent)
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

// If 2 MB hasn't uploaded within this window the uplink is below ~2 Mbps — no need to wait longer.
private const val UPLOAD_PROBE_TIMEOUT_MS = 8_000L

class StreamRepository(
    private val apiClientProvider: ApiClientProvider,
) {
    suspend fun listStreams(): List<StreamMatch> = apiClientProvider.get().listStreams()

    suspend fun listFixtures(): FixturesResponse = apiClientProvider.get().listFixtures()

    suspend fun createPlayCricketStream(matchId: String, label: String): StreamMatch =
        apiClientProvider.get().createPlayCricketStream(matchId = matchId, label = label)

    suspend fun createCricHeroesStream(matchUrl: String, label: String): StreamMatch =
        apiClientProvider.get().createCricHeroesStream(matchUrl = matchUrl, label = label)

    suspend fun createManualStream(label: String): StreamMatch =
        apiClientProvider.get().createManualStream(label = label)

    suspend fun getScorerLink(matchSlug: String): uk.co.cricrelay.shared.model.ScorerLink =
        apiClientProvider.get().getScorerLink(matchSlug)

    suspend fun deleteStream(matchSlug: String) = apiClientProvider.get().deleteStream(matchSlug)

    suspend fun renameStream(matchSlug: String, label: String) =
        apiClientProvider.get().renameStream(matchSlug, label)

    suspend fun getScoring(matchSlug: String): ScoringConfig =
        apiClientProvider.get().getScoring(matchSlug)

    suspend fun setScoring(matchSlug: String, mode: String, provider: String? = null): ScoringConfig =
        apiClientProvider.get().setScoring(matchSlug, mode, provider)

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

    /**
     * Upload throughput to the club server in Mbps. Semantics for the Go Live quality choice:
     * - a number: the measured rate;
     * - 0.0: the probe timed out mid-upload — the uplink is measurably slower than the floor;
     * - null: the probe couldn't run at all (old server, offline) — network quality UNKNOWN,
     *   callers should keep their current default rather than downgrade.
     */
    suspend fun measureUploadMbps(): Double? {
        var unavailable = false
        val measured = withTimeoutOrNull(UPLOAD_PROBE_TIMEOUT_MS) {
            val mbps = apiClientProvider.get().measureUploadMbps()
            if (mbps == null) unavailable = true
            mbps
        }
        return when {
            unavailable -> null
            measured == null -> 0.0
            else -> measured
        }
    }

    suspend fun getOverlayPrefs(matchSlug: String): OverlayLayoutPrefs =
        apiClientProvider.get().getOverlayPrefs(matchSlug)

    suspend fun setOverlayPrefs(matchSlug: String, prefs: OverlayLayoutPrefs) =
        apiClientProvider.get().setOverlayPrefs(matchSlug, prefs)

    suspend fun listSponsors(): List<uk.co.cricrelay.shared.model.Sponsor> =
        apiClientProvider.get().listSponsors()

    suspend fun pairRemote(matchSlug: String): uk.co.cricrelay.shared.model.PairRemoteResult =
        apiClientProvider.get().pairRemote(matchSlug)

    suspend fun pollRemoteCommands(matchSlug: String): List<uk.co.cricrelay.shared.model.RemoteCommand> =
        apiClientProvider.get().pollRemoteCommands(matchSlug)

    suspend fun redeemPairToken(matchSlug: String, pairToken: String, apiBase: String): String =
        apiClientProvider.get().redeemPairToken(matchSlug, pairToken, apiBase)

    suspend fun sendRemoteCommand(matchSlug: String, companionToken: String, command: String) =
        apiClientProvider.get().sendRemoteCommand(matchSlug, companionToken, command)

    suspend fun sendRemoteOverlayPrefs(
        matchSlug: String,
        companionToken: String,
        prefs: uk.co.cricrelay.shared.model.OverlayLayoutPrefs,
    ) = apiClientProvider.get().sendRemoteOverlayPrefs(matchSlug, companionToken, prefs)

    suspend fun getRemoteContext(matchSlug: String, companionToken: String) =
        apiClientProvider.get().getRemoteContext(matchSlug, companionToken)

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
