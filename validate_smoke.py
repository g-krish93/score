from server.app import app


def assert_ok(resp, code=200):
    if resp.status_code != code:
        raise AssertionError(f"Expected {code}, got {resp.status_code}: {resp.get_data(as_text=True)}")


def main():
    c = app.test_client()

    assert_ok(c.get("/health"))
    assert_ok(c.get("/score"))
    assert_ok(c.get("/"))
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

    assert_ok(c.post("/edit", json={"runs": 50, "wickets": 3, "overs": 9, "balls": 5, "extras": 6}))
    score = c.get("/score").get_json()
    assert score["runs"] == 50 and score["wickets"] == 3 and score["overs_display"] == "9.5", score

    assert_ok(c.post("/set-panel", json={"panel": "batting"}))
    assert c.get("/score").get_json()["active_panel"] == "batting"

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

    print("Smoke validation passed for all core endpoints.")


if __name__ == "__main__":
    main()
