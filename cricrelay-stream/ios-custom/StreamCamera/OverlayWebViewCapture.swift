import UIKit
import WebKit

/// Off-screen WKWebView rasterizes the scoreboard for the stream overlay (not screen capture).
final class OverlayWebViewCapture: NSObject {
    private let webView: WKWebView
    private weak var hostViewController: UIViewController?
    private var attached = false
    private var fontScale: Float = 1.0
    private var bgColor: String = ""
    private var textColor: String = ""

    func setStyle(fontScale: Float, bgColor: String, textColor: String) {
        self.fontScale = min(max(fontScale, 0.6), 2.0)
        self.bgColor = bgColor.trimmingCharacters(in: .whitespaces)
        self.textColor = textColor.trimmingCharacters(in: .whitespaces)
        DispatchQueue.main.async { [weak self] in
            self?.webView.evaluateJavaScript(self?.styleInjectScript() ?? "", completionHandler: nil)
        }
    }

    private func injectedCss() -> String {
        let scale = min(max(fontScale, 0.6), 2.0)
        let rootPx = String(format: "%.2f", 16.0 * scale)
        let bg = bgColor.replacingOccurrences(of: "'", with: "").replacingOccurrences(of: "\"", with: "")
        let fg = textColor.replacingOccurrences(of: "'", with: "").replacingOccurrences(of: "\"", with: "")
        var css = "html,body{margin:0 !important;padding:0 !important;background:transparent !important;overflow:hidden !important;}"
        css += "html{font-size:\(rootPx)px !important;}"
        css += "#overlay{position:fixed !important;top:0 !important;bottom:auto !important;left:0 !important;right:0 !important;transform:none !important;width:auto !important;margin:0 !important;transform-origin:top left !important;}"
        if !bg.isEmpty || !fg.isEmpty {
            css += ":root{"
            if !bg.isEmpty { css += "--bg:\(bg) !important;--bg2:\(bg) !important;" }
            if !fg.isEmpty { css += "--text:\(fg) !important;" }
            css += "}"
        }
        return css
    }

    private func styleInjectScript() -> String {
        let cssLiteral = injectedCss()
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "'", with: "\\'")
        return """
        (function(){
          try{
            var vp=document.querySelector('meta[name=viewport]');
            if(!vp){vp=document.createElement('meta');vp.setAttribute('name','viewport');
              (document.head||document.documentElement).appendChild(vp);}
            if(vp.getAttribute('content')!=='width=1280'){vp.setAttribute('content','width=1280');}
            var s=document.getElementById('cr-style');
            if(!s){s=document.createElement('style');s.id='cr-style';
              (document.head||document.documentElement).appendChild(s);}
            var css='\(cssLiteral)';
            if(s.textContent!==css){s.textContent=css;}
          }catch(e){}
        })();
        """
    }

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
            self.webView.evaluateJavaScript(self.styleInjectScript(), completionHandler: nil)
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
