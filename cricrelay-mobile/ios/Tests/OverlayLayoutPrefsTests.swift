import XCTest
@testable import CricRelayLive

final class OverlayLayoutPrefsTests: XCTestCase {

    // MARK: - Defaults

    func testDefaultValues() {
        let prefs = OverlayLayoutPrefs()
        XCTAssertEqual(prefs.heightFraction, 0.16, accuracy: 0.001)
        XCTAssertEqual(prefs.widthFraction, 0.92, accuracy: 0.001)
        XCTAssertEqual(prefs.anchorX, 0.5, accuracy: 0.001)
        XCTAssertEqual(prefs.anchorY, 0.85, accuracy: 0.001)
        XCTAssertEqual(prefs.fontScale, 1.0, accuracy: 0.001)
        XCTAssertEqual(prefs.opacity, 1.0, accuracy: 0.001)
        XCTAssertEqual(prefs.theme, "classic")
        XCTAssertTrue(prefs.videoStabilization)
        XCTAssertTrue(prefs.keepScreenOn)
        XCTAssertTrue(prefs.watermarkEnabled)
        XCTAssertEqual(prefs.watermarkText, OverlayLayoutPrefs.watermarkDefaultText)
    }

    // MARK: - boardDisplayScale

    func testBoardDisplayScaleAtDefault() {
        let prefs = OverlayLayoutPrefs()
        XCTAssertEqual(prefs.boardDisplayScaleX(), 1.0, accuracy: 0.001,
                       "Scale should be 1.0 at default width fraction")
        XCTAssertEqual(prefs.boardDisplayScaleY(), 1.0, accuracy: 0.001,
                       "Scale should be 1.0 at default height fraction")
    }

    func testBoardDisplayScaleTracksSliders() {
        var prefs = OverlayLayoutPrefs()
        prefs.widthFraction = 0.46
        prefs.heightFraction = 0.12
        XCTAssertEqual(prefs.boardDisplayScaleX(), 0.46 / 0.92, accuracy: 0.001)
        XCTAssertEqual(prefs.boardDisplayScaleY(), 0.12 / 0.16, accuracy: 0.001)
    }

    func testBoardDisplayScaleHalfWidth() {
        var prefs = OverlayLayoutPrefs()
        prefs.widthFraction = 0.46
        XCTAssertEqual(prefs.boardDisplayScaleX(), 0.5, accuracy: 0.001)
    }

    func testBoardDisplayScaleThreeQuarterHeight() {
        var prefs = OverlayLayoutPrefs()
        prefs.heightFraction = 0.12
        XCTAssertEqual(prefs.boardDisplayScaleY(), 0.75, accuracy: 0.001)
    }

    // MARK: - effectiveFontScale

    func testEffectiveFontScaleAtDefaultBoardSize() {
        let prefs = OverlayLayoutPrefs()
        XCTAssertEqual(prefs.effectiveFontScale(), 1.0, accuracy: 0.001)
    }

    func testEffectiveFontScaleIsIndependentOfBoardSize() {
        var full = OverlayLayoutPrefs()
        full.fontScale = 1.2

        var small = OverlayLayoutPrefs()
        small.fontScale = 1.2
        small.widthFraction = 0.46
        small.heightFraction = 0.08

        XCTAssertEqual(full.effectiveFontScale(), small.effectiveFontScale(), accuracy: 0.001,
                       "Font scale should not change when board size changes")
    }

    func testEffectiveFontScaleReturnsUserValue() {
        var prefs = OverlayLayoutPrefs()
        prefs.fontScale = 1.5
        XCTAssertEqual(prefs.effectiveFontScale(), 1.5, accuracy: 0.001)
    }

    // MARK: - JSON round-trip

    func testFullRoundTrip() throws {
        var original = OverlayLayoutPrefs()
        original.theme = "neon"
        original.fontScale = 1.3
        original.widthFraction = 0.75
        original.heightFraction = 0.20
        original.opacity = 0.85
        original.watermarkEnabled = false
        original.watermarkText = "test.example.com"

        let encoder = JSONEncoder()
        encoder.keyEncodingStrategy = .convertToSnakeCase
        let data = try encoder.encode(original)

        let decoder = JSONDecoder()
        let decoded = try decoder.decode(OverlayLayoutPrefs.self, from: data)

        XCTAssertEqual(decoded.theme, "neon")
        XCTAssertEqual(decoded.fontScale, 1.3, accuracy: 0.001)
        XCTAssertEqual(decoded.widthFraction, 0.75, accuracy: 0.001)
        XCTAssertEqual(decoded.heightFraction, 0.20, accuracy: 0.001)
        XCTAssertEqual(decoded.opacity, 0.85, accuracy: 0.001)
        XCTAssertFalse(decoded.watermarkEnabled)
        XCTAssertEqual(decoded.watermarkText, "test.example.com")
    }

    func testTolerantDecoderFallsBackToDefaults() throws {
        // Minimal JSON with only a few fields — the rest should use defaults.
        let json = """
        {"theme": "compact", "opacity": 0.7}
        """.data(using: .utf8)!

        let decoded = try JSONDecoder().decode(OverlayLayoutPrefs.self, from: json)

        XCTAssertEqual(decoded.theme, "compact")
        XCTAssertEqual(decoded.opacity, 0.7, accuracy: 0.001)
        // Missing fields fall back to defaults
        XCTAssertEqual(decoded.fontScale, 1.0, accuracy: 0.001)
        XCTAssertEqual(decoded.widthFraction, 0.92, accuracy: 0.001)
        XCTAssertEqual(decoded.heightFraction, 0.16, accuracy: 0.001)
        XCTAssertTrue(decoded.videoStabilization)
        XCTAssertTrue(decoded.keepScreenOn)
    }

    func testTolerantDecoderMissingWatermarkFields() throws {
        // Older server responses without watermark fields
        let json = """
        {"theme": "classic"}
        """.data(using: .utf8)!

        let decoded = try JSONDecoder().decode(OverlayLayoutPrefs.self, from: json)

        XCTAssertTrue(decoded.watermarkEnabled,
                      "watermarkEnabled should default to true when missing from server response")
        XCTAssertEqual(decoded.watermarkText, OverlayLayoutPrefs.watermarkDefaultText)
    }

    func testTolerantDecoderEmptyWatermarkTextUsesDefault() throws {
        let json = """
        {"watermark_text": ""}
        """.data(using: .utf8)!

        let decoded = try JSONDecoder().decode(OverlayLayoutPrefs.self, from: json)

        XCTAssertEqual(decoded.watermarkText, OverlayLayoutPrefs.watermarkDefaultText,
                       "Empty watermark text should fall back to default")
    }

    // MARK: - toEngineLayout

    func testToEngineLayoutMapsFields() {
        let prefs = OverlayLayoutPrefs()
        let layout = prefs.toEngineLayout()

        XCTAssertEqual(layout.heightFraction, Float(prefs.heightFraction), accuracy: 0.001)
        XCTAssertEqual(layout.widthFraction, Float(prefs.widthFraction), accuracy: 0.001)
        XCTAssertEqual(layout.anchorX, Float(prefs.anchorX), accuracy: 0.001)
        XCTAssertEqual(layout.anchorY, Float(prefs.anchorY), accuracy: 0.001)
        XCTAssertEqual(layout.watermarkEnabled, prefs.watermarkEnabled)
        XCTAssertEqual(layout.watermarkText, prefs.watermarkText)
    }
}
