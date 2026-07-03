import Foundation
import Shared

@MainActor
final class SessionViewModel: ObservableObject {
    @Published var isLoading = true
    @Published var isLoggedIn = false
    @Published var onboardingComplete = false
    @Published var baseUrl = "https://cricrelay.co.uk"
    @Published var errorMessage: String?

    private let api = CricRelayAPI.shared
    /// KMP shared session persistence (ADR-001 item 4). The iOS actual writes the exact
    /// keys this app has always used — NSUserDefaults "stream_api_base" /
    /// "stream_onboarding_complete_v1" and the "uk.co.cricrelay" Keychain entry — so
    /// existing installs keep their session across the migration.
    private let store = SessionStore()
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
        guard let session = try? await store.readSession(defaultBaseUrl: baseUrl) else { return }
        baseUrl = session.baseUrl
        if let savedToken = session.token, !savedToken.isEmpty {
            api.configure(baseUrl: session.baseUrl, token: savedToken)
            isLoggedIn = true
            onboardingComplete = await loadOnboardingComplete()
        }
    }

    func login(email: String, password: String) async {
        errorMessage = nil
        do {
            try await api.login(email: email, password: password, baseUrl: baseUrl)
            try await store.writeSession(baseUrl: api.baseUrl, token: api.token)
            isLoggedIn = true
            onboardingComplete = await loadOnboardingComplete()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func register(name: String, email: String, password: String, consent: Bool) async {
        errorMessage = nil
        do {
            try await api.register(name: name, email: email, password: password, consent: consent, baseUrl: baseUrl)
            try await store.writeSession(baseUrl: api.baseUrl, token: api.token)
            isLoggedIn = true
            onboardingComplete = false
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func completeOnboarding() {
        Task { try? await store.markOnboardingComplete() }
        onboardingComplete = true
    }

    func logout() {
        Task { try? await store.clearToken() }
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

    /// Kotlin suspend functions box primitive returns (KotlinBoolean) across the bridge.
    private func loadOnboardingComplete() async -> Bool {
        guard let flag = try? await store.isOnboardingComplete() else { return false }
        return flag.boolValue
    }
}
