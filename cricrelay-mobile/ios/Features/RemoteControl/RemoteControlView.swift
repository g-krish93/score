import SwiftUI
import AVFoundation
import UIKit

// MARK: - Companion remote control (scan QR → preview + camera commands + sponsors)

struct RemoteControlView: View {
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
        .onAppear { restoreSession() }
        .onDisappear {
            previewPollTask?.cancel()
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

                previewPane

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

                controlButton("Start broadcast", icon: "play.fill", command: "start_broadcast", enabled: !camera.streaming)
                controlButton("Stop broadcast", icon: "stop.fill", command: "stop_broadcast", enabled: camera.streaming)
                controlButton(
                    camera.paused ? "Resume" : "Pause",
                    icon: camera.paused ? "play.fill" : "pause.fill",
                    command: camera.paused ? "resume_broadcast" : "pause_broadcast",
                    enabled: camera.streaming
                ) {
                    camera.paused.toggle()
                }
                controlButton(
                    camera.muted ? "Unmute mic" : "Mute mic",
                    icon: camera.muted ? "mic.fill" : "mic.slash.fill",
                    command: "mute_mic"
                ) {
                    camera.muted.toggle()
                }
                controlButton(
                    camera.locked ? "Unlock focus" : "Lock focus",
                    icon: camera.locked ? "lock.open.fill" : "lock.fill",
                    command: "toggle_focus_lock"
                ) {
                    camera.locked.toggle()
                }

                Divider().overlay(Color.white.opacity(0.1))

                sponsorSection

                Button("Unpair") {
                    previewPollTask?.cancel()
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
                }
                .font(.subheadline)
                .foregroundStyle(CricTheme.danger)
                .padding(.top, 12)
            }
            .padding(24)
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
            .frame(height: 220)
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

            if previewStale {
                Text("Camera offline")
                    .font(.caption.bold())
                    .foregroundStyle(.white)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(CricTheme.danger.opacity(0.85), in: Capsule())
                    .padding(.top, 8)
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
        onOptimistic: (() -> Void)? = nil
    ) -> some View {
        Button {
            onOptimistic?()
            Task { await sendCommand(command) }
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

    /// Returns false when the payload is rejected (bad QR or failed redeem) so the
    /// scanner resumes and the operator can simply try again.
    private func handleScan(_ payload: String) async -> Bool {
        error = nil
        guard let components = URLComponents(string: payload),
              components.scheme == "cricrelay",
              components.host == "pair" else {
            error = "Not a CricRelay pairing code"
            return false
        }
        // uniquingKeysWith: a scanned QR is external input — a repeated query key must not trap.
        let items = Dictionary(
            (components.queryItems ?? []).map { ($0.name, $0.value ?? "") },
            uniquingKeysWith: { first, _ in first }
        )
        guard let slug = items["slug"], !slug.isEmpty,
              let token = items["token"], !token.isEmpty else {
            error = "Invalid pairing code"
            return false
        }
        do {
            let session = try await api.redeemPairToken(slug: slug, pairToken: token)
            companionToken = session.companionToken
            matchSlug = session.matchSlug
            CompanionTokenStore.save(token: companionToken, slug: matchSlug)
            phase = .controls
            statusMessage = "Paired successfully"
            await loadContext()
            startPreviewPolling()
            return true
        } catch {
            self.error = error.localizedDescription
            return false
        }
    }

    private func startPreviewPolling() {
        previewPollTask?.cancel()
        previewPollTask = Task {
            while !Task.isCancelled {
                await pollPreviewOnce()
                try? await Task.sleep(nanoseconds: 750_000_000)
            }
        }
    }

    private func pollPreviewOnce() async {
        guard !matchSlug.isEmpty, !companionToken.isEmpty else { return }
        do {
            let frame = try await api.getRemotePreview(slug: matchSlug, companionToken: companionToken)
            if let b64 = frame.jpegB64, let data = Data(base64Encoded: b64), let image = UIImage(data: data) {
                previewImage = image
            }
            previewStale = frame.stale || previewImage == nil
            if let state = frame.state {
                camera = state
                if zoomSendTask == nil {
                    zoomDraft = state.zoom
                }
            }
        } catch {
            previewStale = true
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
                payload: ["level": level]
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
                payload: ["nx": nx, "ny": ny]
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
                payload: ["level": level]
            )
            camera.stab = StabilizationLevel.sanitize(level)
            statusMessage = "Sent stabilization"
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func sendCommand(_ command: String) async {
        guard !matchSlug.isEmpty, !companionToken.isEmpty else { return }
        do {
            try await api.sendRemoteCommand(slug: matchSlug, command: command, companionToken: companionToken)
            if command == "toggle_sponsor" {
                sponsorPrefs.sponsorEnabled.toggle()
            }
            statusMessage = "Sent \(command.replacingOccurrences(of: "_", with: " "))"
        } catch {
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
