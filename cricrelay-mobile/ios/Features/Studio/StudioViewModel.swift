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
    @Published var destination = "custom" {  // "youtube", "twitch", "custom"
        didSet { recomputeDestinationReady() }
    }
    @Published var customRtmpUrl = "" {
        didSet { recomputeDestinationReady() }
    }
    @Published var customStreamKey = "" {
        didSet { recomputeDestinationReady() }
    }
    @Published var customWatchUrl = ""
    @Published private(set) var destinationReady = false

    // Cached live platform connection state, used to gate Go Live (parity with Android, which
    // checks youtube/twitch platform status rather than assuming a selected OAuth platform is ready).
    private var youtubeStatus = PlatformStatus()
    private var twitchStatus = PlatformStatus()

    /// When the current broadcast started, for the recap duration.
    private var liveStartedAt: Date?

    var destinationLabel: String {
        switch destination {
        case "youtube": return "YouTube"
        case "twitch": return "Twitch"
        default: return "Custom RTMP"
        }
    }

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

    // Live elapsed — ticks once per second while on air (parity with Android's LiveTimerBadge)
    @Published var liveElapsedSeconds: Int = 0

    // Active sheet
    @Published var activeSheet: StudioSheet?

    // Settings
    @Published var keepScreenOn = true
    @Published var videoStabilization = true

    // Focus lock
    @Published var focusLocked = false
    @Published var focusIndicator: CGPoint?
    private var focusIndicatorTask: Task<Void, Never>?

    // Thermal / overheat
    @Published var thermalLevel: Int = 0

    // Mic mute (ephemeral, not persisted)
    @Published var micMuted = false

    // Sponsors for overlay compositing
    @Published var sponsors: [Sponsor] = []

    // Remote pairing
    @Published var pairRemotePayload: String?
    @Published var pairRemoteExpiresAt: String?

    @Published var error: String?

    private let api = CricRelayAPI.shared
    private var pollingTask: Task<Void, Never>?
    private var liveTimerTask: Task<Void, Never>?
    private var remotePollTask: Task<Void, Never>?

    init(matchSlug: String) {
        self.matchSlug = matchSlug
    }

    deinit {
        pollingTask?.cancel()
        liveTimerTask?.cancel()
        remotePollTask?.cancel()
    }

    // MARK: - Load

    func load() async {
        // Restore persisted custom RTMP credentials so a custom destination survives relaunch
        // (parity with Android RtmpCredentialsStore).
        let saved = RtmpCredentialsStore.load(slug: matchSlug)
        customRtmpUrl = saved.rtmpUrl
        customStreamKey = saved.streamKey
        customWatchUrl = saved.watchUrl

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

            // Resolve the StreamMatch row (for the recap label) and real platform readiness.
            await loadStudioExtras()

            // Start polling
            startPolling()
            startRemoteCommandPolling()
            sponsors = (try? await api.listSponsors()) ?? []
        } catch {
            self.error = error.localizedDescription
        }
    }

    /// Resolve the StreamMatch row plus live YouTube/Twitch connection state, and pick a sensible
    /// default destination — mirrors Android's loadStudioExtras so Go Live is gated on real platform
    /// readiness rather than assuming a selected OAuth platform is connected.
    private func loadStudioExtras() async {
        if let streams = try? await api.listStreams() {
            match = streams.first { $0.slug == matchSlug }
        }
        youtubeStatus = (try? await api.youtubeStatus()) ?? PlatformStatus()
        twitchStatus = (try? await api.twitchStatus()) ?? PlatformStatus()

        // Prefer a connected OAuth platform; fall back to custom RTMP when creds are saved.
        if youtubeStatus.ready || youtubeStatus.connected {
            destination = "youtube"
        } else if twitchStatus.ready || twitchStatus.connected {
            destination = "twitch"
        } else if RtmpCredentialsStore.load(slug: matchSlug).isConfigured {
            destination = "custom"
        }
        recomputeDestinationReady()
        syncOverlay()
    }

    /// Push the match's scoreboard overlay into the camera engine so it composites in the *preview*
    /// before going live (parity with Android's syncOverlay / startPreviewOverlayPush).
    private func syncOverlay() {
        guard let url = match?.overlayEmbedUrl, !url.isEmpty else { return }
        let logoUrl = overlayPrefs.resolvedSponsorLogoUrl(from: sponsors)
        StreamCameraEngine.shared.updateOverlay(
            url: url,
            layout: overlayPrefs.toEngineLayout(sponsorLogoUrl: logoUrl)
        )
    }

    private func recomputeDestinationReady() {
        switch destination {
        case "custom":
            destinationReady = !customRtmpUrl.isEmpty && !customStreamKey.isEmpty
        case "youtube":
            destinationReady = youtubeStatus.ready || youtubeStatus.connected
        case "twitch":
            destinationReady = twitchStatus.ready || twitchStatus.connected
        default:
            destinationReady = false
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

    func stopRemoteCommandPolling() {
        remotePollTask?.cancel()
        remotePollTask = nil
    }

    // MARK: - Live timer

    /// Tick the on-air elapsed time once per second while live (parity with Android's
    /// startLiveTimer). Keeps counting while paused — the broadcast is still on air. Wall-clock
    /// from `liveStartedAt` so it stays accurate across any tick that's throttled in the
    /// background, and consistent with the recap duration.
    private func startLiveTimer() {
        liveTimerTask?.cancel()
        liveElapsedSeconds = 0
        liveTimerTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                if let start = self.liveStartedAt {
                    self.liveElapsedSeconds = max(0, Int(Date().timeIntervalSince(start)))
                }
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
        }
    }

    private func stopLiveTimer() {
        liveTimerTask?.cancel()
        liveTimerTask = nil
        liveElapsedSeconds = 0
    }

    // MARK: - Go live flow

    /// Entry point from the shutter: if the destination isn't actually ready, send the operator to
    /// the Destination sheet first (parity with Android's requestGoLive) instead of a preflight that
    /// would fail. Otherwise go straight to the preflight check.
    func requestGoLive() {
        guard StreamCameraEngine.shared.isPreviewReady else { return }
        recomputeDestinationReady()
        if !streaming && !destinationReady {
            activeSheet = .destination
            return
        }
        openPreflight()
    }

    func openPreflight() {
        recomputeDestinationReady()
        preflightCameraOk = StreamCameraEngine.shared.isPreviewReady
        preflightDestinationOk = destinationReady
        preflightOverlayOk = match?.overlayEmbedUrl.isEmpty == false
        activeSheet = .preflight
    }

    func confirmGoLive() async {
        activeSheet = nil
        for i in stride(from: 3, through: 1, by: -1) {
            goLiveCountdown = i
            try? await Task.sleep(nanoseconds: 800_000_000)
        }
        goLiveCountdown = nil
        await goLive()
    }

    private func goLive() async {
        statusMessage = "Connecting…"
        do {
            let matchOverlay = match?.overlayEmbedUrl ?? ""
            if destination == "custom" {
                rtmpUrl = customRtmpUrl
                streamKey = customStreamKey
                watchUrl = customWatchUrl
                overlayEmbedUrl = matchOverlay
                let result = GoLiveResult(
                    rtmpUrl: customRtmpUrl,
                    streamKey: customStreamKey,
                    watchUrl: customWatchUrl,
                    overlayEmbedUrl: matchOverlay
                )
                await startStream(result: result)
            } else {
                let result = try await api.goLive(matchSlug: matchSlug, platform: destination)
                rtmpUrl = result.rtmpUrl
                streamKey = result.streamKey
                watchUrl = result.watchUrl
                let embedUrl = matchOverlay.isEmpty ? result.overlayEmbedUrl : matchOverlay
                overlayEmbedUrl = embedUrl
                let streamResult = GoLiveResult(
                    rtmpUrl: result.rtmpUrl,
                    streamKey: result.streamKey,
                    watchUrl: result.watchUrl,
                    overlayEmbedUrl: embedUrl
                )
                await startStream(result: streamResult)
            }
            statusMessage = ""
        } catch {
            statusMessage = ""
            self.error = error.localizedDescription
        }
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
            layout: overlayPrefs.toEngineLayout(
                sponsorLogoUrl: overlayPrefs.resolvedSponsorLogoUrl(from: sponsors)
            )
        )
        streaming = StreamCameraEngine.shared.isStreaming
        if streaming {
            liveStartedAt = Date()
            startLiveTimer()
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
        let duration = liveStartedAt.map { max(0, Int(Date().timeIntervalSince($0))) } ?? 0
        liveStartedAt = nil
        await StreamCameraEngine.shared.stopStream()
        streaming = false
        paused = false
        stopLiveTimer()
        try? await api.stopLive(platform: destination == "custom" ? nil : destination)
        try? await api.updateBroadcastStatus(slug: matchSlug, status: "idle")
        recap = StreamRecap(
            title: match?.label ?? "Stream",
            destinationLabel: destinationLabel,
            durationSeconds: duration,
            watchUrl: watchUrl
        )
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

    // MARK: - Custom RTMP

    /// Persist the custom RTMP credentials entered in the Destination sheet so they survive relaunch
    /// (parity with Android RtmpCredentialsStore).
    func persistCustomRtmp() {
        customRtmpUrl = customRtmpUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        customStreamKey = customStreamKey.trimmingCharacters(in: .whitespacesAndNewlines)
        customWatchUrl = customWatchUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        RtmpCredentialsStore.save(
            slug: matchSlug,
            RtmpCredentials(
                rtmpUrl: customRtmpUrl,
                streamKey: customStreamKey,
                watchUrl: customWatchUrl
            )
        )
    }

    // MARK: - Overlay

    func saveOverlay(_ prefs: OverlayLayoutPrefs) async {
        overlayPrefs = prefs
        let url = match?.overlayEmbedUrl ?? ""
        let effectiveUrl = !url.isEmpty ? url : overlayEmbedUrl
        let logoUrl = prefs.resolvedSponsorLogoUrl(from: sponsors)
        StreamCameraEngine.shared.updateOverlay(
            url: effectiveUrl,
            layout: prefs.toEngineLayout(sponsorLogoUrl: logoUrl)
        )
        StreamCameraEngine.shared.setKeepScreenOnDuringStream(enabled: prefs.keepScreenOn)
        StreamCameraEngine.shared.setVideoStabilization(enabled: prefs.videoStabilization)
        _ = try? await api.saveOverlayPrefs(slug: matchSlug, prefs: prefs)
    }

    func loadSponsors() async {
        sponsors = (try? await api.listSponsors()) ?? []
    }

    /// Flip video stabilisation from the on-screen quick toggle, then persist + apply via
    /// saveOverlay (parity with Android's onToggleStabilization → updateOverlayPrefs).
    func toggleStabilization() async {
        var prefs = overlayPrefs
        prefs.videoStabilization.toggle()
        await saveOverlay(prefs)
    }

    /// Flip keep-screen-on from the on-screen quick toggle, then persist + apply via saveOverlay
    /// (parity with Android's onToggleKeepScreenOn → updateOverlayPrefs).
    func toggleKeepScreenOn() async {
        var prefs = overlayPrefs
        prefs.keepScreenOn.toggle()
        await saveOverlay(prefs)
    }

    // MARK: - Thermal

    func onLowerQuality() {
        StreamCameraEngine.shared.stepDownQuality()
    }

    // MARK: - Mic mute

    func toggleMicMuted() async {
        let next = !micMuted
        await StreamCameraEngine.shared.setMicMuted(next)
        micMuted = next
    }

    // MARK: - Remote control

    func openPairRemote() async {
        do {
            let result = try await api.pairRemote(slug: matchSlug)
            let base = api.baseUrl.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? api.baseUrl
            pairRemotePayload = "cricrelay://pair?slug=\(matchSlug)&token=\(result.pairToken)&base=\(base)"
            pairRemoteExpiresAt = result.expiresAt
            activeSheet = .pairRemote
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func startRemoteCommandPolling() {
        remotePollTask?.cancel()
        remotePollTask = Task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                guard !Task.isCancelled else { return }
                guard let commands = try? await api.pollRemoteCommands(slug: matchSlug) else { continue }
                for cmd in commands where cmd.type == "control" {
                    await dispatchRemoteCommand(cmd.command)
                }
            }
        }
    }

    private func dispatchRemoteCommand(_ command: String) async {
        switch command {
        case "start_broadcast":
            if !streaming { requestGoLive() }
        case "stop_broadcast":
            if streaming { await stopLive() }
        case "mute_mic":
            if !micMuted { await toggleMicMuted() }
        case "toggle_focus_lock":
            await toggleFocusLock()
        default:
            break
        }
    }

    // MARK: - Scoring

    func setScoringMode(_ mode: String, provider: String? = nil) async {
        scoringConfig = try? await api.setScoringMode(slug: matchSlug, mode: mode, provider: provider)
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

    /// Re-prepare the camera when the operator rotates the phone before Go Live.
    func onOrientationChanged() async {
        guard !streaming else { return }
        await StreamCameraEngine.shared.updatePreviewForCurrentOrientation()
        previewReady = StreamCameraEngine.shared.isPreviewReady
    }
}

enum StudioSheet: Identifiable {
    case destination, overlay, scoring, preflight, menu, pairRemote
    var id: String { "\(self)" }
}
