import XCTest
@testable import CricRelayLive

final class KeychainHelperTests: XCTestCase {

    override func setUp() {
        super.setUp()
        KeychainHelper.deleteToken()
    }

    override func tearDown() {
        KeychainHelper.deleteToken()
        super.tearDown()
    }

    func testSaveAndReadToken() {
        KeychainHelper.saveToken("my-test-token")
        let read = KeychainHelper.readToken()
        XCTAssertEqual(read, "my-test-token")
    }

    func testReadReturnsNilWhenNotSet() {
        let read = KeychainHelper.readToken()
        XCTAssertNil(read, "readToken should return nil when no token has been saved")
    }

    func testDeleteRemovesToken() {
        KeychainHelper.saveToken("token-to-delete")
        KeychainHelper.deleteToken()
        let read = KeychainHelper.readToken()
        XCTAssertNil(read, "Token should be nil after deletion")
    }

    func testOverwriteUpdatesToken() {
        KeychainHelper.saveToken("first-token")
        KeychainHelper.saveToken("second-token")
        let read = KeychainHelper.readToken()
        XCTAssertEqual(read, "second-token", "Saving again should overwrite the previous value")
    }

    func testDeleteWhenNothingStoredDoesNotCrash() {
        // Delete when keychain is empty — should not throw or crash
        KeychainHelper.deleteToken()
        KeychainHelper.deleteToken()
    }

    func testMigrationFromUserDefaultsClearsLegacyKey() {
        // Simulate legacy token in UserDefaults
        let legacyKey = "stream_api_token_secure"
        UserDefaults.standard.set("legacy-token", forKey: legacyKey)

        // Reading token should migrate it
        let read = KeychainHelper.readToken()

        XCTAssertEqual(read, "legacy-token", "Should migrate token from UserDefaults")
        XCTAssertNil(UserDefaults.standard.string(forKey: legacyKey),
                     "Legacy UserDefaults entry should be cleared after migration")
    }
}
