import AVFoundation
import HaishinKit
import RTMPHaishinKit
import UIKit

/// Camera RTMP + scoreboard overlay (HaishinKit). Matches Android MethodChannel API.
@available(iOS 15.0, *)
final class StreamCameraEngine: NSObject {
    static let shared = StreamCameraEngine()

    struct OverlayLayout {
        var heightFraction: Float = 0.16
        var widthFraction: Float = 1.0
        var anchorX: Float = 0.5
        var anchorY: Float = 0.85
        var bottomMarginFraction: Float = 0.0
        var horizontalInsetFraction: Float = 0.0
        var fontScale: Float = 1.0
        var bgColor: String = ""
        var textColor: String = ""
        var opacity: Float = 1.0
        var watermarkEnabled: Bool = true
        var watermarkText: String = "Visit cricrelay.co.uk"
        var sponsorEnabled: Bool = false
        var sponsorLogoUrl: String = ""
        var sponsorLogoUrls: [String] = []
        var sponsorLayoutMode: String = "single"
        var sponsorCarouselIntervalSec: Float = 6
        var sponsorDisplayMode: String = "static"
        var sponsorPositionX: Float = 0.92
        var sponsorPositionY: Float = 0.88
        var sponsorSizeScale: Float = 1.0
        var sponsorOpacity: Float = 1.0
        var sponsorScrollSpeed: Float = 1.0
        var sponsorScrollDirection: String = "rtl"
        var theme: String = "barlow"
    }

    private let mixer = MediaMixer()
    private var connection = RTMPConnection()
    private var rtmpStream: RTMPStream?
    private weak var hkView: MTHKView?
    private var overlayCapture: OverlayWebViewCapture?
    private var overlayTimer: Timer?
    private var overlayRefreshInterval: TimeInterval = 0.5
    private var lastThermalState: ProcessInfo.ThermalState = .nominal
    private var overlayObject: ImageScreenObject?
    private var watermarkObject: ImageScreenObject?
    private var sponsorObjects: [String: ImageScreenObject] = [:]
    private var appliedSponsorUrls: Set<String> = []
    private var sponsorScrollTimer: Timer?
    private var sponsorScrollOffset: CGFloat = 0
    // Direction the marquee timer is currently running, so repeated starts stay idempotent
    // (studio init calls updateOverlay several times; restarting would jump the logo to the edge).
    private var sponsorScrollActiveDir: String?
    private var sponsorCarouselTimer: Timer?
    private var carouselUrls: [String] = []
    private var carouselIndex = 0
    private var appliedWatermarkText: String?
    private var overlayLayout = OverlayLayout()
    private var overlayUrl = ""
    private var streamWidth = 1280
    private var streamHeight = 720
    private var streamFps = 30
    private var streamBitrate = 2_500_000
    private var devicesAttached = false
    private var publishing = false
    private var streamPaused = false
    private var previewReady = false
    private var keepScreenOnDuringStream = false
    private var videoStabilizationEnabled = true
    private var micMuted = false
    private var streamRotation = 0
    /// True when the encoded frame is portrait (w < h) — mirrors Android's streamIsPortrait.
    private var streamIsPortrait = false
    private var preparedCaptureOrientation: AVCaptureVideoOrientation?
    private var statusHandler: ((String, String) -> Void)?

    // Background handling: iOS suspends camera capture in the background, so while live we keep the
    // audio session alive and composite a branded standby slate over the (frozen) camera frame so
    // viewers see a clean card + commentary instead of a stuck image. Best-effort — if iOS suspends
    // the encoder under the audio background mode, the stream pauses and reconnects on foreground.
    private var backgroundTaskId = UIBackgroundTaskIdentifier.invalid
    private var standbyObject: ImageScreenObject?
    private var pauseBlackObject: ImageScreenObject?
    private var lifecycleObserversRegistered = false

    var isViewAttached: Bool { hkView != nil }

    var isPreviewReady: Bool { isViewAttached && devicesAttached && previewReady }

    var isStreaming: Bool { publishing }

    func setStatusHandler(_ handler: ((String, String) -> Void)?) {
        statusHandler = handler
    }

    func setKeepScreenOnDuringStream(enabled: Bool) {
        keepScreenOnDuringStream = enabled
        UIApplication.shared.isIdleTimerDisabled = enabled && publishing
    }

    func setVideoStabilization(enabled: Bool) {
        videoStabilizationEnabled = enabled
        Task { await applyVideoStabilizationSetting() }
    }

    /// Manual mitigation for the "Lower quality" banner button.
    /// Spike first: HaishinKit's RTMPStream/MediaMixer live-bitrate change API before wiring for real.
    func stepDownQuality() {
        // TODO(spike): apply a lower bitrate via the real HaishinKit API once confirmed.
    }

    func setMicMuted(_ muted: Bool) async {
        guard micMuted != muted else { return }
        micMuted = muted
        if streamPaused { return }
        if muted {
            try? await mixer.attachAudio(nil)
        } else if let audio = AVCaptureDevice.default(for: .audio) {
            try? await mixer.attachAudio(audio)
        }
    }

    func isMicMuted() -> Bool { micMuted }

    func attachView(_ view: MTHKView) {
        registerLifecycleObservers()
        hkView = view
        view.videoGravity = .resizeAspectFill
        if overlayCapture == nil, let host = topViewController() {
            overlayCapture = OverlayWebViewCapture(hostViewController: host)
        }
        Task {
            _ = await ensureStream()
            await preparePreview(width: streamWidth, height: streamHeight, fps: 30)
        }
    }

    func detachView(_ view: MTHKView) {
        if hkView === view {
            hkView = nil
            previewReady = false
        }
    }

    func preparePreview(width: Int, height: Int, fps: Int, bitrate: Int? = nil, rotation: Int = 0) async {
        streamWidth = width
        streamHeight = height
        streamFps = fps
        streamRotation = rotation
        if let bitrate { streamBitrate = bitrate }
        previewReady = false
        do {
            try await ensureDevices()
            let stream = await ensureStream()
            let captureOrientation = await MainActor.run { currentCaptureOrientation() }
            let encoded = encodedFrameSize(baseWidth: width, baseHeight: height, landscape: isLandscape(captureOrientation))
            streamIsPortrait = encoded.height > encoded.width
            preparedCaptureOrientation = captureOrientation
            await mixer.setVideoOrientation(captureOrientation)
            var settings = VideoCodecSettings(
                videoSize: .init(width: encoded.width, height: encoded.height),
                bitRate: streamBitrate,
                maxKeyFrameIntervalDuration: 2
            )
            try await stream.setVideoSettings(settings)
            try await mixer.setFrameRate(Double(fps))
            await configureScreenSize()
            syncOverlayCaptureWidth()
            if !overlayUrl.isEmpty {
                overlayCapture?.setStyle(
                    fontScale: overlayLayout.fontScale,
                    bgColor: overlayLayout.bgColor,
                    textColor: overlayLayout.textColor,
                    theme: overlayLayout.theme
                )
                overlayCapture?.loadUrl(
                    OverlayThemeBridge.urlWithTheme(baseUrl: overlayUrl, mobileTheme: overlayLayout.theme)
                )
            }
            await ensureWatermarkObject()
            await ensureSponsorObject()
            // Composite the scoreboard overlay into the preview too (parity with Android's
            // startPreviewOverlayPush). The overlay ImageScreenObject lives on mixer.screen, which
            // feeds both the MTHKView preview and the RTMP output, so the operator sees the
            // scoreboard before going live — not only once on air.
            if !publishing, !overlayUrl.isEmpty {
                await ensureOverlayObject()
                startOverlayRefresh()
            }
            previewReady = true
            emit("preview_ready", "\(encoded.width)x\(encoded.height)")
        } catch {
            previewReady = false
            emit("error", error.localizedDescription)
        }
    }

    /// Re-prepare preview when the operator rotates the phone before Go Live (parity with Android's
    /// OrientationEventListener → updatePreviewRotation).
    func updatePreviewForCurrentOrientation(
        width: Int = 1280,
        height: Int = 720,
        fps: Int = 30,
        bitrate: Int? = nil
    ) async {
        guard !publishing else { return }
        let captureOrientation = await MainActor.run { currentCaptureOrientation() }
        if captureOrientation == preparedCaptureOrientation, previewReady { return }
        await preparePreview(width: width, height: height, fps: fps, bitrate: bitrate ?? streamBitrate)
    }

    func resetPreviewForOrientation(width: Int, height: Int, fps: Int, bitrate: Int, rotation: Int = 0) async -> Bool {
        guard !publishing else { return false }
        await preparePreview(width: width, height: height, fps: fps, bitrate: bitrate, rotation: rotation)
        return isPreviewReady
    }

    func updateOverlay(url: String, layout: OverlayLayout) {
        // Match Android updateOverlay: an empty URL updates only the layout/watermark and must NOT
        // clear an overlay URL already set for the preview. Saving overlay prefs before go live
        // passes an empty embed URL, so without this guard the preview scoreboard would vanish the
        // moment the operator tweaks overlay settings.
        if !url.isEmpty {
            overlayUrl = url
            overlayCapture?.setStyle(
                fontScale: layout.fontScale,
                bgColor: layout.bgColor,
                textColor: layout.textColor,
                theme: layout.theme
            )
            overlayCapture?.loadUrl(
                OverlayThemeBridge.urlWithTheme(baseUrl: url, mobileTheme: layout.theme)
            )
        } else {
            overlayCapture?.setStyle(
                fontScale: layout.fontScale,
                bgColor: layout.bgColor,
                textColor: layout.textColor,
                theme: layout.theme
            )
        }
        overlayLayout = layout
        syncOverlayCaptureWidth()
        Task {
            await ensureOverlayObject()
            await ensureWatermarkObject()
            await ensureSponsorObject()
            // When not yet live, drive the preview overlay so the scoreboard shows in the preview
            // (parity with Android). While live, startStream already runs the refresh loop.
            if !publishing, !overlayUrl.isEmpty {
                startOverlayRefresh()
            }
        }
    }

    func startStream(
        rtmpUrl: String,
        streamKey: String,
        overlayUrl: String,
        width: Int,
        height: Int,
        bitrate: Int,
        fps: Int,
        layout: OverlayLayout
    ) async {
        streamWidth = width
        streamHeight = height
        streamFps = fps
        streamBitrate = bitrate
        overlayLayout = layout
        if !overlayUrl.isEmpty {
            self.overlayUrl = overlayUrl
        }

        let endpoint = StreamCameraEngine.buildRtmpEndpoint(rtmpUrl: rtmpUrl, streamKey: streamKey)
        guard endpoint.hasPrefix("rtmp://") else {
            emit("error", "Invalid RTMP URL")
            return
        }
        guard isViewAttached else {
            emit("error", "Camera preview not ready")
            return
        }

        emit("preparing", endpoint)
        do {
            // Re-sync encoder to how the phone is held right now — not how Studio was first opened
            // (parity with Android startStreamOnMain rotation re-prepare before RTMP publish).
            await syncEncoderForGoLive(width: width, height: height, fps: fps, bitrate: bitrate)

            try await ensureDevices()
            await resetRtmpSession()
            let stream = await ensureStream()
            let captureOrientation = await MainActor.run { currentCaptureOrientation() }
            let encoded = encodedFrameSize(baseWidth: width, baseHeight: height, landscape: isLandscape(captureOrientation))
            streamIsPortrait = encoded.height > encoded.width
            preparedCaptureOrientation = captureOrientation
            await mixer.setVideoOrientation(captureOrientation)
            var settings = VideoCodecSettings(
                videoSize: .init(width: encoded.width, height: encoded.height),
                bitRate: bitrate,
                maxKeyFrameIntervalDuration: 2
            )
            try await stream.setVideoSettings(settings)
            try await mixer.setFrameRate(Double(fps))
            await configureScreenSize()

            if !self.overlayUrl.isEmpty {
                overlayCapture?.setStyle(
                    fontScale: layout.fontScale,
                    bgColor: layout.bgColor,
                    textColor: layout.textColor,
                    theme: layout.theme
                )
                overlayCapture?.loadUrl(
                    OverlayThemeBridge.urlWithTheme(baseUrl: self.overlayUrl, mobileTheme: layout.theme)
                )
            }
            await ensureOverlayObject()
            await ensureWatermarkObject()
            await ensureSponsorObject()
            startOverlayRefresh()

            let (base, name) = splitRtmp(endpoint)
            guard !name.isEmpty else {
                emit("error", "Invalid RTMP stream key")
                return
            }
            emit("connecting", endpoint)
            try await connection.connect(base)
            try await stream.publish(name)
            publishing = true
            if keepScreenOnDuringStream {
                UIApplication.shared.isIdleTimerDisabled = true
            }
            emit("connected", "")
        } catch {
            publishing = false
            emit("error", error.localizedDescription)
        }
    }

    func stopStream() async {
        stopOverlayRefresh()
        publishing = false
        streamPaused = false
        UIApplication.shared.isIdleTimerDisabled = false
        await hidePauseBlackOverlay()
        await hideStandbySlate()
        endBackgroundTaskIfNeeded()
        await resetRtmpSession()
        // Re-wire the preview surface to a fresh RTMP stream and restore the camera preview.
        await ensureStream()
        await preparePreview(width: streamWidth, height: streamHeight, fps: streamFps, bitrate: streamBitrate)
        // Back to preview: keep compositing the scoreboard overlay for the operator (parity with
        // Android, which restarts the preview overlay push once a broadcast ends).
        startPreviewOverlayIfNeeded()
    }

    func pauseStream() async {
        guard publishing, !streamPaused else { return }
        streamPaused = true
        stopOverlayRefresh()
        await showPauseBlackOverlay()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        emit("paused", "")
    }

    func resumeStream() async {
        guard publishing, streamPaused else { return }
        streamPaused = false
        await hidePauseBlackOverlay()
        configureAudioSession()
        if !micMuted, let audio = AVCaptureDevice.default(for: .audio) {
            try? await mixer.attachAudio(audio)
        }
        startOverlayRefresh()
        emit("resumed", "")
    }

    var isStreamPaused: Bool { streamPaused && publishing }

    func setZoom(level: Float) {
        Task {
            guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else {
                return
            }
            let maxZoom = min(Float(device.activeFormat.videoMaxZoomFactor), 10)
            let clamped = max(1, min(level, maxZoom))
            do {
                try device.lockForConfiguration()
                device.videoZoomFactor = CGFloat(clamped)
                device.unlockForConfiguration()
            } catch {
                // ignore zoom errors
            }
        }
    }

    func zoomRange() -> (min: Double, max: Double, current: Double) {
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else {
            return (1, 10, 1)
        }
        let maxZoom = min(Double(device.activeFormat.videoMaxZoomFactor), 10)
        return (1, maxZoom, Double(device.videoZoomFactor))
    }

    // MARK: - Focus

    // Device configuration runs off the main thread on a serial queue, so two lockForConfiguration
    // calls can never overlap. lock/unlock report the real device result (the view model owns the
    // user-facing lock state) so the padlock can never show a state the camera didn't reach.
    private let cameraConfigQueue = DispatchQueue(label: "uk.co.cricrelay.camera.config")

    private func backCamera() -> AVCaptureDevice? {
        AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back)
    }

    /// Active interface orientation. UIApplication is main-only — hence the main-actor isolation.
    @MainActor
    private func currentInterfaceOrientation() -> UIInterfaceOrientation {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first?.interfaceOrientation ?? .portrait
    }

    /// Map a normalized view point (origin top-left, current UI orientation) into the camera's
    /// point-of-interest space (origin top-left in the sensor's native landscape). Standard back-
    /// camera transforms — worth a device check, though exact placement is uncritical at range.
    @MainActor
    private func sensorPOI(nx: CGFloat, ny: CGFloat) -> CGPoint {
        switch currentInterfaceOrientation() {
        case .landscapeLeft:      return CGPoint(x: 1 - nx, y: 1 - ny)
        case .landscapeRight:     return CGPoint(x: nx, y: ny)
        case .portraitUpsideDown: return CGPoint(x: 1 - ny, y: nx)
        default:                  return CGPoint(x: ny, y: 1 - nx) // portrait
        }
    }

    /// Focus + meter at a point given in view coordinates, resuming continuous AF/AE (releasing any
    /// lock) so the operator can re-aim before locking again. The HaishinKit preview has no
    /// AVCaptureVideoPreviewLayer to convert through, so we map the view point ourselves. Runs on the
    /// main actor (the orientation read needs it); the device write hops to the camera queue.
    @MainActor
    func tapToFocus(viewWidth: Int, viewHeight: Int, x: Float, y: Float) {
        guard viewWidth > 0, viewHeight > 0 else { return }
        let nx = CGFloat(max(0, min(x / Float(viewWidth), 1)))
        let ny = CGFloat(max(0, min(y / Float(viewHeight), 1)))
        let poi = sensorPOI(nx: nx, ny: ny)
        cameraConfigQueue.async { [weak self] in
            guard let device = self?.backCamera() else { return }
            do {
                try device.lockForConfiguration()
                if device.isFocusPointOfInterestSupported { device.focusPointOfInterest = poi }
                if device.isFocusModeSupported(.continuousAutoFocus) {
                    device.focusMode = .continuousAutoFocus
                } else if device.isFocusModeSupported(.autoFocus) {
                    device.focusMode = .autoFocus
                }
                if device.isExposurePointOfInterestSupported { device.exposurePointOfInterest = poi }
                if device.isExposureModeSupported(.continuousAutoExposure) {
                    device.exposureMode = .continuousAutoExposure
                }
                device.unlockForConfiguration()
            } catch {
                // ignore focus errors
            }
        }
    }

    /// Freeze focus *and* exposure at their current values so a fielder, umpire, or passer-by
    /// crossing between the camera and the pitch can't pull either off the strip. AVFoundation's
    /// `.locked` modes hold the converged lens position and exposure until [unlockFocus] resumes
    /// continuous metering. Returns whether the lock actually took.
    func lockFocus() async -> Bool {
        await configureDevice { device in
            if device.isFocusModeSupported(.locked) { device.focusMode = .locked }
            if device.isExposureModeSupported(.locked) { device.exposureMode = .locked }
            return device.focusMode == .locked || device.exposureMode == .locked
        }
    }

    /// Hand focus + exposure back to continuous metering. Returns whether it took.
    func unlockFocus() async -> Bool {
        await configureDevice { device in
            if device.isFocusModeSupported(.continuousAutoFocus) { device.focusMode = .continuousAutoFocus }
            if device.isExposureModeSupported(.continuousAutoExposure) { device.exposureMode = .continuousAutoExposure }
            return device.focusMode == .continuousAutoFocus || device.exposureMode == .continuousAutoExposure
        }
    }

    /// Lock the back camera for configuration on the serial queue, run `body` while the config is
    /// held, then resume the caller with its result. Returns false if the camera is unavailable.
    private func configureDevice(_ body: @escaping (AVCaptureDevice) -> Bool) async -> Bool {
        await withCheckedContinuation { continuation in
            cameraConfigQueue.async { [weak self] in
                guard let device = self?.backCamera() else {
                    continuation.resume(returning: false)
                    return
                }
                var ok = false
                do {
                    try device.lockForConfiguration()
                    ok = body(device)
                    device.unlockForConfiguration()
                } catch {
                    ok = false
                }
                continuation.resume(returning: ok)
            }
        }
    }

    // MARK: - Private

    private func ensureStream() async -> RTMPStream {
        if let existing = rtmpStream {
            return existing
        }
        let stream = RTMPStream(connection: connection)
        rtmpStream = stream
        await mixer.addOutput(stream)
        if let view = hkView {
            await stream.addOutput(view)
        }
        return stream
    }

    /// Tear down the RTMP session so the next Go Live starts from a clean connection + stream.
    /// Reusing a closed RTMPStream/RTMPConnection can crash inside HaishinKit on publish.
    private func resetRtmpSession() async {
        if let stream = rtmpStream {
            if let view = hkView {
                await stream.removeOutput(view)
            }
            await mixer.removeOutput(stream)
            try? await stream.close()
            rtmpStream = nil
        }
        try? await connection.close()
        connection = RTMPConnection()
    }

    /// Re-prepare preview when orientation drifted between Studio open and Go Live tap.
    private func syncEncoderForGoLive(width: Int, height: Int, fps: Int, bitrate: Int) async {
        let captureOrientation = await MainActor.run { currentCaptureOrientation() }
        if captureOrientation != preparedCaptureOrientation || !previewReady {
            await preparePreview(width: width, height: height, fps: fps, bitrate: bitrate)
        }
    }

    private func encodedFrameSize(baseWidth: Int, baseHeight: Int, landscape: Bool) -> (width: Int, height: Int) {
        if landscape {
            return (baseWidth, baseHeight)
        }
        return (baseHeight, baseWidth)
    }

    private func isLandscape(_ orientation: AVCaptureVideoOrientation) -> Bool {
        orientation == .landscapeLeft || orientation == .landscapeRight
    }

    /// When false (studio orientation lock active) the capture follows the locked interface
    /// orientation instead of the physical sensor — otherwise a locked-landscape UI would fight
    /// a portrait-held phone. Parity with Android's Auto-mode-only sensor gating.
    private var followDeviceOrientation = true

    func setFollowDeviceOrientation(_ follow: Bool) {
        followDeviceOrientation = follow
    }

    @MainActor
    private func currentCaptureOrientation() -> AVCaptureVideoOrientation {
        // Physical device orientation first (parity with Android's OrientationEventListener):
        // orientationDidChangeNotification fires before the interface finishes rotating, so
        // reading the interface here returns the OLD orientation and the re-prepare dedupes
        // itself away — the "studio opened portrait stays portrait" bug. The landscape cases
        // are crossed on purpose: UIDeviceOrientation is defined from the device's viewpoint,
        // AVCaptureVideoOrientation from the video's.
        if followDeviceOrientation {
            switch UIDevice.current.orientation {
            case .landscapeLeft: return .landscapeRight
            case .landscapeRight: return .landscapeLeft
            case .portraitUpsideDown: return .portraitUpsideDown
            case .portrait: return .portrait
            default: break // .faceUp / .faceDown / .unknown — fall back to the interface
            }
        }
        switch currentInterfaceOrientation() {
        case .landscapeLeft: return .landscapeLeft
        case .landscapeRight: return .landscapeRight
        case .portraitUpsideDown: return .portraitUpsideDown
        default: return .portrait
        }
    }

    private func ensureDevices() async throws {
        configureAudioSession()
        if !devicesAttached {
            if let audio = AVCaptureDevice.default(for: .audio) {
                try await mixer.attachAudio(audio)
            }
            if let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) {
                let stabilizationEnabled = videoStabilizationEnabled
                try await mixer.attachVideo(camera, track: 0) { unit in
                    unit.preferredVideoStabilizationMode = stabilizationEnabled ? .cinematicExtended : .off
                }
            }
            var vmSettings = await mixer.videoMixerSettings
            vmSettings.mode = .offscreen
            vmSettings.mainTrack = 0
            await mixer.setVideoMixerSettings(vmSettings)
            devicesAttached = true
        }
        await mixer.startRunning()
    }

    private func configureAudioSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playAndRecord, mode: .videoChat, options: [.defaultToSpeaker, .allowBluetooth])
        try? session.setActive(true)
    }

    private func applyVideoStabilizationSetting() async {
        guard devicesAttached else { return }
        let enabled = videoStabilizationEnabled
        // Stabilisation is applied on the capture connection via VideoDeviceUnit, not AVCaptureDevice.
        try? await mixer.configuration(video: 0) { unit in
            unit.preferredVideoStabilizationMode = enabled ? .cinematicExtended : .off
        }
    }

    private func configureScreenSize() async {
        let encodedW = encodedCanvasWidth()
        let encodedH = encodedCanvasHeight()
        await Task { @ScreenActor in
            await mixer.screen.size = CGSize(width: encodedW, height: encodedH)
            await mixer.screen.backgroundColor = UIColor.black.cgColor
        }.value
    }

    private func ensureOverlayObject() async {
        await Task { @ScreenActor in
            if overlayObject == nil {
                let obj = ImageScreenObject()
                obj.horizontalAlignment = .left
                obj.verticalAlignment = .bottom
                overlayObject = obj
                try? await mixer.screen.addChild(obj)
            }
            applyOverlayLayout()
        }.value
    }

    /// Adds (or refreshes) the brand watermark in the top-right of the encoded frame.
    /// Removed when disabled or text is blank.
    private func ensureWatermarkObject() async {
        let enabled = overlayLayout.watermarkEnabled
        let text = overlayLayout.watermarkText.trimmingCharacters(in: .whitespaces)
        if !enabled || text.isEmpty {
            await Task { @ScreenActor in
                if let obj = watermarkObject {
                    try? await mixer.screen.removeChild(obj)
                    watermarkObject = nil
                    appliedWatermarkText = nil
                }
            }.value
            return
        }
        guard appliedWatermarkText != text || watermarkObject == nil else { return }
        guard let cg = buildWatermarkImage(text)?.cgImage else { return }
        await Task { @ScreenActor in
            if watermarkObject == nil {
                let obj = ImageScreenObject()
                obj.horizontalAlignment = .right
                obj.verticalAlignment = .top
                obj.layoutMargin = UIEdgeInsets(top: 18, left: 0, bottom: 0, right: 18)
                watermarkObject = obj
                try? await mixer.screen.addChild(obj)
            }
            watermarkObject?.cgImage = cg
            appliedWatermarkText = text
        }.value
    }

    private func effectiveSponsorUrls() -> [String] {
        let urls = overlayLayout.sponsorLogoUrls.filter { !$0.isEmpty }
        if !urls.isEmpty { return Array(urls.prefix(6)) }
        return overlayLayout.sponsorLogoUrl.isEmpty ? [] : [overlayLayout.sponsorLogoUrl]
    }

    private func visibleSponsorUrls() -> [String] {
        let all = effectiveSponsorUrls()
        switch overlayLayout.sponsorLayoutMode {
        case "carousel": return all.isEmpty ? [] : [all[carouselIndex % all.count]]
        default: return all
        }
    }

    /// Adds (or refreshes) sponsor logo(s) on the encoded frame.
    private func ensureSponsorObject() async {
        let urls = effectiveSponsorUrls()
        if !overlayLayout.sponsorEnabled || urls.isEmpty {
            stopSponsorCarousel()
            stopSponsorScroll()
            await clearSponsorObjects()
            return
        }
        switch overlayLayout.sponsorLayoutMode {
        case "multi":
            stopSponsorCarousel()
            await ensureMultiSponsorObjects(urls: urls)
        case "carousel":
            carouselUrls = urls
            carouselIndex = 0
            await ensureCarouselSponsorObject(urls: urls)
        default:
            stopSponsorCarousel()
            await ensureSingleSponsorObject(url: urls[0])
        }
        startSponsorScrollIfNeeded()
    }

    private func ensureSingleSponsorObject(url: String) async {
        await syncSponsorObjectKeys(want: Set([url]))
        await loadSponsorObject(url: url, index: 0, total: 1)
    }

    private func ensureMultiSponsorObjects(urls: [String]) async {
        await syncSponsorObjectKeys(want: Set(urls))
        for (index, url) in urls.enumerated() {
            await loadSponsorObject(url: url, index: index, total: urls.count)
        }
    }

    private func ensureCarouselSponsorObject(urls: [String]) async {
        guard !urls.isEmpty else { return }
        await ensureSingleSponsorObject(url: urls[0])
        stopSponsorCarousel()
        guard urls.count > 1 else { return }
        let interval = TimeInterval(max(2, min(30, overlayLayout.sponsorCarouselIntervalSec)))
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.sponsorCarouselTimer?.invalidate()
            self.sponsorCarouselTimer = Timer.scheduledTimer(withTimeInterval: interval, repeats: true) { [weak self] _ in
                guard let self else { return }
                guard self.overlayLayout.sponsorLayoutMode == "carousel", self.carouselUrls.count > 1 else { return }
                self.carouselIndex = (self.carouselIndex + 1) % self.carouselUrls.count
                let url = self.carouselUrls[self.carouselIndex]
                Task { await self.ensureSingleSponsorObject(url: url) }
            }
        }
    }

    private func stopSponsorCarousel() {
        DispatchQueue.main.async { [weak self] in
            self?.sponsorCarouselTimer?.invalidate()
            self?.sponsorCarouselTimer = nil
        }
        carouselUrls = []
        carouselIndex = 0
    }

    @ScreenActor
    private func syncSponsorObjectKeys(want: Set<String>) async {
        let remove = sponsorObjects.keys.filter { !want.contains($0) }
        for key in remove {
            if let obj = sponsorObjects.removeValue(forKey: key) {
                try? await mixer.screen.removeChild(obj)
            }
        }
        appliedSponsorUrls = want
    }

    /// Per-URL cache file under the app caches dir, so a sponsor logo survives a network/DNS blip
    /// at a ground once it has loaded on any prior session.
    private func sponsorCacheFile(for url: String) -> URL? {
        guard let dir = try? FileManager.default.url(
            for: .cachesDirectory, in: .userDomainMask, appropriateFor: nil, create: true
        ).appendingPathComponent("sponsor_logos", isDirectory: true) else { return nil }
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("logo_\(UInt(bitPattern: url.hashValue)).img")
    }

    private func loadSponsorObject(url: String, index: Int, total: Int) async {
        guard let remoteURL = URL(string: url) else { return }
        let cacheFile = sponsorCacheFile(for: url)
        var data: Data
        if let cacheFile, let cached = try? Data(contentsOf: cacheFile), !cached.isEmpty {
            // Cache hit: use it immediately, refresh in the background for next time.
            data = cached
            Task.detached {
                if let fresh = try? await URLSession.shared.data(from: remoteURL).0 {
                    try? fresh.write(to: cacheFile)
                }
            }
        } else {
            do {
                (data, _) = try await URLSession.shared.data(from: remoteURL)
            } catch {
                return
            }
            if let cacheFile { try? data.write(to: cacheFile) }
        }
        guard let rawImage = UIImage(data: data) else { return }
        let image = applyImageOpacity(rawImage, opacity: overlayLayout.sponsorOpacity)
        guard let cg = image.cgImage else { return }
        await Task { @ScreenActor in
            if sponsorObjects[url] == nil {
                let o = ImageScreenObject()
                sponsorObjects[url] = o
                try? await mixer.screen.addChild(o)
            }
            guard let obj = sponsorObjects[url] else { return }
            obj.cgImage = cg
            layoutSponsorObject(obj, index: index, total: total)
        }.value
    }

    @ScreenActor
    private func clearSponsorObjects() async {
        for obj in sponsorObjects.values {
            try? await mixer.screen.removeChild(obj)
        }
        sponsorObjects.removeAll()
        appliedSponsorUrls.removeAll()
    }

    private func isSponsorScrollMode() -> Bool {
        overlayLayout.sponsorDisplayMode.hasPrefix("scroll")
    }

    private func stopSponsorScroll() {
        DispatchQueue.main.async { [weak self] in
            self?.sponsorScrollTimer?.invalidate()
            self?.sponsorScrollTimer = nil
            self?.sponsorScrollActiveDir = nil
        }
    }

    /// Per-frame travel as a fraction of the canvas dimension, so scroll pace is the same on
    /// every resolution (720p/1080p). ~0.45%/frame matches the Android animator.
    private static let sponsorScrollStepFraction: CGFloat = 0.0045

    private static let sponsorScrollGap: CGFloat = 40  // px gap between tiled logos

    private func startSponsorScrollIfNeeded() {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            guard self.overlayLayout.sponsorEnabled, self.isSponsorScrollMode() else {
                self.sponsorScrollTimer?.invalidate()
                self.sponsorScrollTimer = nil
                self.sponsorScrollActiveDir = nil
                return
            }
            let dir = SponsorScrollDirection.sanitize(self.overlayLayout.sponsorScrollDirection)
            // "fixed" = a scroll-band strip pinned in place (no travel): render once, no timer.
            if dir == SponsorScrollDirection.fixed {
                self.sponsorScrollTimer?.invalidate()
                self.sponsorScrollTimer = nil
                self.sponsorScrollActiveDir = dir
                self.sponsorScrollOffset = 0
                Task { await self.layoutAllSponsorObjects() }
                return
            }
            // Idempotent: if the marquee is already running this direction, keep the current offset
            // so the logo doesn't jump back to the entry edge on repeated init calls.
            if self.sponsorScrollTimer != nil, self.sponsorScrollActiveDir == dir { return }
            self.sponsorScrollTimer?.invalidate()
            self.sponsorScrollActiveDir = dir
            let horizontal = SponsorScrollDirection.isHorizontal(dir)
            // Monotonic accumulator in px; direction/edge is applied per-sprite in layoutSponsorObject
            // via a modulo marquee, so the strip slides fully edge-to-edge and tiles seamlessly.
            self.sponsorScrollOffset = 0
            self.sponsorScrollTimer = Timer.scheduledTimer(withTimeInterval: 1.0 / 30.0, repeats: true) { [weak self] _ in
                guard let self else { return }
                let speed = CGFloat(max(0.3, min(3, self.overlayLayout.sponsorScrollSpeed)))
                let ext = CGFloat(horizontal ? self.encodedCanvasWidth() : self.encodedCanvasHeight())
                self.sponsorScrollOffset += ext * Self.sponsorScrollStepFraction * speed
                if self.sponsorScrollOffset > 1_000_000 { self.sponsorScrollOffset = 0 }
                Task { await self.layoutAllSponsorObjects() }
            }
        }
    }

    /// Marquee position (px) for sprite `index` of `total` along a `period`-wide loop.
    private func marqueePhase(period: CGFloat, index: Int, total: Int) -> CGFloat {
        guard period > 0 else { return 0 }
        let spacing = period / CGFloat(max(1, total))
        let raw = (sponsorScrollOffset + CGFloat(index) * spacing).truncatingRemainder(dividingBy: period)
        return raw < 0 ? raw + period : raw
    }

    @ScreenActor
    private func layoutAllSponsorObjects() async {
        let urls = visibleSponsorUrls()
        for (index, url) in urls.enumerated() {
            if let obj = sponsorObjects[url] {
                layoutSponsorObject(obj, index: index, total: urls.count)
            }
        }
    }

    @ScreenActor
    private func layoutSponsorObject(_ obj: ImageScreenObject, index: Int, total: Int) {
        guard let cg = obj.cgImage else { return }
        let canvasW = CGFloat(encodedCanvasWidth())
        let canvasH = CGFloat(encodedCanvasHeight())
        let sizeMul: CGFloat = total <= 1 ? 1 : total == 2 ? 0.85 : 0.7
        let scale = CGFloat(max(0.3, min(3, overlayLayout.sponsorSizeScale))) * sizeMul
        let aspect = CGFloat(cg.height) / max(CGFloat(cg.width), 1)
        let imgW = canvasW * 0.18 * scale
        let imgH = imgW * aspect
        if isSponsorScrollMode() {
            obj.horizontalAlignment = .left
            obj.verticalAlignment = .top
            let dir = SponsorScrollDirection.sanitize(overlayLayout.sponsorScrollDirection)
            let gap = Self.sponsorScrollGap
            if SponsorScrollDirection.isVertical(dir) {
                // Vertical crawl: Y marquees edge-to-edge, X comes from the drag position.
                let period = canvasH + imgH + gap
                let phase = marqueePhase(period: period, index: index, total: total)
                // ttb: enter from top → exit bottom; btt: enter from bottom → exit top.
                let y = dir == SponsorScrollDirection.ttb ? (-imgH - gap + phase) : (canvasH - phase)
                let x = max(0, min(canvasW - imgW,
                    CGFloat(max(0, min(1, overlayLayout.sponsorPositionX))) * canvasW - imgW / 2))
                obj.layoutMargin = UIEdgeInsets(top: y, left: x, bottom: 0, right: 0)
            } else {
                // Horizontal ticker: X marquees edge-to-edge, Y band from display mode.
                let period = canvasW + imgW + gap
                let phase = marqueePhase(period: period, index: index, total: total)
                // rtl: enter from right → exit left; ltr: enter from left → exit right.
                let x = dir == SponsorScrollDirection.ltr ? (-imgW - gap + phase) : (canvasW - phase)
                let y = sponsorScrollY(canvasH: canvasH, imgH: imgH)
                obj.layoutMargin = UIEdgeInsets(top: y, left: x, bottom: 0, right: 0)
            }
        } else {
            obj.horizontalAlignment = .left
            obj.verticalAlignment = .top
            let cx = total <= 1
                ? CGFloat(max(0, min(1, overlayLayout.sponsorPositionX))) * canvasW
                : ((CGFloat(index) + 0.5) / CGFloat(total)) * canvasW
            let cy = CGFloat(max(0, min(1, overlayLayout.sponsorPositionY))) * canvasH
            obj.layoutMargin = UIEdgeInsets(
                top: max(0, cy - imgH / 2),
                left: max(0, cx - imgW / 2),
                bottom: 0,
                right: 0
            )
        }
    }

    private func sponsorScrollY(canvasH: CGFloat, imgH: CGFloat) -> CGFloat {
        let boardTop = CGFloat(overlayLayout.anchorY) * canvasH - CGFloat(overlayLayout.heightFraction) * canvasH
        switch overlayLayout.sponsorDisplayMode {
        case "scroll_top":
            return 8
        case "scroll_bottom":
            return max(8, canvasH - imgH - 8)
        case "scroll_above_board":
            return max(8, boardTop - imgH - 8)
        case "scroll_below_board":
            return min(canvasH - imgH - 8, CGFloat(overlayLayout.anchorY) * canvasH + 8)
        default:
            return CGFloat(overlayLayout.sponsorPositionY) * canvasH
        }
    }

    /// Renders the watermark text onto a translucent rounded pill (white text, ~82%).
    private func buildWatermarkImage(_ text: String) -> UIImage? {
        let font = UIFont.systemFont(ofSize: 30, weight: .bold)
        let attrs: [NSAttributedString.Key: Any] = [
            .font: font,
            .foregroundColor: UIColor.white.withAlphaComponent(0.85),
        ]
        let textSize = (text as NSString).size(withAttributes: attrs)
        let padH: CGFloat = 22
        let padV: CGFloat = 12
        let size = CGSize(width: textSize.width + padH * 2, height: textSize.height + padV * 2)
        let renderer = UIGraphicsImageRenderer(size: size)
        return renderer.image { ctx in
            let rect = CGRect(origin: .zero, size: size)
            let path = UIBezierPath(roundedRect: rect, cornerRadius: 12)
            UIColor.black.withAlphaComponent(0.5).setFill()
            path.fill()
            (text as NSString).draw(
                at: CGPoint(x: padH, y: padV),
                withAttributes: attrs
            )
        }
    }

    @ScreenActor
    private func applyOverlayLayout() {
        guard let obj = overlayObject else { return }
        let streamH = CGFloat(encodedCanvasHeight())
        let streamW = CGFloat(encodedCanvasWidth())
        // Lift straight off the bottom edge by bottomMarginFraction (0 = flush), matching the
        // Android GL sprite math. The old anchorY-derived formula left a ~7% gap below the board.
        let bottomPx = streamH * CGFloat(max(0, min(0.6, overlayLayout.bottomMarginFraction)))
        let insetX = streamW * CGFloat(overlayLayout.horizontalInsetFraction)
        obj.layoutMargin = UIEdgeInsets(
            top: 0,
            left: insetX,
            bottom: bottomPx,
            right: insetX
        )
        obj.horizontalAlignment = .left
        obj.verticalAlignment = .bottom
    }

    /// Scale captured strip to the operator's width slider (fraction of stream canvas).
    private func scaleOverlayImage(_ image: UIImage, targetWidth: CGFloat) -> UIImage {
        guard image.size.width > 0, targetWidth > 0 else { return image }
        let aspect = image.size.height / image.size.width
        let size = CGSize(width: targetWidth, height: targetWidth * aspect)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1.0
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
    }

    /// Bake a uniform alpha into the overlay bitmap (parity with Android applyBitmapOpacity).
    private func applyImageOpacity(_ image: UIImage, opacity: Float) -> UIImage {
        let alpha = CGFloat(max(0.2, min(1.0, opacity)))
        if alpha >= 0.999 { return image }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1.0
        format.opaque = false
        return UIGraphicsImageRenderer(size: image.size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: image.size), blendMode: .normal, alpha: alpha)
        }
    }

    /// Compose the scoreboard overlay into the preview when not yet live (parity with Android's
    /// startPreviewOverlayPush). No-op while publishing (startStream owns the refresh loop then) or
    /// when there is no view / overlay URL.
    private func startPreviewOverlayIfNeeded() {
        guard !publishing, isViewAttached, !overlayUrl.isEmpty else { return }
        Task {
            await ensureOverlayObject()
            startOverlayRefresh()
        }
    }

    // The capture timer touches UIKit/WebKit and must live on the main run loop, so all timer
    // lifecycle goes through the main queue regardless of which executor the caller is on.
    private func startOverlayRefresh() {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.overlayTimer?.invalidate()
            self.overlayTimer = Timer.scheduledTimer(withTimeInterval: self.overlayRefreshInterval, repeats: true) { [weak self] _ in
                self?.refreshOverlayFrame()
            }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { [weak self] in
            self?.refreshOverlayFrame()
        }
    }

    private func stopOverlayRefresh() {
        DispatchQueue.main.async { [weak self] in
            self?.overlayTimer?.invalidate()
            self?.overlayTimer = nil
        }
    }

    private func encodedCanvasWidth() -> Int {
        streamIsPortrait ? streamHeight : streamWidth
    }

    private func encodedCanvasHeight() -> Int {
        streamIsPortrait ? streamWidth : streamHeight
    }

    private func syncOverlayCaptureWidth() {
        overlayCapture?.setCaptureWidth(encodedCanvasWidth())
    }

    private func refreshOverlayFrame() {
        syncOverlayCaptureWidth()
        let canvasW = encodedCanvasWidth()
        let targetW = CGFloat(canvasW) * CGFloat(overlayLayout.widthFraction)
        guard let image = overlayCapture?.capture(width: canvasW, height: 200) else { return }
        let scaled = scaleOverlayImage(image, targetWidth: targetW)
        let withOpacity = applyImageOpacity(scaled, opacity: overlayLayout.opacity)
        guard let cg = withOpacity.cgImage else { return }
        Task { @ScreenActor in
            overlayObject?.cgImage = cg
            applyOverlayLayout()
        }
    }

    private func splitRtmp(_ endpoint: String) -> (String, String) {
        guard let url = URL(string: endpoint) else { return (endpoint, "") }
        let name = url.lastPathComponent
        let base = endpoint.replacingOccurrences(of: "/\(name)", with: "")
        return (base, name)
    }

    private func emit(_ event: String, _ message: String) {
        DispatchQueue.main.async { [weak self] in
            self?.statusHandler?(event, message)
        }
    }

    private func topViewController() -> UIViewController? {
        UIApplication.shared.connectedScenes
            .compactMap { ($0 as? UIWindowScene)?.keyWindow?.rootViewController }
            .first
    }

    // MARK: - Background lifecycle

    private func registerLifecycleObservers() {
        guard !lifecycleObserversRegistered else { return }
        lifecycleObserversRegistered = true
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(appDidEnterBackground),
            name: UIApplication.didEnterBackgroundNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(appWillEnterForeground),
            name: UIApplication.willEnterForegroundNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(thermalStateChanged),
            name: ProcessInfo.thermalStateDidChangeNotification,
            object: nil
        )
        thermalStateChanged()
    }

    @objc private func thermalStateChanged() {
        let state = ProcessInfo.processInfo.thermalState
        lastThermalState = state
        switch state {
        case .critical:
            overlayRefreshInterval = 1.75
        case .serious:
            overlayRefreshInterval = 1.0
        default:
            overlayRefreshInterval = 0.5
        }
        let raw: Int
        switch state {
        case .nominal: raw = 0
        case .fair: raw = 1
        case .serious: raw = 2
        case .critical: raw = 3
        @unknown default: raw = 0
        }
        emit("thermal", String(raw))
    }

    @objc private func appDidEnterBackground() {
        guard publishing else { return }
        beginBackgroundTaskIfNeeded()
        // Keep the audio session active (UIBackgroundModes: audio) so commentary keeps flowing,
        // and cover the suspended camera with a branded standby slate.
        Task { await showStandbySlate() }
    }

    @objc private func appWillEnterForeground() {
        Task {
            await hideStandbySlate()
            // iOS suspends camera capture in the background, so on return we re-kick the mixer and
            // overlay refresh to bring the live frame back immediately instead of leaving viewers on
            // the standby slate. This is the iOS-appropriate counterpart to Android's PiP: Apple has
            // no over-apps camera overlay, but the goal is the same — don't lose the broadcast when
            // the operator leaves the app.
            if publishing && !streamPaused {
                await mixer.startRunning()
                startOverlayRefresh()
            }
        }
        endBackgroundTaskIfNeeded()
    }

    private func beginBackgroundTaskIfNeeded() {
        guard backgroundTaskId == .invalid else { return }
        backgroundTaskId = UIApplication.shared.beginBackgroundTask(withName: "cricrelay-stream") { [weak self] in
            self?.endBackgroundTaskIfNeeded()
        }
    }

    private func endBackgroundTaskIfNeeded() {
        guard backgroundTaskId != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTaskId)
        backgroundTaskId = .invalid
    }

    private func showStandbySlate() async {
        guard publishing, let cg = buildStandbyImage()?.cgImage else { return }
        await Task { @ScreenActor in
            if standbyObject == nil {
                let obj = ImageScreenObject()
                obj.horizontalAlignment = .center
                obj.verticalAlignment = .middle
                standbyObject = obj
                try? await mixer.screen.addChild(obj)
            }
            standbyObject?.cgImage = cg
        }.value
    }

    private func hideStandbySlate() async {
        await Task { @ScreenActor in
            if let obj = standbyObject {
                try? await mixer.screen.removeChild(obj)
                standbyObject = nil
            }
        }.value
    }

    /// Full-frame black cover while paused so viewers see black video (parity with Android BlackFilterRender).
    private func showPauseBlackOverlay() async {
        guard publishing, let cg = buildPauseBlackImage()?.cgImage else { return }
        await Task { @ScreenActor in
            if pauseBlackObject == nil {
                let obj = ImageScreenObject()
                obj.horizontalAlignment = .center
                obj.verticalAlignment = .middle
                pauseBlackObject = obj
                try? await mixer.screen.addChild(obj)
            }
            pauseBlackObject?.cgImage = cg
        }.value
    }

    private func hidePauseBlackOverlay() async {
        await Task { @ScreenActor in
            if let obj = pauseBlackObject {
                try? await mixer.screen.removeChild(obj)
                pauseBlackObject = nil
            }
        }.value
    }

    private func buildPauseBlackImage() -> UIImage? {
        let size = CGSize(width: encodedCanvasWidth(), height: encodedCanvasHeight())
        let renderer = UIGraphicsImageRenderer(size: size)
        return renderer.image { ctx in
            UIColor.black.setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
        }
    }

    private static func buildRtmpEndpoint(rtmpUrl: String, streamKey: String) -> String {
        var server = rtmpUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        while server.hasSuffix("/") { server.removeLast() }
        let key = streamKey.trimmingCharacters(in: .whitespacesAndNewlines)
        if server.isEmpty { return "" }
        if key.isEmpty { return server }
        if server.hasSuffix("/\(key)") { return server }
        return "\(server)/\(key)"
    }

    /// Full-frame Floodlight-branded standby card shown while the app is backgrounded.
    private func buildStandbyImage() -> UIImage? {
        let size = CGSize(width: encodedCanvasWidth(), height: encodedCanvasHeight())
        let ink = UIColor(red: 0x0A / 255, green: 0x0E / 255, blue: 0x15 / 255, alpha: 1)
        let gold = UIColor(red: 0xFF / 255, green: 0xC2 / 255, blue: 0x33 / 255, alpha: 1)
        let renderer = UIGraphicsImageRenderer(size: size)
        return renderer.image { ctx in
            ink.setFill()
            ctx.fill(CGRect(origin: .zero, size: size))

            let titleFont = UIFont.systemFont(ofSize: size.height * 0.06, weight: .bold)
            let subFont = UIFont.systemFont(ofSize: size.height * 0.035, weight: .medium)
            let brandFont = UIFont.systemFont(ofSize: size.height * 0.03, weight: .heavy)
            let para = NSMutableParagraphStyle()
            para.alignment = .center

            let title = "Screen locked"
            let subtitle = "Back shortly — commentary continues"
            let brand = "CricRelay"

            let titleSize = (title as NSString).size(withAttributes: [.font: titleFont])
            let subSize = (subtitle as NSString).size(withAttributes: [.font: subFont])
            let brandSize = (brand as NSString).size(withAttributes: [.font: brandFont])
            let gap = size.height * 0.03
            let blockHeight = titleSize.height + subSize.height + brandSize.height + gap * 2
            var y = (size.height - blockHeight) / 2

            (title as NSString).draw(
                in: CGRect(x: 0, y: y, width: size.width, height: titleSize.height),
                withAttributes: [.font: titleFont, .foregroundColor: gold, .paragraphStyle: para]
            )
            y += titleSize.height + gap
            (subtitle as NSString).draw(
                in: CGRect(x: 0, y: y, width: size.width, height: subSize.height),
                withAttributes: [.font: subFont, .foregroundColor: UIColor.white.withAlphaComponent(0.85), .paragraphStyle: para]
            )
            y += subSize.height + gap
            (brand as NSString).draw(
                in: CGRect(x: 0, y: y, width: size.width, height: brandSize.height),
                withAttributes: [.font: brandFont, .foregroundColor: UIColor.white.withAlphaComponent(0.6), .paragraphStyle: para]
            )
        }
    }
}
