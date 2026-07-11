import Foundation

@MainActor
final class SessionViewModel: ObservableObject {
    @Published var isLoading = true
    @Published var isLoggedIn = false
    @Published var onboardingComplete = false
    @Published var baseUrl = "https://cricrelay.co.uk"
    @Published var errorMessage: String?

    private let api = CricRelayAPI.shared
    private var expiryObserver: NSObjectProtocol?

    init() {
        // CricRelayAPI posts this after a MAIN-token call returns 401 (it has already
        // cleared the Keychain entry and its in-memory token). Flip the published state
        // here so RootView drops back to LoginView instead of an empty dashboard.
        expiryObserver = NotificationCenter.default.addObserver(
            forName: .cricrelaySessionExpired,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                self?.sessionDidExpire()
            }
        }
    }

    deinit {
        if let expiryObserver {
            NotificationCenter.default.removeObserver(expiryObserver)
        }
    }

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

    func register(name: String, email: String, password: String, consent: Bool, clubCode: String = "") async {
        errorMessage = nil
        do {
            try await api.register(
                name: name,
                email: email,
                password: password,
                consent: consent,
                baseUrl: baseUrl,
                playCricketBaseUrl: clubCode
            )
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

    /// The API layer already deleted the token before posting the notification; running
    /// logout() again is harmless (both deletes are idempotent) and keeps the reset in one
    /// place. The message shows on the login screen so the sign-out isn't a mystery.
    private func sessionDidExpire() {
        guard isLoggedIn else { return }
        logout()
        errorMessage = "Session expired — please sign in again."
    }
}
