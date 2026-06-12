import Foundation

@MainActor
final class SessionViewModel: ObservableObject {
    @Published var isLoading = true
    @Published var isLoggedIn = false
    @Published var onboardingComplete = false
    @Published var baseUrl = "https://cricrelay.co.uk"
    @Published var streams: [StreamItem] = []
    @Published var errorMessage: String?

    private let api = CricRelayAPI()

    func bootstrap() async {
        isLoading = true
        defer { isLoading = false }
        baseUrl = UserDefaults.standard.string(forKey: "stream_api_base") ?? baseUrl
        if let token = KeychainHelper.readToken(), !token.isEmpty {
            api.configure(baseUrl: baseUrl, token: token)
            isLoggedIn = true
            onboardingComplete = UserDefaults.standard.bool(forKey: "stream_onboarding_complete_v1")
            await refreshStreams()
        }
    }

    func login(email: String, password: String) async {
        errorMessage = nil
        do {
            try await api.login(email: email, password: password, baseUrl: baseUrl)
            UserDefaults.standard.set(baseUrl, forKey: "stream_api_base")
            if let token = api.token {
                KeychainHelper.saveToken(token)
            }
            isLoggedIn = true
            onboardingComplete = UserDefaults.standard.bool(forKey: "stream_onboarding_complete_v1")
            await refreshStreams()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func register(name: String, email: String, password: String) async {
        errorMessage = nil
        do {
            try await api.register(name: name, email: email, password: password, baseUrl: baseUrl)
            UserDefaults.standard.set(baseUrl, forKey: "stream_api_base")
            if let token = api.token {
                KeychainHelper.saveToken(token)
            }
            isLoggedIn = true
            onboardingComplete = false
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func completeOnboarding() {
        UserDefaults.standard.set(true, forKey: "stream_onboarding_complete_v1")
        onboardingComplete = true
    }

    func logout() {
        KeychainHelper.deleteToken()
        isLoggedIn = false
        streams = []
    }

    func refreshStreams() async {
        do {
            streams = try await api.listStreams()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

struct StreamItem: Identifiable, Decodable {
    let slug: String
    let label: String

    var id: String { slug }

    enum CodingKeys: String, CodingKey {
        case slug, label
    }
}
