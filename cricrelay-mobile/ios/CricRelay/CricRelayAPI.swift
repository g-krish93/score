import Foundation
import Shared

extension Notification.Name {
    /// Posted (on the main thread) when a MAIN-token request comes back 401: the stored
    /// session token has expired server-side (14-day TTL, no refresh endpoint) and the user
    /// must sign in again. SessionViewModel observes this and drops back to the login screen.
    static let cricrelaySessionExpired = Notification.Name("uk.co.cricrelay.sessionExpired")
}

/// Thin adapter over the KMP shared `CricRelayApiClient` (ADR-001 item 3, complete): one
/// definition of every endpoint, slug encoding, error mapping and 401 handling for both
/// platforms. The shared client's ApiException messages reach Swift as
/// NSError.localizedDescription, so failures surface as actionable text. OverlayLayoutPrefs
/// maps to/from its shared counterpart at this boundary (the Swift struct remains the
/// SwiftUI-editable value type; the Kotlin model owns serialization and merge semantics).
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

    // MARK: - Overlay & remote context (OverlayLayoutPrefs maps at the boundary)

    func overlayPrefs(slug: String) async throws -> OverlayLayoutPrefs {
        let shared = try await client.getOverlayPrefs(matchSlug: slug)
        return OverlayLayoutPrefs(shared: shared)
    }

    /// Server echo intentionally ignored — parity with Android's updateOverlayPrefs; the
    /// per-slug local cache is authoritative for the studio.
    func saveOverlayPrefs(slug: String, prefs: OverlayLayoutPrefs) async throws {
        try await client.setOverlayPrefs(matchSlug: slug, prefs: prefs.toShared())
    }

    func pollRemoteCommands(slug: String) async throws -> [RemoteCommand] {
        try await client.pollRemoteCommands(matchSlug: slug)
    }

    func sendRemoteOverlayPrefs(slug: String, prefs: OverlayLayoutPrefs, companionToken: String) async throws {
        try await client.sendRemoteOverlayPrefs(
            matchSlug: slug,
            companionToken: companionToken,
            prefs: prefs.toShared()
        )
    }

    func getRemoteContext(slug: String, companionToken: String) async throws -> RemoteCompanionContext {
        try await client.getRemoteContext(matchSlug: slug, companionToken: companionToken)
    }

    /// Clear the dead session exactly the way logout does (Keychain via the shared
    /// SessionStore + in-memory token), then notify SessionViewModel on the main thread so
    /// RootView swaps to LoginView instead of rendering a silently empty dashboard off a
    /// stale token. Invoked by the shared client's onSessionExpired callback.
    private func handleSessionExpired() {
        client.clearToken()
        Task { try? await self.sessionStore.clearToken() }
        DispatchQueue.main.async {
            NotificationCenter.default.post(name: .cricrelaySessionExpired, object: nil)
        }
    }
}
