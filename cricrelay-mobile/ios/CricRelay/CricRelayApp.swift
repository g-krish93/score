import SwiftUI

/// Exists only to let the studio's orientation lock override the Info.plist supported
/// orientations at runtime. Default mirrors the plist (portrait + both landscapes).
final class AppDelegate: NSObject, UIApplicationDelegate {
    static let defaultOrientations: UIInterfaceOrientationMask = [.portrait, .landscapeLeft, .landscapeRight]
    static var orientationLock: UIInterfaceOrientationMask = defaultOrientations

    func application(
        _ application: UIApplication,
        supportedInterfaceOrientationsFor window: UIWindow?
    ) -> UIInterfaceOrientationMask {
        Self.orientationLock
    }
}

@main
struct CricRelayApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}

struct RootView: View {
    @StateObject private var session = SessionViewModel()
    @State private var splashDone = false
    @State private var openRemoteControl = false
    @State private var remotePairPayload: String?
    /// Companion-only cold start: show Remote Control without club login.
    @State private var companionOnlyMode = false

    var body: some View {
        ZStack {
            Group {
                if companionOnlyMode {
                    NavigationStack {
                        RemoteControlView(initialPairPayload: remotePairPayload)
                            .toolbar {
                                ToolbarItem(placement: .cancellationAction) {
                                    Button("Close") {
                                        companionOnlyMode = false
                                        openRemoteControl = false
                                        remotePairPayload = nil
                                    }
                                }
                            }
                    }
                } else if session.isLoading {
                    ProgressView("Loading…")
                } else if !session.isLoggedIn {
                    LoginView(session: session)
                } else if !session.onboardingComplete {
                    OnboardingView(session: session)
                } else {
                    HomeView(
                        session: session,
                        openRemoteControl: $openRemoteControl,
                        remotePairPayload: $remotePairPayload
                    )
                }
            }
            if !splashDone || session.isLoading {
                SplashView { splashDone = true }
                    .transition(.opacity)
                    .zIndex(1)
            }
        }
        .animation(.easeOut(duration: 0.35), value: splashDone && !session.isLoading)
        .task { await session.bootstrap() }
        .onOpenURL { url in
            handleIncomingPairURL(url)
        }
        .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
            guard let url = activity.webpageURL else { return }
            handleIncomingPairURL(url)
        }
        .onChange(of: session.isLoggedIn) { loggedIn in
            if loggedIn, session.onboardingComplete, PairDeepLinkStore.pendingUri != nil {
                remotePairPayload = PairDeepLinkStore.pendingUri
                openRemoteControl = true
                companionOnlyMode = false
            }
        }
        .onChange(of: session.onboardingComplete) { done in
            if done, session.isLoggedIn, PairDeepLinkStore.pendingUri != nil {
                remotePairPayload = PairDeepLinkStore.pendingUri
                openRemoteControl = true
                companionOnlyMode = false
            }
        }
        .onChange(of: session.isLoading) { loading in
            if !loading, !session.isLoggedIn, PairDeepLinkStore.pendingUri != nil {
                remotePairPayload = PairDeepLinkStore.pendingUri
                companionOnlyMode = true
            }
        }
    }

    private func handleIncomingPairURL(_ url: URL) {
        let raw = url.absoluteString
        guard PairDeepLinkParser.parse(raw) != nil else { return }
        PairDeepLinkStore.pendingUri = raw
        remotePairPayload = raw
        if session.isLoggedIn && session.onboardingComplete {
            companionOnlyMode = false
            openRemoteControl = true
        } else if !session.isLoading {
            companionOnlyMode = true
        }
    }
}
