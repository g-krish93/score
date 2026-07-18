import SwiftUI
import AVFoundation
import HaishinKit

// MARK: - Camera preview UIViewRepresentable

struct CameraPreviewView: UIViewRepresentable {
    /// Single-finger tap on the preview, reported with its location and the view's size. A
    /// UITapGestureRecognizer only fires for a stationary one-finger tap, so it never collides with
    /// a two-finger pinch-to-zoom — no movement thresholds or timing windows needed.
    var onTap: (CGPoint, CGSize) -> Void = { _, _ in }

    func makeCoordinator() -> Coordinator { Coordinator(onTap: onTap) }

    func makeUIView(context: Context) -> MTHKView {
        let view = MTHKView(frame: .zero)
        StreamCameraEngine.shared.attachView(view)
        let tap = UITapGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handleTap(_:))
        )
        view.addGestureRecognizer(tap)
        return view
    }

    func updateUIView(_ uiView: MTHKView, context: Context) {
        context.coordinator.onTap = onTap
    }

    static func dismantleUIView(_ uiView: MTHKView, coordinator: Coordinator) {
        StreamCameraEngine.shared.detachView(uiView)
    }

    final class Coordinator: NSObject {
        var onTap: (CGPoint, CGSize) -> Void
        init(onTap: @escaping (CGPoint, CGSize) -> Void) { self.onTap = onTap }

        @objc func handleTap(_ gesture: UITapGestureRecognizer) {
            guard let view = gesture.view else { return }
            onTap(gesture.location(in: view), view.bounds.size)
        }
    }
}

// MARK: - Studio view

struct StudioView: View {
    let matchSlug: String

    @StateObject private var viewModel: StudioViewModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var cameraPermissionGranted = false
    @State private var zoom: Float = 1.0

    init(matchSlug: String) {
        self.matchSlug = matchSlug
        _viewModel = StateObject(wrappedValue: StudioViewModel(matchSlug: matchSlug))
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if cameraPermissionGranted {
                CameraPreviewView(onTap: { point, size in
                    // UITapGestureRecognizer fires on the main thread, but its callback is a
                    // nonisolated context — hop onto the main actor for the @MainActor view model.
                    Task { @MainActor in viewModel.tapToFocus(at: point, viewSize: size) }
                })
                .ignoresSafeArea()
                .gesture(
                    MagnificationGesture()
                        .onChanged { scale in
                            // Arrange mode reclaims pinch for board resize (see ArrangeOverlayView).
                            guard !viewModel.arrangeMode else { return }
                            let bounds = viewModel.zoomBounds
                            let newZoom = min(max(zoom * Float(scale), bounds.min), bounds.max)
                            viewModel.setZoom(newZoom)
                        }
                        .onEnded { scale in
                            guard !viewModel.arrangeMode else { return }
                            let bounds = viewModel.zoomBounds
                            // Clamp to the device's real range so pinch-out can reach the 0.5×
                            // ultra-wide (min drops below 1 on multi-lens phones), not floored at 1×.
                            zoom = min(max(zoom * Float(scale), bounds.min), bounds.max)
                        }
                )
                .overlay { focusReticle }
            } else {
                permissionDeniedView
            }

            // Watermark is burned into the mixer screen (see StreamCameraEngine), so it's
            // already visible on this preview — no separate SwiftUI overlay needed.

            // Countdown overlay
            if let count = viewModel.goLiveCountdown {
                countdownOverlay(count)
                    .transition(.opacity)
            }

            // Controls overlay (hidden while arranging so the whole frame is grabbable)
            if !viewModel.arrangeMode {
                VStack(spacing: 0) {
                    topBar
                    Spacer()
                    if let recap = viewModel.recap {
                        recapBanner(recap)
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                    if let error = viewModel.error {
                        errorBanner(error)
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                    if !viewModel.statusMessage.isEmpty {
                        statusBanner(viewModel.statusMessage)
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                    if viewModel.thermalLevel >= 2 {
                        thermalBanner
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                    bottomControls
                }

                // Idle glance rail (AF / MIC): right edge in portrait, left edge in landscape
                // so it never collides with the right-docked checklist.
                if cameraPermissionGranted && !viewModel.streaming {
                    glanceRail
                        .frame(
                            maxWidth: .infinity,
                            maxHeight: .infinity,
                            alignment: verticalSizeClass == .compact ? .leading : .trailing
                        )
                        .padding(.horizontal, 12)
                }
            }

            // Pre-live Arrange mode: pinch/drag the board + sponsor over the live preview.
            if viewModel.arrangeMode {
                ArrangeOverlayView(viewModel: viewModel)
                    .transition(.opacity)
            }
        }
        .cricEnterAnimation(value: viewModel.streaming)
        .cricEnterAnimation(value: viewModel.recap != nil, duration: CricMotion.sheetEnterDuration)
        .cricExitAnimation(value: viewModel.recap != nil)
        .cricEnterAnimation(value: viewModel.error != nil, duration: CricMotion.sheetEnterDuration)
        .cricExitAnimation(value: viewModel.error != nil)
        .cricEnterAnimation(value: viewModel.goLiveCountdown != nil, duration: CricMotion.sheetEnterDuration)
        .cricExitAnimation(value: viewModel.goLiveCountdown != nil)
        .navigationBarHidden(true)
        .sheet(item: $viewModel.activeSheet) { sheet in
            switch sheet {
            case .destination:    DestinationSheet(viewModel: viewModel)
            case .overlay:        OverlaySheet(viewModel: viewModel)
            case .scoring:        ScoringSheet(viewModel: viewModel)
            case .cameraSettings: CameraSettingsSheet(viewModel: viewModel)
            case .menu:           StudioMenuSheet(viewModel: viewModel)
            case .pairRemote:     PairRemoteSheet(viewModel: viewModel)
            case .scorerQr:       ScorerQrSheet(slug: viewModel.matchSlug)
            }
        }
        .task {
            cameraPermissionGranted = await requestCameraPermission()
            await viewModel.load()
            StreamCameraEngine.shared.setStatusHandler { event, message in
                Task { @MainActor in
                    viewModel.previewReady = StreamCameraEngine.shared.isPreviewReady
                    if event == "connected" { viewModel.streaming = true }
                    if event == "disconnected" { viewModel.onStreamDisconnected(message) }
                    if event == "thermal" { viewModel.thermalLevel = Int(message) ?? viewModel.thermalLevel }
                    if event == "error" {
                        // Keep the engine's failure detail: it's the only field record of
                        // WHY a go-live failed (connect timeout vs bad key vs device error).
                        viewModel.error = message.isEmpty
                            ? "Stream error — tap restart camera."
                            : "Stream error: \(message)"
                    }
                }
            }
            await StreamCameraEngine.shared.preparePreview(
                width: StreamCameraEngine.defaultStreamWidth,
                height: StreamCameraEngine.defaultStreamHeight,
                fps: 30
            )
            viewModel.previewReady = StreamCameraEngine.shared.isPreviewReady
        }
        .onDisappear {
            viewModel.stopPolling()
            viewModel.stopRemoteCommandPolling()
            StreamCameraEngine.shared.setStatusHandler(nil)
            UIDevice.current.endGeneratingDeviceOrientationNotifications()
            // Release the studio's orientation lock so the rest of the app rotates normally.
            Task { await viewModel.resetOrientationLock() }
            // Off-air only: fully release camera/mic/timers so leaving Studio turns the green
            // indicator off. A live broadcast keeps running for the background standby slate.
            Task { await StreamCameraEngine.shared.releaseIfIdle() }
        }
        .onAppear {
            UIDevice.current.beginGeneratingDeviceOrientationNotifications()
        }
        .onChange(of: verticalSizeClass) { _ in
            Task { await viewModel.onOrientationChanged() }
        }
        .onChange(of: horizontalSizeClass) { _ in
            Task { await viewModel.onOrientationChanged() }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIDevice.orientationDidChangeNotification)) { _ in
            Task { await viewModel.onOrientationChanged() }
        }
    }

    // MARK: - Top bar

    private var topBar: some View {
        HStack(spacing: 12) {
            Button {
                dismiss()
            } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 40, height: 40)
                    .glassPillSurface(cornerRadius: 20)
            }
            .buttonStyle(PressableScaleStyle())

            if viewModel.streaming {
                // Live: single broadcast bug top-left (ON AIR | timer | health).
                BroadcastBug(
                    paused: viewModel.paused,
                    elapsedText: elapsedTimeText,
                    qualityText: StudioViewModel.streamQualityLabel,
                    healthDot: healthDotColor
                )
                .transition(CricMotion.asymmetricReveal)
            } else {
                Text("Studio")
                    .font(CricFont.archivo(15))
                    .foregroundStyle(.white)
            }

            Spacer()

            if !viewModel.streaming {
                Button {
                    viewModel.activeSheet = .menu
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 40, height: 40)
                        .glassPillSurface(cornerRadius: 20)
                }
                .buttonStyle(PressableScaleStyle())
                .accessibilityLabel("Studio options")
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 56)
    }

    /// Health dot beside the configured quality: coral once thermals bite (level ≥ 2),
    /// error red on a surfaced stream error, sky otherwise. The iOS engine publishes no
    /// live bitrate stats, so connection quality can't be graded finer than this.
    private var healthDotColor: Color {
        if viewModel.error != nil { return CricTheme.danger }
        if viewModel.thermalLevel >= 2 || viewModel.paused { return CricTheme.warning }
        return CricTheme.accent
    }

    /// mm:ss elapsed broadcast time for the broadcast bug (matches Android's "%02d:%02d").
    private var elapsedTimeText: String {
        let s = viewModel.liveElapsedSeconds
        return String(format: "%02d:%02d", s / 60, s % 60)
    }

    // MARK: - Focus reticle

    @ViewBuilder
    private var focusReticle: some View {
        if let point = viewModel.focusIndicator {
            FocusReticle(locked: viewModel.focusLocked)
                .position(point)
                .allowsHitTesting(false)
                .transition(.opacity)
        }
    }

    // MARK: - Glance rail (idle AF / MIC pills)

    private var glanceRail: some View {
        VStack(spacing: 10) {
            GlancePill(
                label: "AF",
                systemImage: viewModel.focusLocked ? "lock.fill" : "lock.open",
                state: viewModel.focusLocked ? .gold : .idle
            ) { Task { await viewModel.toggleFocusLock() } }
            GlancePill(
                label: "MIC",
                systemImage: viewModel.micMuted ? "mic.slash.fill" : "mic.fill",
                state: viewModel.micMuted ? .error : .idle
            ) { Task { await viewModel.toggleMicMuted() } }
        }
    }

    // MARK: - Bottom controls (idle checklist gate / live transport strip)

    @ViewBuilder
    private var bottomControls: some View {
        if viewModel.streaming {
            liveControls
        } else {
            idleControls
        }
    }

    private var liveControls: some View {
        LiveTransportStrip(
            focusLocked: viewModel.focusLocked,
            micMuted: viewModel.micMuted,
            paused: viewModel.paused,
            watchUrl: viewModel.watchUrl,
            // The full six-control row only fits a landscape frame — split it in portrait.
            twoRow: verticalSizeClass != .compact,
            onBoard: { viewModel.activeSheet = .overlay },
            onFocusLock: { Task { await viewModel.toggleFocusLock() } },
            onMic: { Task { await viewModel.toggleMicMuted() } },
            onPause: { Task { await viewModel.togglePause() } },
            onStop: { Task { await viewModel.stopLive() } }
        )
        .padding(.horizontal, 16)
        .padding(.bottom, 12)
        .transition(CricMotion.asymmetricReveal)
    }

    @ViewBuilder
    private var idleControls: some View {
        if verticalSizeClass == .compact {
            landscapeIdleControls
        } else {
            portraitIdleControls
        }
    }

    private var portraitIdleControls: some View {
        VStack(spacing: 12) {
            HStack(spacing: 10) {
                if zoom > 1.05 || zoom < 0.95 { ZoomPill(zoom: zoom) }
                BoardChip { viewModel.activeSheet = .overlay }
            }
            ChecklistPanel(checks: viewModel.checks) { kind in
                viewModel.openSheet(for: kind)
            }
            goLiveRingStack
        }
        .padding(.horizontal, 12)
        .padding(.bottom, 16)
    }

    /// Landscape idle: checklist right-docked (~340pt) with the ring beneath it, so the
    /// framing stays clear (glance pills sit on the left edge — see glanceRail).
    private var landscapeIdleControls: some View {
        HStack(alignment: .bottom) {
            HStack(spacing: 10) {
                if zoom > 1.05 || zoom < 0.95 { ZoomPill(zoom: zoom) }
                BoardChip { viewModel.activeSheet = .overlay }
            }
            Spacer()
            VStack(spacing: 10) {
                ChecklistPanel(checks: viewModel.checks) { kind in
                    viewModel.openSheet(for: kind)
                }
                goLiveRingStack
            }
            .frame(width: 340)
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 10)
    }

    private var goLiveRingStack: some View {
        VStack(spacing: 10) {
            SegmentedGoLiveRing(
                completedCount: viewModel.completedChecksCount,
                ready: viewModel.firstIncompleteCheck == nil,
                busy: viewModel.goLiveBusy,
                fixLabel: viewModel.goLiveFixLabel
            ) {
                viewModel.requestGoLive()
            }
            Text(viewModel.ringCaption)
                .font(CricFont.dmSans(11.5, weight: .medium))
                .foregroundStyle(CricTheme.textMuted)
                .lineLimit(1)
        }
    }

    // MARK: - Countdown

    private func countdownOverlay(_ count: Int) -> some View {
        ZStack {
            Color.black.opacity(0.6).ignoresSafeArea()
            VStack(spacing: 12) {
                Text("\(count)")
                    .font(.system(size: 96, weight: .black, design: .rounded))
                    .foregroundStyle(CricTheme.primary)
                    .contentTransition(.numericText(countsDown: true))
                    .animation(CricMotion.enter(0.22), value: count)
                Text("Going live…")
                    .font(.title3.bold())
                    .foregroundStyle(.white)
                Button("Cancel") { viewModel.cancelCountdown() }
                    .font(.subheadline)
                    .foregroundStyle(CricTheme.textMuted)
                    .padding(.top, 8)
            }
        }
    }

    // MARK: - Recap banner

    private func recapBanner(_ recap: StreamRecap) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "checkmark.circle.fill")
                .foregroundStyle(CricTheme.accent)
            VStack(alignment: .leading, spacing: 2) {
                Text("\(recap.title) · \(recap.destinationLabel)")
                    .font(.subheadline.bold())
                    .foregroundStyle(.white)
                    .lineLimit(1)
                Text("Live for \(recap.durationText)")
                    .font(.caption)
                    .foregroundStyle(CricTheme.textMuted)
                if !recap.watchUrl.isEmpty {
                    Text(recap.watchUrl)
                        .font(.caption)
                        .foregroundStyle(CricTheme.accent)
                        .lineLimit(1)
                }
            }
            Spacer()
            Button { viewModel.dismissRecap() } label: {
                Image(systemName: "xmark")
                    .font(.caption)
                    .foregroundStyle(CricTheme.textDim)
            }
        }
        .padding(14)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14))
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
    }

    // MARK: - Thermal banner

    private var thermalBanner: some View {
        HStack(spacing: 10) {
            Image(systemName: "flame.fill")
                .foregroundStyle(CricTheme.warning)
                .font(.footnote)
            Text("Phone is overheating — quality may drop automatically soon.")
                .font(.footnote)
                .foregroundStyle(CricTheme.warning)
                .lineLimit(2)
            Spacer()
            if viewModel.thermalLevel >= 3 {
                Button("Lower quality") { viewModel.onLowerQuality() }
                    .font(.footnote.bold())
                    .foregroundStyle(CricTheme.warning)
            }
        }
        .padding(12)
        .background(CricTheme.warning.opacity(0.15), in: RoundedRectangle(cornerRadius: 12))
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
    }

    // MARK: - Status banner

    /// Transient progress/hint line ("Connecting…", orientation-toggle hints) — the view
    /// model clears it when the action completes.
    private func statusBanner(_ message: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "info.circle.fill")
                .foregroundStyle(CricTheme.accent)
                .font(.footnote)
            Text(message)
                .font(.footnote)
                .foregroundStyle(.white)
                .lineLimit(2)
            Spacer()
        }
        .padding(12)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12))
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
    }

    // MARK: - Error banner

    private func errorBanner(_ message: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(CricTheme.danger)
                .font(.footnote)
            Text(message)
                .font(.footnote)
                .foregroundStyle(CricTheme.danger)
                .lineLimit(2)
            Spacer()
            Button { viewModel.error = nil } label: {
                Image(systemName: "xmark").font(.caption).foregroundStyle(CricTheme.textDim)
            }
        }
        .padding(12)
        .background(CricTheme.danger.opacity(0.15), in: RoundedRectangle(cornerRadius: 12))
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
    }

    // MARK: - Permission denied

    private var permissionDeniedView: some View {
        VStack(spacing: 16) {
            Image(systemName: "camera.slash")
                .font(.system(size: 44))
                .foregroundStyle(CricTheme.textDim)
            Text("Camera access required")
                .font(.title3.bold())
                .foregroundStyle(.white)
            Text("Go to Settings → CricRelay → Camera and enable access.")
                .font(.subheadline)
                .foregroundStyle(CricTheme.textMuted)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            Button("Open Settings") {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    Task { await UIApplication.shared.open(url) }
                }
            }
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(CricTheme.accent)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black)
    }

    // MARK: - Permission helpers

    private func requestCameraPermission() async -> Bool {
        let camStatus = AVCaptureDevice.authorizationStatus(for: .video)
        let micStatus = AVCaptureDevice.authorizationStatus(for: .audio)

        var camOk: Bool
        switch camStatus {
        case .authorized: camOk = true
        case .notDetermined: camOk = await AVCaptureDevice.requestAccess(for: .video)
        default: camOk = false
        }

        if micStatus == .notDetermined {
            _ = await AVCaptureDevice.requestAccess(for: .audio)
        }

        return camOk
    }
}

// MARK: - Arrange overlay

/// Full-screen direct-manipulation layer shown in Arrange mode over the live composited preview.
/// Pinch (or the gold corner handle) scales the board (aspect-locked); one-finger drag moves the
/// selected target (Board or Sponsor) with snap-to-centre/safe-margin guides and a live readout.
/// Transparent so the real camera + scoreboard sprite show through. Mirrors Android's
/// ArrangeOverlay + the arrange prototype's guides/handle.
struct ArrangeOverlayView: View {
    @ObservedObject var viewModel: StudioViewModel
    @State private var lastPinchScale: CGFloat = 1.0
    @State private var lastDragTranslation: CGSize = .zero

    var body: some View {
        GeometryReader { geo in
            ZStack {
                // Transparent gesture surface covering the whole preview.
                Color.black.opacity(0.001)
                    .contentShape(Rectangle())
                    .gesture(dragGesture(in: geo.size))
                    .simultaneousGesture(pinchGesture())

                guides(in: geo.size)
                boardOutline(in: geo.size)
                sponsorOutline(in: geo.size)

                // Controls live at the TOP so the lower area (where the board sits) stays grabbable.
                VStack(spacing: 12) {
                    HStack(spacing: 12) {
                        Button {
                            viewModel.cancelArrangeMode()
                        } label: {
                            Text("Cancel")
                                .font(CricFont.dmSans(15, weight: .bold))
                                .foregroundStyle(.white)
                                .frame(maxWidth: .infinity, minHeight: 48)
                                .glassPillSurface(cornerRadius: 12)
                        }
                        .buttonStyle(PressableScaleStyle())
                        Button {
                            viewModel.commitArrangeMode()
                        } label: {
                            Text("Done")
                                .font(CricFont.dmSans(15, weight: .bold))
                                .foregroundStyle(CricTheme.onPrimary)
                                .frame(maxWidth: .infinity, minHeight: 48)
                                .background(CricTheme.ctaGradient, in: RoundedRectangle(cornerRadius: 12))
                        }
                        .buttonStyle(PressableScaleStyle())
                    }
                    HStack(spacing: 8) {
                        arrangeChip("Scoreboard", selected: viewModel.arrangeTarget == .board) {
                            viewModel.arrangeTarget = .board
                        }
                        arrangeChip("Sponsor", selected: viewModel.arrangeTarget == .sponsor) {
                            viewModel.arrangeTarget = .sponsor
                        }
                    }
                    if let readout = viewModel.arrangeReadout {
                        Text(readout)
                            .font(.system(size: 11, weight: .semibold, design: .monospaced))
                            .foregroundStyle(CricTheme.primary)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 5)
                            .glassPillSurface(cornerRadius: 8)
                            .transition(.opacity)
                    }
                    Spacer()
                }
                .padding(16)
                .padding(.top, 40)

                // Persistent hint near the bottom, above the board it describes.
                VStack {
                    Spacer()
                    Text("Drag anywhere to move the \(targetLabel) · pinch or pull the gold handle to resize the scoreboard")
                        .font(CricFont.dmSans(12, weight: .bold))
                        .foregroundStyle(.white)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .glassPillSurface(cornerRadius: 10)
                        .padding(.bottom, 140)
                        .padding(.horizontal, 24)
                        .allowsHitTesting(false)
                }

                cornerHandle(in: geo.size)
            }
        }
        .ignoresSafeArea()
    }

    private var targetLabel: String {
        viewModel.arrangeTarget == .board ? "scoreboard" : "sponsor"
    }

    /// The prefs being manipulated right now (draft while a gesture is in flight).
    private var draftPrefs: OverlayLayoutPrefs {
        viewModel.arrangeDraft ?? viewModel.overlayPrefs
    }

    // MARK: Outlines, guides, handle

    /// Board rect in view coordinates — mirrors the engine's sprite placement math
    /// (StreamCameraEngine.applyOverlayLayout): width/height fractions of the frame,
    /// anchorX-centred with inset clamping, lifted off the bottom by bottomMargin/720.
    private func boardRect(in size: CGSize) -> CGRect {
        let prefs = draftPrefs
        let w = size.width
        let h = size.height
        let boardW = w * prefs.widthFraction
        let boardH = h * prefs.heightFraction
        let insetX = w * (prefs.horizontalInset / 400.0)
        let maxLeft = max(w - boardW - insetX, 0)
        let minLeft = min(insetX, maxLeft)
        let left = min(max(prefs.anchorX * w - boardW / 2, minLeft), maxLeft)
        let bottom = h * (prefs.bottomMargin / 720.0)
        return CGRect(x: left, y: h - bottom - boardH, width: boardW, height: boardH)
    }

    /// Approximate sponsor rect: the engine sizes the logo at 18% of frame width × sizeScale;
    /// height uses a ~2:1 aspect as a placement aid (the real bitmap's aspect varies).
    private func sponsorRect(in size: CGSize) -> CGRect {
        let prefs = draftPrefs
        let scale = min(max(prefs.sponsorSizeScale, 0.3), 3.0)
        let w = size.width * 0.18 * scale
        let h = w / 2
        return CGRect(
            x: prefs.sponsorPositionX * size.width - w / 2,
            y: prefs.sponsorPositionY * size.height - h / 2,
            width: w,
            height: h
        )
    }

    @ViewBuilder
    private func boardOutline(in size: CGSize) -> some View {
        if draftPrefs.overlayEnabled {
            let rect = boardRect(in: size)
            RoundedRectangle(cornerRadius: 10)
                .stroke(
                    CricTheme.primary.opacity(0.6),
                    style: StrokeStyle(lineWidth: 1.5, dash: [6, 4])
                )
                .frame(width: rect.width, height: rect.height)
                .position(x: rect.midX, y: rect.midY)
                .allowsHitTesting(false)
        }
    }

    @ViewBuilder
    private func sponsorOutline(in size: CGSize) -> some View {
        // Only meaningful for the positionable (non-scroll) sponsor placement.
        if draftPrefs.sponsorEnabled && !SponsorDisplayMode.isScroll(draftPrefs.sponsorDisplayMode) {
            let rect = sponsorRect(in: size)
            RoundedRectangle(cornerRadius: 6)
                .stroke(
                    CricTheme.accent.opacity(0.6),
                    style: StrokeStyle(lineWidth: 1.5, dash: [5, 4])
                )
                .frame(width: rect.width, height: rect.height)
                .position(x: rect.midX, y: rect.midY)
                .allowsHitTesting(false)
        }
    }

    @ViewBuilder
    private func guides(in size: CGSize) -> some View {
        if viewModel.arrangeGuideV {
            Rectangle()
                .fill(CricTheme.primary.opacity(0.6))
                .frame(width: 1, height: size.height)
                .position(x: size.width / 2, y: size.height / 2)
                .allowsHitTesting(false)
        }
        if viewModel.arrangeGuideH {
            Rectangle()
                .fill(CricTheme.primary.opacity(0.6))
                .frame(width: size.width, height: 1)
                .position(x: size.width / 2, y: size.height / 2)
                .allowsHitTesting(false)
        }
    }

    /// 24pt gold corner handle on the board's bottom-right corner — dragging right grows the
    /// board (scale = s0·(1+dx/140)), through the same clamps as pinch.
    @ViewBuilder
    private func cornerHandle(in size: CGSize) -> some View {
        if draftPrefs.overlayEnabled {
            let rect = boardRect(in: size)
            Circle()
                .fill(CricTheme.primary)
                .frame(width: 24, height: 24)
                .overlay(
                    Image(systemName: "arrow.up.left.and.arrow.down.right")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(CricTheme.onPrimary)
                )
                .position(x: rect.maxX, y: rect.maxY)
                .gesture(
                    DragGesture(minimumDistance: 1)
                        .onChanged { value in
                            viewModel.resizeBoardHandle(dxPx: Double(value.translation.width))
                        }
                        .onEnded { _ in
                            viewModel.dragEnded()
                        }
                )
        }
    }

    // MARK: Gestures

    /// MagnificationGesture reports cumulative scale — convert to incremental ratios for pinchBoard.
    private func pinchGesture() -> some Gesture {
        MagnificationGesture()
            .onChanged { scale in
                let increment = scale / lastPinchScale
                lastPinchScale = scale
                viewModel.pinchBoard(Double(increment))
            }
            .onEnded { _ in
                lastPinchScale = 1.0
                viewModel.dragEnded()
            }
    }

    /// DragGesture reports cumulative translation — convert to incremental preview-fraction deltas.
    private func dragGesture(in size: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 2)
            .onChanged { value in
                let dx = value.translation.width - lastDragTranslation.width
                let dy = value.translation.height - lastDragTranslation.height
                lastDragTranslation = value.translation
                let w = max(size.width, 1)
                let h = max(size.height, 1)
                viewModel.dragArrange(
                    dxFraction: Double(dx / w),
                    dyFraction: Double(dy / h),
                    previewWidth: Double(w),
                    previewHeight: Double(h)
                )
            }
            .onEnded { _ in
                lastDragTranslation = .zero
                viewModel.dragEnded()
            }
    }

    private func arrangeChip(_ label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(CricFont.dmSans(12, weight: selected ? .bold : .medium))
                .foregroundStyle(selected ? CricTheme.primary : .white.opacity(0.7))
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(
                    selected ? CricTheme.primary.opacity(0.14) : CricTheme.glassPillBg,
                    in: RoundedRectangle(cornerRadius: 10)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 10)
                        .stroke(selected ? CricTheme.primary : CricTheme.glassBorder, lineWidth: 1)
                )
        }
        .buttonStyle(PressableScaleStyle())
    }
}
