import Foundation

/// Server/transport failure carrying the exact message the server sent (parity with the Kotlin
/// client, which reads the JSON `error` field and maps 401 to a session-expired hint) — so
/// failures surface as actionable text instead of "The operation couldn't be completed".
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

final class CricRelayAPI {
    static let shared = CricRelayAPI()
    private init() {}

    private(set) var baseUrl = ""
    private(set) var token = ""

    func configure(baseUrl: String, token: String) {
        self.baseUrl = baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        self.token = token
    }

    /// Drop the in-memory bearer token on sign-out — Keychain deletion alone leaves any
    /// still-running task authenticated as the signed-out user.
    func clearToken() {
        token = ""
    }

    // Percent-encode a value interpolated into a URL path segment. Slugs reach this client from
    // scanned QR codes and server data — an unencoded "/", "?" or space must not change the route
    // (the Kotlin client encodes every slug for the same reason).
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
        self.baseUrl = baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let json = try await postJson("/api/auth/login", body: ["email": email, "password": password], auth: false)
        guard let newToken = json["token"] as? String else { throw URLError(.badServerResponse) }
        token = newToken
    }

    /// Returns the Play-Cricket site the server linked to the new account — empty when none,
    /// in which case the UI nudges the user to link one so fixtures appear.
    @discardableResult
    func register(
        name: String,
        email: String,
        password: String,
        consent: Bool,
        baseUrl: String,
        playCricketBaseUrl: String = ""
    ) async throws -> String {
        self.baseUrl = baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        var body: [String: Any] = [
            "name": name, "email": email, "password": password, "consent": consent
        ]
        let club = playCricketBaseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        if !club.isEmpty { body["play_cricket_base_url"] = club }
        let json = try await postJson("/api/auth/register", body: body, auth: false, expectedStatus: 201)
        guard let newToken = json["token"] as? String else { throw URLError(.badServerResponse) }
        token = newToken
        return json["play_cricket_base_url"] as? String ?? ""
    }

    /// Link (or replace) the account's Play-Cricket club site. Accepts the short club code
    /// ("bmacc") or a full site URL — the server normalizes it and responds 400 with a clear
    /// message when the club isn't recognised. Returns the normalized site URL the server stored.
    func updateAccount(playCricketBaseUrl: String) async throws -> String {
        let json = try await sendPatch("/api/auth/account", body: [
            "play_cricket_base_url": playCricketBaseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        ])
        return json["play_cricket_base_url"] as? String ?? ""
    }

    // MARK: - Streams

    func listStreams() async throws -> [StreamMatch] {
        let json = try await getJson("/api/streams")
        guard let rows = json["streams"] as? [[String: Any]] else { return [] }
        let data = try JSONSerialization.data(withJSONObject: rows)
        return (try? JSONDecoder().decode([StreamMatch].self, from: data)) ?? []
    }

    func listFixtures() async throws -> FixturesResponse {
        let json = try await getJson("/api/fixtures")
        let data = try JSONSerialization.data(withJSONObject: json)
        return try JSONDecoder().decode(FixturesResponse.self, from: data)
    }

    func createStream(type: String, matchId: String? = nil, matchUrl: String? = nil, label: String) async throws -> StreamMatch {
        var body: [String: Any] = ["type": type, "label": label]
        if let matchId { body["play_cricket_match_id"] = matchId }
        if let matchUrl { body["match_url"] = matchUrl }
        let json = try await postJson("/api/streams", body: body)
        guard let streamJson = json["stream"] as? [String: Any] else { throw URLError(.badServerResponse) }
        let data = try JSONSerialization.data(withJSONObject: streamJson)
        return try JSONDecoder().decode(StreamMatch.self, from: data)
    }

    func deleteStream(slug: String) async throws {
        try await sendDelete("/api/streams/\(enc(slug))")
    }

    /// PATCH returns only `stream: {slug, label}` — not a full StreamMatch — so don't decode one.
    func renameStream(slug: String, label: String) async throws {
        let json = try await sendPatch("/api/streams/\(enc(slug))", body: ["label": label])
        guard json["stream"] is [String: Any] else { throw URLError(.badServerResponse) }
    }

    // MARK: - Match day

    func matchDay(slug: String) async throws -> MatchDayStatus {
        let json = try await getJson("/api/match/\(enc(slug))/match-day")
        let data = try JSONSerialization.data(withJSONObject: json)
        return try JSONDecoder().decode(MatchDayStatus.self, from: data)
    }

    func scoringConfig(slug: String) async throws -> ScoringConfig {
        let json = try await getJson("/api/match/\(enc(slug))/scoring")
        let data = try JSONSerialization.data(withJSONObject: json)
        return try JSONDecoder().decode(ScoringConfig.self, from: data)
    }

    func setScoringMode(slug: String, mode: String, provider: String? = nil) async throws -> ScoringConfig {
        var body: [String: Any] = ["mode": mode]
        if let provider { body["provider"] = provider }
        let json = try await postJson("/api/match/\(enc(slug))/scoring", body: body)
        let data = try JSONSerialization.data(withJSONObject: json)
        return try JSONDecoder().decode(ScoringConfig.self, from: data)
    }

    func setRelayPaused(slug: String, paused: Bool) async throws {
        _ = try await postJson("/api/match/\(enc(slug))/relay-pause", body: ["paused": paused])
    }

    func updateBroadcastStatus(slug: String, status: String, platform: String? = nil, watchUrl: String? = nil) async throws {
        var body: [String: Any] = ["status": status]
        if let platform { body["platform"] = platform }
        if let watchUrl { body["watch_url"] = watchUrl }
        _ = try await postJson("/api/match/\(enc(slug))/broadcast-status", body: body)
    }

    // MARK: - Broadcast

    func goLive(matchSlug: String, platform: String) async throws -> GoLiveResult {
        let json = try await postJson("/api/stream/go-live", body: ["match_slug": matchSlug, "platform": platform])
        let data = try JSONSerialization.data(withJSONObject: json)
        return try JSONDecoder().decode(GoLiveResult.self, from: data)
    }

    func stopLive(platform: String? = nil) async throws {
        var body: [String: Any] = [:]
        if let platform { body["platform"] = platform }
        _ = try await postJson("/api/stream/stop", body: body)
    }

    // MARK: - Saved RTMP destinations

    func listDestinations() async throws -> [SavedRtmpDestination] {
        let json = try await getJson("/api/stream/destinations")
        guard let arr = json["destinations"] as? [[String: Any]] else { return [] }
        let data = try JSONSerialization.data(withJSONObject: arr)
        return (try? JSONDecoder().decode([SavedRtmpDestination].self, from: data)) ?? []
    }

    func getDestination(id: String) async throws -> SavedRtmpDestination {
        let json = try await getJson("/api/stream/destinations/\(enc(id))")
        guard let dest = json["destination"] as? [String: Any] else { throw URLError(.badServerResponse) }
        let data = try JSONSerialization.data(withJSONObject: dest)
        return try JSONDecoder().decode(SavedRtmpDestination.self, from: data)
    }

    func createDestination(label: String, rtmpUrl: String, streamKey: String, watchUrl: String = "") async throws -> SavedRtmpDestination {
        var body: [String: Any] = [
            "label": label,
            "rtmp_url": rtmpUrl,
            "stream_key": streamKey,
        ]
        if !watchUrl.isEmpty { body["watch_url"] = watchUrl }
        let json = try await postJson("/api/stream/destinations", body: body)
        guard let dest = json["destination"] as? [String: Any] else { throw URLError(.badServerResponse) }
        let data = try JSONSerialization.data(withJSONObject: dest)
        return try JSONDecoder().decode(SavedRtmpDestination.self, from: data)
    }

    func assignStreamDestination(slug: String, destinationId: String?) async throws {
        let body: [String: Any]
        if let destinationId, !destinationId.isEmpty {
            body = ["stream_destination_id": destinationId]
        } else {
            body = ["stream_destination_id": NSNull()]
        }
        _ = try await sendPatch("/api/streams/\(enc(slug))", body: body)
    }

    // MARK: - Platforms

    func youtubeStatus() async throws -> PlatformStatus {
        let json = try await getJson("/api/stream/youtube-status")
        return PlatformStatus(
            connected: json["connected"] as? Bool ?? false,
            ready: json["ready"] as? Bool ?? false,
            label: json["channel_title"] as? String ?? json["label"] as? String ?? ""
        )
    }

    func twitchStatus() async throws -> PlatformStatus {
        let json = try await getJson("/api/stream/twitch-status")
        return PlatformStatus(
            connected: json["connected"] as? Bool ?? false,
            ready: json["ready"] as? Bool ?? false,
            label: json["display_name"] as? String ?? json["label"] as? String ?? ""
        )
    }

    func youtubeAuthorizeUrl() async throws -> String {
        let json = try await getJson("/api/stream/youtube/authorize")
        return json["authorize_url"] as? String ?? ""
    }

    func twitchAuthorizeUrl() async throws -> String {
        let json = try await getJson("/api/stream/twitch/authorize")
        return json["authorize_url"] as? String ?? ""
    }

    func disconnectYoutube() async throws {
        _ = try await postJson("/api/stream/youtube-disconnect", body: [:])
    }

    func disconnectTwitch() async throws {
        _ = try await postJson("/api/stream/twitch-disconnect", body: [:])
    }

    // MARK: - Overlay

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

    // MARK: - Sponsors

    func listSponsors() async throws -> [Sponsor] {
        let json = try await getJson("/api/sponsors")
        guard let rows = json["sponsors"] as? [[String: Any]] else { return [] }
        let data = try JSONSerialization.data(withJSONObject: rows)
        return (try? JSONDecoder().decode([Sponsor].self, from: data)) ?? []
    }

    // MARK: - Remote control

    func pairRemote(slug: String) async throws -> PairRemoteResult {
        let json = try await postJson("/api/match/\(enc(slug))/pair", body: [:])
        guard let token = json["pair_token"] as? String else { throw URLError(.badServerResponse) }
        return PairRemoteResult(
            pairToken: token,
            expiresAt: json["expires_at"] as? String
        )
    }

    func scorerLink(slug: String) async throws -> ScorerLink {
        do {
            let json = try await postJson("/api/match/\(enc(slug))/scorer-link", body: [:])
            guard let url = json["scorer_url"] as? String, !url.isEmpty else {
                throw URLError(.badServerResponse)
            }
            return ScorerLink(scorerUrl: url, expiresAt: json["expires_at"] as? String)
        } catch let e as APIError where e.statusCode == 404 {
            // Older server without the mint endpoint — fall back to the static
            // legacy scorer URL (nil expiry hides the expiry caption).
            let config = try await scoringConfig(slug: slug)
            return ScorerLink(scorerUrl: config.scorerUrl, expiresAt: nil)
        }
    }

    func pollRemoteCommands(slug: String) async throws -> [RemoteCommand] {
        let json = try await getJson("/api/match/\(enc(slug))/remote/commands")
        guard let rows = json["commands"] as? [[String: Any]] else { return [] }
        return rows.map { RemoteCommand.from($0) }
    }

    func redeemPairToken(slug: String, pairToken: String) async throws -> CompanionSession {
        let json = try await postJson(
            "/stream/\(enc(slug))/pair/redeem",
            body: ["pair_token": pairToken],
            auth: false
        )
        guard let token = json["companion_token"] as? String else { throw URLError(.badServerResponse) }
        return CompanionSession(
            companionToken: token,
            matchSlug: json["match_slug"] as? String ?? slug
        )
    }

    func sendRemoteCommand(slug: String, command: String, companionToken: String) async throws {
        _ = try await postJsonWithToken(
            "/api/match/\(enc(slug))/remote/command",
            body: ["type": "control", "command": command],
            token: companionToken
        )
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
        let sponsors: [Sponsor] = sponsorRows.compactMap { row in
            guard let data = try? JSONSerialization.data(withJSONObject: row),
                  let sponsor = try? JSONDecoder().decode(Sponsor.self, from: data) else { return nil }
            return sponsor
        }
        return RemoteCompanionContext(
            sponsorPrefs: prefs,
            sponsors: sponsors,
            watchUrl: json["watch_url"] as? String ?? ""
        )
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

    private static let pairingExpiredHint = "Pairing expired — scan the QR code on the broadcast phone again."

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

    // MARK: - Request helpers

    /// Shared success check: 2xx passes; 401 maps to a session-expired hint; anything else
    /// surfaces the server's own `error` message (the Kotlin client does both, and the server
    /// sends precise reasons like "YouTube OAuth is not configured…").
    ///
    /// `sessionAuth` is true only for requests that attached the MAIN bearer token. A 401 on
    /// those means the stored session token expired (14-day TTL, no refresh endpoint), so the
    /// token is cleared and the session layer told to fall back to the login screen. Requests
    /// with `auth: false` (login/register — a wrong password is a 401 too) and companion-token
    /// requests must never trip that path.
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

    /// Clear the dead session exactly the way logout does (Keychain entry + in-memory token),
    /// then notify SessionViewModel on the main thread so RootView swaps to LoginView instead
    /// of rendering a silently empty dashboard off a stale token.
    private func handleSessionExpired() {
        KeychainHelper.deleteToken()
        clearToken()
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
    private func postJson(
        _ path: String,
        body: [String: Any],
        auth: Bool = true,
        expectedStatus: Int? = nil
    ) async throws -> [String: Any] {
        guard let url = URL(string: "\(baseUrl)\(path)") else { throw URLError(.badURL) }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if auth { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, response) = try await URLSession.shared.data(for: request)
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        try checkResponse(response, json: json, sessionAuth: auth)
        return json
    }

    @discardableResult
    private func sendPatch(_ path: String, body: [String: Any]) async throws -> [String: Any] {
        guard let url = URL(string: "\(baseUrl)\(path)") else { throw URLError(.badURL) }
        var request = URLRequest(url: url)
        request.httpMethod = "PATCH"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, response) = try await URLSession.shared.data(for: request)
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        try checkResponse(response, json: json)
        return json
    }

    private func sendDelete(_ path: String) async throws {
        guard let url = URL(string: "\(baseUrl)\(path)") else { throw URLError(.badURL) }
        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (data, response) = try await URLSession.shared.data(for: request)
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        try checkResponse(response, json: json)
    }
}
