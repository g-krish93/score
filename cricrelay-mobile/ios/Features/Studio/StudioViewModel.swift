import Foundation
import Combine
import CoreGraphics

@MainActor
final class StudioViewModel: ObservableObject {
    // Match data
    @Published var match: StreamMatch?
    @Published var matchSlug: String
    @Published var statusMessage = ""

    // Camera / stream state
    @Published var previewReady = false
    @Published var streaming = false
    @Published var paused = false

    // RTMP credentials (from go-live response)
    @Published var rtmpUrl = ""
    @Published var streamKey = ""
    @Published var watchUrl = ""
    @Published var overlayEmbedUrl = ""

    // Destination selection (before go-live)
    @Published var destination = "youtube"  // "youtube", "twitch", "custom"
    @Published var customRtmpUrl = ""
    @Published var customStreamKey = ""
    @Published var customWatchUrl = ""

    // Overlay
    @Published var overlayPrefs = OverlayLayoutPrefs()

    // Scoring
    @Published var scoringConfig: ScoringConfig?

    // Preflight
    @Published var preflightCameraOk = false
    @Published var preflightDestinationOk = false
    @Published var preflightOverlayOk = false

    // Countdown
    @Published var goLiveCountdown: Int?

    // Recap
    @Published var recap: StreamRecap?

    // Active sheet
    @Published var activeSheet: StudioSheet?

    // Settings
    @Published var keepScreenOn = true
    @Published var videoStabilization = true

    // Focus lock
    @Published var focusLocked = false
    @Published var focusIndicator: CGPoint?
    private var focusIndicatorTask: Task<Void, Never>?

    @Published var error: String?

    private let api = CricRelayAPI.shared
    private var pollingTask: Task<Void, Never>?
    private var streamStartDate: Date?

    init(matchSlug: String) {
        self.matchSlug = matchSlug
    }

    deinit {
        pollingTask?.cancel()
    }

    // MARK: - Load

    func load() async {
        do {
            async let matchFetch = api.matchDay(slug: matchSlug)
            async let overlayFetch = api.overlayPrefs(slug: matchSlug)
            async let scoringFetch = api.scoringConfig(slug: matchSlug)

            let (status, prefs, scoring) = try await (matchFetch, overlayFetch, scoringFetch)

            overlayPrefs = prefs
            scoringConfig = scoring
            streaming = status.broadcast.isStreaming
            paused = status.broadcast.isPaused
            if let url = status.broadcast.watchUrl { watchUrl = url }

            // Bootstrap camera settings from prefs
            StreamCameraEngine.shared.setKeepScreenOnDuringStream(enabled: prefs.keepScreenOn)
            StreamCameraEngine.shared.setVideoStabilization(enabled: prefs.videoStabilization)

            // Start polling
            startPolling()
        } catch {
            self.error = error.localizedDescription
        }
    }

    // MARK: - Polling

    private func startPolling() {
        pollingTask?.cancel()
        pollingTask = Task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 5_000_000_000)  // 5 s
                guard !Task.isCancelled else { return }
                if let status = try? await api.matchDay(slug: matchSlug) {
                    await MainActor.run {
                        streaming = status.broadcast.isStreaming
                        paused = status.broadcast.isPaused
                        if let url = status.broadcast.watchUrl, !url.isEmpty { watchUrl = url }
                    }
                }
            }
        }
    }

    func stopPolling() {
        pollingTask?.cancel()
        pollingTask = nil
    }

    // MARK: - Go live flow

    func openPreflight() {
        preflightCameraOk = StreamCameraEngine.shared.isPreviewReady
        preflightDestinationOk = destination == "custom"
            ? !customRtmpUrl.isEmpty && !customStreamKey.isEmpty
            : true  // OAuth platforms considered ready when selected
        preflightOverlayOk = !overlayEmbedUrl.isEmpty || !overlayPrefs.theme.isEmpty
        activeSheet = .preflight
    }

    func confirmGoLive() async {
        activeSheet = nil
        do {
            let result: GoLiveResult
            if destination == "custom" {
                rtmpUrl = customRtmpUrl
                streamKey = customStreamKey
                watchUrl = customWatchUrl
                overlayEmbedUrl = overlayPrefs.theme.isEmpty ? "" : ""
                result = GoLiveResult(
                    rtmpUrl: customRtmpUrl,
                    streamKey: customStreamKey,
                    watchUrl: customWatchUrl,
                    overlayEmbedUrl: ""
                )
            } else {
                result = try await api.goLive(matchSlug: matchSlug, platform: destination)
                rtmpUrl = result.rtmpUrl
                streamKey = result.streamKey
                watchUrl = result.watchUrl
                overlayEmbedUrl = result.overlayEmbedUrl
            }
            await startCountdown(result: result)
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func startCountdown(result: GoLiveResult) async {
        for i in stride(from: 5, through: 1, by: -1) {
            goLiveCountdown = i
            try? await Task.sleep(nanoseconds: 1_000_000_000)
        }
        goLiveCountdown = nil
        await startStream(result: result)
    }

    private func startStream(result: GoLiveResult) async {
        await StreamCameraEngine.shared.startStream(
            rtmpUrl: result.rtmpUrl,
            streamKey: result.streamKey,
            overlayUrl: result.overlayEmbedUrl,
            width: 1280,
            height: 720,
            bitrate: 2_500_000,
            fps: 30,
            layout: overlayPrefs.toEngineLayout()
        )
        streaming = StreamCameraEngine.shared.isStreaming
        if streaming {
            streamStartDate = Date()
            try? await api.updateBroadcastStatus(
                slug: matchSlug,
                status: "streaming",
                platform: destination == "custom" ? nil : destination,
                watchUrl: result.watchUrl.isEmpty ? nil : result.watchUrl
            )
        }
    }

    func cancelCountdown() {
        goLiveCountdown = nil
    }

    // MARK: - Stop live

    func stopLive() async {
        let duration = streamStartDate.map { Int(Date().timeIntervalSince($0)) } ?? 0
        streamStartDate = nil
        await StreamCameraEngine.shared.stopStream()
        streaming = false
        paused = false
        try? await api.stopLive(platform: destination == "custom" ? nil : destination)
        try? await api.updateBroadcastStatus(slug: matchSlug, status: "idle")
        recap = StreamRecap(title: match?.label ?? "Stream", watchUrl: watchUrl, durationSeconds: duration)
        watchUrl = ""
    }

    func dismissRecap() {
        recap = nil
    }

    // MARK: - Pause / resume

    func togglePause() async {
        if paused {
            await StreamCameraEngine.shared.resumeStream()
            paused = false
            try? await api.updateBroadcastStatus(slug: matchSlug, status: "streaming")
        } else {
            await StreamCameraEngine.shared.pauseStream()
            paused = true
            try? await api.updateBroadcastStatus(slug: matchSlug, status: "paused")
        }
    }

    // MARK: - Overlay

    func saveOverlay(_ prefs: OverlayLayoutPrefs) async {
        overlayPrefs = prefs
        StreamCameraEngine.shared.updateOverlay(url: overlayEmbedUrl, layout: prefs.toEngineLayout())
        StreamCameraEngine.shared.setKeepScreenOnDuringStream(enabled: prefs.keepScreenOn)
        StreamCameraEngine.shared.setVideoStabilization(enabled: prefs.videoStabilization)
        _ = try? await api.saveOverlayPrefs(slug: matchSlug, prefs: prefs)
    }

    // MARK: - Scoring

    func setScoringMode(_ mode: String) async {
        scoringConfig = try? await api.setScoringMode(slug: matchSlug, mode: mode)
    }

    // MARK: - Zoom

    func setZoom(_ level: Float) {
        StreamCameraEngine.shared.setZoom(level: level)
    }

    // MARK: - Focus lock

    /// Tap the preview to focus + meter at that point (continuous AF/AE). Releases any lock so
    /// the operator can re-aim before locking again.
    func tapToFocus(at point: CGPoint, viewSize: CGSize) {
        guard viewSize.width > 0, viewSize.height > 0 else { return }
        StreamCameraEngine.shared.tapToFocus(
            viewWidth: Int(viewSize.width),
            viewHeight: Int(viewSize.height),
            x: Float(point.x),
            y: Float(point.y)
        )
        focusLocked = false
        showFocusIndicator(at: point)
    }

    /// Freeze focus + exposure on the pitch (or hand them back to continuous AF/AE). Reflects the
    /// real device result so the padlock only shows "locked" if the camera actually locked.
    func toggleFocusLock() async {
        if focusLocked {
            _ = await StreamCameraEngine.shared.unlockFocus()
            focusLocked = false
            focusIndicatorTask?.cancel()
            focusIndicator = nil
        } else {
            focusLocked = await StreamCameraEngine.shared.lockFocus()
        }
    }

    private func showFocusIndicator(at point: CGPoint) {
        focusIndicator = point
        focusIndicatorTask?.cancel()
        focusIndicatorTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            guard let self, !Task.isCancelled else { return }
            // Keep the reticle on screen while locked so the operator can see what's held.
            if !self.focusLocked { self.focusIndicator = nil }
        }
    }

    // MARK: - Camera restart

    func restartCameraPreview() async {
        await StreamCameraEngine.shared.preparePreview(width: 1280, height: 720, fps: 30)
        previewReady = StreamCameraEngine.shared.isPreviewReady
    }
}

enum StudioSheet: Identifiable {
    case destination, overlay, scoring, preflight, menu
    var id: String { "\(self)" }
}
