import Foundation

final class CricRelayAPI {
    static let shared = CricRelayAPI()
    private init() {}

    private(set) var baseUrl = ""
    private(set) var token = ""

    func configure(baseUrl: String, token: String) {
        self.baseUrl = baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        self.token = token
    }

    // MARK: - Auth

    func login(email: String, password: String, baseUrl: String) async throws {
        self.baseUrl = baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let json = try await postJson("/api/auth/login", body: ["email": email, "password": password], auth: false)
        guard let newToken = json["token"] as? String else { throw URLError(.badServerResponse) }
        token = newToken
    }

    func register(name: String, email: String, password: String, baseUrl: String) async throws {
        self.baseUrl = baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let json = try await postJson("/api/auth/register", body: [
            "name": name, "email": email, "password": password, "consent": true
        ], auth: false, expectedStatus: 201)
        guard let newToken = json["token"] as? String else { throw URLError(.badServerResponse) }
        token = newToken
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
        try await sendDelete("/api/streams/\(slug)")
    }

    func renameStream(slug: String, label: String) async throws -> StreamMatch {
        let json = try await sendPatch("/api/streams/\(slug)", body: ["label": label])
        guard let streamJson = json["stream"] as? [String: Any] else { throw URLError(.badServerResponse) }
        let data = try JSONSerialization.data(withJSONObject: streamJson)
        return try JSONDecoder().decode(StreamMatch.self, from: data)
    }

    // MARK: - Match day

    func matchDay(slug: String) async throws -> MatchDayStatus {
        let json = try await getJson("/api/match/\(slug)/match-day")
        let data = try JSONSerialization.data(withJSONObject: json)
        return try JSONDecoder().decode(MatchDayStatus.self, from: data)
    }

    func scoringConfig(slug: String) async throws -> ScoringConfig {
        let json = try await getJson("/api/match/\(slug)/scoring")
        let data = try JSONSerialization.data(withJSONObject: json)
        return try JSONDecoder().decode(ScoringConfig.self, from: data)
    }

    func setScoringMode(slug: String, mode: String, provider: String? = nil) async throws -> ScoringConfig {
        var body: [String: Any] = ["mode": mode]
        if let provider { body["provider"] = provider }
        let json = try await postJson("/api/match/\(slug)/scoring", body: body)
        let data = try JSONSerialization.data(withJSONObject: json)
        return try JSONDecoder().decode(ScoringConfig.self, from: data)
    }

    func setRelayPaused(slug: String, paused: Bool) async throws {
        _ = try await postJson("/api/match/\(slug)/relay-pause", body: ["paused": paused])
    }

    func updateBroadcastStatus(slug: String, status: String, platform: String? = nil, watchUrl: String? = nil) async throws {
        var body: [String: Any] = ["status": status]
        if let platform { body["platform"] = platform }
        if let watchUrl { body["watch_url"] = watchUrl }
        _ = try await postJson("/api/match/\(slug)/broadcast-status", body: body)
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
        let json = try await getJson("/api/match/\(slug)/overlay")
        let data = try JSONSerialization.data(withJSONObject: json)
        return (try? JSONDecoder().decode(OverlayLayoutPrefs.self, from: data)) ?? OverlayLayoutPrefs()
    }

    func saveOverlayPrefs(slug: String, prefs: OverlayLayoutPrefs) async throws -> OverlayLayoutPrefs {
        let encoder = JSONEncoder()
        encoder.keyEncodingStrategy = .convertToSnakeCase
        let data = try encoder.encode(prefs)
        let body = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        let json = try await postJson("/api/match/\(slug)/overlay", body: body)
        let responseData = try JSONSerialization.data(withJSONObject: json)
        return (try? JSONDecoder().decode(OverlayLayoutPrefs.self, from: responseData)) ?? prefs
    }

    // MARK: - Request helpers

    @discardableResult
    private func getJson(_ path: String) async throws -> [String: Any] {
        guard let url = URL(string: "\(baseUrl)\(path)") else { throw URLError(.badURL) }
        var request = URLRequest(url: url)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
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
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        let target = expectedStatus ?? 200
        if !(200..<300).contains(http.statusCode) && http.statusCode != target {
            let message = json["error"] as? String ?? "Request failed (\(http.statusCode))"
            throw NSError(domain: "CricRelayAPI", code: http.statusCode, userInfo: [NSLocalizedDescriptionKey: message])
        }
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
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    }

    private func sendDelete(_ path: String) async throws {
        guard let url = URL(string: "\(baseUrl)\(path)") else { throw URLError(.badURL) }
        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (_, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
    }
}
