import Foundation
import Combine
import CoreGraphics
import UIKit

/// What the Arrange-mode drag gesture moves (pinch always scales the board).
enum ArrangeTarget {
    case board
    case sponsor
}

/// Studio orientation control (parity with Android's OrientationMode). Auto follows the physical
/// sensor; the lock modes force the interface orientation so an operator with the system rotation
/// lock on can still get a landscape studio + landscape stream.
enum OrientationMode {
    case auto
    case landscape
    case portrait
}

@MainActor
final class StudioViewModel: ObservableObject {
    // Match data
    @Published var match: StreamMatch?
    @Published var matchSlug: String
    @Published var statusMessage = ""
    /// Latest MatchDayStatus from the ~5 s poll — feeds the scoreboard check's sublabel
    /// ("Play-Cricket · live" / "Manual scorer" / "feed stale").
    @Published var matchDay: MatchDayStatus?

    // Camera / stream state
    @Published var previewReady = false
    @Published var streaming = false
    @Published var paused = false
    @Published var orientationMode: OrientationMode = .auto

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
    @Published var savedDestinations: [SavedRtmpDestination] = []
    @Published var selectedSavedDestinationId: String?
    @Published var saveAsLabel = ""

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
        default:
            if let id = selectedSavedDestinationId,
               let label = savedDestinations.first(where: { $0.id == id })?.label,
               !label.isEmpty {
                return label
            }
            return destinationReady ? "Custom RTMP" : "Set stream key"
        }
    }

    // Overlay
    @Published var overlayPrefs = OverlayLayoutPrefs()

    // Pre-live "Arrange" mode: direct pinch/drag of the board + sponsor over the live preview.
    // The draft is published so ArrangeOverlayView can draw the board/sponsor outlines live.
    @Published var arrangeMode = false
    @Published var arrangeTarget: ArrangeTarget = .board
    @Published private(set) var arrangeDraft: OverlayLayoutPrefs?

    // Arrange snap feedback: gold guide lines + a live percentage readout while dragging.
    @Published var arrangeGuideV = false
    @Published var arrangeGuideH = false
    @Published var arrangeReadout: String?
    /// Board scale at the moment the corner resize handle was grabbed (prototype: s0·(1+dx/140)).
    private var boardHandleStartScale: Double?

    // Scoring
    @Published var scoringConfig: ScoringConfig?

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

        // Fetch in parallel but land each result independently — one endpoint failing on flaky
        // ground Wi-Fi must not abort the whole load, which would skip the local overlay cache,
        // sponsors, and polling below.
        async let matchFetch = api.matchDay(slug: matchSlug)
        async let overlayFetch = api.overlayPrefs(slug: matchSlug)
        async let scoringFetch = api.scoringConfig(slug: matchSlug)

        var fetchError: Error?
        var status: MatchDayStatus?
        var serverPrefs: OverlayLayoutPrefs?
        var scoring: ScoringConfig?
        do { status = try await matchFetch } catch { fetchError = error }
        do { serverPrefs = try await overlayFetch } catch { fetchError = fetchError ?? error }
        do { scoring = try await scoringFetch } catch { fetchError = fetchError ?? error }

        // Surface the first fetch failure the same way the old all-or-nothing load did.
        if let fetchError {
            self.error = fetchError.localizedDescription
        }

        // Local-first: this phone owns its studio setup. The server copy only seeds a
        // fresh install; camera/device settings are never read from the server at all.
        // Runs even when every fetch failed, so the studio comes up from cache offline.
        let cached = StudioLocalPrefsStore.loadOverlayPrefs(slug: matchSlug)
        if cached == nil, let serverPrefs {
            StudioLocalPrefsStore.saveOverlayPrefs(slug: matchSlug, serverPrefs)
        }
        let prefs = StudioLocalPrefsStore.loadDeviceSettings()
            .appliedTo(cached ?? serverPrefs ?? OverlayLayoutPrefs())

        overlayPrefs = prefs
        if let scoring { scoringConfig = scoring }
        if let status {
            matchDay = status
            streaming = status.broadcast.isStreaming
            paused = status.broadcast.isPaused
            if let url = status.broadcast.watchUrl { watchUrl = url }
        }

        // Bootstrap camera settings from prefs
        StreamCameraEngine.shared.setKeepScreenOnDuringStream(enabled: prefs.keepScreenOn)
        StreamCameraEngine.shared.setStabilizationLevel(prefs.stabilizationLevel)

        // Resolve the StreamMatch row (for the recap label) and real platform readiness.
        await loadStudioExtras()

        // Start polling even when the fetches failed — the operator may regain signal mid-session.
        startPolling()
        startRemoteCommandPolling()
        sponsors = (try? await api.listSponsors()) ?? []
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
        savedDestinations = (try? await api.listDestinations()) ?? []

        let assignedId = match?.streamDestinationId ?? match?.destination?.id
        if let assignedId, !assignedId.isEmpty,
           let full = try? await api.getDestination(id: assignedId),
           !full.rtmpUrl.isEmpty, !full.streamKey.isEmpty {
            selectedSavedDestinationId = full.id
            customRtmpUrl = full.rtmpUrl
            customStreamKey = full.streamKey
            customWatchUrl = full.watchUrl
            RtmpCredentialsStore.save(
                slug: matchSlug,
                RtmpCredentials(rtmpUrl: full.rtmpUrl, streamKey: full.streamKey, watchUrl: full.watchUrl)
            )
            destination = "custom"
        } else if youtubeStatus.ready || youtubeStatus.connected {
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
        let logoUrls = overlayPrefs.resolveSponsorLogoUrls(from: sponsors)
        StreamCameraEngine.shared.updateOverlay(
            url: url,
            layout: overlayPrefs.toEngineLayout(sponsorLogoUrls: logoUrls)
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
                        matchDay = status
                        // Engine truth wins while this phone is publishing: the server only knows
                        // what our own fire-and-forget status POST told it, so a failed POST must
                        // not flip the shutter back to "Go Live" mid-broadcast (double-start risk).
                        if !StreamCameraEngine.shared.isStreaming {
                            streaming = status.broadcast.isStreaming
                            paused = status.broadcast.isPaused
                        }
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

    /// True while the countdown or the RTMP connect is in flight — drives the ring's
    /// spinner and blocks a second tap from double-starting the broadcast.
    var goLiveBusy: Bool {
        goLiveCountdown != nil || statusMessage == "Connecting…"
    }

    /// Entry point from the Go Live ring (1b Checklist gate). All three checks complete →
    /// straight into the 3-2-1 countdown confirmation. A blocked ring is guidance, never
    /// dead: it opens the sheet that fixes the first incomplete check (preserves the old
    /// "no destination → Destination sheet" behaviour).
    func requestGoLive() {
        guard !streaming, !goLiveBusy else { return }
        recomputeDestinationReady()
        if let incomplete = firstIncompleteCheck {
            openSheet(for: incomplete.kind)
            return
        }
        Task { await confirmGoLive() }
    }

    func confirmGoLive() async {
        activeSheet = nil
        for i in stride(from: 3, through: 1, by: -1) {
            goLiveCountdown = i
            try? await Task.sleep(nanoseconds: 800_000_000)
            // Cancel tapped mid-countdown (cancelCountdown nils this) — don't go live.
            if goLiveCountdown == nil || Task.isCancelled { return }
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
            width: StreamCameraEngine.defaultStreamWidth,
            height: StreamCameraEngine.defaultStreamHeight,
            bitrate: StreamCameraEngine.defaultStreamBitrate,
            fps: 30,
            layout: overlayPrefs.toEngineLayout(
                sponsorLogoUrls: overlayPrefs.resolveSponsorLogoUrls(from: sponsors)
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

    /// Remote "start_broadcast" must actually start the stream — nobody is standing at the
    /// tripod phone to tap through a sheet. Goes live directly (no countdown; the companion
    /// operator already confirmed) when the destination is ready; otherwise falls back to
    /// surfacing the Destination sheet so setup can be finished on the phone.
    ///
    /// Deliberate asymmetry with the checklist ring: the remote path stays destination-only
    /// gated (plus a live camera preview) — it does NOT require the scoreboard-source check,
    /// because the companion operator may intentionally start a board-less broadcast and
    /// can't see this phone's checklist to fix it.
    private func remoteStartBroadcast() async {
        guard !streaming, StreamCameraEngine.shared.isPreviewReady else { return }
        recomputeDestinationReady()
        guard destinationReady else {
            activeSheet = .destination
            return
        }
        activeSheet = nil
        goLiveCountdown = nil
        await goLive()
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

    /// Mid-broadcast connection loss reported by the engine. The RTMP session is already torn
    /// down and the preview restored — bring the UI and server state back to idle and tell the
    /// operator, so the badge doesn't keep pulsing ON AIR over a dead stream.
    func onStreamDisconnected(_ detail: String) {
        guard streaming else { return }
        streaming = false
        paused = false
        liveStartedAt = nil
        stopLiveTimer()
        error = "Stream disconnected — check your connection, then tap Go Live to resume."
        Task { try? await api.updateBroadcastStatus(slug: matchSlug, status: "idle") }
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
        selectedSavedDestinationId = nil
        RtmpCredentialsStore.save(
            slug: matchSlug,
            RtmpCredentials(
                rtmpUrl: customRtmpUrl,
                streamKey: customStreamKey,
                watchUrl: customWatchUrl
            )
        )
        recomputeDestinationReady()
    }

    func refreshSavedDestinations() async {
        savedDestinations = (try? await api.listDestinations()) ?? savedDestinations
    }

    func selectSavedDestination(id: String) async {
        do {
            let full = try await api.getDestination(id: id)
            guard !full.rtmpUrl.isEmpty, !full.streamKey.isEmpty else {
                error = "Destination is missing URL or key"
                return
            }
            selectedSavedDestinationId = full.id
            destination = "custom"
            customRtmpUrl = full.rtmpUrl
            customStreamKey = full.streamKey
            customWatchUrl = full.watchUrl
            RtmpCredentialsStore.save(
                slug: matchSlug,
                RtmpCredentials(rtmpUrl: full.rtmpUrl, streamKey: full.streamKey, watchUrl: full.watchUrl)
            )
            try? await api.assignStreamDestination(slug: matchSlug, destinationId: full.id)
            recomputeDestinationReady()
        } catch {
            self.error = error.localizedDescription
        }
    }

    func saveCustomAsDestination() async {
        let label = saveAsLabel.trimmingCharacters(in: .whitespacesAndNewlines)
        persistCustomRtmp()
        guard !customRtmpUrl.isEmpty, !customStreamKey.isEmpty else { return }
        do {
            let created = try await api.createDestination(
                label: label.isEmpty ? "Saved RTMP" : label,
                rtmpUrl: customRtmpUrl,
                streamKey: customStreamKey,
                watchUrl: customWatchUrl
            )
            selectedSavedDestinationId = created.id
            try? await api.assignStreamDestination(slug: matchSlug, destinationId: created.id)
            savedDestinations = (try? await api.listDestinations()) ?? (savedDestinations + [created])
            saveAsLabel = ""
        } catch {
            self.error = error.localizedDescription
        }
    }

    // MARK: - Overlay

    func saveOverlay(_ prefs: OverlayLayoutPrefs) async {
        overlayPrefs = prefs
        // Local persistence + camera apply first — the studio must work fully offline.
        StudioLocalPrefsStore.saveOverlayPrefs(slug: matchSlug, prefs)
        StudioLocalPrefsStore.saveDeviceSettings(
            DeviceStreamSettings(
                stabilizationLevel: prefs.stabilizationLevel,
                keepScreenOn: prefs.keepScreenOn
            )
        )
        applyOverlayPreview(prefs)
        StreamCameraEngine.shared.setKeepScreenOnDuringStream(enabled: prefs.keepScreenOn)
        StreamCameraEngine.shared.setStabilizationLevel(prefs.stabilizationLevel)
        // Best-effort mirror so the club dashboard / remote companion stay informed.
        _ = try? await api.saveOverlayPrefs(slug: matchSlug, prefs: prefs)
    }

    /// Push overlay/sponsor prefs to the camera preview without persisting to the server.
    func previewOverlay(_ prefs: OverlayLayoutPrefs) {
        applyOverlayPreview(prefs)
    }

    /// Restore the last saved overlay on the preview after cancel/dismiss without save.
    func revertOverlayPreview() {
        applyOverlayPreview(overlayPrefs)
    }

    private func applyOverlayPreview(_ prefs: OverlayLayoutPrefs) {
        let url = match?.overlayEmbedUrl ?? ""
        let effectiveUrl = !url.isEmpty ? url : overlayEmbedUrl
        let logoUrls = prefs.resolveSponsorLogoUrls(from: sponsors)
        StreamCameraEngine.shared.updateOverlay(
            url: effectiveUrl,
            layout: prefs.toEngineLayout(sponsorLogoUrls: logoUrls)
        )
    }

    func loadSponsors() async {
        sponsors = (try? await api.listSponsors()) ?? []
    }

    // MARK: - Arrange mode
    // Direct manipulation of the board + sponsor on the live composited preview. Gestures push
    // to the engine via previewOverlay (no network); commit persists once, on "Done".

    func enterArrangeMode() {
        arrangeDraft = overlayPrefs
        arrangeMode = true
        activeSheet = nil
        dragEnded()
    }

    func cancelArrangeMode() {
        revertOverlayPreview()
        arrangeMode = false
        arrangeDraft = nil
        dragEnded()
    }

    func commitArrangeMode() {
        let draft = arrangeDraft
        arrangeMode = false
        arrangeDraft = nil
        dragEnded()
        if let draft {
            Task { await saveOverlay(draft) }
        }
    }

    private func mutateArrangeDraft(_ block: (OverlayLayoutPrefs) -> OverlayLayoutPrefs) {
        let current = arrangeDraft ?? overlayPrefs
        let next = block(current)
        arrangeDraft = next
        previewOverlay(next)
    }

    /// Pinch: `zoom` is the incremental scale ratio (~1.0) from the gesture.
    func pinchBoard(_ zoom: Double) {
        guard zoom > 0 else { return }
        mutateArrangeDraft { $0.withBoardScale($0.boardScale() * zoom) }
    }

    /// Snap distance to the preview's centre lines (px, from the arrange prototype).
    private static let snapCentrePx = 7.0
    /// Safe-margin distance from the preview edges the board edges snap to.
    private static let snapMarginPx = 16.0
    /// How close (px) a board edge must get to a safe margin before it snaps.
    private static let snapMarginThresholdPx = 8.0

    /// Drag the active target by a fraction of the preview (dy<0 = up). Board vertical drag maps
    /// to bottomMargin (px, /720 in the engine) — parity with Android's dragArrange. Snapping is
    /// mapped onto the existing anchorX/bottomMargin/sponsorPosition fractions: centre lines at
    /// 7 px, 16 px safe margins at 8 px on the board's edges (anchorX ± widthFraction/2).
    func dragArrange(dxFraction: Double, dyFraction: Double, previewWidth: Double, previewHeight: Double) {
        let w = max(previewWidth, 1.0)
        let h = max(previewHeight, 1.0)
        var guideV = false
        var guideH = false
        var readout: String?
        mutateArrangeDraft { p in
            var next = p
            switch arrangeTarget {
            case .board:
                var anchorX = min(1.0, max(0.0, p.anchorX + dxFraction))
                var bottomMargin = min(400.0, max(0.0, p.bottomMargin - dyFraction * 400.0))
                let boardWpx = p.widthFraction * w
                // Vertical centre line wins; otherwise try the 16 px safe margins on each edge.
                if abs(anchorX - 0.5) * w < Self.snapCentrePx {
                    anchorX = 0.5
                    guideV = true
                } else {
                    let leftEdge = anchorX * w - boardWpx / 2
                    let rightEdge = anchorX * w + boardWpx / 2
                    if abs(leftEdge - Self.snapMarginPx) < Self.snapMarginThresholdPx {
                        anchorX = (Self.snapMarginPx + boardWpx / 2) / w
                    } else if abs((w - Self.snapMarginPx) - rightEdge) < Self.snapMarginThresholdPx {
                        anchorX = (w - Self.snapMarginPx - boardWpx / 2) / w
                    }
                    anchorX = min(1.0, max(0.0, anchorX))
                }
                // Horizontal centre line via the board's vertical centre: bottomMargin is in
                // 720-canvas px, so /720 is its fraction of the frame height.
                let centreFromBottomPx = (bottomMargin / 720.0 + p.heightFraction / 2) * h
                if abs(centreFromBottomPx - h / 2) < Self.snapCentrePx {
                    bottomMargin = min(400.0, max(0.0, (0.5 - p.heightFraction / 2) * 720.0))
                    guideH = true
                }
                next.anchorX = anchorX
                next.bottomMargin = bottomMargin
                readout = String(
                    format: "BOARD %d%% · %d%%",
                    Int((anchorX * 100).rounded()),
                    Int((bottomMargin / 720.0 * 100).rounded())
                )
            case .sponsor:
                // Sponsor is drag-only; its rendered size depends on the logo's aspect (engine
                // sizes by bitmap), so it snaps its centre to the centre lines only.
                var x = min(1.0, max(0.0, p.sponsorPositionX + dxFraction))
                var y = min(1.0, max(0.0, p.sponsorPositionY + dyFraction))
                if abs(x - 0.5) * w < Self.snapCentrePx {
                    x = 0.5
                    guideV = true
                }
                if abs(y - 0.5) * h < Self.snapCentrePx {
                    y = 0.5
                    guideH = true
                }
                next.sponsorPositionX = x
                next.sponsorPositionY = y
                readout = String(
                    format: "SPONSOR %d%% · %d%%",
                    Int((x * 100).rounded()),
                    Int((y * 100).rounded())
                )
            }
            return next
        }
        arrangeGuideV = guideV
        arrangeGuideH = guideH
        arrangeReadout = readout
    }

    /// Corner-handle resize (prototype: scale = s0 · (1 + dx/140)). `dxPx` is the gesture's
    /// cumulative horizontal translation; the start scale latches on the first change and
    /// clears in [dragEnded]. Goes through the same withBoardScale path as pinch, so the
    /// existing clamps stay authoritative.
    func resizeBoardHandle(dxPx: Double) {
        let start = boardHandleStartScale ?? (arrangeDraft ?? overlayPrefs).boardScale()
        boardHandleStartScale = start
        mutateArrangeDraft { $0.withBoardScale(start * (1.0 + dxPx / 140.0)) }
        let widthPercent = Int(((arrangeDraft ?? overlayPrefs).widthFraction * 100).rounded())
        arrangeGuideV = false
        arrangeGuideH = false
        arrangeReadout = "BOARD WIDTH \(widthPercent)%"
    }

    /// Gesture finished: clear guides, readout, and the resize handle's latched start scale.
    func dragEnded() {
        boardHandleStartScale = nil
        arrangeGuideV = false
        arrangeGuideH = false
        arrangeReadout = nil
    }

    /// Flip keep-screen-on from the Camera settings sheet, then persist + apply via saveOverlay
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
                for cmd in commands where cmd.type == "overlay" {
                    if let patch = cmd.prefs {
                        await applyRemoteOverlayPatch(patch)
                    }
                }
            }
        }
    }

    private func dispatchRemoteCommand(_ command: String) async {
        switch command {
        case "start_broadcast":
            if !streaming { await remoteStartBroadcast() }
        case "stop_broadcast":
            if streaming { await stopLive() }
        case "mute_mic":
            if !micMuted { await toggleMicMuted() }
        case "toggle_focus_lock":
            await toggleFocusLock()
        case "toggle_sponsor":
            var prefs = overlayPrefs
            prefs.sponsorEnabled.toggle()
            await saveOverlay(prefs)
        default:
            break
        }
    }

    private func applyRemoteOverlayPatch(_ patch: [String: Any]) async {
        let merged = overlayPrefs.mergeSponsorPatch(patch)
        await saveOverlay(merged)
    }

    // MARK: - Scoring

    func setScoringMode(_ mode: String, provider: String? = nil) async {
        scoringConfig = try? await api.setScoringMode(slug: matchSlug, mode: mode, provider: provider)
    }

    // MARK: - Zoom

    /// Display-zoom bounds (× relative to the wide lens) from the active capture device. `min`
    /// drops below 1 (≈0.5) on phones with an ultra-wide lens; read live so it reflects whichever
    /// virtual device the engine attached. Used to clamp the pinch gesture.
    var zoomBounds: (min: Float, max: Float) {
        let range = StreamCameraEngine.shared.zoomRange()
        return (Float(range.min), Float(range.max))
    }

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
        // Mirror onOrientationChanged: never reconfigure the encoder under an active publish —
        // re-running orientation/codec setup on the live mixer glitches or drops the stream.
        guard !streaming else { return }
        await StreamCameraEngine.shared.preparePreview(
            width: StreamCameraEngine.defaultStreamWidth,
            height: StreamCameraEngine.defaultStreamHeight,
            fps: 30
        )
        previewReady = StreamCameraEngine.shared.isPreviewReady
    }

    /// Re-prepare the camera when the operator rotates the phone before Go Live.
    func onOrientationChanged() async {
        guard !streaming else { return }
        await StreamCameraEngine.shared.updatePreviewForCurrentOrientation()
        previewReady = StreamCameraEngine.shared.isPreviewReady
    }

    // MARK: - Orientation lock

    /// Flip the studio between portrait and landscape: one tap goes to the opposite of what's
    /// on screen now, the next tap comes back (parity with Android's toggleOrientation — a
    /// three-state Auto/lock cycle proved invisible in the field). Physical auto-rotate still
    /// works until the first tap. No-op while live (RTMP is fixed).
    func toggleOrientation() async {
        guard !streaming else { return }
        let currentlyLandscape = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first?.interfaceOrientation.isLandscape ?? false
        orientationMode = currentlyLandscape ? .portrait : .landscape
        statusMessage = currentlyLandscape
            ? "Portrait — tap again for landscape"
            : "Landscape — tap again for portrait"
        await applyOrientationMode()
    }

    /// Restore the app-default orientations when leaving the studio.
    func resetOrientationLock() async {
        orientationMode = .auto
        await applyOrientationMode()
    }

    private func applyOrientationMode() async {
        let mask: UIInterfaceOrientationMask
        switch orientationMode {
        case .auto: mask = AppDelegate.defaultOrientations
        case .landscape: mask = .landscape
        case .portrait: mask = .portrait
        }
        AppDelegate.orientationLock = mask
        StreamCameraEngine.shared.setFollowDeviceOrientation(orientationMode == .auto)
        if let scene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .first {
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask))
            scene.keyWindow?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
        }
        await onOrientationChanged()
    }
}

enum StudioSheet: Identifiable {
    case destination, overlay, scoring, cameraSettings, menu, pairRemote, scorerQr
    var id: String { "\(self)" }
}
