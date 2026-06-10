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
import uk.co.cricrelay.shared.model.ScoringConfig
import uk.co.cricrelay.shared.model.StreamMatch
import uk.co.cricrelay.shared.model.array
import uk.co.cricrelay.shared.model.string
import uk.co.cricrelay.shared.util.isAllowedApiBaseUrl
import uk.co.cricrelay.shared.util.normalizeApiBaseUrl

class ApiException(message: String) : Exception(message)

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

    suspend fun createPcsBleStream(label: String): StreamMatch {
        val response = httpClient.post("$baseUrl/api/streams") {
            authHeaders().forEach { (k, v) -> header(k, v) }
            setBody(buildJsonObject {
                put("type", "pcs_ble")
                put("label", label)
            })
        }
        val body = parseJsonObject(response)
        requireSuccess(response, body, "Could not create stream")
        val stream = body["stream"] as? JsonObject
            ?: throw ApiException("Could not create stream")
        return StreamMatch.fromJson(stream, baseUrl)
    }

    suspend fun getScoring(matchSlug: String): ScoringConfig {
        val response = httpClient.get(matchUri(matchSlug, "scoring")) {
            authHeaders().forEach { (k, v) -> header(k, v) }
        }
        val body = parseJsonObject(response)
        requireSuccess(response, body, "Failed to load scoring mode")
        return ScoringConfig.fromJson(body, baseUrl)
    }

    suspend fun setScoring(matchSlug: String, mode: String): ScoringConfig {
        return try {
            val response = httpClient.post(matchUri(matchSlug, "scoring")) {
                authHeaders().forEach { (k, v) -> header(k, v) }
                setBody(buildJsonObject { put("mode", mode) })
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
