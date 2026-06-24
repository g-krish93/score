import XCTest
@testable import CricRelayLive

final class BroadcastStatusTests: XCTestCase {

    func testStreamingStatus() {
        let status = BroadcastStatus(status: "streaming", platform: "youtube", watchUrl: "https://youtu.be/abc")
        XCTAssertTrue(status.isStreaming)
        XCTAssertFalse(status.isPaused)
    }

    func testPausedStatus() {
        let status = BroadcastStatus(status: "paused", platform: "twitch", watchUrl: nil)
        XCTAssertFalse(status.isStreaming)
        XCTAssertTrue(status.isPaused)
    }

    func testIdleStatus() {
        let status = BroadcastStatus(status: "idle", platform: nil, watchUrl: nil)
        XCTAssertFalse(status.isStreaming)
        XCTAssertFalse(status.isPaused)
    }

    func testUnknownStatusIsNotStreamingOrPaused() {
        let status = BroadcastStatus(status: "unknown", platform: nil, watchUrl: nil)
        XCTAssertFalse(status.isStreaming)
        XCTAssertFalse(status.isPaused)
    }

    // MARK: - Codable

    func testDecodesSnakeCaseWatchUrl() throws {
        let json = """
        {"status": "streaming", "platform": "youtube", "watch_url": "https://youtu.be/xyz"}
        """.data(using: .utf8)!

        let decoded = try JSONDecoder().decode(BroadcastStatus.self, from: json)
        XCTAssertEqual(decoded.status, "streaming")
        XCTAssertEqual(decoded.platform, "youtube")
        XCTAssertEqual(decoded.watchUrl, "https://youtu.be/xyz")
    }

    func testDecodesNullPlatformAndWatchUrl() throws {
        let json = """
        {"status": "idle", "platform": null, "watch_url": null}
        """.data(using: .utf8)!

        let decoded = try JSONDecoder().decode(BroadcastStatus.self, from: json)
        XCTAssertEqual(decoded.status, "idle")
        XCTAssertNil(decoded.platform)
        XCTAssertNil(decoded.watchUrl)
    }
}
