import XCTest
@testable import CricRelayLive

final class StreamMatchCodableTests: XCTestCase {

    private let sampleJSON = """
    {
        "slug": "my-club-2024",
        "label": "My Club vs Visitors",
        "overlay_embed_url": "https://cricrelay.co.uk/overlay/my-club-2024",
        "relay_source": "play_cricket",
        "relay_paused": false,
        "scoring_mode": "auto",
        "scoring_active": true,
        "scoring_stale": false,
        "is_live": true,
        "broadcast": {
            "status": "streaming",
            "platform": "youtube",
            "watch_url": "https://youtu.be/abc123"
        }
    }
    """.data(using: .utf8)!

    func testDecodesAllFields() throws {
        let match = try JSONDecoder().decode(StreamMatch.self, from: sampleJSON)

        XCTAssertEqual(match.slug, "my-club-2024")
        XCTAssertEqual(match.label, "My Club vs Visitors")
        XCTAssertEqual(match.overlayEmbedUrl, "https://cricrelay.co.uk/overlay/my-club-2024")
        XCTAssertEqual(match.relaySource, "play_cricket")
        XCTAssertFalse(match.relayPaused)
        XCTAssertEqual(match.scoringMode, "auto")
        XCTAssertTrue(match.scoringActive)
        XCTAssertFalse(match.scoringStale)
        XCTAssertTrue(match.isLive)
    }

    func testDecodesNestedBroadcastStatus() throws {
        let match = try JSONDecoder().decode(StreamMatch.self, from: sampleJSON)

        XCTAssertTrue(match.broadcast.isStreaming)
        XCTAssertEqual(match.broadcast.platform, "youtube")
        XCTAssertEqual(match.broadcast.watchUrl, "https://youtu.be/abc123")
    }

    func testIdPropertyEqualsSlug() throws {
        let match = try JSONDecoder().decode(StreamMatch.self, from: sampleJSON)
        XCTAssertEqual(match.id, match.slug)
    }

    func testDecodesPcsBleStream() throws {
        let json = """
        {
            "slug": "ble-stream",
            "label": "BLE Scoreboard",
            "overlay_embed_url": "",
            "relay_source": "pcs",
            "relay_paused": false,
            "scoring_mode": "ble",
            "scoring_active": false,
            "scoring_stale": false,
            "is_live": false,
            "broadcast": {"status": "idle", "platform": null, "watch_url": null}
        }
        """.data(using: .utf8)!

        let match = try JSONDecoder().decode(StreamMatch.self, from: json)
        XCTAssertEqual(match.relaySource, "pcs")
        XCTAssertEqual(match.scoringMode, "ble")
        XCTAssertFalse(match.isLive)
        XCTAssertFalse(match.broadcast.isStreaming)
    }

    func testDecodesFixtureItem() throws {
        let json = """
        {"match_id": "pc_12345", "title": "My Club vs Opponents — 3 May 2025"}
        """.data(using: .utf8)!

        let fixture = try JSONDecoder().decode(FixtureItem.self, from: json)
        XCTAssertEqual(fixture.matchId, "pc_12345")
        XCTAssertEqual(fixture.title, "My Club vs Opponents — 3 May 2025")
        XCTAssertEqual(fixture.id, fixture.matchId)
    }

    func testDecodesFixturesResponse() throws {
        let json = """
        {
            "fixtures": [{"match_id": "pc_1", "title": "A vs B"}],
            "active_match_ids": ["pc_1"],
            "slots_used": 1,
            "slots_total": 3
        }
        """.data(using: .utf8)!

        let response = try JSONDecoder().decode(FixturesResponse.self, from: json)
        XCTAssertEqual(response.fixtures.count, 1)
        XCTAssertEqual(response.activeMatchIds, ["pc_1"])
        XCTAssertEqual(response.slotsUsed, 1)
        XCTAssertEqual(response.slotsTotal, 3)
        XCTAssertNil(response.error)
    }

    func testDecodesGoLiveResult() throws {
        let json = """
        {
            "rtmp_url": "rtmp://a.rtmp.youtube.com/live2",
            "stream_key": "xxxx-xxxx-xxxx",
            "watch_url": "https://youtu.be/abc",
            "overlay_embed_url": "https://cricrelay.co.uk/overlay/slug"
        }
        """.data(using: .utf8)!

        let result = try JSONDecoder().decode(GoLiveResult.self, from: json)
        XCTAssertEqual(result.rtmpUrl, "rtmp://a.rtmp.youtube.com/live2")
        XCTAssertEqual(result.streamKey, "xxxx-xxxx-xxxx")
        XCTAssertEqual(result.watchUrl, "https://youtu.be/abc")
        XCTAssertEqual(result.overlayEmbedUrl, "https://cricrelay.co.uk/overlay/slug")
    }
}
