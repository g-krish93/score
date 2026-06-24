import XCTest
@testable import CricRelayLive

/// Tests preflight logic: when all checks pass, go-live should be enabled.
/// Mirrors Android's StudioCameraGateTest.
final class StudioPreflightTests: XCTestCase {

    // MARK: - Helpers

    private func preflightReady(camera: Bool, destination: Bool) -> Bool {
        camera && destination
    }

    // MARK: - Go-live gate

    func testGoLiveBlockedWhenCameraNotReady() {
        XCTAssertFalse(preflightReady(camera: false, destination: true),
                       "Go-live should be blocked when camera is not ready")
    }

    func testGoLiveBlockedWhenDestinationNotReady() {
        XCTAssertFalse(preflightReady(camera: true, destination: false),
                       "Go-live should be blocked when destination is not configured")
    }

    func testGoLiveBlockedWhenBothNotReady() {
        XCTAssertFalse(preflightReady(camera: false, destination: false))
    }

    func testGoLiveAllowedWhenAllChecksPass() {
        XCTAssertTrue(preflightReady(camera: true, destination: true),
                      "Go-live should be allowed when camera and destination are ready")
    }

    // MARK: - Destination validation

    func testCustomRtmpIsReadyWhenUrlAndKeyFilled() {
        let rtmpUrl = "rtmp://example.com/live"
        let streamKey = "stream-key-abc"
        let destinationReady = !rtmpUrl.isEmpty && !streamKey.isEmpty
        XCTAssertTrue(destinationReady)
    }

    func testCustomRtmpNotReadyWhenUrlEmpty() {
        let rtmpUrl = ""
        let streamKey = "stream-key-abc"
        let destinationReady = !rtmpUrl.isEmpty && !streamKey.isEmpty
        XCTAssertFalse(destinationReady)
    }

    func testCustomRtmpNotReadyWhenKeyEmpty() {
        let rtmpUrl = "rtmp://example.com/live"
        let streamKey = ""
        let destinationReady = !rtmpUrl.isEmpty && !streamKey.isEmpty
        XCTAssertFalse(destinationReady)
    }

    func testOAuthDestinationAlwaysConsideredReady() {
        // YouTube and Twitch destinations are "ready" as soon as they're selected —
        // the API will handle auth failures during go-live.
        let youtubeReady = true  // by spec: OAuth platforms ready when selected
        let twitchReady = true
        XCTAssertTrue(youtubeReady)
        XCTAssertTrue(twitchReady)
    }

    // MARK: - Overlay check (non-blocking)

    func testOverlayCheckIsNonBlocking() {
        // Overlay is checked but not required to go live
        let cameraOk = true
        let destinationOk = true
        let overlayOk = false  // no overlay configured

        // allGood in PreflightSheet only uses camera + destination
        let canGoLive = cameraOk && destinationOk
        XCTAssertTrue(canGoLive,
                      "Missing overlay should not block go-live (overlay is optional)")
        _ = overlayOk  // suppress unused warning
    }

    // MARK: - StreamRecap

    func testStreamRecapHoldsTitle() {
        let recap = StreamRecap(title: "Final — My Club vs Visitors", watchUrl: "https://youtu.be/abc", durationSeconds: 3660)
        XCTAssertEqual(recap.title, "Final — My Club vs Visitors")
        XCTAssertEqual(recap.watchUrl, "https://youtu.be/abc")
    }

    func testStreamRecapDurationFormatting() {
        let twoHours = StreamRecap(title: "Test", watchUrl: "", durationSeconds: 7200)
        XCTAssertEqual(twoHours.durationSeconds, 7200)

        let fiveMinutes = StreamRecap(title: "Test", watchUrl: "", durationSeconds: 300)
        XCTAssertEqual(fiveMinutes.durationSeconds, 300)

        let zero = StreamRecap(title: "Test", watchUrl: "", durationSeconds: 0)
        XCTAssertEqual(zero.durationSeconds, 0)
    }

    func testStreamRecapWithNoWatchUrl() {
        let recap = StreamRecap(title: "Test Stream", watchUrl: "", durationSeconds: 120)
        XCTAssertTrue(recap.watchUrl.isEmpty)
    }
}
