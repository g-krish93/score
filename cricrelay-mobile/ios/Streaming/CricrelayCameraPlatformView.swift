import Flutter
import HaishinKit
import UIKit

@available(iOS 15.0, *)
final class CricrelayCameraPlatformView: NSObject, FlutterPlatformView {
    private let hkView: MTHKView

    init(frame: CGRect) {
        hkView = MTHKView(frame: frame)
        super.init()
        StreamCameraEngine.shared.attachView(hkView)
    }

    func view() -> UIView {
        hkView
    }

    deinit {
        StreamCameraEngine.shared.detachView(hkView)
    }
}

@available(iOS 15.0, *)
final class CricrelayCameraViewFactory: NSObject, FlutterPlatformViewFactory {
    func create(
        withFrame frame: CGRect,
        viewIdentifier viewId: Int64,
        arguments args: Any?
    ) -> FlutterPlatformView {
        CricrelayCameraPlatformView(frame: frame)
    }

    func createArgsCodec() -> FlutterMessageCodec & NSObjectProtocol {
        FlutterStandardMessageCodec.sharedInstance()
    }
}
