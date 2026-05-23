import os

os.environ["RELAY_AUTO_POLL"] = "0"

from server.app import app


def assert_ok(resp, code=200):
    if resp.status_code != code:
        raise AssertionError(f"Expected {code}, got {resp.status_code}: {resp.get_data(as_text=True)}")


def main():
    c = app.test_client()

    assert_ok(c.get("/health"))
    c.post("/relay/config", json={"relay_mode": "manual"})
    if (os.getenv("RELAY_WORKER_HTTP") or "").strip().lower() in {"1", "true", "yes", "on"}:
        assert_ok(c.get("/relay-worker/health"))
    score0 = c.get("/score").get_json()
    home = c.get("/")
    assert_ok(home)
    assert "CricRelay" in home.get_data(as_text=True)
    assert_ok(c.get("/forgot-password"))
    assert_ok(c.get("/pricing"))
    assert_ok(c.get("/compare"))
    assert_ok(c.get("/sitemap.xml"))
    r404 = c.get("/club/this-club-slug-does-not-exist-xyz")
    if r404.status_code != 404:
        raise AssertionError(f"Expected 404 for unknown club, got {r404.status_code}")
    assert_ok(c.get("/stream"))
    assert "scoring_locked" in score0
    assert_ok(c.get("/input"))

    setup_payload = {
        "batting_team": "Team A",
        "bowling_team": "Team B",
        "total_overs": 20,
        "batting_squad": [f"A{i}" for i in range(1, 12)],
        "bowling_squad": [f"B{i}" for i in range(1, 12)],
    }
    r = c.post("/setup", json=setup_payload)
    assert_ok(r)
    data = r.get_json()
    assert data["match_started"] is True
    assert data["runs"] == 0 and data["wickets"] == 0

    r = c.post("/set-players", json={"striker": "A1", "non_striker": "A2", "current_bowler": "B1"})
    assert_ok(r)

    for ball in ["1", "4", "Wd", "Nb", "W", "."]:
        assert_ok(c.post("/ball", json={"type": ball}))

    score = c.get("/score").get_json()
    assert score["runs"] == 7, score
    assert score["wickets"] == 1, score
    assert score["extras"] == 2, score
    assert score["overs_display"] == "0.4", score
    assert score["striker"] == "", score

    assert_ok(c.post("/undo"))
    score = c.get("/score").get_json()
    assert score["overs_display"] == "0.3", score

    # Retired hurt/unhurt and law events
    assert_ok(c.post("/set-players", json={"striker": "A1", "non_striker": "A2", "current_bowler": "B1"}))
    assert_ok(c.post("/retire-batter", json={"batter": "striker", "type": "hurt"}))
    score = c.get("/score").get_json()
    assert score["striker"] == "", score
    assert any(p["status"] == "retired hurt" for p in score["batting_squad"] if p["name"] == "A1")
    assert_ok(c.post("/set-players", json={"striker": "A1"}))
    score = c.get("/score").get_json()
    assert any(p["status"] == "batting" for p in score["batting_squad"] if p["name"] == "A1")
    assert_ok(c.post("/retire-batter", json={"batter": "non_striker", "type": "unhurt"}))
    score = c.get("/score").get_json()
    assert score["non_striker"] == "", score
    assert any(p["status"] == "retired out" for p in score["batting_squad"] if p["name"] == "A2")
    assert_ok(c.post("/record-dismissal", json={"kind": "run_out", "batter": "striker", "credited_to_bowler": False}))
    score = c.get("/score").get_json()
    assert score["wickets"] >= 2, score
    assert_ok(c.post("/penalty-runs", json={"runs": 5, "side": "batting", "reason": "test"}))
    score = c.get("/score").get_json()
    assert score["runs"] >= 12 and score["extras"] >= 7, score
    assert_ok(c.post("/dead-ball", json={"note": "test"}))

    assert_ok(c.post("/edit", json={"runs": 50, "wickets": 3, "overs": 9, "balls": 5, "extras": 6}))
    score = c.get("/score").get_json()
    assert score["runs"] == 50 and score["wickets"] == 3 and score["overs_display"] == "9.5", score

    assert_ok(c.post("/set-panel", json={"panel": "batting"}))
    assert c.get("/score").get_json()["active_panel"] == "batting"
    assert_ok(c.post("/set-overlay-scale", json={"scale": 1.35}))
    assert c.get("/score").get_json()["overlay_scale"] == 1.35

    assert_ok(c.post("/end-over"))
    score = c.get("/score").get_json()
    assert score["overs_display"] == "10.0", score

    second_payload = {
        "batting_team": "Team B",
        "batting_squad": [f"B{i}" for i in range(1, 12)],
        "bowling_squad": [f"A{i}" for i in range(1, 12)],
    }
    assert_ok(c.post("/start-second-innings", json=second_payload))
    score = c.get("/score").get_json()
    assert score["innings"] == 2, score
    assert score["target"] == 51, score
    assert score["batting_team"] == "Team B" and score["bowling_team"] == "Team A", score
    assert score["runs_needed"] == 51 and score["balls_remaining"] == 120, score

    assert_ok(c.post("/save"))
    restore_resp = c.post("/restore")
    if restore_resp.status_code not in (200, 404):
        raise AssertionError(
            f"Expected 200 or 404 for restore, got {restore_resp.status_code}: "
            f"{restore_resp.get_data(as_text=True)}"
        )
    assert_ok(c.get("/health"))

    assert_ok(c.post("/reset-match"))
    assert_ok(c.post("/setup", json=setup_payload))
    assert_ok(c.post("/set-players", json={"striker": "A1", "non_striker": "A2", "current_bowler": "B1"}))
    assert_ok(
        c.post(
            "/relay/config",
            json={
                "relay_mode": "play_cricket",
                "relay_play_cricket_url": "https://bmacc.play-cricket.com/website/results/7681278",
            },
        )
    )
    pc_snap = {
        "status": "Smoke relay",
        "innings_1": {
            "team": "Team A",
            "runs": 7,
            "wickets": 0,
            "overs": "1.0",
            "score": "7/0",
            "overs_display": "1.0",
        },
    }
    assert_ok(c.post("/relay/ingest", json={"snapshot": pc_snap, "stale": False}))
    relay_score = c.get("/score").get_json()
    assert relay_score["relay_bundle"]["enabled"] is True, relay_score
    assert relay_score["relay_bundle"]["snapshot"]["innings_1"]["runs"] == 7, relay_score
    assert_ok(c.post("/relay/config", json={"relay_mode": "manual"}))

    cfg = c.post("/relay/config", json={"relay_mode": "pcs_ble"})
    assert_ok(cfg)
    pcs_headers = {}
    global_tok = (os.getenv("RELAY_INGEST_TOKEN") or "").strip()
    if global_tok:
        pcs_headers["Authorization"] = f"Bearer {global_tok}"
    else:
        per_tok = (cfg.get_json() or {}).get("pcs_ingest_token") or ""
        if per_tok:
            pcs_headers["Authorization"] = f"Bearer {per_tok}"
    pcs_events = {
        "events": [
            "BTNHome Side",
            "FTNAway Side",
            "B1NStriker",
            "B2NNonStrike",
            "BTS42/3",
            "B1S45",
            "B2S12",
            "B1B32",
            "B2B8",
            "OVB8.2",
            "FTS200/8",
            "RRQ159",
            "BTS10/1",
            "B1NOpener",
            "B1S10",
            "OVB15.0",
        ]
    }
    assert_ok(c.post("/relay/pcs-ingest", json=pcs_events, headers=pcs_headers))
    pcs_score = c.get("/score").get_json()
    assert pcs_score["relay_bundle"]["enabled"] is True, pcs_score
    assert pcs_score["relay_bundle"]["mode"] == "pcs_ble", pcs_score
    snap = pcs_score["relay_bundle"]["snapshot"]
    live = snap.get("live") or {}
    assert live.get("runs") == 10, pcs_score
    assert (live.get("batsman_1") or {}).get("runs") == 10, pcs_score
    assert live.get("target") == 201, pcs_score
    assert live.get("runs_required") == 159, pcs_score
    assert snap["innings_1"]["runs"] == 200, pcs_score
    st = c.get("/relay/pcs-status", query_string={"match": "default"}, headers=pcs_headers)
    assert_ok(st)
    stj = st.get_json()
    assert stj["pcs_live"]["packet_count"] >= 6, stj
    assert_ok(c.post("/relay/pcs-ingest", json={"line": "XYZunknown"}, headers=pcs_headers))
    cap = c.get("/relay/pcs-status", query_string={"match": "default"}, headers=pcs_headers).get_json()["pcs_capture"]
    assert "XYZ" in cap.get("unknown_opcodes", []) or any(
        e.get("opcode") == "XYZ" for e in cap.get("packet_log_tail", [])
    ), cap
    assert_ok(c.post("/relay/config", json={"relay_mode": "manual"}))

    print("Smoke validation passed for all core endpoints.")


if __name__ == "__main__":
    main()
