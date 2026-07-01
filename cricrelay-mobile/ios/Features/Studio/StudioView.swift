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
                            let newZoom = zoom * Float(scale)
                            viewModel.setZoom(newZoom)
                        }
                        .onEnded { scale in
                            guard !viewModel.arrangeMode else { return }
                            zoom = max(1, zoom * Float(scale))
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
                    if viewModel.thermalLevel >= 2 {
                        thermalBanner
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                    bottomControls
                }
                .ignoresSafeArea(edges: .bottom)
            }

            // Pre-live Arrange mode: pinch/drag the board + sponsor over the live preview.
            if viewModel.arrangeMode {
                ArrangeOverlayView(viewModel: viewModel)
                    .transition(.opacity)
            }

            // First-run guided precheck (Camera → Arrange → Ready)
            if viewModel.precheckActive && !viewModel.arrangeMode && viewModel.goLiveCountdown == nil {
                PrecheckCard(viewModel: viewModel)
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
            case .destination: DestinationSheet(viewModel: viewModel)
            case .overlay:     OverlaySheet(viewModel: viewModel)
            case .scoring:     ScoringSheet(viewModel: viewModel)
            case .preflight:   PreflightSheet(viewModel: viewModel)
            case .menu:        StudioMenuSheet(viewModel: viewModel)
            case .pairRemote:  PairRemoteSheet(viewModel: viewModel)
            }
        }
        .task {
            cameraPermissionGranted = await requestCameraPermission()
            await viewModel.load()
            StreamCameraEngine.shared.setStatusHandler { event, message in
                Task { @MainActor in
                    viewModel.previewReady = StreamCameraEngine.shared.isPreviewReady
                    viewModel.advancePrecheckIfCameraReady()
                    if event == "connected" { viewModel.streaming = true }
                    if event == "thermal" { viewModel.thermalLevel = Int(message) ?? viewModel.thermalLevel }
                    if event == "error" { viewModel.error = "Stream error — tap restart camera." }
                }
            }
            await StreamCameraEngine.shared.preparePreview(width: 1280, height: 720, fps: 30)
            viewModel.previewReady = StreamCameraEngine.shared.isPreviewReady
            viewModel.advancePrecheckIfCameraReady()
        }
        .onDisappear {
            viewModel.stopPolling()
            viewModel.stopRemoteCommandPolling()
            StreamCameraEngine.shared.setStatusHandler(nil)
            UIDevice.current.endGeneratingDeviceOrientationNotifications()
            // Release the studio's orientation lock so the rest of the app rotates normally.
            Task { await viewModel.resetOrientationLock() }
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
                    .frame(width: 36, height: 36)
                    .background(.ultraThinMaterial, in: Circle())
            }
            .buttonStyle(PressableScaleStyle())

            Spacer()

            if viewModel.streaming {
                let badgeTint = viewModel.paused ? Color.orange : CricTheme.primary
                HStack(spacing: 6) {
                    Circle()
                        .fill(badgeTint)
                        .frame(width: 7, height: 7)
                        .pulseOpacity(active: !viewModel.paused, min: 0.35)
                    Text(viewModel.paused ? "PAUSED" : "ON AIR")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(badgeTint)
                        .tracking(1)
                    if !viewModel.paused {
                        Text(elapsedTimeText)
                            .font(.system(size: 10, weight: .semibold).monospacedDigit())
                            .foregroundStyle(.white)
                    }
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(Color.black.opacity(0.55), in: Capsule())
                .overlay(Capsule().stroke(badgeTint.opacity(0.5), lineWidth: 1))
                .transition(CricMotion.asymmetricReveal)
            }

            Spacer()

            // Orientation lock cycle (Auto → Landscape → Portrait) in the top bar so it never
            // competes with the Go Live controls for space (parity with Android). Hidden while
            // live — the RTMP orientation is fixed once streaming starts.
            if viewModel.streaming {
                // Placeholder for symmetry
                Color.clear.frame(width: 36, height: 36)
            } else {
                Button {
                    Task { await viewModel.toggleOrientation() }
                } label: {
                    Image(systemName: viewModel.orientationMode == .auto ? "rotate.right" : "lock.rotation")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(viewModel.orientationMode == .auto ? Color.white : CricTheme.primary)
                        .frame(width: 36, height: 36)
                        .background(.ultraThinMaterial, in: Circle())
                }
                .buttonStyle(PressableScaleStyle())
                .accessibilityLabel(orientationAccessibilityLabel)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 56)
    }

    private var orientationAccessibilityLabel: String {
        switch viewModel.orientationMode {
        case .auto: return "Switch orientation"
        case .landscape: return "Landscape — tap for portrait"
        case .portrait: return "Portrait — tap for landscape"
        }
    }

    /// mm:ss elapsed broadcast time for the ON AIR badge (matches Android's "%02d:%02d").
    private var elapsedTimeText: String {
        let s = viewModel.liveElapsedSeconds
        return String(format: "%02d:%02d", s / 60, s % 60)
    }

    // MARK: - Focus reticle

    @ViewBuilder
    private var focusReticle: some View {
        if let point = viewModel.focusIndicator {
            let color = viewModel.focusLocked ? CricTheme.primary : Color.white
            RoundedRectangle(cornerRadius: 8)
                .stroke(color, lineWidth: 1.5)
                .frame(width: 76, height: 76)
                .overlay {
                    if viewModel.focusLocked {
                        Image(systemName: "lock.fill")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(color)
                    }
                }
                .position(point)
                .allowsHitTesting(false)
                .transition(.opacity)
        }
    }

    // MARK: - Quick toggles

    private var quickToggleRow: some View {
        HStack(spacing: 10) {
            quickTogglePill(
                label: viewModel.focusLocked ? "Locked" : "Focus",
                systemImage: viewModel.focusLocked ? "lock.fill" : "lock.open",
                active: viewModel.focusLocked
            ) { Task { await viewModel.toggleFocusLock() } }

            quickTogglePill(
                label: "Stabilize",
                systemImage: "gyroscope",
                active: viewModel.overlayPrefs.videoStabilization
            ) { Task { await viewModel.toggleStabilization() } }

            quickTogglePill(
                label: "Screen on",
                systemImage: "sun.max.fill",
                active: viewModel.overlayPrefs.keepScreenOn
            ) { Task { await viewModel.toggleKeepScreenOn() } }

            quickTogglePill(
                label: viewModel.micMuted ? "Muted" : "Mic",
                systemImage: viewModel.micMuted ? "mic.slash.fill" : "mic.fill",
                active: viewModel.micMuted
            ) { Task { await viewModel.toggleMicMuted() } }
        }
    }

    private func quickTogglePill(
        label: String,
        systemImage: String,
        active: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: systemImage)
                    .font(.system(size: 14, weight: .semibold))
                Text(label)
                    .font(.system(size: 13, weight: .semibold))
            }
            .foregroundStyle(active ? CricTheme.onPrimary : .white)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(
                active ? AnyShapeStyle(CricTheme.primary) : AnyShapeStyle(.ultraThinMaterial),
                in: Capsule()
            )
        }
        .buttonStyle(PressableScaleStyle())
        .animation(CricMotion.enter(), value: active)
    }

    // MARK: - Bottom controls

    private var bottomControls: some View {
        VStack(spacing: 0) {
            // Quick toggles — focus lock, stabilisation, keep-screen-on — surfaced on the camera
            // screen (parity with Android's QuickToggles row) so each is one tap instead of being
            // buried in the overlay sheet. Focus lock still frames + locks the pitch so a fielder
            // crossing the frame can't pull focus or exposure off the strip.
            quickToggleRow
                .frame(maxWidth: .infinity)
                .padding(.bottom, 12)

            // Tool row
            HStack(spacing: 0) {
                toolButton("Destination", icon: "arrow.triangle.branch") {
                    viewModel.activeSheet = .destination
                }
                toolButton("Overlay", icon: "rectangle.on.rectangle") {
                    viewModel.activeSheet = .overlay
                }
                toolButton("Scoring", icon: "pencil.and.list.clipboard") {
                    viewModel.activeSheet = .scoring
                }
                toolButton("Menu", icon: "ellipsis") {
                    viewModel.activeSheet = .menu
                }
            }
            .background(.ultraThinMaterial)

            // Shutter row
            HStack(spacing: 32) {
                // Pause button (only visible when streaming)
                if viewModel.streaming {
                    Button {
                        Task { await viewModel.togglePause() }
                    } label: {
                        Image(systemName: viewModel.paused ? "play.fill" : "pause.fill")
                            .font(.system(size: 20, weight: .semibold))
                            .foregroundStyle(.white)
                            .frame(width: 52, height: 52)
                            .background(.ultraThinMaterial, in: Circle())
                    }
                    .buttonStyle(PressableScaleStyle())
                    .transition(CricMotion.asymmetricReveal)
                } else {
                    Color.clear.frame(width: 52, height: 52)
                }

                // Main shutter
                shutterButton

                // Zoom indicator
                if zoom > 1.1 {
                    Text(String(format: "%.1fx", zoom))
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 52, height: 52)
                        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 10))
                } else {
                    Color.clear.frame(width: 52, height: 52)
                }
            }
            .padding(.vertical, 20)
            .padding(.bottom, 24)
            .background(.ultraThinMaterial)
        }
    }

    private var shutterButton: some View {
        Button {
            if viewModel.streaming {
                Task { await viewModel.stopLive() }
            } else {
                viewModel.requestGoLive()
            }
        } label: {
            ZStack {
                Circle()
                    .fill(viewModel.streaming ? CricTheme.danger : CricTheme.primary)
                    .frame(width: 72, height: 72)
                    .shadow(
                        color: (viewModel.streaming ? CricTheme.danger : CricTheme.primary).opacity(0.5),
                        radius: 16
                    )

                if viewModel.streaming {
                    RoundedRectangle(cornerRadius: 6)
                        .fill(.white)
                        .frame(width: 24, height: 24)
                } else {
                    Image(systemName: "dot.radiowaves.left.and.right")
                        .font(.system(size: 20, weight: .bold))
                        .foregroundStyle(CricTheme.onPrimary)
                }
            }
        }
        .buttonStyle(PressableScaleStyle())
        .scaleEffect(viewModel.goLiveCountdown != nil ? CricMotion.pressScale : 1)
        .animation(CricMotion.press, value: viewModel.goLiveCountdown != nil)
        .animation(CricMotion.enter(), value: viewModel.streaming)
    }

    private func toolButton(_ label: String, icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: icon)
                    .font(.system(size: 18, weight: .regular))
                Text(label)
                    .font(.system(size: 9))
            }
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 10)
        }
        .buttonStyle(PressableScaleStyle())
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
/// Pinch always scales the board (aspect-locked); one-finger drag moves the selected target
/// (Board or Sponsor). Transparent so the real camera + scoreboard sprite show through.
/// Mirrors Android's ArrangeOverlay.
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

                // Controls live at the TOP so the lower area (where the board sits) stays grabbable.
                VStack(spacing: 12) {
                    HStack(spacing: 12) {
                        Button {
                            viewModel.cancelArrangeMode()
                        } label: {
                            Text("Cancel")
                                .font(.headline)
                                .foregroundColor(.white)
                                .frame(maxWidth: .infinity, minHeight: 48)
                                .background(Color.black.opacity(0.5))
                                .cornerRadius(10)
                        }
                        Button {
                            viewModel.commitArrangeMode()
                        } label: {
                            Text("Done")
                                .font(.headline)
                                .fontWeight(.bold)
                                .foregroundColor(.black)
                                .frame(maxWidth: .infinity, minHeight: 48)
                                .background(Color.yellow)
                                .cornerRadius(10)
                        }
                    }
                    Text("Move:")
                        .font(.caption)
                        .foregroundColor(.white)
                    HStack(spacing: 8) {
                        arrangeChip("Scoreboard", selected: viewModel.arrangeTarget == .board) {
                            viewModel.arrangeTarget = .board
                        }
                        arrangeChip("Sponsor", selected: viewModel.arrangeTarget == .sponsor) {
                            viewModel.arrangeTarget = .sponsor
                        }
                    }
                    Spacer()
                }
                .padding(16)

                // Persistent hint near the bottom, above the board it describes.
                VStack {
                    Spacer()
                    Text("Drag anywhere to move the \(targetLabel) · pinch with two fingers to resize the scoreboard")
                        .font(.caption)
                        .fontWeight(.bold)
                        .foregroundColor(.white)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(Color.black.opacity(0.5))
                        .cornerRadius(8)
                        .padding(.bottom, 140)
                        .padding(.horizontal, 24)
                        .allowsHitTesting(false)
                }
            }
        }
        .ignoresSafeArea()
    }

    private var targetLabel: String {
        viewModel.arrangeTarget == .board ? "scoreboard" : "sponsor"
    }

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
                viewModel.dragArrange(dxFraction: Double(dx / w), dyFraction: Double(dy / h))
            }
            .onEnded { _ in
                lastDragTranslation = .zero
            }
    }

    private func arrangeChip(_ label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.caption)
                .fontWeight(selected ? .bold : .regular)
                .foregroundColor(selected ? .white : .white.opacity(0.7))
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(selected ? Color.yellow.opacity(0.35) : Color.black.opacity(0.4))
                .cornerRadius(8)
        }
    }
}

// MARK: - First-run precheck

/// Guided first-run stepper (Camera → Arrange → Ready) gating the first Go Live.
/// Mirrors the Android precheck card.
struct PrecheckCard: View {
    @ObservedObject var viewModel: StudioViewModel

    var body: some View {
        VStack {
            Spacer()
            VStack(alignment: .leading, spacing: 14) {
                Text("Quick setup before you go live")
                    .font(.headline)
                    .foregroundColor(.white)

                precheckRow(
                    index: 1,
                    title: "Camera",
                    done: viewModel.previewReady,
                    active: viewModel.precheckStep == .camera,
                    subtitle: viewModel.previewReady ? "Preview is running" : "Waiting for the camera…"
                )
                precheckRow(
                    index: 2,
                    title: "Arrange board & sponsor",
                    done: viewModel.precheckStep == .ready,
                    active: viewModel.precheckStep == .arrange,
                    subtitle: "Pinch to resize, drag to place them on the frame"
                )
                precheckRow(
                    index: 3,
                    title: "Ready",
                    done: false,
                    active: viewModel.precheckStep == .ready,
                    subtitle: "You can rearrange any time from the Board menu"
                )

                HStack(spacing: 12) {
                    Button("Skip") {
                        viewModel.finishPrecheck()
                    }
                    .font(.subheadline)
                    .foregroundColor(.white.opacity(0.7))

                    Spacer()

                    switch viewModel.precheckStep {
                    case .camera:
                        EmptyView()
                    case .arrange:
                        Button {
                            viewModel.precheckStartArrange()
                        } label: {
                            Text("Arrange now")
                                .font(.subheadline).bold()
                                .foregroundColor(.black)
                                .padding(.horizontal, 18)
                                .padding(.vertical, 10)
                                .background(Color.yellow)
                                .cornerRadius(8)
                        }
                    case .ready:
                        Button {
                            viewModel.finishPrecheck()
                        } label: {
                            Text("All set")
                                .font(.subheadline).bold()
                                .foregroundColor(.black)
                                .padding(.horizontal, 18)
                                .padding(.vertical, 10)
                                .background(Color.yellow)
                                .cornerRadius(8)
                        }
                    }
                }
            }
            .padding(18)
            .background(Color.black.opacity(0.75))
            .cornerRadius(14)
            .padding(.horizontal, 24)
            .padding(.bottom, 120)
        }
    }

    private func precheckRow(index: Int, title: String, done: Bool, active: Bool, subtitle: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: done ? "checkmark.circle.fill" : "\(index).circle")
                .foregroundColor(done ? .green : active ? .yellow : .white.opacity(0.5))
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline)
                    .fontWeight(active || done ? .bold : .regular)
                    .foregroundColor(.white)
                Text(subtitle)
                    .font(.caption)
                    .foregroundColor(.white.opacity(0.6))
            }
        }
    }
}
