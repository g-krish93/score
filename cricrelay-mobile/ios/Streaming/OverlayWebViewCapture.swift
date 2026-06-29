import UIKit
import WebKit

/// Off-screen WKWebView rasterizes the scoreboard for the stream overlay (not screen capture).
final class OverlayWebViewCapture: NSObject {
    private let webView: WKWebView
    private weak var hostViewController: UIViewController?
    private var attached = false

    init(hostViewController: UIViewController) {
        self.hostViewController = hostViewController
        let config = WKWebViewConfiguration()
        webView = WKWebView(frame: .zero, configuration: config)
        super.init()
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
    }

    func loadUrl(_ url: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.ensureAttached()
            if self.webView.url?.absoluteString != url, let u = URL(string: url) {
                self.webView.load(URLRequest(url: u))
            }
        }
    }

    func capture(width: Int, height: Int) -> UIImage? {
        if Thread.isMainThread {
            return captureOnMain(width: width, height: height)
        }
        var result: UIImage?
        let group = DispatchGroup()
        group.enter()
        DispatchQueue.main.async { [weak self] in
            result = self?.captureOnMain(width: width, height: height)
            group.leave()
        }
        _ = group.wait(timeout: .now() + 2)
        return result
    }

    private func ensureAttached() {
        guard !attached, let host = hostViewController else { return }
        webView.frame = CGRect(x: -10_000, y: -10_000, width: 1280, height: 360)
        host.view.addSubview(webView)
        attached = true
    }

    private func captureOnMain(width: Int, height: Int) -> UIImage? {
        guard attached else { return nil }
        // Always render at the full design width (1280px) regardless of widthFraction so the
        // CSS viewport never changes after initial load — this avoids a JS resize-event race
        // that would leave the first captured frame at the wrong scale.
        // HaishinKit will centre the image on the stream canvas, giving natural side margins.
        let designW = max(width, 1280)
        let h = max(48, min(height, 600))
        webView.frame = CGRect(x: -10_000, y: -10_000, width: designW, height: h)
        webView.setNeedsLayout()
        webView.layoutIfNeeded()

        // Render at scale 1.0 so the cgImage pixel dimensions equal the stream canvas coordinates.
        // Without this, the device screen scale (2× / 3× on modern iPhones) makes the captured
        // cgImage 2–3× wider than the 1280×720 stream canvas, causing the overlay to be cropped.
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1.0
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: designW, height: h), format: format)
        return renderer.image { _ in
            webView.drawHierarchy(in: webView.bounds, afterScreenUpdates: false)
        }
    }

    deinit {
        DispatchQueue.main.async { [webView] in
            webView.removeFromSuperview()
        }
    }
}
