import Foundation

@MainActor
final class SessionViewModel: ObservableObject {
    @Published var isLoading = true
    @Published var isLoggedIn = false
    @Published var onboardingComplete = false
    @Published var baseUrl = "https://cricrelay.co.uk"
    @Published var errorMessage: String?

    private let api = CricRelayAPI.shared

    func bootstrap() async {
        isLoading = true
        defer { isLoading = false }
        baseUrl = UserDefaults.standard.string(forKey: "stream_api_base") ?? baseUrl
        if let savedToken = KeychainHelper.readToken(), !savedToken.isEmpty {
            api.configure(baseUrl: baseUrl, token: savedToken)
            isLoggedIn = true
            onboardingComplete = UserDefaults.standard.bool(forKey: "stream_onboarding_complete_v1")
        }
    }

    func login(email: String, password: String) async {
        errorMessage = nil
        do {
            try await api.login(email: email, password: password, baseUrl: baseUrl)
            UserDefaults.standard.set(baseUrl, forKey: "stream_api_base")
            KeychainHelper.saveToken(api.token)
            isLoggedIn = true
            onboardingComplete = UserDefaults.standard.bool(forKey: "stream_onboarding_complete_v1")
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func register(name: String, email: String, password: String, consent: Bool) async {
        errorMessage = nil
        do {
            try await api.register(name: name, email: email, password: password, consent: consent, baseUrl: baseUrl)
            UserDefaults.standard.set(baseUrl, forKey: "stream_api_base")
            KeychainHelper.saveToken(api.token)
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
        // Also drop the in-memory token — any still-running poll would otherwise keep
        // making authenticated calls as the signed-out user.
        api.clearToken()
        isLoggedIn = false
    }
}
