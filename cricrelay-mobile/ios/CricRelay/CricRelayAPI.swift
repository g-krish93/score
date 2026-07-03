import Foundation
import Shared

/// Server/transport failure for the few endpoints still on URLSession below. The shared
/// Kotlin client raises ApiException with the same carefully-worded messages, which reach
/// Swift as NSError.localizedDescription — so both paths surface actionable text instead
/// of "The operation couldn't be completed".
struct APIError: LocalizedError {
    let statusCode: Int
    let message: String
    var errorDescription: String? { message }
}

extension Notification.Name {
    /// Posted (on the main thread) when a MAIN-token request comes back 401: the stored
    /// session token has expired server-side (14-day TTL, no refresh endpoint) and the user
    /// must sign in again. SessionViewModel observes this and drops back to the login screen.
    static let cricrelaySessionExpired = Notification.Name("uk.co.cricrelay.sessionExpired")
}

/// Thin adapter over the KMP shared `CricRelayApiClient` (ADR-001 item 3): one definition
/// of every endpoint, slug encoding, error mapping and 401 handling for both platforms.
/// The OverlayLayoutPrefs-carrying endpoints (overlay get/save, remote overlay/context,
/// command polling) stay on URLSession until OverlayLayoutPrefs itself migrates to the
/// shared model (ADR-001 item 2 remainder) — they are the only reason this file still has
/// request helpers of its own.
final class CricRelayAPI {
    static let shared = CricRelayAPI()

    private var client: CricRelayApiClient
    private let sessionStore = SessionStore()

    private init() {
        client = Self.newClient(baseUrl: "", token: nil)
        client.onSessionExpired = { [weak self] in self?.handleSessionExpired() }
    }

    private static func newClient(baseUrl: String, token: String?) -> CricRelayApiClient {
        CricRelayApiClient(
            httpClient: RepositoriesKt.defaultHttpClient(),
            baseUrl: baseUrl,
            token: token
        )
    }

    /// The Kotlin client takes base+token at construction (mirroring the shared
    /// AuthRepository), so changing session means swapping the client instance.
    private func installClient(baseUrl: String, token: String?) {
        client = Self.newClient(baseUrl: baseUrl, token: token)
        client.onSessionExpired = { [weak self] in self?.handleSessionExpired() }
    }

    var baseUrl: String { client.baseUrl }
    var token: String { client.token ?? "" }

    func configure(baseUrl: String, token: String) {
        installClient(baseUrl: baseUrl, token: token)
    }

    /// Drop the in-memory bearer token on sign-out — Keychain deletion alone leaves any
    /// still-running task authenticated as the signed-out user.
    func clearToken() {
        client.clearToken()
    }

    // Percent-encode a value interpolated into a URL path segment (URLSession endpoints
    // below only — the shared client encodes slugs itself).
    private static let pathSegmentAllowed: CharacterSet = {
        var set = CharacterSet.urlPathAllowed
        set.remove(charactersIn: "/?#")
        return set
    }()

    private func enc(_ segment: String) -> String {
        segment.addingPercentEncoding(withAllowedCharacters: Self.pathSegmentAllowed) ?? segment
    }

    // MARK: - Auth

    func login(email: String, password: String, baseUrl: String) async throws {
        installClient(baseUrl: baseUrl, token: nil)
        _ = try await client.login(email: email, password: password)
    }

    func register(name: String, email: String, password: String, consent: Bool, baseUrl: String) async throws {
        installClient(baseUrl: baseUrl, token: nil)
        _ = try await client.register(name: name, email: email, password: password, consent: consent)
    }

    // MARK: - Streams

    func listStreams() async throws -> [StreamMatch] {
        try await client.listStreams()
    }

    func listFixtures() async throws -> FixturesResponse {
        try await client.listFixtures()
    }

    func createPlayCricketStream(matchId: String, label: String) async throws -> StreamMatch {
        try await client.createPlayCricketStream(matchId: matchId, label: label, playCricketBaseUrl: "")
    }

    func createCricHeroesStream(matchUrl: String, label: String) async throws -> StreamMatch {
        try await client.createCricHeroesStream(matchUrl: matchUrl, label: label)
    }

    func deleteStream(slug: String) async throws {
        try await client.deleteStream(matchSlug: slug)
    }

    func renameStream(slug: String, label: String) async throws {
        try await client.renameStream(matchSlug: slug, label: label)
    }

    // MARK: - Match day

    func matchDay(slug: String) async throws -> MatchDayStatus {
        try await client.getMatchDayStatus(matchSlug: slug)
    }

    func scoringConfig(slug: String) async throws -> ScoringConfig {
        try await client.getScoring(matchSlug: slug)
    }

    func setScoringMode(slug: String, mode: String, provider: String? = nil) async throws -> ScoringConfig {
        try await client.setScoring(matchSlug: slug, mode: mode, provider: provider)
    }

    func updateBroadcastStatus(slug: String, status: String, platform: String? = nil, watchUrl: String? = nil) async throws {
        try await client.updateBroadcastStatus(matchSlug: slug, status: status, platform: platform, watchUrl: watchUrl)
    }

    // MARK: - Broadcast

    func goLive(matchSlug: String, platform: String) async throws -> GoLiveResult {
        try await client.goLive(matchSlug: matchSlug, platform: platform)
    }

    func stopLive(platform: String? = nil) async throws {
        try await client.stopLive(platform: platform)
    }

    // MARK: - Platforms

    func youtubeStatus() async throws -> PlatformStatus {
        // The shared mapping also honors live_streaming_enabled — the old Swift duplicate didn't.
        let json = try await client.youtubeStatus()
        return PlatformStatus.companion.fromYoutube(json: json)
    }

    func twitchStatus() async throws -> PlatformStatus {
        let json = try await client.twitchStatus()
        return PlatformStatus.companion.fromTwitch(json: json)
    }

    func youtubeAuthorizeUrl() async throws -> String {
        try await client.youtubeAuthorizeUrl()
    }

    func twitchAuthorizeUrl() async throws -> String {
        try await client.twitchAuthorizeUrl()
    }

    func disconnectYoutube() async throws {
        try await client.youtubeDisconnect()
    }

    func disconnectTwitch() async throws {
        try await client.twitchDisconnect()
    }

    // MARK: - Sponsors

    func listSponsors() async throws -> [Sponsor] {
        try await client.listSponsors()
    }

    // MARK: - Remote control

    func pairRemote(slug: String) async throws -> PairRemoteResult {
        try await client.pairRemote(matchSlug: slug)
    }

    func redeemPairToken(slug: String, pairToken: String) async throws -> CompanionSession {
        let companionToken = try await client.redeemPairToken(
            matchSlug: slug,
            pairToken: pairToken,
            apiBase: client.baseUrl
        )
        return CompanionSession(companionToken: companionToken, matchSlug: slug)
    }

    func sendRemoteCommand(slug: String, command: String, companionToken: String) async throws {
        try await client.sendRemoteCommand(matchSlug: slug, companionToken: companionToken, command: command)
    }

    // MARK: - URLSession endpoints (OverlayLayoutPrefs still lives in Swift)

    func overlayPrefs(slug: String) async throws -> OverlayLayoutPrefs {
        let json = try await getJson("/api/match/\(enc(slug))/overlay")
        let data = try JSONSerialization.data(withJSONObject: json)
        return (try? JSONDecoder().decode(OverlayLayoutPrefs.self, from: data)) ?? OverlayLayoutPrefs()
    }

    func saveOverlayPrefs(slug: String, prefs: OverlayLayoutPrefs) async throws -> OverlayLayoutPrefs {
        let encoder = JSONEncoder()
        encoder.keyEncodingStrategy = .convertToSnakeCase
        let data = try encoder.encode(prefs)
        let body = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        let json = try await postJson("/api/match/\(enc(slug))/overlay", body: body)
        let responseData = try JSONSerialization.data(withJSONObject: json)
        return (try? JSONDecoder().decode(OverlayLayoutPrefs.self, from: responseData)) ?? prefs
    }

    func pollRemoteCommands(slug: String) async throws -> [RemoteCommand] {
        let json = try await getJson("/api/match/\(enc(slug))/remote/commands")
        guard let rows = json["commands"] as? [[String: Any]] else { return [] }
        return rows.map { RemoteCommand.from($0) }
    }

    func sendRemoteOverlayPrefs(slug: String, prefs: OverlayLayoutPrefs, companionToken: String) async throws {
        _ = try await postJsonWithToken(
            "/api/match/\(enc(slug))/remote/command",
            body: ["type": "overlay", "prefs": prefs.sponsorPatchDictionary()],
            token: companionToken
        )
    }

    func getRemoteContext(slug: String, companionToken: String) async throws -> RemoteCompanionContext {
        let json = try await getJsonWithToken("/api/match/\(enc(slug))/remote/context", token: companionToken)
        var prefs = OverlayLayoutPrefs()
        if let patch = json["sponsor_prefs"] as? [String: Any] {
            prefs = prefs.mergeSponsorPatch(patch)
        }
        let sponsorRows = json["sponsors"] as? [[String: Any]] ?? []
        // Hand-mapped: Sponsor is the KMP shared model now, so it can't be JSONDecoder-decoded.
        let sponsors: [Sponsor] = sponsorRows.map { row in
            Sponsor(
                id: row["id"] as? String ?? "",
                name: row["name"] as? String ?? "",
                logoUrl: row["logo_url"] as? String,
                linkUrl: row["link_url"] as? String,
                isActive: row["is_active"] as? Bool ?? true
            )
        }
        return RemoteCompanionContext(
            sponsorPrefs: prefs,
            sponsors: sponsors,
            watchUrl: json["watch_url"] as? String ?? ""
        )
    }

    // MARK: - Request helpers (URLSession endpoints only)

    private static let pairingExpiredHint = "Pairing expired — scan the QR code on the broadcast phone again."

    /// Shared success check: 2xx passes; 401 maps to a session-expired hint; anything else
    /// surfaces the server's own `error` message. `sessionAuth` is true only for requests
    /// that attached the MAIN bearer token — mirrors the shared client's requireSuccess.
    private func checkResponse(
        _ response: URLResponse,
        json: [String: Any],
        unauthorizedHint: String = "Session expired — sign out and sign back in.",
        sessionAuth: Bool = true
    ) throws {
        guard let http = response as? HTTPURLResponse else {
            throw URLError(.badServerResponse)
        }
        guard !(200..<300).contains(http.statusCode) else { return }
        if http.statusCode == 401 {
            if sessionAuth { handleSessionExpired() }
            throw APIError(statusCode: 401, message: unauthorizedHint)
        }
        let message = json["error"] as? String ?? "Request failed (\(http.statusCode))"
        throw APIError(statusCode: http.statusCode, message: message)
    }

    /// Clear the dead session exactly the way logout does (Keychain via the shared
    /// SessionStore + in-memory token), then notify SessionViewModel on the main thread so
    /// RootView swaps to LoginView instead of rendering a silently empty dashboard off a
    /// stale token. Reached from both transports: the shared client's onSessionExpired
    /// callback and checkResponse above.
    private func handleSessionExpired() {
        client.clearToken()
        Task { try? await self.sessionStore.clearToken() }
        DispatchQueue.main.async {
            NotificationCenter.default.post(name: .cricrelaySessionExpired, object: nil)
        }
    }

    @discardableResult
    private func getJson(_ path: String) async throws -> [String: Any] {
        guard let url = URL(string: "\(baseUrl)\(path)") else { throw URLError(.badURL) }
        var request = URLRequest(url: url)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (data, response) = try await URLSession.shared.data(for: request)
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        try checkResponse(response, json: json)
        return json
    }

    @discardableResult
    private func postJson(_ path: String, body: [String: Any]) async throws -> [String: Any] {
        guard let url = URL(string: "\(baseUrl)\(path)") else { throw URLError(.badURL) }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, response) = try await URLSession.shared.data(for: request)
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        try checkResponse(response, json: json)
        return json
    }

    private func getJsonWithToken(_ path: String, token: String) async throws -> [String: Any] {
        guard let url = URL(string: "\(baseUrl)\(path)") else { throw URLError(.badURL) }
        var request = URLRequest(url: url)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (data, response) = try await URLSession.shared.data(for: request)
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        // A companion 401 means the pairing lapsed, not the user session.
        try checkResponse(response, json: json, unauthorizedHint: Self.pairingExpiredHint, sessionAuth: false)
        return json
    }

    @discardableResult
    private func postJsonWithToken(
        _ path: String,
        body: [String: Any],
        token: String
    ) async throws -> [String: Any] {
        guard let url = URL(string: "\(baseUrl)\(path)") else { throw URLError(.badURL) }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, response) = try await URLSession.shared.data(for: request)
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        try checkResponse(response, json: json, unauthorizedHint: Self.pairingExpiredHint, sessionAuth: false)
        return json
    }
}
