import Foundation

enum OverlayThemeBridge {
    static func cricketOverlayTheme(_ mobileTheme: String) -> String { "navy" }

    static func urlWithTheme(baseUrl: String, mobileTheme: String) -> String {
        guard !baseUrl.isEmpty, var components = URLComponents(string: baseUrl) else { return baseUrl }
        var items = components.queryItems ?? []
        items.removeAll { $0.name == "theme" || $0.name == "boardStyle" }
        items.append(URLQueryItem(name: "theme", value: "navy"))
        items.append(URLQueryItem(name: "boardStyle", value: "barlow"))
        components.queryItems = items
        return components.url?.absoluteString ?? baseUrl
    }

    static func applyThemeScript(mobileTheme: String) -> String {
        """
        (function(){
          document.body.classList.add('board-barlow');
          if(typeof applyBoardStyle==='function'){ applyBoardStyle(); }
        })();
        """
    }
}
