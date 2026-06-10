import SwiftUI

struct LoginView: View {
    @ObservedObject var session: SessionViewModel
    @State private var email = ""
    @State private var password = ""
    @State private var busy = false

    var body: some View {
        VStack(spacing: 16) {
            Text("CricRelay Live").font(.largeTitle.bold())
            TextField("Club server", text: $session.baseUrl)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            TextField("Email", text: $email)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            SecureField("Password", text: $password)
            if let error = session.errorMessage {
                Text(error).foregroundStyle(.red).font(.footnote)
            }
            Button(busy ? "Signing in…" : "Sign in to studio") {
                Task {
                    busy = true
                    await session.login(email: email, password: password)
                    busy = false
                }
            }
            .buttonStyle(.borderedProminent)
            .disabled(busy)
        }
        .padding()
    }
}

struct OnboardingView: View {
    @ObservedObject var session: SessionViewModel

    var body: some View {
        VStack(spacing: 24) {
            Text("Welcome to the studio").font(.title.bold())
            Text("Paste your RTMP key, position the overlay, then go live when ready.")
                .multilineTextAlignment(.center)
            Button("Enter studio") { session.completeOnboarding() }
                .buttonStyle(.borderedProminent)
        }
        .padding()
    }
}

struct HomeView: View {
    @ObservedObject var session: SessionViewModel

    var body: some View {
        NavigationStack {
            List(session.streams) { stream in
                NavigationLink(stream.label) {
                    StudioView(matchSlug: stream.slug)
                }
            }
            .navigationTitle("Studio")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Log out") { session.logout() }
                }
            }
            .refreshable { await session.refreshStreams() }
        }
    }
}

struct StudioView: View {
    let matchSlug: String

    var body: some View {
        VStack(spacing: 16) {
            Text("Broadcast studio").font(.title2.bold())
            Text(matchSlug).font(.footnote)
            Text("Native iOS streaming uses StreamCamera engine — wire Go Live in a future sprint.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
        }
        .padding()
    }
}
