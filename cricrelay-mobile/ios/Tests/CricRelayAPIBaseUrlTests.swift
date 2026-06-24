import XCTest
@testable import CricRelayLive

/// Tests URL normalisation and API base URL validation.
/// Mirrors the KMP UrlValidatorTest (commonTest).
final class CricRelayAPIBaseUrlTests: XCTestCase {

    // MARK: - Trailing slash normalisation

    func testTrimsTrailingSlash() {
        let api = CricRelayAPI.shared
        // The configure() method trims trailing slashes
        api.configure(baseUrl: "https://cricrelay.co.uk/", token: "tok")
        XCTAssertEqual(api.baseUrl, "https://cricrelay.co.uk")
    }

    func testTrimsMultipleTrailingSlashes() {
        let api = CricRelayAPI.shared
        api.configure(baseUrl: "https://cricrelay.co.uk///", token: "tok")
        XCTAssertEqual(api.baseUrl, "https://cricrelay.co.uk")
    }

    func testNoChangeWhenNoTrailingSlash() {
        let api = CricRelayAPI.shared
        api.configure(baseUrl: "https://cricrelay.co.uk", token: "tok")
        XCTAssertEqual(api.baseUrl, "https://cricrelay.co.uk")
    }

    func testTrimsLocalHostTrailingSlash() {
        let api = CricRelayAPI.shared
        api.configure(baseUrl: "http://localhost:5000/", token: "tok")
        XCTAssertEqual(api.baseUrl, "http://localhost:5000")
    }

    // MARK: - Token storage

    func testConfigureStoresToken() {
        let api = CricRelayAPI.shared
        api.configure(baseUrl: "https://cricrelay.co.uk", token: "my-secret-token")
        XCTAssertEqual(api.token, "my-secret-token")
    }

    func testConfigureUpdatesBaseUrl() {
        let api = CricRelayAPI.shared
        api.configure(baseUrl: "https://cricrelay.co.uk", token: "tok1")
        api.configure(baseUrl: "https://staging.cricrelay.co.uk", token: "tok2")
        XCTAssertEqual(api.baseUrl, "https://staging.cricrelay.co.uk")
        XCTAssertEqual(api.token, "tok2")
    }

    // MARK: - URL construction sanity checks (non-network)

    func testBaseUrlWithLocalhostPort() {
        let api = CricRelayAPI.shared
        api.configure(baseUrl: "http://192.168.1.10:5000", token: "tok")
        XCTAssertEqual(api.baseUrl, "http://192.168.1.10:5000")
    }
}
