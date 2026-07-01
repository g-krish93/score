import Flutter
import UIKit

@available(iOS 15.0, *)
public final class StreamRtmpPlugin: NSObject, FlutterPlugin, FlutterStreamHandler {
    private var eventSink: FlutterEventSink?

    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(
            name: "uk.co.cricrelay.stream/rtmp",
            binaryMessenger: registrar.messenger()
        )
        let events = FlutterEventChannel(
            name: "uk.co.cricrelay.stream/rtmp_events",
            binaryMessenger: registrar.messenger()
        )
        let instance = StreamRtmpPlugin()
        registrar.addMethodCallDelegate(instance, channel: channel)
        events.setStreamHandler(instance)
        registrar.register(
            CricrelayCameraViewFactory(),
            withId: "cricrelay-camera-preview"
        )
        StreamCameraEngine.shared.setStatusHandler { event, message in
            instance.eventSink?(["event": event, "message": message])
        }
    }

    public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        eventSink = events
        return nil
    }

    public func onCancel(withArguments arguments: Any?) -> FlutterError? {
        eventSink = nil
        return nil
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "isCaptureSupported":
            result(true)
        case "isCameraReady":
            result(StreamCameraEngine.shared.isPreviewReady)
        case "prepareCamera":
            if StreamCameraEngine.shared.isStreaming {
                result(StreamCameraEngine.shared.isPreviewReady)
                return
            }
            let args = call.arguments as? [String: Any]
            let width = args?["width"] as? Int ?? 1280
            let height = args?["height"] as? Int ?? 720
            let fps = args?["fps"] as? Int ?? 30
            let bitrate = args?["bitrateBps"] as? Int ?? 2_500_000
            let rotation = args?["rotation"] as? Int ?? 0
            Task {
                await StreamCameraEngine.shared.preparePreview(
                    width: width,
                    height: height,
                    fps: fps,
                    bitrate: bitrate,
                    rotation: rotation
                )
                result(StreamCameraEngine.shared.isPreviewReady)
            }
        case "resetCameraOrientation":
            if StreamCameraEngine.shared.isStreaming {
                result(false)
                return
            }
            let args = call.arguments as? [String: Any]
            let width = args?["width"] as? Int ?? 1280
            let height = args?["height"] as? Int ?? 720
            let fps = args?["fps"] as? Int ?? 30
            let bitrate = args?["bitrateBps"] as? Int ?? 2_500_000
            let rotation = args?["rotation"] as? Int ?? 0
            Task {
                let ok = await StreamCameraEngine.shared.resetPreviewForOrientation(
                    width: width,
                    height: height,
                    fps: fps,
                    bitrate: bitrate,
                    rotation: rotation
                )
                result(ok)
            }
        case "setKeepScreenOnDuringStream":
            let enabled = (call.arguments as? [String: Any])?["enabled"] as? Bool ?? false
            StreamCameraEngine.shared.setKeepScreenOnDuringStream(enabled: enabled)
            result(nil)
        case "setVideoStabilization":
            let enabled = (call.arguments as? [String: Any])?["enabled"] as? Bool ?? true
            StreamCameraEngine.shared.setVideoStabilization(enabled: enabled)
            result(nil)
        case "getDeviceCapabilities":
            result([
                "tier": "high",
                "lowRam": false,
                "overlayRefreshMs": 500,
                "maxOverlayCaptureWidth": 1280,
                "suggestedQuality": "high",
                "defaultEis": true,
                "powerSave": ProcessInfo.processInfo.isLowPowerModeEnabled,
            ])
        case "getZoomRange":
            let range = StreamCameraEngine.shared.zoomRange()
            result(["min": range.min, "max": range.max, "current": range.current])
        case "setZoom":
            let args = call.arguments as? [String: Any]
            let level = Float(args?["level"] as? Double ?? 1.0)
            StreamCameraEngine.shared.setZoom(level: level)
            result(nil)
        case "updateOverlay":
            let args = call.arguments as? [String: Any]
            let url = args?["overlayUrl"] as? String ?? ""
            StreamCameraEngine.shared.updateOverlay(
                url: url,
                layout: overlayLayout(from: args)
            )
            result(nil)
        case "startStream":
            guard let args = call.arguments as? [String: Any],
                  let url = args["rtmpUrl"] as? String,
                  let key = args["streamKey"] as? String,
                  !url.isEmpty, !key.isEmpty else {
                result(FlutterError(code: "args", message: "rtmpUrl and streamKey required", details: nil))
                return
            }
            if !StreamCameraEngine.shared.isViewAttached {
                result(FlutterError(code: "camera", message: "Camera preview not ready yet", details: nil))
                return
            }
            let overlayUrl = args["overlayUrl"] as? String ?? ""
            let width = args["width"] as? Int ?? 1280
            let height = args["height"] as? Int ?? 720
            let bitrate = args["bitrateBps"] as? Int ?? 2_500_000
            let fps = args["fps"] as? Int ?? 30
            let layout = overlayLayout(from: args)
            Task {
                await StreamCameraEngine.shared.startStream(
                    rtmpUrl: url,
                    streamKey: key,
                    overlayUrl: overlayUrl,
                    width: width,
                    height: height,
                    bitrate: bitrate,
                    fps: fps,
                    layout: layout
                )
                let endpoint = Self.buildEndpoint(rtmpUrl: url, streamKey: key)
                result(["endpoint": endpoint])
            }
        case "stopStream":
            Task {
                await StreamCameraEngine.shared.stopStream()
                result(nil)
            }
        case "pauseStream":
            Task {
                await StreamCameraEngine.shared.pauseStream()
                result(nil)
            }
        case "resumeStream":
            Task {
                await StreamCameraEngine.shared.resumeStream()
                result(nil)
            }
        case "isStreamPaused":
            result(StreamCameraEngine.shared.isStreamPaused)
        case "setPipWhenLive", "setPipAspectRatio", "updateStreamNotification",
             "showNativePreview", "hideNativePreview", "lockActivityOrientation":
            result(nil)
        default:
            result(FlutterMethodNotImplemented)
        }
    }

    static func buildEndpoint(rtmpUrl: String, streamKey: String) -> String {
        var server = rtmpUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        while server.hasSuffix("/") { server.removeLast() }
        let key = streamKey.trimmingCharacters(in: .whitespacesAndNewlines)
        if server.isEmpty { return "" }
        if key.isEmpty { return server }
        if server.hasSuffix("/\(key)") { return server }
        return "\(server)/\(key)"
    }

    private func overlayLayout(from args: [String: Any]?) -> StreamCameraEngine.OverlayLayout {
        let height = Float(args?["overlayHeightFraction"] as? Double ?? 0.16)
        let width = Float(args?["overlayWidthFraction"] as? Double ?? 1.0)
        let anchorX = Float(args?["overlayAnchorX"] as? Double ?? 0.5)
        let anchorY = Float(args?["overlayAnchorY"] as? Double ?? 0.85)
        let bottom = Float(args?["overlayBottomMargin"] as? Double ?? 0.0) / 400
        let inset = Float(args?["overlayHorizontalInset"] as? Double ?? 0.0) / 400
        let fontScale = Float(args?["overlayFontScale"] as? Double ?? 1.0)
        let bgColor = args?["overlayBgColor"] as? String ?? ""
        let textColor = args?["overlayTextColor"] as? String ?? ""
        return StreamCameraEngine.OverlayLayout(
            heightFraction: height,
            widthFraction: width,
            anchorX: anchorX,
            anchorY: anchorY,
            bottomMarginFraction: bottom,
            horizontalInsetFraction: inset,
            fontScale: fontScale,
            bgColor: bgColor,
            textColor: textColor
        )
    }
}
