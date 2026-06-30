import UIKit
import WebKit

/// Off-screen WKWebView rasterizes the scoreboard for the stream overlay (not screen capture).
/// Parity with Android OverlayWebViewCapture: design layout at 1280px, capture width tracks the
/// encoded RTMP frame (720 portrait, 640 low-tier, 1280 landscape, etc.).
final class OverlayWebViewCapture: NSObject, WKNavigationDelegate {
    static let designWidth = 1280
    private static let minCaptureWidth = 320
    private static let minCaptureHeight = 40
    private static let maxCaptureHeight = 640
    private static let measureInterval: TimeInterval = 2.0
    private static let bottomPadCss = 10

    private let webView: WKWebView
    private weak var hostViewController: UIViewController?
    private var attached = false
    private var pageLoaded = false
    private var captureWidthPx = designWidth
    private var captureHeightPx = 0
    private var measureTimer: Timer?
    private var fontScale: Float = 1.0
    private var bgColor: String = ""
    private var textColor: String = ""

    init(hostViewController: UIViewController) {
        self.hostViewController = hostViewController
        let config = WKWebViewConfiguration()
        webView = WKWebView(frame: .zero, configuration: config)
        super.init()
        webView.navigationDelegate = self
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
    }

    func setStyle(fontScale: Float, bgColor: String, textColor: String) {
        self.fontScale = min(max(fontScale, 0.6), 2.0)
        self.bgColor = bgColor.trimmingCharacters(in: .whitespaces)
        self.textColor = textColor.trimmingCharacters(in: .whitespaces)
        DispatchQueue.main.async { [weak self] in
            self?.webView.evaluateJavaScript(self?.measureScript() ?? "", completionHandler: nil)
        }
    }

    func setCaptureWidth(_ px: Int) {
        let w = min(max(px, Self.minCaptureWidth), Self.designWidth)
        guard w != captureWidthPx else { return }
        captureWidthPx = w
        captureHeightPx = 0
        DispatchQueue.main.async { [weak self] in
            self?.webView.evaluateJavaScript(self?.measureScript() ?? "", completionHandler: nil)
        }
    }

    func loadUrl(_ url: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.ensureAttached()
            if self.webView.url?.absoluteString != url, let u = URL(string: url) {
                self.pageLoaded = false
                self.captureHeightPx = 0
                self.webView.load(URLRequest(url: u))
            }
        }
    }

    func capture(width: Int, height: Int) -> UIImage? {
        if Thread.isMainThread {
            return captureOnMain()
        }
        var result: UIImage?
        let group = DispatchGroup()
        group.enter()
        DispatchQueue.main.async { [weak self] in
            result = self?.captureOnMain()
            group.leave()
        }
        _ = group.wait(timeout: .now() + 2)
        return result
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        pageLoaded = true
        captureHeightPx = 0
        runMeasure()
        startMeasureLoop()
    }

    private func ensureAttached() {
        guard !attached, let host = hostViewController else { return }
        webView.frame = CGRect(
            x: -10_000,
            y: -10_000,
            width: captureWidthPx,
            height: Self.maxCaptureHeight
        )
        host.view.addSubview(webView)
        attached = true
    }

    private func startMeasureLoop() {
        measureTimer?.invalidate()
        measureTimer = Timer.scheduledTimer(withTimeInterval: Self.measureInterval, repeats: true) { [weak self] _ in
            self?.runMeasure()
        }
    }

    private func runMeasure() {
        webView.evaluateJavaScript(measureScript()) { [weak self] result, _ in
            guard let self, let cssHeight = Self.parseMeasureHeight(from: result) else { return }
            let phys = Int((Double(cssHeight) * Double(self.captureWidthPx) / Double(Self.designWidth)).rounded())
            let clamped = min(max(phys, Self.minCaptureHeight), Self.maxCaptureHeight)
            if self.captureHeightPx == 0 || abs(clamped - self.captureHeightPx) > 8 {
                self.captureHeightPx = clamped
            }
        }
    }

    /// Returns the raw CSS height from the measure script (design-space px), before width scaling.
    private static func parseMeasureHeight(from result: Any?) -> Int? {
        let raw: String?
        if let s = result as? String {
            raw = s
        } else if let dict = result as? [String: Any],
                  let ready = dict["ready"] as? Bool, ready,
                  let h = dict["h"] as? Int, h > 0 {
            return h
        } else {
            return nil
        }
        guard let json = raw?.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: json) as? [String: Any],
              obj["ready"] as? Bool == true,
              let cssHeight = obj["h"] as? Int,
              cssHeight > 0 else { return nil }
        return cssHeight
    }

    private func captureOnMain() -> UIImage? {
        guard attached, pageLoaded, captureHeightPx > 0 else { return nil }
        let w = captureWidthPx
        let h = captureHeightPx
        webView.frame = CGRect(x: -10_000, y: -10_000, width: w, height: h)
        webView.setNeedsLayout()
        webView.layoutIfNeeded()

        let format = UIGraphicsImageRendererFormat()
        format.scale = 1.0
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: w, height: h), format: format)
        return renderer.image { _ in
            webView.drawHierarchy(in: webView.bounds, afterScreenUpdates: false)
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

    private func measureScript() -> String {
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
            var o=document.getElementById('overlay');
            if(!o){return JSON.stringify({ready:false,why:'no-overlay'});}
            var label=(o.textContent||'').replace(/\\s+/g,' ').trim();
            if(label===''||/^Loading\\.?\\.?\\.?$/i.test(label)){
              return JSON.stringify({ready:false,why:'loading'});}
            var r=o.getBoundingClientRect();
            if(r.height<8){return JSON.stringify({ready:false,why:'zero-rect'});}
            return JSON.stringify({ready:true,h:Math.ceil(r.bottom)+\(Self.bottomPadCss)});
          }catch(e){return JSON.stringify({ready:false,why:String(e)});}
        })();
        """
    }

    deinit {
        measureTimer?.invalidate()
        DispatchQueue.main.async { [webView] in
            webView.removeFromSuperview()
        }
    }
}
