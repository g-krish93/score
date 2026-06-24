import XCTest
@testable import CricRelayLive

final class ScoringConfigTests: XCTestCase {

    // MARK: - scorerUrl fallback

    func testScorerUrlUsesManualScorerUrlWhenPresent() {
        let config = ScoringConfig(
            mode: "manual",
            manualInputUrl: "https://cricrelay.co.uk/input/abc",
            manualScorerUrl: "https://cricrelay.co.uk/score/abc",
            pcsIngestUrl: "",
            pcsIngestToken: "",
            pcsRelayApkUrl: ""
        )
        XCTAssertEqual(config.scorerUrl, "https://cricrelay.co.uk/score/abc")
    }

    func testScorerUrlFallsBackToInputUrlWithSlashScore() {
        let config = ScoringConfig(
            mode: "manual",
            manualInputUrl: "https://cricrelay.co.uk/input/abc",
            manualScorerUrl: "",
            pcsIngestUrl: "",
            pcsIngestToken: "",
            pcsRelayApkUrl: ""
        )
        XCTAssertEqual(config.scorerUrl, "https://cricrelay.co.uk/score/abc",
                       "Should replace /input with /score when manualScorerUrl is empty")
    }

    func testScorerUrlEmptyWhenBothInputsEmpty() {
        let config = ScoringConfig(
            mode: "auto",
            manualInputUrl: "",
            manualScorerUrl: "",
            pcsIngestUrl: "",
            pcsIngestToken: "",
            pcsRelayApkUrl: ""
        )
        XCTAssertEqual(config.scorerUrl, "")
    }

    // MARK: - Codable

    func testDecodesScoringConfig() throws {
        let json = """
        {
            "mode": "ble",
            "manual_input_url": "https://cricrelay.co.uk/input/abc",
            "manual_scorer_url": "https://cricrelay.co.uk/score/abc",
            "pcs_ingest_url": "https://cricrelay.co.uk/ingest/abc",
            "pcs_ingest_token": "tok123",
            "pcs_relay_apk_url": "https://cricrelay.co.uk/pcs.apk"
        }
        """.data(using: .utf8)!

        let config = try JSONDecoder().decode(ScoringConfig.self, from: json)
        XCTAssertEqual(config.mode, "ble")
        XCTAssertEqual(config.manualInputUrl, "https://cricrelay.co.uk/input/abc")
        XCTAssertEqual(config.pcsIngestToken, "tok123")
    }
}
