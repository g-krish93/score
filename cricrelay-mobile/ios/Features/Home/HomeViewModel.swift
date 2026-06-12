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
    @Published var activeMatchIds: [String] = []

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
            let updated = try await api.renameStream(slug: slug, label: label)
            if let idx = streams.firstIndex(where: { $0.slug == slug }) {
                streams[idx] = updated
            }
        } catch {
            self.error = error.localizedDescription
        }
    }

    func loadFixtures() async {
        do {
            let response = try await api.listFixtures()
            fixtures = response.fixtures
            activeMatchIds = response.activeMatchIds
            slotsUsed = response.slotsUsed
            slotsTotal = response.slotsTotal
        } catch {
            // fixtures failure is non-fatal
        }
    }

    func createPlayCricketStream(matchId: String, label: String) async throws -> StreamMatch {
        let stream = try await api.createStream(type: "play_cricket", matchId: matchId, label: label)
        streams.insert(stream, at: 0)
        slotsUsed = streams.count
        return stream
    }

    func createPcsBleStream(label: String) async throws -> StreamMatch {
        let stream = try await api.createStream(type: "pcs_ble", label: label)
        streams.insert(stream, at: 0)
        slotsUsed = streams.count
        return stream
    }

    func youtubeAuthorizeUrl() async -> String? {
        try? await api.youtubeAuthorizeUrl()
    }

    func twitchAuthorizeUrl() async -> String? {
        try? await api.twitchAuthorizeUrl()
    }

    func disconnectYoutube() async {
        try? await api.disconnectYoutube()
        youtube = PlatformStatus()
    }

    func disconnectTwitch() async {
        try? await api.disconnectTwitch()
        twitch = PlatformStatus()
    }

    private func fetchAll() async {
        async let streamsFetch = api.listStreams()
        async let ytFetch = api.youtubeStatus()
        async let twFetch = api.twitchStatus()

        if let s = try? await streamsFetch { streams = s }
        if let yt = try? await ytFetch { youtube = yt }
        if let tw = try? await twFetch { twitch = tw }
        slotsUsed = streams.count
    }
}
