import SwiftUI

// MARK: - Brand theme (kept in-file so no Xcode project changes are needed)

/// "Floodlight" palette — sunlight-legible: ink base, stadium-gold hero
/// (gold always carries ink text, never white), sky accent, red = danger only.
enum CricTheme {
    static let background = Color(red: 0.039, green: 0.055, blue: 0.082)   // #0A0E15
    static let surface = Color(red: 0.078, green: 0.102, blue: 0.149)      // #141A26
    static let surfaceElevated = Color(red: 0.110, green: 0.141, blue: 0.200) // #1C2433
    static let primary = Color(red: 1.0, green: 0.761, blue: 0.2)          // #FFC233
    static let primaryDeep = Color(red: 0.910, green: 0.663, blue: 0.071)  // #E8A912
    static let onPrimary = Color(red: 0.102, green: 0.075, blue: 0.020)    // #1A1305
    static let accent = Color(red: 0.341, green: 0.780, blue: 1.0)         // #57C7FF
    static let danger = Color(red: 1.0, green: 0.361, blue: 0.478)         // #FF5C7A
    static let textMuted = Color(red: 0.780, green: 0.804, blue: 0.851)    // #C7CDD9
    static let textDim = Color(red: 0.596, green: 0.631, blue: 0.702)      // #98A1B3

    static var ctaGradient: LinearGradient {
        LinearGradient(colors: [primary, primaryDeep], startPoint: .topLeading, endPoint: .bottomTrailing)
    }

    static var brandGradient: LinearGradient {
        LinearGradient(colors: [primary, accent], startPoint: .topLeading, endPoint: .bottomTrailing)
    }
}

struct StudioBackdrop<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [CricTheme.background, Color.black],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()
            content
        }
        .preferredColorScheme(.dark)
    }
}

private struct StudioFieldStyle: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(14)
            .background(CricTheme.surface, in: RoundedRectangle(cornerRadius: 14))
            .overlay(
                RoundedRectangle(cornerRadius: 14)
                    .stroke(Color.white.opacity(0.12), lineWidth: 1)
            )
            .foregroundStyle(.white)
    }
}

private struct PrimaryCtaStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(CricTheme.onPrimary)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 15)
            .background(CricTheme.ctaGradient, in: RoundedRectangle(cornerRadius: 14))
            .shadow(color: CricTheme.primary.opacity(0.4), radius: 12, y: 4)
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

private struct BrandMark: View {
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 18)
                .fill(CricTheme.brandGradient)
                .frame(width: 72, height: 72)
                .shadow(color: CricTheme.primary.opacity(0.5), radius: 18, y: 6)
            Image(systemName: "dot.radiowaves.left.and.right")
                .font(.system(size: 30, weight: .semibold))
                .foregroundStyle(CricTheme.onPrimary)
        }
    }
}

// MARK: - Login

struct LoginView: View {
    @ObservedObject var session: SessionViewModel
    @State private var email = ""
    @State private var password = ""
    @State private var busy = false
    @State private var showRegister = false

    var body: some View {
        StudioBackdrop {
            ScrollView {
                VStack(spacing: 16) {
                    Spacer(minLength: 48)
                    BrandMark()
                    Text("CricRelay Live")
                        .font(.largeTitle.bold())
                        .foregroundStyle(.white)
                    Text("Broadcast cricket like a pro — live scoreboard burned into every stream.")
                        .font(.subheadline)
                        .foregroundStyle(CricTheme.textMuted)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 8)
                        .padding(.bottom, 16)

                    TextField("Club server", text: $session.baseUrl)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .modifier(StudioFieldStyle())
                    TextField("Email", text: $email)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.emailAddress)
                        .modifier(StudioFieldStyle())
                    SecureField("Password", text: $password)
                        .modifier(StudioFieldStyle())

                    if let error = session.errorMessage {
                        Text(error)
                            .font(.footnote)
                            .foregroundStyle(CricTheme.danger)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(12)
                            .background(CricTheme.danger.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                    }

                    Button {
                        Task {
                            busy = true
                            await session.login(email: email, password: password)
                            busy = false
                        }
                    } label: {
                        if busy {
                            ProgressView().tint(CricTheme.onPrimary)
                        } else {
                            Text("Sign in to studio")
                        }
                    }
                    .buttonStyle(PrimaryCtaStyle())
                    .disabled(busy)
                    .padding(.top, 8)

                    Button("Don't have an account? Sign up") {
                        showRegister = true
                    }
                    .font(.subheadline)
                    .foregroundStyle(CricTheme.accent)
                    .padding(.top, 4)
                }
                .padding(24)
            }
        }
        .sheet(isPresented: $showRegister) {
            RegisterView(session: session)
        }
    }
}

// MARK: - Register

struct RegisterView: View {
    @ObservedObject var session: SessionViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var email = ""
    @State private var password = ""
    @State private var confirmPassword = ""
    @State private var busy = false
    @State private var localError: String?

    var body: some View {
        StudioBackdrop {
            ScrollView {
                VStack(spacing: 16) {
                    Spacer(minLength: 48)
                    BrandMark()
                    Text("Create account")
                        .font(.largeTitle.bold())
                        .foregroundStyle(.white)
                    Text("Set up your CricRelay account to start broadcasting.")
                        .font(.subheadline)
                        .foregroundStyle(CricTheme.textMuted)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 8)
                        .padding(.bottom, 16)

                    TextField("Club or your name", text: $name)
                        .textInputAutocapitalization(.words)
                        .modifier(StudioFieldStyle())
                    TextField("Email", text: $email)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.emailAddress)
                        .modifier(StudioFieldStyle())
                    SecureField("Password (min 8 characters)", text: $password)
                        .modifier(StudioFieldStyle())
                    SecureField("Confirm password", text: $confirmPassword)
                        .modifier(StudioFieldStyle())

                    let displayError = localError ?? session.errorMessage
                    if let error = displayError {
                        Text(error)
                            .font(.footnote)
                            .foregroundStyle(CricTheme.danger)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(12)
                            .background(CricTheme.danger.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                    }

                    Button {
                        guard password == confirmPassword else {
                            localError = "Passwords do not match"
                            return
                        }
                        localError = nil
                        Task {
                            busy = true
                            await session.register(name: name, email: email, password: password)
                            busy = false
                            if session.errorMessage == nil {
                                dismiss()
                            }
                        }
                    } label: {
                        if busy {
                            ProgressView().tint(CricTheme.onPrimary)
                        } else {
                            Text("Create account")
                        }
                    }
                    .buttonStyle(PrimaryCtaStyle())
                    .disabled(busy)
                    .padding(.top, 8)

                    Button("Already have an account? Sign in") { dismiss() }
                        .font(.subheadline)
                        .foregroundStyle(CricTheme.accent)
                        .padding(.top, 4)
                }
                .padding(24)
            }
        }
    }
}

// MARK: - Onboarding

struct OnboardingView: View {
    @ObservedObject var session: SessionViewModel

    private let steps: [(icon: String, title: String, body: String)] = [
        ("key.fill", "Paste your stream key", "Tap Destination on the broadcast screen and paste the RTMP key from YouTube Studio or Twitch."),
        ("rectangle.inset.filled.and.person.filled", "Position the overlay", "Drag and resize the scoreboard, then lock it so touches don't move it while you film."),
        ("dot.radiowaves.left.and.right", "Go live when ready", "Run the pre-flight checklist, then tap Go Live. Keep the phone plugged in on a stable connection."),
    ]

    var body: some View {
        StudioBackdrop {
            VStack(spacing: 24) {
                Spacer()
                Text("Welcome to the studio")
                    .font(.title.bold())
                    .foregroundStyle(.white)
                VStack(spacing: 14) {
                    ForEach(steps, id: \.title) { step in
                        HStack(spacing: 14) {
                            Image(systemName: step.icon)
                                .font(.system(size: 18, weight: .semibold))
                                .foregroundStyle(CricTheme.accent)
                                .frame(width: 42, height: 42)
                                .background(CricTheme.accent.opacity(0.14), in: RoundedRectangle(cornerRadius: 12))
                            VStack(alignment: .leading, spacing: 3) {
                                Text(step.title)
                                    .font(.subheadline.bold())
                                    .foregroundStyle(.white)
                                Text(step.body)
                                    .font(.footnote)
                                    .foregroundStyle(CricTheme.textMuted)
                            }
                            Spacer(minLength: 0)
                        }
                        .padding(14)
                        .background(CricTheme.surface, in: RoundedRectangle(cornerRadius: 16))
                    }
                }
                Spacer()
                Button("Enter studio") { session.completeOnboarding() }
                    .buttonStyle(PrimaryCtaStyle())
            }
            .padding(24)
        }
    }
}

// MARK: - Home

struct HomeView: View {
    @ObservedObject var session: SessionViewModel

    var body: some View {
        NavigationStack {
            StudioBackdrop {
                List(session.streams) { stream in
                    NavigationLink {
                        StudioView(matchSlug: stream.slug)
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "video.fill")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(CricTheme.accent)
                                .frame(width: 36, height: 36)
                                .background(CricTheme.accent.opacity(0.14), in: RoundedRectangle(cornerRadius: 10))
                            VStack(alignment: .leading, spacing: 2) {
                                Text(stream.label)
                                    .font(.subheadline.bold())
                                    .foregroundStyle(.white)
                                Text(stream.slug)
                                    .font(.caption)
                                    .foregroundStyle(CricTheme.textDim)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                    .listRowBackground(CricTheme.surface)
                }
                .scrollContentBackground(.hidden)
            }
            .navigationTitle("Studio")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Log out") { session.logout() }
                        .foregroundStyle(CricTheme.textMuted)
                }
            }
            .refreshable { await session.refreshStreams() }
        }
        .preferredColorScheme(.dark)
    }
}

// MARK: - Studio placeholder

struct StudioView: View {
    let matchSlug: String

    var body: some View {
        StudioBackdrop {
            VStack(spacing: 16) {
                Image(systemName: "video.badge.waveform")
                    .font(.system(size: 40))
                    .foregroundStyle(CricTheme.accent)
                Text("Broadcast studio")
                    .font(.title2.bold())
                    .foregroundStyle(.white)
                Text(matchSlug)
                    .font(.footnote)
                    .foregroundStyle(CricTheme.textDim)
                Text("Native iOS streaming uses StreamCamera engine — wire Go Live in a future sprint.")
                    .multilineTextAlignment(.center)
                    .foregroundStyle(CricTheme.textMuted)
            }
            .padding(24)
        }
    }
}
