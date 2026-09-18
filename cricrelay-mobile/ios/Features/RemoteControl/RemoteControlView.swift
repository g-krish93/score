import SwiftUI
import AVFoundation
import UIKit

// MARK: - Companion remote control (scan QR → preview + camera commands + sponsors)

struct RemoteControlView: View {
    var initialPairPayload: String? = nil

    @State private var phase: Phase = .scan
    @State private var matchSlug = ""
    @State private var companionToken = ""
    @State private var statusMessage = ""
    @State private var error: String?
    @State private var sponsors: [Sponsor] = []
    @State private var sponsorPrefs = OverlayLayoutPrefs()
    @State private var watchUrl = ""
    @State private var contextLoading = false
    @State private var sponsorSendTask: Task<Void, Never>?
    @State private var previewPollTask: Task<Void, Never>?
    @State private var zoomSendTask: Task<Void, Never>?
    @State private var previewImage: UIImage?
    @State private var previewStale = true
    @State private var camera = RemoteCameraState()
    @State private var zoomDraft: Float = 1
    @State private var pendingAck: String?
    @State private var confirmCommand: String?
    @State private var ackTimeoutTask: Task<Void, Never>?
    @State private var pendingMute: Bool?
    @State private var pendingLock: Bool?
    @State private var pendingPaused: Bool?
    @State private var selectedCameraId = RemoteCameraIds.endA
    @State private var liveCameraId: String?
    @State private var cameras: [RemoteCameraInfo] = []
    @State private var confirmTakeLive = false
    @State private var camerasPollTask: Task<Void, Never>?
    @State private var healthAlert: String?
    @State private var sawStreaming = false
    @State private var pendingMetrics: [[String: Any]] = []
    @State private var metricsFlushTask: Task<Void, Never>?
    @State private var pairedAt: Date?
    @State private var firstPreviewLogged = false
    @State private var commandSentAt: Date?

    private let api = CricRelayAPI.shared

    enum Phase {
        case scan, controls
    }

    var body: some View {
        StudioBackdrop {
            switch phase {
            case .scan:
                scanContent
            case .controls:
                controlsContent
            }
        }
        .navigationTitle("Remote Control")
        .navigationBarTitleDisplayMode(.inline)
        .preferredColorScheme(.dark)
        .confirmationDialog(
            confirmCommand == "stop_broadcast" ? "Stop broadcast?" : "Start broadcast?",
            isPresented: Binding(
                get: { confirmCommand != nil },
                set: { if !$0 { confirmCommand = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button(confirmCommand == "stop_broadcast" ? "Stop" : "Go live", role: .destructive) {
                if let cmd = confirmCommand {
                    confirmCommand = nil
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    Task { await sendCommand(cmd) }
                }
            }
            Button("Cancel", role: .cancel) { confirmCommand = nil }
        } message: {
            Text(
                confirmCommand == "stop_broadcast"
                    ? "Viewers will lose the live feed until you go live again."
                    : "The tripod phone will go live to the configured destination."
            )
        }
        .onAppear {
            restoreSession()
            redeemPendingDeepLinkIfNeeded()
        }
        .onDisappear {
            previewPollTask?.cancel()
            camerasPollTask?.cancel()
            zoomSendTask?.cancel()
            sponsorSendTask?.cancel()
        }
    }

    private var scanContent: some View {
        VStack(spacing: 20) {
            Text("Scan the Pair Remote QR code shown on the broadcasting phone.")
                .font(.subheadline)
                .foregroundStyle(CricTheme.textMuted)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 16)

            QRScannerView { payload in
                await handleScan(payload)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 320)
            .clipShape(RoundedRectangle(cornerRadius: 16))
            .padding(.horizontal, 16)

            if let error {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(CricTheme.danger)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 16)
            }
        }
        .padding(.top, 16)
    }

    private var controlsContent: some View {
        ScrollView {
            VStack(spacing: 16) {
                Text("Paired to \(matchSlug)")
                    .font(.subheadline.bold())
                    .foregroundStyle(.white)

                if !statusMessage.isEmpty {
                    Text(statusMessage)
                        .font(.caption)
                        .foregroundStyle(CricTheme.accent)
                }
                if let error {
                    Text(error)
                        .font(.caption)
                        .foregroundStyle(CricTheme.danger)
                }

                dualEndStrip

                if let healthAlert {
                    HStack(alignment: .top, spacing: 10) {
                        Text(healthAlert)
                            .font(.caption)
                            .foregroundStyle(.white)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        Button("Dismiss") { self.healthAlert = nil }
                            .font(.caption.bold())
                            .foregroundStyle(CricTheme.warning)
                            .accessibilityLabel("Dismiss health alert")
                    }
                    .padding(12)
                    .background(CricTheme.warning.opacity(0.22), in: RoundedRectangle(cornerRadius: 10))
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel("Stream health alert: \(healthAlert)")
                }

                previewPane
                    .accessibilityLabel(previewAccessibilityLabel)

                if !watchUrl.isEmpty {
                    ShareLink(item: watchUrl, subject: Text("Watch live on CricRelay")) {
                        Label("Share watch link", systemImage: "square.and.arrow.up")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .tint(CricTheme.accent)
                    .accessibilityLabel("Share watch party link with viewers")
                    .simultaneousGesture(TapGesture().onEnded {
                        emitMetric("share_watch")
                    })
                }

                let zoomMin = min(camera.zoomMin, camera.zoomMax)
                let zoomMax = max(camera.zoomMax, zoomMin + 0.01)
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text("Zoom")
                            .font(.subheadline)
                            .foregroundStyle(CricTheme.textMuted)
                        Spacer()
                        Text(String(format: "%.1f×", zoomDraft))
                            .font(.subheadline.monospacedDigit())
                            .foregroundStyle(CricTheme.primary)
                    }
                    Slider(
                        value: Binding(
                            get: { Double(zoomDraft) },
                            set: { newVal in
                                zoomDraft = Float(newVal)
                                scheduleZoomSend(Float(newVal))
                            }
                        ),
                        in: Double(zoomMin)...Double(zoomMax)
                    )
                    .tint(CricTheme.primary)
                }

                HStack(spacing: 8) {
                    stabChip("Off", level: 0)
                    stabChip("Standard", level: 1)
                    stabChip("Cinematic", level: 2)
                }
                if camera.streaming {
                    Text("Stabilization is locked while live")
                        .font(.caption)
                        .foregroundStyle(CricTheme.textDim)
                }

                Button {
                    confirmTakeLive = true
                } label: {
                    Label("Take live", systemImage: "arrow.triangle.2.circlepath.camera")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(CricTheme.primary)
                .disabled(busyForTakeLive)
                .confirmationDialog(
                    "Take live on \(RemoteCameraIds.label(selectedCameraId))?",
                    isPresented: $confirmTakeLive,
                    titleVisibility: .visible
                ) {
                    Button("Take live", role: .destructive) {
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        Task { await takeLive() }
                    }
                    Button("Cancel", role: .cancel) {}
                } message: {
                    Text("The other end soft-stops. This phone publishes on the same YouTube/RTMP feed.")
                }

                controlButton("Start broadcast", icon: "play.fill", command: "start_broadcast", enabled: !camera.streaming, confirm: true)
                controlButton("Stop broadcast", icon: "stop.fill", command: "stop_broadcast", enabled: camera.streaming, confirm: true)
                controlButton(
                    camera.paused ? "Resume" : "Pause",
                    icon: camera.paused ? "play.fill" : "pause.fill",
                    command: camera.paused ? "resume_broadcast" : "pause_broadcast",
                    enabled: camera.streaming && pendingAck == nil
                )
                controlButton(
                    camera.muted ? "Unmute mic" : "Mute mic",
                    icon: camera.muted ? "mic.fill" : "mic.slash.fill",
                    command: "set_mute",
                    enabled: pendingAck == nil,
                    payload: ["muted": !camera.muted]
                )
                controlButton(
                    camera.locked ? "Unlock focus" : "Lock focus",
                    icon: camera.locked ? "lock.open.fill" : "lock.fill",
                    command: "set_focus_lock",
                    enabled: pendingAck == nil,
                    payload: ["locked": !camera.locked]
                )

                Divider().overlay(Color.white.opacity(0.1))

                sponsorSection

                Button("Unpair") {
                    previewPollTask?.cancel()
                    camerasPollTask?.cancel()
                    CompanionTokenStore.clear()
                    phase = .scan
                    matchSlug = ""
                    companionToken = ""
                    statusMessage = ""
                    sponsors = []
                    sponsorPrefs = OverlayLayoutPrefs()
                    previewImage = nil
                    previewStale = true
                    camera = RemoteCameraState()
                    cameras = []
                    liveCameraId = nil
                    selectedCameraId = RemoteCameraIds.endA
                }
                .font(.subheadline)
                .foregroundStyle(CricTheme.danger)
                .padding(.top, 12)
            }
            .padding(24)
        }
    }

    private var busyForTakeLive: Bool {
        selectedCameraId == liveCameraId
    }

    private var previewAccessibilityLabel: String {
        var parts = ["Camera preview"]
        if camera.reconnecting { parts.append("reconnecting") }
        else if camera.paused { parts.append("paused") }
        else if camera.streaming { parts.append("live") }
        else { parts.append("idle") }
        if previewStale { parts.append("stale") }
        parts.append("Double tap to focus")
        return parts.joined(separator: ", ")
    }

    private var dualEndStrip: some View {
        HStack(spacing: 8) {
            ForEach(RemoteCameraIds.all, id: \.self) { id in
                let selected = selectedCameraId == id
                let isLive = liveCameraId == id
                Button {
                    selectedCameraId = id
                } label: {
                    VStack(spacing: 6) {
                        Text(RemoteCameraIds.label(id))
                            .font(.subheadline.bold())
                            .foregroundStyle(.white)
                        if isLive {
                            Text("LIVE")
                                .font(.caption2.bold())
                                .foregroundStyle(CricTheme.danger)
                        } else if cameras.first(where: { $0.cameraId == id })?.stale == false {
                            Text("Ready")
                                .font(.caption2)
                                .foregroundStyle(CricTheme.accent)
                        } else {
                            Text("—")
                                .font(.caption2)
                                .foregroundStyle(CricTheme.textDim)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .background(
                        RoundedRectangle(cornerRadius: 10)
                            .fill(selected ? CricTheme.primary.opacity(0.35) : CricTheme.surface)
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 10)
                            .stroke(selected ? CricTheme.primary : Color.white.opacity(0.15), lineWidth: selected ? 2 : 1)
                    )
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(RemoteCameraIds.label(id))\(selected ? ", selected" : "")\(isLive ? ", live" : "")")
                .accessibilityAddTraits(.isButton)
            }
        }
    }

    private var previewPane: some View {
        ZStack(alignment: .top) {
            Group {
                if let previewImage {
                    Image(uiImage: previewImage)
                        .resizable()
                        .scaledToFill()
                } else {
                    Text("Waiting for camera preview…")
                        .font(.subheadline)
                        .foregroundStyle(CricTheme.textMuted)
                }
            }
            .frame(maxWidth: .infinity)
            .frame(height: 280)
            .background(CricTheme.surface)
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .overlay(
                GeometryReader { geo in
                    Color.clear.contentShape(Rectangle())
                        .onTapGesture { location in
                            let nx = Float(location.x / max(geo.size.width, 1))
                            let ny = Float(location.y / max(geo.size.height, 1))
                            Task { await sendTapFocus(nx: nx, ny: ny) }
                        }
                }
            )

            HStack(spacing: 8) {
                let phaseLabel: String = {
                    if camera.reconnecting { return "RECONNECTING" }
                    if camera.paused { return "PAUSED" }
                    if camera.streaming { return "LIVE" }
                    return "IDLE"
                }()
                Text(phaseLabel)
                    .font(.caption.bold())
                    .foregroundStyle(
                        camera.reconnecting ? CricTheme.warning
                            : (camera.streaming && !camera.paused ? CricTheme.danger : .white)
                    )
                if let kbps = camera.bitrateKbps, kbps > 0 {
                    Text("\(kbps) kbps").font(.caption2).foregroundStyle(.white.opacity(0.85))
                }
                if camera.thermal >= 3 {
                    Text("HOT").font(.caption2.bold()).foregroundStyle(CricTheme.warning)
                }
                Spacer()
                if camera.muted { Text("MUTED").font(.caption2).foregroundStyle(CricTheme.warning) }
                if camera.locked { Text("AF LOCK").font(.caption2).foregroundStyle(CricTheme.accent) }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .frame(maxWidth: .infinity)
            .background(Color.black.opacity(0.45))

            Text("YouTube is ~15–30s behind this preview")
                .font(.caption2)
                .foregroundStyle(.white.opacity(0.85))
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .background(Color.black.opacity(0.4), in: Capsule())
                .frame(maxHeight: .infinity, alignment: .bottom)
                .padding(.bottom, 8)

            if previewStale {
                Text("Camera offline")
                    .font(.caption.bold())
                    .foregroundStyle(.white)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(CricTheme.danger.opacity(0.85), in: Capsule())
                    .padding(.top, 36)
            }
            if let pendingAck {
                Text(pendingAck)
                    .font(.caption.bold())
                    .foregroundStyle(.white)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Color.black.opacity(0.65), in: Capsule())
                    .frame(maxHeight: .infinity, alignment: .center)
            }
        }
    }

    private func stabChip(_ label: String, level: Int) -> some View {
        let selected = camera.stab == level
        return Button {
            guard !camera.streaming else { return }
            Task { await sendStabilization(level) }
        } label: {
            Text(label)
                .font(.caption.weight(selected ? .bold : .regular))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
                .background(
                    selected ? CricTheme.primary.opacity(0.35) : CricTheme.surface,
                    in: RoundedRectangle(cornerRadius: 10)
                )
                .foregroundStyle(camera.streaming ? CricTheme.textDim : .white)
        }
        .disabled(camera.streaming)
    }

    private var sponsorSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Sponsor overlay")
                .font(.headline)
                .foregroundStyle(.white)
            Text("Changes apply on the broadcast phone.")
                .font(.caption)
                .foregroundStyle(CricTheme.textDim)
            if !watchUrl.isEmpty {
                Text("Watch live: \(watchUrl)")
                    .font(.caption)
                    .foregroundStyle(CricTheme.accent)
            }
            if contextLoading {
                Text("Loading sponsor settings…")
                    .font(.caption)
                    .foregroundStyle(CricTheme.textMuted)
            }

            Toggle("Sponsor logo", isOn: Binding(
                get: { sponsorPrefs.sponsorEnabled },
                set: { sponsorPrefs.sponsorEnabled = $0; scheduleSponsorSend() }
            ))
            .tint(CricTheme.primary)

            if sponsorPrefs.sponsorEnabled {
                let active = sponsors.filter(\.isActive)
                if !active.isEmpty {
                    Text("How to show")
                        .font(.caption)
                        .foregroundStyle(CricTheme.textDim)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(SponsorLayoutMode.modes, id: \.id) { mode in
                                Button {
                                    sponsorPrefs.sponsorLayoutMode = mode.id
                                    if !SponsorLayoutMode.allowsMultiSelect(mode.id), sponsorPrefs.activeSponsorIds.count > 1 {
                                        sponsorPrefs.activeSponsorIds = Array(sponsorPrefs.activeSponsorIds.prefix(1))
                                        sponsorPrefs.activeSponsorId = sponsorPrefs.activeSponsorIds.first
                                    }
                                    scheduleSponsorSend()
                                } label: {
                                    Text(mode.label)
                                        .font(.caption.weight(sponsorPrefs.sponsorLayoutMode == mode.id ? .bold : .regular))
                                        .padding(.horizontal, 10)
                                        .padding(.vertical, 8)
                                        .background(
                                            sponsorPrefs.sponsorLayoutMode == mode.id ? CricTheme.primary.opacity(0.35) : CricTheme.surface,
                                            in: Capsule()
                                        )
                                }
                            }
                        }
                    }
                    Text(SponsorLayoutMode.allowsMultiSelect(sponsorPrefs.sponsorLayoutMode) ? "Select sponsors" : "Select sponsor")
                        .font(.caption)
                        .foregroundStyle(CricTheme.textDim)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(active) { sponsor in
                                Button {
                                    if SponsorLayoutMode.allowsMultiSelect(sponsorPrefs.sponsorLayoutMode) {
                                        if sponsorPrefs.activeSponsorIds.contains(sponsor.id) {
                                            sponsorPrefs.activeSponsorIds.removeAll { $0 == sponsor.id }
                                        } else if sponsorPrefs.activeSponsorIds.count < 6 {
                                            sponsorPrefs.activeSponsorIds.append(sponsor.id)
                                        }
                                        sponsorPrefs.activeSponsorId = sponsorPrefs.activeSponsorIds.first
                                    } else {
                                        sponsorPrefs.activeSponsorIds = [sponsor.id]
                                        sponsorPrefs.activeSponsorId = sponsor.id
                                    }
                                    scheduleSponsorSend()
                                } label: {
                                    let selected = sponsorPrefs.activeSponsorIds.contains(sponsor.id) ||
                                        (sponsorPrefs.activeSponsorIds.isEmpty && sponsorPrefs.activeSponsorId == sponsor.id)
                                    Text(sponsor.name)
                                        .font(.caption.weight(selected ? .bold : .regular))
                                        .foregroundStyle(selected ? CricTheme.onPrimary : .white)
                                        .padding(.horizontal, 12)
                                        .padding(.vertical, 8)
                                        .background(
                                            selected ? CricTheme.primary : CricTheme.surface,
                                            in: Capsule()
                                        )
                                }
                            }
                        }
                    }
                }

                Text("Display mode")
                    .font(.caption)
                    .foregroundStyle(CricTheme.textDim)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(SponsorDisplayMode.modes, id: \.id) { mode in
                            Button {
                                sponsorPrefs.sponsorDisplayMode = mode.id
                                scheduleSponsorSend()
                            } label: {
                                Text(mode.label)
                                    .font(.caption.weight(sponsorPrefs.sponsorDisplayMode == mode.id ? .bold : .regular))
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 8)
                                    .background(
                                        sponsorPrefs.sponsorDisplayMode == mode.id ? CricTheme.primary.opacity(0.35) : CricTheme.surface,
                                        in: Capsule()
                                    )
                            }
                        }
                    }
                }

                remoteSlider(
                    label: "Logo size",
                    value: $sponsorPrefs.sponsorSizeScale,
                    range: 0.3...3.0,
                    format: { "\(Int($0 * 100))%" }
                )
                remoteSlider(
                    label: "Logo opacity",
                    value: $sponsorPrefs.sponsorOpacity,
                    range: 0.2...1.0,
                    format: { "\(Int($0 * 100))%" }
                )
                if SponsorDisplayMode.isScroll(sponsorPrefs.sponsorDisplayMode) {
                    remoteSlider(
                        label: "Scroll speed",
                        value: $sponsorPrefs.sponsorScrollSpeed,
                        range: 0.3...3.0,
                        format: { String(format: "%.1f×", $0) }
                    )
                } else {
                    remoteSlider(
                        label: "Horizontal position",
                        value: $sponsorPrefs.sponsorPositionX,
                        range: 0...1,
                        format: { "\(Int($0 * 100))%" }
                    )
                    remoteSlider(
                        label: "Vertical position",
                        value: $sponsorPrefs.sponsorPositionY,
                        range: 0...1,
                        format: { "\(Int($0 * 100))%" }
                    )
                }
                if sponsorPrefs.sponsorLayoutMode == SponsorLayoutMode.carousel {
                    remoteSlider(
                        label: "Carousel interval",
                        value: $sponsorPrefs.sponsorCarouselIntervalSec,
                        range: 2...30,
                        format: { "\(Int($0))s" }
                    )
                }
            }

            Button("Refresh from broadcast") {
                Task { await loadContext() }
            }
            .font(.caption)
            .foregroundStyle(CricTheme.textMuted)
        }
    }

    private func remoteSlider(
        label: String,
        value: Binding<Double>,
        range: ClosedRange<Double>,
        format: (Double) -> String
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(label)
                    .font(.subheadline)
                    .foregroundStyle(CricTheme.textMuted)
                Spacer()
                Text(format(value.wrappedValue))
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(CricTheme.primary)
            }
            Slider(value: value, in: range)
                .tint(CricTheme.primary)
                .onChange(of: value.wrappedValue) { _ in scheduleSponsorSend() }
        }
    }

    private func controlButton(
        _ label: String,
        icon: String,
        command: String,
        enabled: Bool = true,
        confirm: Bool = false,
        payload: [String: Any]? = nil
    ) -> some View {
        Button {
            if confirm {
                confirmCommand = command
                return
            }
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            Task { await sendCommand(command, payload: payload) }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(CricTheme.primary)
                    .frame(width: 36, height: 36)
                    .background(CricTheme.primary.opacity(0.14), in: RoundedRectangle(cornerRadius: 10))
                Text(label)
                    .font(.subheadline.bold())
                    .foregroundStyle(.white)
                Spacer()
            }
            .padding(14)
            .background(CricTheme.surface, in: RoundedRectangle(cornerRadius: 14))
            .opacity(enabled ? 1 : 0.45)
        }
        .buttonStyle(PressableScaleStyle())
        .disabled(!enabled)
    }

    private func restoreSession() {
        if let saved = CompanionTokenStore.load() {
            companionToken = saved.token
            matchSlug = saved.slug
            phase = .controls
            Task {
                await loadContext()
                startPreviewPolling()
            }
        }
    }

    /// Redeem a system-camera deep link (`cricrelay://pair?…`) if one is waiting.
    private func redeemPendingDeepLinkIfNeeded() {
        let payload = initialPairPayload ?? PairDeepLinkStore.consume()
        guard let payload, !payload.isEmpty else { return }
        PairDeepLinkStore.pendingUri = nil
        Task {
            _ = await handleScan(payload)
        }
    }

    /// Returns false when the payload is rejected (bad QR or failed redeem) so the
    /// scanner resumes and the operator can simply try again.
    private func handleScan(_ payload: String) async -> Bool {
        error = nil
        guard let link = PairDeepLinkParser.parse(payload) else {
            error = "Not a CricRelay pairing code"
            return false
        }
        // Point the shared API at the QR host (keeps any existing club token).
        api.configure(baseUrl: link.apiBase, token: api.token)
        UserDefaults.standard.set(link.apiBase, forKey: "stream_api_base")
        do {
            let session = try await api.redeemPairToken(slug: link.slug, pairToken: link.token)
            companionToken = session.companionToken
            matchSlug = session.matchSlug
            CompanionTokenStore.save(token: companionToken, slug: matchSlug)
            phase = .controls
            statusMessage = "Paired successfully"
            pairedAt = Date()
            firstPreviewLogged = false
            emitMetric("pair_ok")
            await loadContext()
            startPreviewPolling()
            return true
        } catch {
            emitMetric("pair_fail")
            self.error = error.localizedDescription
            return false
        }
    }

    private func startPreviewPolling() {
        previewPollTask?.cancel()
        previewPollTask = Task {
            while !Task.isCancelled {
                await pollPreviewOnce()
                try? await Task.sleep(nanoseconds: 400_000_000)
            }
        }
        startCamerasPolling()
    }

    private func startCamerasPolling() {
        camerasPollTask?.cancel()
        camerasPollTask = Task {
            while !Task.isCancelled {
                await pollCamerasOnce()
                try? await Task.sleep(nanoseconds: 1_200_000_000)
            }
        }
    }

    private func pollCamerasOnce() async {
        guard !matchSlug.isEmpty, !companionToken.isEmpty else { return }
        if let snap = try? await api.listRemoteCameras(slug: matchSlug, companionToken: companionToken) {
            cameras = snap.cameras
            if let live = snap.liveCameraId {
                liveCameraId = live
            }
        }
    }

    private func pollPreviewOnce() async {
        guard !matchSlug.isEmpty, !companionToken.isEmpty else { return }
        do {
            let frame = try await api.getRemotePreview(
                slug: matchSlug,
                companionToken: companionToken,
                cameraId: selectedCameraId
            )
            let previous = camera
            if let b64 = frame.jpegB64, let data = Data(base64Encoded: b64), let image = UIImage(data: data) {
                previewImage = image
                logFirstPreviewIfNeeded()
            }
            previewStale = frame.stale || previewImage == nil
            if let live = frame.liveCameraId {
                liveCameraId = live
            }
            if let state = frame.state {
                camera = state
                reconcileAck(with: state)
                if zoomSendTask == nil {
                    zoomDraft = state.zoom
                }
                if state.streaming { sawStreaming = true }
                if let alert = deriveHealthAlert(camera: state, stale: previewStale, previous: previous) {
                    healthAlert = alert
                }
            }
        } catch {
            previewStale = true
        }
    }

    private func deriveHealthAlert(camera: RemoteCameraState, stale: Bool, previous: RemoteCameraState) -> String? {
        if camera.reconnecting {
            return "Broadcast reconnecting — check signal on the camera phone"
        }
        if camera.thermal >= 3 {
            return "Camera phone is hot — bitrate may drop"
        }
        if stale && (camera.streaming || sawStreaming) {
            return "Camera offline — preview frozen"
        }
        if sawStreaming && previous.streaming && !camera.streaming && !camera.paused {
            return "Broadcast stopped or lost on the camera phone"
        }
        return nil
    }

    private func emitMetric(_ name: String, value: Int? = nil) {
        var row: [String: Any] = ["name": name]
        if let value { row["value"] = value }
        pendingMetrics.append(row)
        scheduleMetricsFlush()
    }

    private func logFirstPreviewIfNeeded() {
        guard !firstPreviewLogged, let pairedAt else { return }
        firstPreviewLogged = true
        let ms = Int(Date().timeIntervalSince(pairedAt) * 1000)
        emitMetric("preview_first_frame_ms", value: max(0, ms))
    }

    private func scheduleMetricsFlush() {
        guard metricsFlushTask == nil else { return }
        metricsFlushTask = Task {
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            metricsFlushTask = nil
            await flushMetrics()
        }
    }

    private func flushMetrics() async {
        guard !matchSlug.isEmpty, !companionToken.isEmpty, !pendingMetrics.isEmpty else { return }
        let batch = pendingMetrics
        pendingMetrics = []
        _ = try? await api.postRemoteMetrics(slug: matchSlug, companionToken: companionToken, events: batch)
    }

    private func withCameraPayload(_ payload: [String: Any]?) -> [String: Any] {
        var out = payload ?? [:]
        out["camera_id"] = selectedCameraId
        return out
    }

    private func takeLive() async {
        guard selectedCameraId != liveCameraId else { return }
        emitMetric("take_live")
        await sendCommand("take_live", payload: ["camera_id": selectedCameraId])
        liveCameraId = selectedCameraId
        statusMessage = "Take live → \(RemoteCameraIds.label(selectedCameraId))"
    }

    private func reconcileAck(with state: RemoteCameraState) {
        var cleared = false
        if let want = pendingMute, state.muted == want {
            pendingMute = nil
            cleared = true
        }
        if let want = pendingLock, state.locked == want {
            pendingLock = nil
            cleared = true
        }
        if let want = pendingPaused, state.paused == want {
            pendingPaused = nil
            cleared = true
        }
        if cleared, pendingMute == nil, pendingLock == nil, pendingPaused == nil {
            ackTimeoutTask?.cancel()
            pendingAck = nil
            statusMessage = "Applied on camera"
            if let commandSentAt {
                let ms = Int(Date().timeIntervalSince(commandSentAt) * 1000)
                emitMetric("command_ack_ms", value: max(0, ms))
                self.commandSentAt = nil
            }
        }
    }

    private func scheduleZoomSend(_ level: Float) {
        zoomSendTask?.cancel()
        zoomSendTask = Task {
            try? await Task.sleep(nanoseconds: 200_000_000)
            guard !Task.isCancelled else { return }
            defer { zoomSendTask = nil }
            await sendZoom(level)
        }
    }

    private func sendZoom(_ level: Float) async {
        guard !matchSlug.isEmpty, !companionToken.isEmpty else { return }
        do {
            try await api.sendRemoteCommand(
                slug: matchSlug,
                command: "set_zoom",
                companionToken: companionToken,
                payload: withCameraPayload(["level": level])
            )
            statusMessage = "Sent set zoom"
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func sendTapFocus(nx: Float, ny: Float) async {
        guard !matchSlug.isEmpty, !companionToken.isEmpty else { return }
        do {
            try await api.sendRemoteCommand(
                slug: matchSlug,
                command: "tap_focus",
                companionToken: companionToken,
                payload: withCameraPayload(["nx": nx, "ny": ny])
            )
            statusMessage = "Sent tap focus"
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func sendStabilization(_ level: Int) async {
        guard !matchSlug.isEmpty, !companionToken.isEmpty else { return }
        do {
            try await api.sendRemoteCommand(
                slug: matchSlug,
                command: "set_stabilization",
                companionToken: companionToken,
                payload: withCameraPayload(["level": level])
            )
            camera.stab = StabilizationLevel.sanitize(level)
            statusMessage = "Sent stabilization"
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func sendCommand(_ command: String, payload: [String: Any]? = nil) async {
        guard !matchSlug.isEmpty, !companionToken.isEmpty else { return }
        if command == "set_mute", let muted = payload?["muted"] as? Bool {
            pendingMute = muted
            pendingAck = muted ? "Muting…" : "Unmuting…"
        } else if command == "set_focus_lock", let locked = payload?["locked"] as? Bool {
            pendingLock = locked
            pendingAck = locked ? "Locking focus…" : "Unlocking focus…"
        } else if command == "pause_broadcast" {
            pendingPaused = true
            pendingAck = "Pausing…"
        } else if command == "resume_broadcast" {
            pendingPaused = false
            pendingAck = "Resuming…"
        }
        if pendingAck != nil {
            commandSentAt = Date()
            ackTimeoutTask?.cancel()
            ackTimeoutTask = Task {
                try? await Task.sleep(nanoseconds: 3_500_000_000)
                guard !Task.isCancelled else { return }
                if pendingAck != nil {
                    pendingMute = nil
                    pendingLock = nil
                    pendingPaused = nil
                    pendingAck = nil
                    commandSentAt = nil
                    emitMetric("command_ack_timeout")
                    statusMessage = "Camera did not confirm — check the broadcast phone"
                }
            }
        }
        do {
            try await api.sendRemoteCommand(
                slug: matchSlug,
                command: command,
                companionToken: companionToken,
                payload: withCameraPayload(payload)
            )
            if command == "toggle_sponsor" {
                sponsorPrefs.sponsorEnabled.toggle()
            }
            statusMessage = pendingAck ?? "Sent \(command.replacingOccurrences(of: "_", with: " "))"
        } catch {
            pendingMute = nil
            pendingLock = nil
            pendingPaused = nil
            pendingAck = nil
            statusMessage = ""
            self.error = error.localizedDescription
        }
    }

    private func scheduleSponsorSend() {
        sponsorSendTask?.cancel()
        sponsorSendTask = Task {
            try? await Task.sleep(for: .milliseconds(120))
            guard !Task.isCancelled else { return }
            await sendSponsorPrefs()
        }
    }

    private func sendSponsorPrefs() async {
        guard !matchSlug.isEmpty, !companionToken.isEmpty else { return }
        do {
            try await api.sendRemoteOverlayPrefs(
                slug: matchSlug,
                prefs: sponsorPrefs,
                companionToken: companionToken
            )
            statusMessage = "Sponsor updated on broadcast phone"
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func loadContext() async {
        guard !matchSlug.isEmpty, !companionToken.isEmpty else { return }
        contextLoading = true
        defer { contextLoading = false }
        do {
            let ctx = try await api.getRemoteContext(slug: matchSlug, companionToken: companionToken)
            sponsors = ctx.sponsors
            sponsorPrefs = ctx.sponsorPrefs
            watchUrl = ctx.watchUrl
        } catch {
            self.error = error.localizedDescription
        }
    }
}

// MARK: - QR scanner (AVCaptureMetadataOutput)

struct QRScannerView: UIViewControllerRepresentable {
    /// Return true when the payload was accepted; false resumes scanning for another attempt.
    var onScan: (String) async -> Bool

    func makeUIViewController(context: Context) -> QRScannerViewController {
        let vc = QRScannerViewController()
        vc.onScan = onScan
        return vc
    }

    func updateUIViewController(_ uiViewController: QRScannerViewController, context: Context) {
        uiViewController.onScan = onScan
    }
}

final class QRScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onScan: ((String) async -> Bool)?
    private let session = AVCaptureSession()
    // AVCaptureSession is not thread-safe: every startRunning/stopRunning goes through this
    // one serial queue (they block, so never on main). didReport stays main-thread-only.
    private let sessionQueue = DispatchQueue(label: "uk.co.cricrelay.qr-scanner")
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var didReport = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device) else { return }
        if session.canAddInput(input) { session.addInput(input) }
        let output = AVCaptureMetadataOutput()
        if session.canAddOutput(output) {
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
            output.metadataObjectTypes = [.qr]
        }
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.layer.bounds
        view.layer.addSublayer(layer)
        previewLayer = layer
        sessionQueue.async { [weak self] in
            self?.session.startRunning()
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.layer.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        sessionQueue.async { [weak self] in
            guard let self, self.session.isRunning else { return }
            self.session.stopRunning()
        }
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard !didReport,
              let obj = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              obj.type == .qr,
              let value = obj.stringValue,
              let onScan else { return }
        didReport = true
        sessionQueue.async { [weak self] in self?.session.stopRunning() }
        // Hand the payload to SwiftUI; a rejected scan (bad QR, failed redeem) resumes
        // scanning so the operator can try again instead of a permanently frozen camera.
        Task { @MainActor [weak self] in
            let accepted = await onScan(value)
            if !accepted { self?.resumeScanning() }
        }
    }

    private func resumeScanning() {
        didReport = false
        sessionQueue.async { [weak self] in
            guard let self, !self.session.isRunning else { return }
            self.session.startRunning()
        }
    }
}
