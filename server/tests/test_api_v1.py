"""Contract tests for the versioned /api/v1 surface (Task D / #7).

Asserts the stable response shapes so internal refactors can't silently break
clients. The authed /streams endpoint is checked for its 401 contract; the
public health + score endpoints are checked end-to-end.
"""
from __future__ import annotations

import os
import tempfile

import pytest

_TMP = tempfile.mkdtemp(prefix="cr_apiv1_test_")
os.environ["STATE_DIR"] = _TMP
os.environ["DATABASE_URL"] = f"sqlite:///{os.path.join(_TMP, 'test.db')}"
os.environ.setdefault("SECRET_KEY", "test-secret")

from server.app import API_V1_VERSION, app  # noqa: E402


@pytest.fixture()
def client():
    app.config["TESTING"] = True
    with app.test_client() as c:
        yield c


def test_health_contract(client):
    r = client.get("/api/v1/health")
    assert r.status_code == 200
    body = r.get_json()
    assert body["ok"] is True
    assert body["api_version"] == API_V1_VERSION
    assert body["service"] == "cricrelay"


def test_score_contract_shape(client):
    r = client.get("/api/v1/matches/some-match/score")
    assert r.status_code == 200
    body = r.get_json()
    assert body["ok"] is True
    score = body["score"]
    # The stable contract keys must always be present.
    for key in (
        "match_id", "started", "runs", "wickets", "extras", "overs", "crr",
        "striker", "non_striker", "current_over", "match_complete",
    ):
        assert key in score, f"missing contract key: {key}"
    assert score["match_id"] == "some-match"
    assert isinstance(score["current_over"], list)


def test_score_reflects_live_scoring(client):
    # Score a couple of balls via the legacy engine, then read v1.
    slug = "apiv1-live"
    setup = {
        "team1": "Home", "team2": "Away", "total_overs": 20,
        "batting_squad": ["A", "B", "C"], "bowling_squad": ["X"],
    }
    assert client.post(f"/setup?match={slug}", json=setup).status_code == 200
    client.post(f"/ball?match={slug}", json={"type": "4"})
    client.post(f"/ball?match={slug}", json={"type": "1"})
    body = client.get(f"/api/v1/matches/{slug}/score").get_json()
    assert body["score"]["started"] is True
    assert body["score"]["runs"] == 5


def test_streams_requires_bearer(client):
    r = client.get("/api/v1/streams")
    assert r.status_code == 401
    assert r.get_json().get("error") == "unauthorized"
