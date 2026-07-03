package uk.co.cricrelay.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import uk.co.cricrelay.shared.model.FixturesResponse
import uk.co.cricrelay.shared.model.GoLiveResult
import uk.co.cricrelay.shared.model.MatchDayStatus
import uk.co.cricrelay.shared.model.OverlayLayoutPrefs
import uk.co.cricrelay.shared.model.PairRemoteResult
import uk.co.cricrelay.shared.model.RemoteCommand
import uk.co.cricrelay.shared.model.RemoteCompanionContext
import uk.co.cricrelay.shared.model.Sponsor
import uk.co.cricrelay.shared.model.ScorerLink
import uk.co.cricrelay.shared.model.ScoringConfig
import uk.co.cricrelay.shared.model.StreamMatch
import uk.co.cricrelay.shared.model.array
import uk.co.cricrelay.shared.model.string
import uk.co.cricrelay.shared.util.isAllowedApiBaseUrl
import uk.co.cricrelay.shared.util.normalizeApiBaseUrl
import uk.co.cricrelay.shared.util.resolveAbsoluteUrl

class ApiException(message: String) : Exception(message)

// 2 MB: big enough to ride out TCP slow start (~2.7s at the 6 Mbps 1080p threshold), small
// enough to finish fast on a good link and fail fast through a proxy body-size cap.
private const val UPLOAD_PROBE_BYTES = 2_000_000

class CricRelayApiClient(
    private val httpClient: HttpClient,
    baseUrl: String,
    token: String? = null,
) {
    var baseUrl: String = normalizeApiBaseUrl(baseUrl)
        private set

    var token: String? = token
        private set

    val hasToken: Boolean get() = !token.isNullOrBlank()

    fun updateSession(base: String, newToken: String) {
        val normalized = normalizeApiBaseUrl(base)
        if (!isAllowedApiBaseUrl(normalized)) {
            throw ApiException("Server URL must use HTTPS (http only for local testing).")
        }
        baseUrl = normalized
        token = newToken
    }

    fun clearToken() {
        token = null
    }

    private fun authHeaders(): Map<String, String> = buildMap {
        put(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        put(HttpHeaders.Accept, ContentType.Application.Json.toString())
        token?.takeIf { it.isNotBlank() }?.let { put(HttpHeaders.Authorization, "Bearer $it") }
    }

    private suspend fun parseJsonObject(response: HttpResponse): JsonObject {
        val raw = response.bodyAsText().trim()
        val contentType = response.headers[HttpHeaders.ContentType]?.lowercase().orEmpty()
        val looksHtml = raw.lowercase().startsWith("<!doctype") ||
            raw.lowercase().startsWith("<html") ||
            (!contentType.contains("json") && raw.startsWith("<"))

        if (looksHtml) {
            when (response.status.value) {
                401 -> throw ApiException("Session expired — log out and sign in again.")
                404 -> throw ApiException(
                    "API not found on $baseUrl (404). The server may need updating, or this stream no longer exists.",
                )
                else -> throw ApiException(
                    "Server returned a web page instead of JSON (${response.status.value}) from $baseUrl. " +
                        "Check the club server URL on the login screen and sign in again.",
                )
            }
        }

        return try {
            Json.parseToJsonElement(raw) as JsonObject
        } catch (_: Exception) {
            val snippet = if (raw.length > 120) raw.take(120) + "…" else raw
            throw ApiException("Invalid server response (${response.status.value}). $snippet")
        }
    }

    private suspend fun requireSuccess(response: HttpResponse, body: JsonObject, fallback: String) {
        if (!response.status.isSuccess()) {
            throw ApiException(body["error"]?.toString()?.trim('"') ?: fallback)
        }
    }

    suspend fun login(email: String, password: String): JsonObject {
        if (!isAllowedApiBaseUrl(baseUrl)) {
            throw ApiException("Server URL must use HTTPS (http only for local testing).")
        }
        val response = httpClient.post("$baseUrl/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("email", email)
                put("password", password)
            })
        }
        val body = parseJsonObject(response)
        requireSuccess(response, body, "Login failed")
        val newToken = body["token"]?.toString()?.trim('"')
            ?: throw ApiException("Login failed")
        updateSession(baseUrl, newToken)
        return body
    }

    suspend fun register(name: String, email: String, password: String, consent: Boolean = false): JsonObject {
        if (!isAllowedApiBaseUrl(baseUrl)) {
            throw ApiException("Server URL must use HTTPS (http only for local testing).")
        }
        val response = httpClient.post("$baseUrl/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("name", name)
                put("email", email)
                put("password", password)
                put("consent", consent)
            })
        }
        val body = parseJsonObject(response)
        requireSuccess(response, body, "Registration failed")
        val newToken = body["token"]?.toString()?.trim('"')
            ?: throw ApiException("Registration failed")
        updateSession(baseUrl, newToken)
        return body
    }

    suspend fun listStreams(): List<StreamMatch> {
        val response = httpClient.get("$baseUrl/api/streams") {
            authHeaders().forEach { (k, v) -> header(k, v) }
        }
        val body = parseJsonObject(response)
        requireSuccess(response, body, "Failed to load streams")
        return body.array("streams").mapNotNull { el ->
            (el as? JsonObject)?.let { obj -> StreamMatch.fromJson(obj, baseUrl) }
        }
    }

    suspend fun listFixtures(): FixturesResponse {
        val response = httpClient.get("$baseUrl/api/fixtures") {
            authHeaders().forEach { (k, v) -> header(k, v) }
        }
        val body = parseJsonObject(response)
        requireSuccess(response, body, "Failed to load fixtures")
        return FixturesResponse.fromJson(body)
    }

    suspend fun goLive(matchSlug: String, platform: String = "youtube"): GoLiveResult {
        val response = httpClient.post("$baseUrl/api/stream/go-live") {
            authHeaders().forEach { (k, v) -> header(k, v) }
            setBody(buildJsonObject {
                put("match_slug", matchSlug)
                put("platform", platform)
            })
        }
        val body = parseJsonObject(response)
        requireSuccess(response, body, "Go live failed")
        return GoLiveResult.fromJson(body)
    }

    suspend fun stopLive(platform: String? = null) {
        val payload = buildJsonObject {
            if (!platform.isNullOrBlank()) put("platform", platform)
        }
        val response = httpClient.post("$baseUrl/api/stream/stop") {
            authHeaders().forEach { (k, v) -> header(k, v) }
            if (payload.isNotEmpty()) setBody(payload)
        }
        if (!response.status.isSuccess()) {
            val body = parseJsonObject(response)
            throw ApiException(body["error"]?.toString()?.trim('"') ?: "Stop failed")
        }
    }

    suspend fun createPlayCricketStream(
        matchId: String,
        label: String = "",
        playCricketBaseUrl: String = "",
    ): StreamMatch {
        val response = httpClient.post("$baseUrl/api/streams") {
            authHeaders().forEach { (k, v) -> header(k, v) }
            setBody(buildJsonObject {
                put("type", "play_cricket")
                put("play_cricket_match_id", matchId)
                put("label", label)
                if (playCricketBaseUrl.isNotEmpty()) put("play_cricket_base_url", playCricketBaseUrl)
            })
        }
        val body = parseJsonObject(response)
        requireSuccess(response, body, "Could not create stream")
        val stream = body["stream"] as? JsonObject
            ?: throw ApiException("Could not create stream")
        return StreamMatch.fromJson(stream, baseUrl)
    }

    suspend fun createCricHeroesStream(matchUrl: String, label: String): StreamMatch {
        val response = httpClient.post("$baseUrl/api/streams") {
            authHeaders().forEach { (k, v) -> header(k, v) }
            setBody(buildJsonObject {
                put("type", "cricheroes")
                put("match_url", matchUrl)
                put("label", label)
            })
        }
        val body = parseJsonObject(response)
        requireSuccess(response, body, "Could not create stream")
        val stream = body["stream"] as? JsonObject
            ?: throw ApiException("Could not create stream")
        return StreamMatch.fromJson(stream, baseUrl)
    }

    suspend fun createManualStream(label: String = ""): StreamMatch {
        val response = httpClient.post("$baseUrl/api/streams") {
            authHeaders().forEach { (k, v) -> header(k, v) }
            setBody(buildJsonObject {
                put("type", "manual")
                put("label", label)
            })
        }
        val body = parseJsonObject(response)
        requireSuccess(response, body, "Could not create stream")
        val stream = body["stream"] as? JsonObject
            ?: throw ApiException("Could not create stream")
        return StreamMatch.fromJson(stream, baseUrl)
    }

    suspend fun getScorerLink(matchSlug: String): ScorerLink {
        return try {
            val response = httpClient.post(matchUri(matchSlug, "scorer-link")) {
                authHeaders().forEach { (k, v) -> header(k, v) }
            }
            val body = parseJsonObject(response)
            requireSuccess(response, body, "Failed to create scorer link")
            val url = body.string("scorer_url").orEmpty()
            if (url.isBlank()) throw ApiException("Scorer link missing from server response")
            ScorerLink(
                scorerUrl = resolveAbsoluteUrl(baseUrl, url),
                expiresAt = body.string("expires_at").orEmpty(),
            )
        } catch (e: ApiException) {
            // Servers without the mint endpoint 404 with an HTML page — fall
            // back to the static legacy scorer URL (blank expiry hides the
            // expiry caption in the QR UI).
            val msg = e.message.orEmpty()
            val fallbackable = msg.contains("(404)") ||
                msg.contains("web page instead of JSON") ||
                msg.contains("Invalid server response")
            if (fallbackable) ScorerLink(scorerUrl = getScoring(matchSlug).scorerUrl) else throw e
        }
    }

    suspend fun getScoring(matchSlug: String): ScoringConfig {
        val response = httpClient.get(matchUri(matchSlug, "scoring")) {
            authHeaders().forEach { (k, v) -> header(k, v) }
        }
        val body = parseJsonObject(response)
        requireSuccess(response, body, "Failed to load scoring mode")
        return ScoringConfig.fromJson(body, baseUrl)
    }

    suspend fun setScoring(matchSlug: String, mode: String, provider: String? = null): ScoringConfig {
        return try {
            val response = httpClient.post(matchUri(matchSlug, "scoring")) {
                authHeaders().forEach { (k, v) -> header(k, v) }
                setBody(buildJsonObject {
                    put("mode", mode)
                    if (!provider.isNullOrBlank()) put("provider", provider)
                })
            }
            val body = parseJsonObject(response)
            requireSuccess(response, body, "Failed to set scoring mode")
            ScoringConfig.fromJson(body, baseUrl)
        } catch (e: ApiException) {
            val msg = e.message.orEmpty()
            val htmlLike = msg.contains("web page instead of JSON") ||
                msg.contains("Invalid server response") ||
                msg.contains("(500)") || msg.contains("(502)") || msg.contains("(503)")
            if (htmlLike) ScoringConfig.localFallback(baseUrl, matchSlug, mode) else throw e
        }
    }

    suspend fun getMatchDayStatus(matchSlug: String): MatchDayStatus {
        val response = httpClient.get(matchUri(matchSlug, "match-day")) {
            authHeaders().forEach { (k, v) -> header(k, v) }
        }
        val body = parseJsonObject(response)
        requireSuccess(response, body, "Failed to load match status")
        return MatchDayStatus.fromJson(body)
    }

    suspend fun setRelayPause(matchSlug: String, paused: Boolean) {
        val response = httpClient.post(matchUri(matchSlug, "relay-pause")) {
            authHeaders().forEach { (k, v) -> header(k, v) }
            setBody(buildJsonObject { put("paused", paused) })
        }
        if (!response.status.isSuccess()) {
            val body = parseJsonObject(response)
            throw ApiException(body["error"]?.toString()?.trim('"') ?: "Failed to update relay pause")
        }
    }

    suspend fun youtubeStatus(): JsonObject {
        val response = httpClient.get("$baseUrl/api/stream/youtube-status") {
            authHeaders().forEach { (k, v) -> header(k, v) }
        }
        return parseJsonObject(response)
    }

    suspend fun twitchStatus(): JsonObject {
        val response = httpClient.get("$baseUrl/api/stream/twitch-status") {
            authHeaders().forEach { (k, v) -> header(k, v) }
        }
        return parseJsonObject(response)
    }

    suspend fun deleteStream(matchSlug: String) {
        val slug = encode(matchSlug)
        val response = httpClient.delete("$baseUrl/api/streams/$slug") {
            authHeaders().forEach { (k, v) -> header(k, v) }
        }
        if (!response.status.isSuccess()) {
            val body = parseJsonObject(response)
            throw ApiException(body["error"]?.toString()?.trim('"') ?: "Failed to delete stream")
        }
    }

    suspend fun renameStream(matchSlug: String, label: String) {
        val slug = encode(matchSlug)
        val response = httpClient.patch("$baseUrl/api/streams/$slug") {
            authHeaders().forEach { (k, v) -> header(k, v) }
            setBody(buildJsonObject { put("label", label) })
        }
        if (!response.status.isSuccess()) {
            val body = parseJsonObject(response)
            throw ApiException(body["error"]?.toString()?.trim('"') ?: "Failed to rename stream")
        }
    }

    suspend fun updateBroadcastStatus(
        matchSlug: String,
        status: String,
        platform: String? = null,
        watchUrl: String? = null,
    ) {
        val response = httpClient.post(matchUri(matchSlug, "broadcast-status")) {
            authHeaders().forEach { (k, v) -> header(k, v) }
            setBody(buildJsonObject {
                put("status", status)
                if (platform != null) put("platform", platform)
                if (watchUrl != null) put("watch_url", watchUrl)
            })
        }
        if (!response.status.isSuccess()) {
            val body = parseJsonObject(response)
            throw ApiException(body["error"]?.toString()?.trim('"') ?: "Failed to update broadcast status")
        }
    }

    /**
     * Measure usable upload throughput by timing a discarded POST to the club server. Returns
     * megabits/second, or null when the probe can't run (old server without the endpoint,
     * proxy body-size limit, offline). TCP slow start makes short probes read a little low —
     * that errs toward the conservative (lower-resolution) choice, which is the right bias
     * for a live broadcast.
     */
    suspend fun measureUploadMbps(probeBytes: Int = UPLOAD_PROBE_BYTES): Double? {
        val payload = ByteArray(probeBytes)
        return try {
            val mark = kotlin.time.TimeSource.Monotonic.markNow()
            val response = httpClient.post("$baseUrl/api/net-probe") {
                token?.takeIf { it.isNotBlank() }?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                contentType(ContentType.Application.OctetStream)
                setBody(payload)
            }
            val seconds = mark.elapsedNow().toDouble(kotlin.time.DurationUnit.SECONDS)
            if (!response.status.isSuccess() || seconds <= 0.0) return null
            probeBytes * 8.0 / seconds / 1_000_000.0
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getOverlayPrefs(matchSlug: String): OverlayLayoutPrefs {
        val response = httpClient.get(matchUri(matchSlug, "overlay")) {
            authHeaders().forEach { (k, v) -> header(k, v) }
        }
        val body = parseJsonObject(response)
        requireSuccess(response, body, "Failed to load overlay settings")
        return OverlayLayoutPrefs.fromJson(body)
    }

    suspend fun setOverlayPrefs(matchSlug: String, prefs: OverlayLayoutPrefs) {
        val response = httpClient.post(matchUri(matchSlug, "overlay")) {
            authHeaders().forEach { (k, v) -> header(k, v) }
            setBody(prefs.toJson())
        }
        val body = parseJsonObject(response)
        requireSuccess(response, body, "Failed to save overlay settings")
    }

    suspend fun listSponsors(): List<Sponsor> {
        val response = httpClient.get("$baseUrl/api/sponsors") {
            authHeaders().forEach { (k, v) -> header(k, v) }
        }
        val body = parseJsonObject(response)
        requireSuccess(response, body, "Failed to load sponsors")
        return body.array("sponsors").mapNotNull { el ->
            (el as? JsonObject)?.let { Sponsor.fromJson(it) }
        }
    }

    suspend fun pairRemote(matchSlug: String): PairRemoteResult {
        val response = httpClient.post(matchUri(matchSlug, "pair")) {
            authHeaders().forEach { (k, v) -> header(k, v) }
        }
        val body = parseJsonObject(response)
        requireSuccess(response, body, "Failed to create pairing code")
        val token = body.string("pair_token").orEmpty()
        if (token.isBlank()) throw ApiException("Pairing code missing from server response")
        return PairRemoteResult(
            pairToken = token,
            expiresAt = body.string("expires_at").orEmpty(),
        )
    }

    suspend fun pollRemoteCommands(matchSlug: String): List<RemoteCommand> {
        val response = httpClient.get(matchUri(matchSlug, "remote/commands")) {
            authHeaders().forEach { (k, v) -> header(k, v) }
        }
        val body = parseJsonObject(response)
        requireSuccess(response, body, "Failed to poll remote commands")
        return body.array("commands").mapNotNull { el ->
            (el as? JsonObject)?.let { RemoteCommand.fromJson(it) }
        }
    }

    suspend fun redeemPairToken(matchSlug: String, pairToken: String, apiBase: String = baseUrl): String {
        val slug = encode(matchSlug)
        val normalizedBase = normalizeApiBaseUrl(apiBase)
        val response = httpClient.post("$normalizedBase/stream/$slug/pair/redeem") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("pair_token", pairToken) })
        }
        val body = parseJsonObject(response)
        requireSuccess(response, body, "Failed to redeem pairing code")
        val companionToken = body.string("companion_token").orEmpty()
        if (companionToken.isBlank()) throw ApiException("Companion token missing from server response")
        return companionToken
    }

    suspend fun sendRemoteCommand(matchSlug: String, companionToken: String, command: String) {
        val response = httpClient.post(matchUri(matchSlug, "remote/command")) {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header(HttpHeaders.Authorization, "Bearer $companionToken")
            setBody(buildJsonObject {
                put("type", "control")
                put("command", command)
            })
        }
        if (!response.status.isSuccess()) {
            val body = parseJsonObject(response)
            throw ApiException(body["error"]?.toString()?.trim('"') ?: "Remote command failed")
        }
    }

    suspend fun sendRemoteOverlayPrefs(
        matchSlug: String,
        companionToken: String,
        prefs: OverlayLayoutPrefs,
    ) {
        val response = httpClient.post(matchUri(matchSlug, "remote/command")) {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header(HttpHeaders.Authorization, "Bearer $companionToken")
            setBody(buildJsonObject {
                put("type", "overlay")
                put("prefs", prefs.sponsorPatchJson())
            })
        }
        if (!response.status.isSuccess()) {
            val body = parseJsonObject(response)
            throw ApiException(body["error"]?.toString()?.trim('"') ?: "Remote overlay update failed")
        }
    }

    suspend fun getRemoteContext(matchSlug: String, companionToken: String): RemoteCompanionContext {
        val response = httpClient.get(matchUri(matchSlug, "remote/context")) {
            header(HttpHeaders.Authorization, "Bearer $companionToken")
        }
        val body = parseJsonObject(response)
        requireSuccess(response, body, "Failed to load remote context")
        return RemoteCompanionContext.fromJson(body)
    }

    suspend fun youtubeAuthorizeUrl(): String {
        val response = httpClient.get("$baseUrl/api/stream/youtube/authorize") {
            authHeaders().forEach { (k, v) -> header(k, v) }
        }
        val body = parseJsonObject(response)
        requireSuccess(response, body, "YouTube authorize failed")
        return body.string("authorize_url").orEmpty()
    }

    suspend fun twitchAuthorizeUrl(): String {
        val response = httpClient.get("$baseUrl/api/stream/twitch/authorize") {
            authHeaders().forEach { (k, v) -> header(k, v) }
        }
        val body = parseJsonObject(response)
        requireSuccess(response, body, "Twitch authorize failed")
        return body.string("authorize_url").orEmpty()
    }

    suspend fun youtubeDisconnect() {
        val response = httpClient.post("$baseUrl/api/stream/youtube-disconnect") {
            authHeaders().forEach { (k, v) -> header(k, v) }
        }
        if (!response.status.isSuccess()) {
            val body = parseJsonObject(response)
            throw ApiException(body["error"]?.toString()?.trim('"') ?: "YouTube disconnect failed")
        }
    }

    suspend fun twitchDisconnect() {
        val response = httpClient.post("$baseUrl/api/stream/twitch-disconnect") {
            authHeaders().forEach { (k, v) -> header(k, v) }
        }
        if (!response.status.isSuccess()) {
            val body = parseJsonObject(response)
            throw ApiException(body["error"]?.toString()?.trim('"') ?: "Twitch disconnect failed")
        }
    }

    suspend fun getAppBuilds(): JsonObject {
        val response = httpClient.get("$baseUrl/api/stream/app-builds") {
            authHeaders().forEach { (k, v) -> header(k, v) }
        }
        val body = parseJsonObject(response)
        requireSuccess(response, body, "Failed to load app downloads")
        return body
    }

    private fun matchUri(matchSlug: String, suffix: String): String {
        val slug = encode(matchSlug)
        return "$baseUrl/api/match/$slug/$suffix"
    }

    private fun encode(value: String): String =
        value.encodeToByteArray().let { bytes ->
            buildString {
                for (b in bytes) {
                    val c = b.toInt() and 0xFF
                    when {
                        c in 'a'.code..'z'.code ||
                            c in 'A'.code..'Z'.code ||
                            c in '0'.code..'9'.code ||
                            c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code -> append(c.toChar())
                        else -> {
                            append('%')
                            append(c.toString(16).uppercase().padStart(2, '0'))
                        }
                    }
                }
            }
        }
}
