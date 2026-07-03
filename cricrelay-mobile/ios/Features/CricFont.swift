import SwiftUI

// MARK: - Brand typography — "Floodlight" type ramp
//
// Archivo ExtraBold: wordmark, ON AIR, timers, GO LIVE (display/broadcast text).
// DM Sans: all other UI. Fonts are bundled in CricRelay/Fonts/ and registered
// via UIAppFonts in project.yml; the names below are the fonts' PostScript
// names (same convention SplashView has shipped with).

enum CricFont {
    /// Display face for broadcast chrome (wordmark, ON AIR, timers, GO LIVE).
    static func archivo(_ size: CGFloat) -> Font {
        Font.custom("Archivo-ExtraBold", size: size)
    }

    /// Body/UI face. Bundled static weights: Regular (400), Medium (500),
    /// Bold (700) — other requested weights map to the nearest of the three.
    static func dmSans(_ size: CGFloat, weight: Font.Weight = .medium) -> Font {
        let name: String
        switch weight {
        case .ultraLight, .thin, .light, .regular:
            name = "DMSans-Regular"
        case .semibold, .bold, .heavy, .black:
            name = "DMSans-Bold"
        default:
            name = "DMSans-Medium"
        }
        return Font.custom(name, size: size)
    }
}
