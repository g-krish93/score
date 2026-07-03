import Foundation

/// Maps mobile board prefs onto the served overlay page (cricket_overlay.html).
/// New pages carry the Floodlight board (`#fl-root` + `applyBoardStyle(style, opts)`);
/// old server HTML only knows the Barlow board, so every injection is capability-guarded.
enum OverlayThemeBridge {
    /// Legacy accent palette param kept on the URL for very old overlay pages that still
    /// read `?theme=` for the #overlay accent. Harmless on newer pages.
    static func cricketOverlayTheme(_ mobileTheme: String) -> String { "navy" }

    /// Emit `boardStyle=<sanitized preset id>` + `island=<0|1>` (plus the legacy
    /// `theme=navy` accent param). Unknown ids sanitize to the Floodlight default here;
    /// an old server page simply ignores the params and renders its Barlow board.
    static func urlWithTheme(baseUrl: String, mobileTheme: String, islandEnabled: Bool = true) -> String {
        guard !baseUrl.isEmpty, var components = URLComponents(string: baseUrl) else { return baseUrl }
        let style = OverlayLayoutPrefs.sanitizeTheme(mobileTheme)
        var items = components.queryItems ?? []
        items.removeAll { $0.name == "theme" || $0.name == "boardStyle" || $0.name == "island" }
        items.append(URLQueryItem(name: "theme", value: cricketOverlayTheme(mobileTheme)))
        items.append(URLQueryItem(name: "boardStyle", value: style))
        // Barlow URLs stay byte-identical to pre-Floodlight builds; the island only
        // exists on Floodlight-family boards (matches the Android bridge).
        if style != "barlow" {
            items.append(URLQueryItem(name: "island", value: islandEnabled ? "1" : "0"))
        }
        components.queryItems = items
        return components.url?.absoluteString ?? baseUrl
    }

    /// JS applied on every measure/style pass. Capability-guarded for old server HTML:
    /// only a page with the Floodlight DOM (`#fl-root`) gets the new
    /// `applyBoardStyle(style, {island, compact})` call — anything older falls back to
    /// today's board-barlow injection, so a new app against an old server keeps the
    /// legacy board instead of a blank overlay.
    static func applyThemeScript(mobileTheme: String, islandEnabled: Bool, compact: Bool) -> String {
        let style = OverlayLayoutPrefs.sanitizeTheme(mobileTheme)
        let island = islandEnabled ? "true" : "false"
        let compactFlag = compact ? "true" : "false"
        return """
        (function(){
          if(document.getElementById('fl-root') && typeof applyBoardStyle==='function'){
            applyBoardStyle('\(style)',{island:\(island),compact:\(compactFlag)});
          }else{
            document.body.classList.add('board-barlow');
            if(typeof applyBoardStyle==='function'){ applyBoardStyle(); }
          }
        })();
        """
    }
}
