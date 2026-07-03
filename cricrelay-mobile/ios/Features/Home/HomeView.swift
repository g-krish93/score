import SwiftUI
import Shared

struct HomeView: View {
    @ObservedObject var session: SessionViewModel
    @StateObject private var viewModel = HomeViewModel()

    @State private var managedStream: StreamMatch?
    @State private var renameLabel = ""
    @State private var showRenameAlert = false
    @State private var showDeleteConfirm = false
    @State private var showCreateMode: CreateMode?

    enum CreateMode: Identifiable {
        case playCricket, cricheroes
        var id: String { self == .playCricket ? "pc" : "ch" }
    }

    private var greeting: String {
        let hour = Calendar.current.component(.hour, from: Date())
        switch hour {
        case 5..<12: return "Good morning"
        case 12..<17: return "Good afternoon"
        default: return "Good evening"
        }
    }

    private var liveCount: Int {
        viewModel.streams.filter { $0.broadcast.isStreaming }.count
    }

    private var platformsLinked: Int {
        (viewModel.youtube.connected ? 1 : 0) + (viewModel.twitch.connected ? 1 : 0)
    }

    var body: some View {
        NavigationStack {
            StudioBackdrop {
                Group {
                    if viewModel.loading {
                        ProgressView()
                            .tint(CricTheme.accent)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    } else {
                        scrollContent
                    }
                }
            }
            .navigationTitle("")
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(greeting)
                            .font(.caption)
                            .foregroundStyle(CricTheme.textDim)
                        Text("CricRelay Studio")
                            .font(.headline.bold())
                            .foregroundStyle(.white)
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Menu {
                        Button(role: .destructive) { session.logout() } label: {
                            Label("Sign out", systemImage: "arrow.right.square")
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                            .foregroundStyle(CricTheme.textMuted)
                    }
                }
            }
        }
        .preferredColorScheme(.dark)
        .sheet(item: $showCreateMode) { mode in
            CreateStreamView(
                mode: mode == .playCricket ? "play_cricket" : "cricheroes",
                viewModel: viewModel
            )
        }
        .alert("Rename stream", isPresented: $showRenameAlert, presenting: managedStream) { stream in
            TextField("Name", text: $renameLabel)
            Button("Save") {
                let slug = stream.slug; let label = renameLabel
                Task { await viewModel.renameStream(slug: slug, label: label) }
            }
            Button("Cancel", role: .cancel) {}
        }
        .alert("Delete stream?", isPresented: $showDeleteConfirm, presenting: managedStream) { stream in
            Button("Delete", role: .destructive) {
                Task { await viewModel.deleteStream(slug: stream.slug) }
            }
            Button("Cancel", role: .cancel) {}
        } message: { stream in
            Text("\"\(stream.label)\" will be permanently deleted.")
        }
        .task { await viewModel.load() }
    }

    // MARK: - Scroll content

    private var scrollContent: some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                glanceRow
                    .padding(.top, 4)

                if let live = viewModel.streams.first(where: { $0.broadcast.isStreaming }) {
                    liveNowCard(live)
                        .transition(.move(edge: .top).combined(with: .opacity))
                }

                if let error = viewModel.error {
                    errorBanner(error)
                        .transition(.move(edge: .top).combined(with: .opacity))
                }

                streamsSection

                platformsSection

                remoteControlSection
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 32)
            .cricEnterAnimation(value: viewModel.streams.count)
            .cricEnterAnimation(value: viewModel.error != nil, duration: CricMotion.sheetEnterDuration)
            .cricExitAnimation(value: viewModel.error != nil)
        }
        .refreshable { await viewModel.refresh() }
    }

    // MARK: - Glance row

    private var glanceRow: some View {
        HStack(spacing: 10) {
            glanceCard(
                value: "\(liveCount)",
                label: "LIVE",
                icon: "dot.radiowaves.left.and.right",
                tint: liveCount > 0 ? CricTheme.primary : CricTheme.textDim
            )
            glanceCard(
                value: "\(viewModel.slotsUsed)/\(viewModel.slotsTotal)",
                label: "STREAMS",
                icon: "play.rectangle.on.rectangle",
                tint: CricTheme.accent
            )
            glanceCard(
                value: "\(platformsLinked)",
                label: "LINKED",
                icon: "link",
                tint: CricTheme.primary
            )
        }
    }

    private func glanceCard(value: String, label: String, icon: String, tint: Color) -> some View {
        VStack(spacing: 4) {
            Image(systemName: icon)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(tint)
            Text(value)
                .font(.title3.bold())
                .foregroundStyle(.white)
            Text(label)
                .font(.system(size: 9, weight: .semibold))
                .foregroundStyle(CricTheme.textDim)
                .tracking(0.5)
        }
        .frame(maxWidth: .infinity)
        .padding(12)
        .background(CricTheme.surface, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.white.opacity(0.08), lineWidth: 1))
    }

    // MARK: - Live now

    private func liveNowCard(_ stream: StreamMatch) -> some View {
        NavigationLink { StudioView(matchSlug: stream.slug) } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 5) {
                        Circle()
                            .fill(CricTheme.primary)
                            .frame(width: 7, height: 7)
                            .pulseOpacity(active: true, min: 0.4)
                        Text("LIVE NOW")
                            .font(.system(size: 9, weight: .bold))
                            .foregroundStyle(CricTheme.primary)
                            .tracking(1)
                    }
                    Text(stream.label)
                        .font(.subheadline.bold())
                        .foregroundStyle(.white)
                    if let platform = stream.broadcast.platform {
                        Text(platform.capitalized)
                            .font(.caption)
                            .foregroundStyle(CricTheme.textMuted)
                    }
                }
                Spacer()
                Text("Open studio →")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(CricTheme.accent)
            }
            .padding(14)
            .background(CricTheme.primary.opacity(0.1), in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(CricTheme.primary.opacity(0.35), lineWidth: 1))
        }
        .buttonStyle(PressableScaleStyle())
    }

    // MARK: - Streams section

    private var streamsSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                sectionHeader("Your streams")
                Spacer()
                Menu {
                    Button {
                        Task { await viewModel.loadFixtures() }
                        showCreateMode = .playCricket
                    } label: {
                        Label("Play-Cricket fixture", systemImage: "sportscourt")
                    }
                    Button { showCreateMode = .cricheroes } label: {
                        Label("CricHeroes scorecard", systemImage: "link")
                    }
                } label: {
                    Label("Add", systemImage: "plus.circle.fill")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(CricTheme.accent)
                }
            }

            if viewModel.streams.isEmpty {
                emptyState
            } else {
                ForEach(viewModel.streams, id: \.slug) { stream in
                    streamTile(stream)
                }
            }
        }
    }

    private func streamTile(_ stream: StreamMatch) -> some View {
        NavigationLink { StudioView(matchSlug: stream.slug) } label: {
            HStack(spacing: 12) {
                Image(systemName: "video.fill")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(CricTheme.accent)
                    .frame(width: 36, height: 36)
                    .background(CricTheme.accent.opacity(0.14), in: RoundedRectangle(cornerRadius: 10))

                VStack(alignment: .leading, spacing: 5) {
                    Text(stream.label)
                        .font(.subheadline.bold())
                        .foregroundStyle(.white)
                        .lineLimit(1)
                    HStack(spacing: 5) {
                        if stream.broadcast.isStreaming {
                            chip("ON AIR", color: CricTheme.primary)
                        } else if stream.broadcast.isPaused {
                            chip("PAUSED", color: .orange)
                        }
                        if stream.scoringActive { chip("SCORING", color: CricTheme.accent) }
                        else if stream.scoringStale { chip("STALE", color: .orange) }
                        if stream.relaySource == "pcs" { chip("BLE", color: CricTheme.primary) }
                        if stream.relayPaused { chip("RELAY OFF", color: CricTheme.textDim) }
                    }
                }

                Spacer(minLength: 0)

                Image(systemName: "chevron.right")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(CricTheme.textDim)
            }
            .padding(12)
            .background(CricTheme.surface, in: RoundedRectangle(cornerRadius: 14))
            .overlay(
                RoundedRectangle(cornerRadius: 14)
                    .stroke(
                        stream.broadcast.isStreaming ? CricTheme.primary.opacity(0.4) : Color.white.opacity(0.08),
                        lineWidth: 1
                    )
            )
        }
        .buttonStyle(PressableScaleStyle())
        .transition(.opacity.combined(with: .move(edge: .trailing)))
        .contextMenu {
            Button {
                managedStream = stream
                renameLabel = stream.label
                showRenameAlert = true
            } label: { Label("Rename", systemImage: "pencil") }

            Button(role: .destructive) {
                managedStream = stream
                showDeleteConfirm = true
            } label: { Label("Delete", systemImage: "trash") }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 10) {
            Image(systemName: "video.slash")
                .font(.system(size: 28))
                .foregroundStyle(CricTheme.textDim)
            Text("No streams yet")
                .font(.subheadline.bold())
                .foregroundStyle(CricTheme.textMuted)
            Text("Tap Add to create your first live stream.")
                .font(.footnote)
                .foregroundStyle(CricTheme.textDim)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(36)
    }

    // MARK: - Platforms section

    private var platformsSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            sectionHeader("Platforms")

            platformCard(
                name: "YouTube",
                icon: "play.rectangle.fill",
                tint: Color(red: 1, green: 0.1, blue: 0.1),
                status: viewModel.youtube
            ) {
                if viewModel.youtube.connected {
                    Task { await viewModel.disconnectYoutube() }
                } else {
                    Task {
                        if let urlStr = await viewModel.youtubeAuthorizeUrl(),
                           let url = URL(string: urlStr) {
                            await UIApplication.shared.open(url)
                        }
                    }
                }
            }

            platformCard(
                name: "Twitch",
                icon: "gamecontroller.fill",
                tint: Color(red: 0.576, green: 0.286, blue: 1.0),
                status: viewModel.twitch
            ) {
                if viewModel.twitch.connected {
                    Task { await viewModel.disconnectTwitch() }
                } else {
                    Task {
                        if let urlStr = await viewModel.twitchAuthorizeUrl(),
                           let url = URL(string: urlStr) {
                            await UIApplication.shared.open(url)
                        }
                    }
                }
            }
        }
    }

    private func platformCard(name: String, icon: String, tint: Color, status: PlatformStatus, action: @escaping () -> Void) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: 38, height: 38)
                .background(tint.opacity(0.14), in: RoundedRectangle(cornerRadius: 11))

            VStack(alignment: .leading, spacing: 2) {
                Text(name)
                    .font(.subheadline.bold())
                    .foregroundStyle(.white)
                Text(status.connected ? (status.label.isEmpty ? "Connected" : status.label) : "Not linked")
                    .font(.caption)
                    .foregroundStyle(status.connected ? CricTheme.accent : CricTheme.textDim)
            }

            Spacer()

            Button(action: action) {
                Text(status.connected ? "Disconnect" : "Connect")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(status.connected ? CricTheme.danger : CricTheme.accent)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(
                        (status.connected ? CricTheme.danger : CricTheme.accent).opacity(0.15),
                        in: Capsule()
                    )
            }
            .buttonStyle(PressableScaleStyle())
        }
        .padding(12)
        .background(CricTheme.surface, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.white.opacity(0.08), lineWidth: 1))
    }

    // MARK: - Remote control

    private var remoteControlSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            sectionHeader("Remote control")
            NavigationLink {
                RemoteControlView()
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: "qrcode.viewfinder")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(CricTheme.primary)
                        .frame(width: 38, height: 38)
                        .background(CricTheme.primary.opacity(0.14), in: RoundedRectangle(cornerRadius: 11))
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Pair as companion")
                            .font(.subheadline.bold())
                            .foregroundStyle(.white)
                        Text("Scan a QR code to control a live broadcast")
                            .font(.caption)
                            .foregroundStyle(CricTheme.textDim)
                    }
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(CricTheme.textDim)
                }
                .padding(12)
                .background(CricTheme.surface, in: RoundedRectangle(cornerRadius: 14))
                .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.white.opacity(0.08), lineWidth: 1))
            }
            .buttonStyle(PressableScaleStyle())
        }
    }

    // MARK: - Helpers

    private func sectionHeader(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 10, weight: .semibold))
            .foregroundStyle(CricTheme.textDim)
            .textCase(.uppercase)
            .tracking(0.8)
    }

    private func chip(_ text: String, color: Color) -> some View {
        Text(text)
            .font(.system(size: 9, weight: .bold))
            .foregroundStyle(color)
            .padding(.horizontal, 6)
            .padding(.vertical, 3)
            .background(color.opacity(0.15), in: Capsule())
    }

    private func errorBanner(_ message: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(CricTheme.danger)
                .font(.footnote)
            Text(message)
                .font(.footnote)
                .foregroundStyle(CricTheme.danger)
                .lineLimit(2)
            Spacer()
            Button { viewModel.error = nil } label: {
                Image(systemName: "xmark")
                    .font(.caption)
                    .foregroundStyle(CricTheme.textDim)
            }
        }
        .padding(12)
        .background(CricTheme.danger.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
    }
}
