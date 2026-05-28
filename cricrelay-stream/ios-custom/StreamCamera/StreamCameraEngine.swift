import AVFoundation
import HaishinKit
import RTMPHaishinKit
import UIKit

/// Camera RTMP + scoreboard overlay (HaishinKit). Matches Android MethodChannel API.
@available(iOS 15.0, *)
final class StreamCameraEngine: NSObject {
    static let shared = StreamCameraEngine()

    struct OverlayLayout {
        var heightFraction: Float = 0.22
        var bottomMarginFraction: Float = 0.02
        var horizontalInsetFraction: Float = 0.02
    }

    private let mixer = MediaMixer()
    private let connection = RTMPConnection()
    private var rtmpStream: RTMPStream?
    private weak var hkView: MTHKView?
    private var overlayCapture: OverlayWebViewCapture?
    private var overlayTimer: Timer?
    private var overlayObject: ImageScreenObject?
    private var overlayLayout = OverlayLayout()
    private var overlayUrl = ""
    private var streamWidth = 1280
    private var streamHeight = 720
    private var streamBitrate = 2_500_000
    private var devicesAttached = false
    private var publishing = false
    private var statusHandler: ((String, String) -> Void)?

    var isViewAttached: Bool { hkView != nil }

    func setStatusHandler(_ handler: ((String, String) -> Void)?) {
        statusHandler = handler
    }

    func attachView(_ view: MTHKView) {
        hkView = view
        view.videoGravity = .resizeAspectFill
        if overlayCapture == nil, let host = topViewController() {
            overlayCapture = OverlayWebViewCapture(hostViewController: host)
        }
        Task {
            let stream = await ensureStream()
            await MainActor.run {
                Task { await stream.addOutput(view) }
            }
            await preparePreview(width: streamWidth, height: streamHeight, fps: 30)
        }
    }

    func detachView(_ view: MTHKView) {
        if hkView === view {
            hkView = nil
        }
    }

    func preparePreview(width: Int, height: Int, fps: Int) async {
        streamWidth = width
        streamHeight = height
        do {
            try await ensureDevices()
            let stream = await ensureStream()
            var settings = VideoCodecSettings(
                videoSize: .init(width: width, height: height),
                bitRate: streamBitrate,
                maxKeyFrameIntervalDuration: 2
            )
            try await stream.setVideoSettings(settings)
            try await mixer.setFrameRate(Double(fps))
            await configureScreenSize()
            if !overlayUrl.isEmpty {
                overlayCapture?.loadUrl(overlayUrl)
            }
        } catch {
            emit("error", error.localizedDescription)
        }
    }

    func updateOverlay(url: String, layout: OverlayLayout) {
        overlayUrl = url
        overlayLayout = layout
        overlayCapture?.loadUrl(url)
        Task { await ensureOverlayObject() }
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
        streamBitrate = bitrate
        overlayLayout = layout
        self.overlayUrl = overlayUrl

        let endpoint = StreamRtmpPlugin.buildEndpoint(rtmpUrl: rtmpUrl, streamKey: streamKey)
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
            try await ensureDevices()
            let stream = await ensureStream()
            var settings = VideoCodecSettings(
                videoSize: .init(width: width, height: height),
                bitRate: bitrate,
                maxKeyFrameIntervalDuration: 2
            )
            try await stream.setVideoSettings(settings)
            try await mixer.setFrameRate(Double(fps))
            await configureScreenSize()

            if !overlayUrl.isEmpty {
                overlayCapture?.loadUrl(overlayUrl)
            }
            await ensureOverlayObject()
            startOverlayRefresh()

            let (base, name) = splitRtmp(endpoint)
            emit("connecting", endpoint)
            try await connection.connect(base)
            try await stream.publish(name)
            publishing = true
            emit("connected", "")
        } catch {
            publishing = false
            emit("error", error.localizedDescription)
        }
    }

    func stopStream() async {
        stopOverlayRefresh()
        publishing = false
        if let stream = rtmpStream {
            try? await stream.close()
        }
        try? await connection.close()
    }

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

    // MARK: - Private

    private func ensureStream() async -> RTMPStream {
        if let existing = rtmpStream {
            return existing
        }
        let stream = RTMPStream(connection: connection)
        rtmpStream = stream
        await mixer.addOutput(stream)
        return stream
    }

    private func ensureDevices() async throws {
        configureAudioSession()
        if !devicesAttached {
            if let audio = AVCaptureDevice.default(for: .audio) {
                try await mixer.attachAudio(audio)
            }
            if let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) {
                try await mixer.attachVideo(camera, track: 0)
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

    private func configureScreenSize() async {
        await Task { @ScreenActor in
            await mixer.screen.size = CGSize(width: streamWidth, height: streamHeight)
            await mixer.screen.backgroundColor = UIColor.black.cgColor
        }.value
    }

    private func ensureOverlayObject() async {
        await Task { @ScreenActor in
            if overlayObject == nil {
                let obj = ImageScreenObject()
                obj.horizontalAlignment = .center
                obj.verticalAlignment = .bottom
                overlayObject = obj
                try? await mixer.screen.addChild(obj)
            }
            applyOverlayLayout()
        }.value
    }

    @ScreenActor
    private func applyOverlayLayout() {
        guard let obj = overlayObject else { return }
        let bottom = CGFloat(overlayLayout.bottomMarginFraction * 400)
        obj.layoutMargin = UIEdgeInsets(top: 0, left: 0, bottom: bottom, right: 0)
    }

    private func startOverlayRefresh() {
        stopOverlayRefresh()
        overlayTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            self?.refreshOverlayFrame()
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { [weak self] in
            self?.refreshOverlayFrame()
        }
    }

    private func stopOverlayRefresh() {
        overlayTimer?.invalidate()
        overlayTimer = nil
    }

    private func refreshOverlayFrame() {
        let inset = overlayLayout.horizontalInsetFraction
        let w = Int(Float(streamWidth) * (1 - inset * 2))
        let h = Int(Float(streamHeight) * overlayLayout.heightFraction)
        guard let image = overlayCapture?.capture(width: max(w, 320), height: max(h, 64)),
              let cg = image.cgImage else { return }
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
}
