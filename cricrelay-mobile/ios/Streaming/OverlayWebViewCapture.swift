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
        let w = max(160, min(width, 1920))
        let h = max(48, min(height, 600))
        webView.frame = CGRect(x: -10_000, y: -10_000, width: w, height: h)
        webView.setNeedsLayout()
        webView.layoutIfNeeded()

        let renderer = UIGraphicsImageRenderer(size: CGSize(width: w, height: h))
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
