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

    var body: some View {
        ZStack {
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
            // Opening splash — plays once per cold start over the bootstrapping app,
            // then fades out on the logo lockup frame. Tap skips. Also gated on the
            // session bootstrap so an early skip holds the lockup, not a spinner.
            if !splashDone || session.isLoading {
                SplashView { splashDone = true }
                    .transition(.opacity)
                    .zIndex(1)
            }
        }
        .animation(.easeOut(duration: 0.35), value: splashDone && !session.isLoading)
        .task { await session.bootstrap() }
    }
}
