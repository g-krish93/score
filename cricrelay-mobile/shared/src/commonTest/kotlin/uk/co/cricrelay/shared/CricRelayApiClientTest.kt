package uk.co.cricrelay.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.cricrelay.shared.api.ApiException
import uk.co.cricrelay.shared.api.CricRelayApiClient

private const val BASE = "https://club.example.com"

/**
 * [CricRelayApiClient] against Ktor's MockEngine: token/session handling, the HTML-error-page
 * detection that guards against proxies and stale servers, `error`-field extraction, the
 * setScoring 5xx local fallback, and slug URL-encoding.
 */
class CricRelayApiClientTest {

    private fun client(
        token: String? = null,
        baseUrl: String = BASE,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): CricRelayApiClient {
        val http = HttpClient(MockEngine { request -> handler(request) }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
        return CricRelayApiClient(http, baseUrl, token)
    }

    private fun MockRequestHandleScope.jsonResponse(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpResponseData = respond(
        content = body,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private fun MockRequestHandleScope.htmlResponse(
        status: HttpStatusCode,
        body: String = "<!DOCTYPE html><html><body>Error</body></html>",
    ): HttpResponseData = respond(
        content = body,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "text/html"),
    )

    // ── login / session ─────────────────────────────────────────────────────

    @Test
    fun `login extracts token and installs it on the session`() = runTest {
        val api = client { request ->
            assertEquals("$BASE/api/auth/login", request.url.toString())
            jsonResponse("""{"token":"tok-123","name":"Club"}""")
        }
        assertFalse(api.hasToken)

        val body = api.login("a@b.com", "pw")

        assertTrue(api.hasToken)
        assertEquals("tok-123", api.token)
        assertEquals("\"Club\"", body["name"].toString())
    }

    @Test
    fun `login without token in response fails`() = runTest {
        val api = client { jsonResponse("""{"ok":true}""") }
        val e = runCatching { api.login("a@b.com", "pw") }.exceptionOrNull()
        assertTrue(e is ApiException)
        assertEquals("Login failed", e?.message)
        assertFalse(api.hasToken)
    }

    @Test
    fun `login surfaces the server error field`() = runTest {
        val api = client { jsonResponse("""{"error":"Bad credentials"}""", HttpStatusCode.Unauthorized) }
        val e = runCatching { api.login("a@b.com", "wrong") }.exceptionOrNull()
        assertTrue(e is ApiException)
        assertEquals("Bad credentials", e?.message)
    }

    @Test
    fun `login refuses a plain http base url`() = runTest {
        val api = client(baseUrl = "http://club.example.com") {
            jsonResponse("""{"token":"t"}""")
        }
        val e = runCatching { api.login("a@b.com", "pw") }.exceptionOrNull()
        assertTrue(e is ApiException)
        assertTrue(e!!.message!!.contains("HTTPS"))
    }

    // ── HTML-error-page detection ───────────────────────────────────────────

    @Test
    fun `html 401 maps to session expired`() = runTest {
        var expired = false
        val api = client(token = "stale") { htmlResponse(HttpStatusCode.Unauthorized) }
        api.onSessionExpired = { expired = true }
        val e = runCatching { api.listStreams() }.exceptionOrNull()
        assertTrue(e is ApiException)
        // Wording unified with the app-facing hint (ADR-001 item 3).
        assertEquals("Session expired — sign out and sign back in.", e?.message)
        // A main-token 401 also drops the in-memory token and notifies the host app.
        assertNull(api.token)
        assertTrue(expired)
    }

    @Test
    fun `html 404 maps to api-not-found`() = runTest {
        val api = client(token = "t") { htmlResponse(HttpStatusCode.NotFound) }
        val e = runCatching { api.listStreams() }.exceptionOrNull()
        assertTrue(e is ApiException)
        assertTrue(e!!.message!!.contains("API not found on $BASE (404)"))
    }

    @Test
    fun `html error page from a proxy names the status and server`() = runTest {
        val api = client(token = "t") { htmlResponse(HttpStatusCode.BadGateway) }
        val e = runCatching { api.listStreams() }.exceptionOrNull()
        assertTrue(e is ApiException)
        assertTrue(e!!.message!!.contains("web page instead of JSON (502)"))
        assertTrue(e.message!!.contains(BASE))
    }

    @Test
    fun `html detection also triggers on non-json content type with angle bracket body`() = runTest {
        // No <!doctype / <html prefix, but a "<" body served as text/plain — still a web page.
        val api = client(token = "t") {
            respond(
                content = "<h1>Maintenance</h1>",
                status = HttpStatusCode.ServiceUnavailable,
                headers = headersOf(HttpHeaders.ContentType, "text/plain"),
            )
        }
        val e = runCatching { api.listStreams() }.exceptionOrNull()
        assertTrue(e is ApiException)
        assertTrue(e!!.message!!.contains("web page instead of JSON (503)"))
    }

    @Test
    fun `unparseable body maps to invalid server response with snippet`() = runTest {
        val api = client(token = "t") { jsonResponse("not json at all") }
        val e = runCatching { api.listStreams() }.exceptionOrNull()
        assertTrue(e is ApiException)
        assertTrue(e!!.message!!.contains("Invalid server response (200)"))
        assertTrue(e.message!!.contains("not json at all"))
    }

    // ── error field extraction on non-auth endpoints ────────────────────────

    @Test
    fun `goLive surfaces the server error field over the generic fallback`() = runTest {
        val api = client(token = "t") {
            jsonResponse("""{"error":"No stream key configured"}""", HttpStatusCode.BadRequest)
        }
        val e = runCatching { api.goLive("village-vs-town") }.exceptionOrNull()
        assertTrue(e is ApiException)
        assertEquals("No stream key configured", e?.message)
    }

    @Test
    fun `goLive without error field falls back to generic message`() = runTest {
        val api = client(token = "t") { jsonResponse("""{}""", HttpStatusCode.BadRequest) }
        val e = runCatching { api.goLive("village-vs-town") }.exceptionOrNull()
        assertTrue(e is ApiException)
        assertEquals("Go live failed", e?.message)
    }

    // ── setScoring 5xx local fallback ───────────────────────────────────────

    @Test
    fun `setScoring falls back to local config when the server 500s with html`() = runTest {
        val api = client(token = "t") { htmlResponse(HttpStatusCode.InternalServerError) }

        val config = api.setScoring("village-vs-town", "manual")

        assertEquals("manual", config.mode)
        assertEquals("$BASE/m/village-vs-town/input", config.manualInputUrl)
        assertEquals("$BASE/m/village-vs-town/score", config.manualScorerUrl)
        assertEquals("$BASE/relay/pcs-ingest?match=village-vs-town", config.pcsIngestUrl)
    }

    @Test
    fun `setScoring falls back on an invalid 502 body`() = runTest {
        val api = client(token = "t") { htmlResponse(HttpStatusCode.BadGateway) }
        val config = api.setScoring("village-vs-town", "auto")
        assertEquals("auto", config.mode)
        assertEquals("$BASE/m/village-vs-town/score", config.scorerUrl)
    }

    @Test
    fun `setScoring does not swallow a genuine validation error`() = runTest {
        val api = client(token = "t") {
            jsonResponse("""{"error":"Unknown scoring mode"}""", HttpStatusCode.BadRequest)
        }
        val e = runCatching { api.setScoring("village-vs-town", "bogus") }.exceptionOrNull()
        assertTrue(e is ApiException)
        assertEquals("Unknown scoring mode", e?.message)
    }

    @Test
    fun `setScoring parses a healthy server response`() = runTest {
        val api = client(token = "t") {
            jsonResponse(
                """{"mode":"auto","manual_input_url":"/m/x/input","pcs_ingest_url":"/relay/pcs-ingest?match=x"}""",
            )
        }
        val config = api.setScoring("x", "auto")
        assertEquals("auto", config.mode)
        // Relative URLs come back resolved against the club server base.
        assertEquals("$BASE/m/x/input", config.manualInputUrl)
        assertEquals("$BASE/relay/pcs-ingest?match=x", config.pcsIngestUrl)
    }

    // ── slug encoding ───────────────────────────────────────────────────────

    @Test
    fun `match slugs are percent-encoded in the request path`() = runTest {
        var requestedUrl = ""
        val api = client(token = "t") { request ->
            requestedUrl = request.url.toString()
            jsonResponse("""{"mode":"manual"}""")
        }

        api.getScoring("t20 friendly/2nd XI")

        assertEquals("$BASE/api/match/t20%20friendly%2F2nd%20XI/scoring", requestedUrl)
    }

    @Test
    fun `unreserved slug characters pass through unencoded`() = runTest {
        var requestedUrl = ""
        val api = client(token = "t") { request ->
            requestedUrl = request.url.toString()
            jsonResponse("""{"slug":"a-b_c.d~e","label":"x"}""")
        }

        api.getMatchDayStatus("a-b_c.d~e")

        assertEquals("$BASE/api/match/a-b_c.d~e/match-day", requestedUrl)
    }

    @Test
    fun `deleteStream encodes the slug too`() = runTest {
        var requestedUrl = ""
        val api = client(token = "t") { request ->
            requestedUrl = request.url.toString()
            jsonResponse("""{"ok":true}""")
        }

        api.deleteStream("sunday cup #3")

        assertEquals("$BASE/api/streams/sunday%20cup%20%233", requestedUrl)
    }

    // ── auth header plumbing ────────────────────────────────────────────────

    @Test
    fun `bearer token rides on authenticated calls and clearToken removes it`() = runTest {
        var authHeader: String? = null
        val api = client(token = "tok-9") { request ->
            authHeader = request.headers[HttpHeaders.Authorization]
            jsonResponse("""{"streams":[]}""")
        }

        api.listStreams()
        assertEquals("Bearer tok-9", authHeader)

        api.clearToken()
        assertNull(api.token)
        api.listStreams()
        assertNull(authHeader)
    }
}
