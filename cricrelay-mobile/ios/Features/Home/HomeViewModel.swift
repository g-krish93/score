import Foundation

@MainActor
final class HomeViewModel: ObservableObject {
    @Published var streams: [StreamMatch] = []
    @Published var youtube = PlatformStatus()
    @Published var twitch = PlatformStatus()
    @Published var slotsUsed = 0
    @Published var slotsTotal = 6
    @Published var loading = false
    @Published var refreshing = false
    @Published var error: String?
    @Published var fixtures: [FixtureItem] = []
    @Published var fixturesLoading = false
    @Published var fixturesError: String?
    @Published var activeMatchIds: [String] = []
    /// Linked Play-Cricket site: nil until the first fixtures load, "" when none linked.
    @Published var clubSiteUrl: String?
    @Published var clubSaving = false
    @Published var clubError: String?

    private let api = CricRelayAPI.shared

    func load() async {
        guard !loading else { return }
        loading = true
        error = nil
        await fetchAll()
        loading = false
    }

    func refresh() async {
        refreshing = true
        error = nil
        await fetchAll()
        refreshing = false
    }

    func deleteStream(slug: String) async {
        do {
            try await api.deleteStream(slug: slug)
            streams.removeAll { $0.slug == slug }
            slotsUsed = streams.count
        } catch {
            self.error = error.localizedDescription
        }
    }

    func renameStream(slug: String, label: String) async {
        do {
            try await api.renameStream(slug: slug, label: label)
            if let idx = streams.firstIndex(where: { $0.slug == slug }) {
                streams[idx].label = label
            }
        } catch {
            self.error = error.localizedDescription
        }
    }

    func loadFixtures() async {
        fixturesLoading = true
        fixturesError = nil
        defer { fixturesLoading = false }
        do {
            let response = try await api.listFixtures()
            fixtures = response.fixtures
            activeMatchIds = response.activeMatchIds
            slotsUsed = response.slotsUsed
            slotsTotal = response.slotsTotal
            clubSiteUrl = response.fixtureSourceUrl ?? ""
            // The server reports scrape failures as fixtures:[] + error — surface it instead of
            // letting the picker sit on an empty list that reads as "still loading".
            if fixtures.isEmpty, let serverError = response.error, !serverError.isEmpty {
                fixturesError = serverError
            }
        } catch {
            fixturesError = error.localizedDescription
        }
    }

    func createPlayCricketStream(matchId: String, label: String) async throws -> StreamMatch {
        let stream = try await api.createStream(type: "play_cricket", matchId: matchId, label: label)
        streams.insert(stream, at: 0)
        slotsUsed = streams.count
        return stream
    }

    func createCricHeroesStream(matchUrl: String, label: String) async throws -> StreamMatch {
        let stream = try await api.createStream(type: "cricheroes", matchUrl: matchUrl, label: label)
        streams.insert(stream, at: 0)
        slotsUsed = streams.count
        return stream
    }

    func createManualStream(label: String) async throws -> StreamMatch {
        let stream = try await api.createStream(type: "manual", label: label)
        streams.insert(stream, at: 0)
        slotsUsed = streams.count
        return stream
    }

    func youtubeAuthorizeUrl() async -> String? {
        await authorizeUrl(platform: "YouTube") { try await self.api.youtubeAuthorizeUrl() }
    }

    func twitchAuthorizeUrl() async -> String? {
        await authorizeUrl(platform: "Twitch") { try await self.api.twitchAuthorizeUrl() }
    }

    /// A failed or empty authorize URL must not make the Connect button a silent no-op —
    /// the server sends precise reasons (e.g. "YouTube OAuth is not configured…").
    private func authorizeUrl(platform: String, _ fetch: () async throws -> String) async -> String? {
        do {
            let url = try await fetch()
            guard !url.isEmpty else {
                error = "\(platform) connect isn't available right now — try again later."
                return nil
            }
            return url
        } catch {
            self.error = error.localizedDescription
            return nil
        }
    }

    func disconnectYoutube() async {
        do {
            try await api.disconnectYoutube()
            youtube = PlatformStatus()
        } catch {
            self.error = error.localizedDescription
        }
    }

    func disconnectTwitch() async {
        do {
            try await api.disconnectTwitch()
            twitch = PlatformStatus()
        } catch {
            self.error = error.localizedDescription
        }
    }

    /// Save the club's Play-Cricket link (code or URL). Returns true when it saved so the
    /// sheet can dismiss; validation failures land in `clubError` with the server's message.
    func saveClubSite(_ input: String) async -> Bool {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            clubError = "Enter your club code — the short name before .play-cricket.com"
            return false
        }
        clubSaving = true
        clubError = nil
        defer { clubSaving = false }
        do {
            clubSiteUrl = try await api.updateAccount(playCricketBaseUrl: trimmed)
            await loadFixtures()
            return true
        } catch {
            clubError = error.localizedDescription
            return false
        }
    }

    private func fetchAll() async {
        async let streamsFetch = api.listStreams()
        async let ytFetch = api.youtubeStatus()
        async let twFetch = api.twitchStatus()
        async let fixturesFetch = api.listFixtures()

        do {
            streams = try await streamsFetch
        } catch {
            // Keep whatever is on screen — a failed load must say so, not fake an empty
            // account ("No streams yet") over a network error or expired session.
            self.error = error.localizedDescription
        }
        // Platform badges are secondary; their failure alone shouldn't banner the home screen.
        if let yt = try? await ytFetch { youtube = yt }
        if let tw = try? await twFetch { twitch = tw }
        // Fixtures ride along to learn whether a Play-Cricket site is linked (drives the
        // "link your club" nudge); a failure just leaves clubSiteUrl nil = unknown.
        if let fx = try? await fixturesFetch {
            fixtures = fx.fixtures
            activeMatchIds = fx.activeMatchIds
            clubSiteUrl = fx.fixtureSourceUrl ?? ""
        }
        slotsUsed = streams.count
    }
}
