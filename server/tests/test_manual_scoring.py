"""Manual (QR scorer page) stream type: creation, token auth, score flow, guards."""
from __future__ import annotations

import json
import os
import tempfile
import uuid
from datetime import datetime, timedelta, timezone

import pytest

_TMP = tempfile.mkdtemp(prefix="cr_manual_scoring_test_")
os.environ["STATE_DIR"] = _TMP
os.environ["DATABASE_URL"] = f"sqlite:///{os.path.join(_TMP, 'test.db')}"
os.environ.setdefault("SECRET_KEY", "test-secret")

import server.app as app_mod  # noqa: E402
from server.app import app  # noqa: E402
from server.models_cricrelay import (  # noqa: E402
    Organization,
    RelayMatch,
    db,
    relay_source_to_provider,
)
from server.stream_api import issue_manual_scorer_token, issue_stream_token  # noqa: E402


@pytest.fixture()
def client():
    app.config["TESTING"] = True
    with app.test_client() as c:
        yield c


_seq = 0


def _seed_org() -> tuple[str, str]:
    global _seq
    _seq += 1
    with app.app_context():
        org = Organization(
            id=str(uuid.uuid4()),
            slug=f"manual-club-{_seq}",
            name=f"Manual Club {_seq}",
            email=f"manual-{_seq}@example.com",
        )
        org.set_password("pw")
        db.session.add(org)
        db.session.commit()
        return org.id, issue_stream_token(org)


def _seed_scraper_match(org_id: str) -> str:
    global _seq
    _seq += 1
    slug = f"scraper-stream-{_seq}"
    with app.app_context():
        match = RelayMatch(
            id=str(uuid.uuid4()),
            organization_id=org_id,
            play_cricket_match_id=f"8888{_seq}",
            full_scrape_url=f"https://example.play-cricket.com/website/results/8888{_seq}",
            score_match_slug=slug,
        )
        db.session.add(match)
        db.session.commit()
    return slug


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}


def _create_manual(c, token: str, label: str = "") -> dict:
    resp = c.post("/api/streams", headers=_auth(token), json={"type": "manual", "label": label})
    assert resp.status_code == 200, resp.get_json()
    return resp.get_json()["stream"]


def _scorer_link(c, token: str, slug: str) -> dict:
    resp = c.post(f"/api/match/{slug}/scorer-link", headers=_auth(token))
    assert resp.status_code == 200, resp.get_json()
    return resp.get_json()


def _totals_payload(seq: int = 1, **over) -> dict:
    payload = {
        "seq": seq,
        "team_a": "Sunbury CC",
        "team_b": "Rivals CC",
        "total_overs": 20,
        "batting_first": "team_a",
        "current_innings": 1,
        "innings": [
            {"innings": 1, "batting": "team_a", "runs": 57, "wickets": 3, "overs": 8, "balls": 4},
        ],
        "match_over": False,
        "result_text": "",
    }
    payload.update(over)
    return payload


# ── creation ────────────────────────────────────────────────────────────────


def test_create_manual_stream(client):
    _org_id, token = _seed_org()
    stream = _create_manual(client, token, label="3rd XI friendly")
    assert stream["relay_source"] == "manual"
    assert stream["label"] == "3rd XI friendly"
    assert stream["slug"]


def test_create_manual_stream_default_label(client):
    _org_id, token = _seed_org()
    stream = _create_manual(client, token)
    assert stream["label"] == "Manual scoring"


def test_create_manual_stream_respects_quota(client, monkeypatch):
    _org_id, token = _seed_org()
    monkeypatch.setattr("server.app.MAX_LIVE_STREAMS_PER_CLUB", 1)
    _create_manual(client, token)
    resp = client.post("/api/streams", headers=_auth(token), json={"type": "manual"})
    assert resp.status_code == 400
    assert "up to 1 live stream" in resp.get_json()["error"]


def test_manual_stream_skips_poller():
    assert relay_source_to_provider("manual") is None


# ── scorer link ─────────────────────────────────────────────────────────────


def test_scorer_link_mints_url(client):
    _org_id, token = _seed_org()
    stream = _create_manual(client, token)
    body = _scorer_link(client, token, stream["slug"])
    assert f"/m/{stream['slug']}/scorer?token=" in body["scorer_url"]
    assert body["expires_at"]


def test_scorer_link_rejected_for_scraper_stream(client):
    org_id, token = _seed_org()
    slug = _seed_scraper_match(org_id)
    resp = client.post(f"/api/match/{slug}/scorer-link", headers=_auth(token))
    assert resp.status_code == 400


def test_scorer_link_unknown_stream(client):
    _org_id, token = _seed_org()
    resp = client.post("/api/match/no-such-slug/scorer-link", headers=_auth(token))
    assert resp.status_code == 404


# ── token auth ──────────────────────────────────────────────────────────────


def test_scorer_page_valid_token(client):
    _org_id, token = _seed_org()
    stream = _create_manual(client, token, label="QR Match")
    url = _scorer_link(client, token, stream["slug"])["scorer_url"]
    resp = client.get(url)
    assert resp.status_code == 200
    assert b"QR Match" in resp.data


def test_scorer_page_tampered_token(client):
    _org_id, token = _seed_org()
    stream = _create_manual(client, token)
    resp = client.get(f"/m/{stream['slug']}/scorer?token=not-a-real-token")
    assert resp.status_code == 403
    assert b"expired" in resp.data.lower()


def test_scorer_token_wrong_slug(client):
    _org_id, token = _seed_org()
    stream_a = _create_manual(client, token)
    stream_b = _create_manual(client, token)
    url_a = _scorer_link(client, token, stream_a["slug"])["scorer_url"]
    token_a = url_a.split("token=")[1]
    resp = client.get(f"/m/{stream_b['slug']}/scorer?token={token_a}")
    assert resp.status_code == 403


def test_scorer_token_expired(client, monkeypatch):
    _org_id, token = _seed_org()
    stream = _create_manual(client, token)
    url = _scorer_link(client, token, stream["slug"])["scorer_url"]
    monkeypatch.setattr("server.stream_api.MANUAL_SCORER_TOKEN_MAX_AGE", -1)
    assert client.get(url).status_code == 403


def test_scorer_state_requires_token(client):
    _org_id, token = _seed_org()
    stream = _create_manual(client, token)
    resp = client.get(f"/m/{stream['slug']}/scorer/state")
    assert resp.status_code == 403


# ── score flow ──────────────────────────────────────────────────────────────


def _setup_scored_stream(client, label: str = "") -> tuple[str, str]:
    """Create a manual stream and post initial totals. Returns (slug, scorer_token)."""
    _org_id, token = _seed_org()
    stream = _create_manual(client, token, label=label)
    slug = stream["slug"]
    with app.app_context():
        org = db.session.get(Organization, _org_id)
        scorer_token = issue_manual_scorer_token(org, slug)
    resp = client.post(
        f"/m/{slug}/scorer/state", headers=_auth(scorer_token), json=_totals_payload()
    )
    assert resp.status_code == 200, resp.get_json()
    return slug, scorer_token


def test_scorer_setup_flows_to_overlay(client):
    slug, _tok = _setup_scored_stream(client)
    data = client.get(f"/m/{slug}/overlay-data").get_json()
    assert data["home_team"] == "Sunbury CC"
    assert data["away_team"] == "Rivals CC"
    assert data["data_pattern"] == "manual"
    assert data["relay_source"] == "manual"
    assert data["total_overs"] == 20
    assert data["batting_team"] == "Sunbury CC"
    assert data["target"] is None
    assert data["stale"] is False
    inn = data["innings"][0]
    assert (inn["runs"], inn["wickets"], inn["overs"]) == (57, 3, "8.4")
    assert inn["batters"] == [] and inn["bowlers"] == []
    assert data["striker"]["name"] == ""


def test_scorer_state_roundtrip(client):
    slug, tok = _setup_scored_stream(client)
    body = client.get(f"/m/{slug}/scorer/state", headers=_auth(tok)).get_json()
    assert body["setup_complete"] is True
    assert body["seq"] == 1
    assert body["state"]["team_a"] == "Sunbury CC"


def test_second_innings_target(client):
    slug, tok = _setup_scored_stream(client)
    payload = _totals_payload(
        seq=2,
        current_innings=2,
        innings=[
            {"innings": 1, "batting": "team_a", "runs": 142, "wickets": 8, "overs": 20, "balls": 0},
            {"innings": 2, "batting": "team_b", "runs": 30, "wickets": 1, "overs": 4, "balls": 2},
        ],
    )
    resp = client.post(f"/m/{slug}/scorer/state", headers=_auth(tok), json=payload)
    assert resp.status_code == 200
    assert resp.get_json()["target"] == 143
    data = client.get(f"/m/{slug}/overlay-data").get_json()
    assert data["target"] == 143
    assert data["batting_team"] == "Rivals CC"
    assert len(data["innings"]) == 2


def test_stale_seq_conflict(client):
    slug, tok = _setup_scored_stream(client)
    resp = client.post(
        f"/m/{slug}/scorer/state", headers=_auth(tok), json=_totals_payload(seq=1)
    )
    assert resp.status_code == 409
    body = resp.get_json()
    assert body["error"] == "stale_seq"
    assert body["seq"] == 1
    assert body["state"]["team_a"] == "Sunbury CC"


def test_validation_rejects_bad_totals(client):
    slug, tok = _setup_scored_stream(client)
    bad = _totals_payload(
        seq=2,
        innings=[{"innings": 1, "batting": "team_a", "runs": 57, "wickets": 11, "overs": 8, "balls": 4}],
    )
    resp = client.post(f"/m/{slug}/scorer/state", headers=_auth(tok), json=bad)
    assert resp.status_code == 400


def test_match_over_result_text(client):
    slug, tok = _setup_scored_stream(client)
    payload = _totals_payload(seq=2, match_over=True, result_text="Sunbury won by 24 runs")
    assert client.post(f"/m/{slug}/scorer/state", headers=_auth(tok), json=payload).status_code == 200
    data = client.get(f"/m/{slug}/overlay-data").get_json()
    assert data["match"]["status"] == "Sunbury won by 24 runs"


def test_scoring_status_active_and_live_page(client):
    slug, _tok = _setup_scored_stream(client)
    from server.stream_api import scoring_status_for_slug

    with app.app_context():
        status = scoring_status_for_slug(slug)
    assert status["scoring_mode"] == "manual"
    assert status["scoring_active"] is True
    assert status["scoring_stale"] is False
    # legacy-state mirror keeps the public live page working
    resp = client.get(f"/live/{slug}")
    assert resp.status_code == 200


def test_pre_setup_overlay_shell_shows_label(client):
    _org_id, token = _seed_org()
    stream = _create_manual(client, token, label="Village Cup Final")
    data = client.get(f"/m/{stream['slug']}/overlay-data").get_json()
    assert data["innings"] == []
    assert data["match"]["status"] == "NOT STARTED"
    assert data["match"]["competition"] == "Village Cup Final"
    assert data["data_pattern"] == "manual"


def test_staleness_after_quiet_period(client):
    slug, _tok = _setup_scored_stream(client)
    backdated = (datetime.now(timezone.utc) - timedelta(minutes=11)).isoformat()
    with app_mod.match_context(slug):
        app_mod.state["last_manual_at"] = backdated
        app_mod.save_state()
    data = client.get(f"/m/{slug}/overlay-data").get_json()
    assert data["stale"] is True
    from server.stream_api import scoring_status_for_slug

    with app.app_context():
        assert scoring_status_for_slug(slug)["scoring_stale"] is True


# ── guards ──────────────────────────────────────────────────────────────────


def test_legacy_scoring_endpoints_blocked(client):
    slug, _tok = _setup_scored_stream(client)
    resp = client.post(f"/ball?match={slug}", json={"type": "4"})
    assert resp.status_code == 400
    assert "QR scorer" in resp.get_json()["error"]


def test_set_scoring_mode_manual_is_noop(client):
    _org_id, token = _seed_org()
    stream = _create_manual(client, token)
    slug = stream["slug"]
    resp = client.post(f"/api/match/{slug}/scoring", headers=_auth(token), json={"mode": "manual"})
    assert resp.status_code == 200
    with app.app_context():
        row = RelayMatch.query.filter_by(score_match_slug=slug).first()
        assert row.relay_source == "manual"


def test_set_scoring_mode_auto_rejected(client):
    _org_id, token = _seed_org()
    stream = _create_manual(client, token)
    slug = stream["slug"]
    resp = client.post(f"/api/match/{slug}/scoring", headers=_auth(token), json={"mode": "auto"})
    assert resp.status_code == 400
    with app.app_context():
        row = RelayMatch.query.filter_by(score_match_slug=slug).first()
        assert row.relay_source == "manual"


def test_rename_and_delete_manual_stream(client):
    _org_id, token = _seed_org()
    stream = _create_manual(client, token)
    slug = stream["slug"]
    resp = client.patch(f"/api/streams/{slug}", headers=_auth(token), json={"label": "Renamed"})
    assert resp.status_code == 200
    resp = client.delete(f"/api/streams/{slug}", headers=_auth(token))
    assert resp.status_code == 200
    with app.app_context():
        assert RelayMatch.query.filter_by(score_match_slug=slug).first() is None
