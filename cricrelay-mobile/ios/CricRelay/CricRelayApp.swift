import SwiftUI

@main
struct CricRelayApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}

struct RootView: View {
    @StateObject private var session = SessionViewModel()

    var body: some View {
        Group {
            if session.isLoading {
                ProgressView("Loading…")
            } else if !session.isLoggedIn {
                LoginView(session: session)
            } else if !session.onboardingComplete {
                OnboardingView(session: session)
            } else {
                HomeView(session: session)
            }
        }
        .task { await session.bootstrap() }
    }
}
