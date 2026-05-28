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
        case "prepareCamera":
            let args = call.arguments as? [String: Any]
            let width = args?["width"] as? Int ?? 1280
            let height = args?["height"] as? Int ?? 720
            let fps = args?["fps"] as? Int ?? 30
            Task {
                await StreamCameraEngine.shared.preparePreview(width: width, height: height, fps: fps)
                result(true)
            }
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
            if !url.isEmpty {
                StreamCameraEngine.shared.updateOverlay(
                    url: url,
                    layout: overlayLayout(from: args)
                )
            }
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
        let height = Float(args?["overlayHeightFraction"] as? Double ?? 0.22)
        let bottom = Float(args?["overlayBottomMargin"] as? Double ?? 8.0) / 400
        let inset = Float(args?["overlayHorizontalInset"] as? Double ?? 8.0) / 400
        return StreamCameraEngine.OverlayLayout(
            heightFraction: height,
            bottomMarginFraction: bottom,
            horizontalInsetFraction: inset
        )
    }
}
