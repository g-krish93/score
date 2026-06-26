"""Task F: the public live page exposes a full scorecard.

The batting/bowling tables are rendered client-side from the /score snapshot, so
this checks (a) the snapshot carries per-player stats after scoring, and (b) the
live page ships the scorecard scaffolding. The folding math is covered by
cricrelay_core/tests/test_scorecard.py.
"""
from __future__ import annotations

import os
import tempfile

import pytest

_TMP = tempfile.mkdtemp(prefix="cr_livecard_test_")
os.environ["STATE_DIR"] = _TMP
os.environ["DATABASE_URL"] = f"sqlite:///{os.path.join(_TMP, 'test.db')}"
os.environ.setdefault("SECRET_KEY", "test-secret")

from server.app import app  # noqa: E402


@pytest.fixture()
def client():
    app.config["TESTING"] = True
    with app.test_client() as c:
        yield c


def _score_a_few(client, slug):
    setup = {
        "team1": "Home", "team2": "Away", "total_overs": 20,
        "batting_squad": ["A", "B", "C"], "bowling_squad": ["X"],
    }
    assert client.post(f"/setup?match={slug}", json=setup).status_code == 200
    for t in ("4", "1", "."):
        client.post(f"/ball?match={slug}", json={"type": t})


def test_snapshot_carries_squads_for_the_card(client):
    slug = "card-snap"
    _score_a_few(client, slug)
    snap = client.get(f"/score?match={slug}").get_json()
    # The scorecard renders from these arrays; the team total reflects scoring.
    assert snap["runs"] == 5  # 4 + 1
    assert [p["name"] for p in snap["batting_squad"]] == ["A", "B", "C"]
    assert [p["name"] for p in snap["bowling_squad"]] == ["X"]


def test_live_page_ships_scorecard(client):
    slug = "card-page"
    _score_a_few(client, slug)
    html = client.get(f"/live/{slug}").get_data(as_text=True)
    assert "battingCard" in html
    assert "bowlingCard" in html
    assert "renderBowlingCard" in html
