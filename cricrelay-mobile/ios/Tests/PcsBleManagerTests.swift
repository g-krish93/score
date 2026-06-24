import XCTest
@testable import CricRelayLive

@MainActor
final class PcsBleManagerTests: XCTestCase {

    // MARK: - Initial state (pure published properties, no BLE hardware)

    func testInitialStateNotAdvertising() {
        let manager = PcsBleManager()
        XCTAssertFalse(manager.advertising, "BLE manager should not be advertising on init")
    }

    func testInitialPacketCountsAreZero() {
        let manager = PcsBleManager()
        XCTAssertEqual(manager.packetCount, 0)
        XCTAssertEqual(manager.postedOk, 0)
        XCTAssertEqual(manager.postFail, 0)
    }

    func testInitialRecentPacketsAreEmpty() {
        let manager = PcsBleManager()
        XCTAssertTrue(manager.recentPackets.isEmpty)
    }

    func testInitialStatusMessageIsIdle() {
        let manager = PcsBleManager()
        XCTAssertEqual(manager.statusMessage, "Idle")
    }

    // MARK: - stop() transitions to known state

    func testStopSetsAdvertisingFalse() {
        let manager = PcsBleManager()
        // stop() should be safe to call even when not started
        manager.stop()
        XCTAssertFalse(manager.advertising)
    }

    func testStopUpdatesStatusMessage() {
        let manager = PcsBleManager()
        manager.stop()
        XCTAssertEqual(manager.statusMessage, "Stopped")
    }
}
