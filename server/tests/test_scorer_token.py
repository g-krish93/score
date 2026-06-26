"""Tests for match-scoped scorer-token authz (Task C2 / S-2).

Enforcement is OFF by default (no behaviour change); when SCORER_TOKEN_REQUIRED
is on and a match has a token, mutating scoring routes require it — except for
the owning club's logged-in session.
"""
from __future__ import annotations

import os
import tempfile

import pytest

_TMP = tempfile.mkdtemp(prefix="cr_scorer_test_")
os.environ["STATE_DIR"] = _TMP
os.environ["DATABASE_URL"] = f"sqlite:///{os.path.join(_TMP, 'test.db')}"
os.environ.setdefault("SECRET_KEY", "test-secret")
os.environ["SCORER_TOKEN_REQUIRED"] = ""  # default OFF

from server.app import app  # noqa: E402
from server.models_cricrelay import Organization, RelayMatch, db  # noqa: E402


@pytest.fixture()
def client():
    app.config["TESTING"] = True
    with app.test_client() as c:
        yield c


_seq = 0


def _make_match(token: str | None = None) -> tuple[str, str]:
    """Create an org + RelayMatch, return (org_id, slug)."""
    global _seq
    _seq += 1
    slug = f"club{_seq}-m1"
    with app.app_context():
        org = Organization(slug=f"club{_seq}", name=f"club{_seq}", email=f"c{_seq}@e.com")
        org.set_password("pw")
        db.session.add(org)
        db.session.flush()
        db.session.add(RelayMatch(
            organization_id=org.id,
            play_cricket_match_id=f"native-{_seq}",
            full_scrape_url="",
            score_match_slug=slug,
            relay_source="native",
            scorer_token=token,
        ))
        db.session.commit()
        return org.id, slug


def _setup_body():
    return {
        "team1": "Home", "team2": "Away", "total_overs": 20,
        "batting_squad": ["A", "B", "C"], "bowling_squad": ["X"],
    }


def _enforce(on: bool):
    os.environ["SCORER_TOKEN_REQUIRED"] = "1" if on else ""


def test_scoring_open_when_enforcement_off(client):
    _enforce(False)
    _org, slug = _make_match(token=None)
    assert client.post(f"/setup?match={slug}", json=_setup_body()).status_code == 200
    assert client.post(f"/ball?match={slug}", json={"type": "1"}).status_code == 200


def test_match_without_token_not_enforced(client):
    _enforce(True)
    try:
        _org, slug = _make_match(token=None)  # no token -> open even when enforced
        assert client.post(f"/setup?match={slug}", json=_setup_body()).status_code == 200
    finally:
        _enforce(False)


def test_token_required_blocks_and_allows(client):
    _enforce(True)
    try:
        _org, slug = _make_match(token="secret-123")
        # setup needs the token too
        assert client.post(f"/setup?match={slug}", json=_setup_body()).status_code == 403
        ok = client.post(
            f"/setup?match={slug}", json=_setup_body(),
            headers={"X-Scorer-Token": "secret-123"},
        )
        assert ok.status_code == 200
        # ball: wrong/no token blocked, correct token allowed
        assert client.post(f"/ball?match={slug}", json={"type": "1"}).status_code == 403
        good = client.post(
            f"/ball?match={slug}", json={"type": "1"},
            headers={"X-Scorer-Token": "secret-123"},
        )
        assert good.status_code == 200
    finally:
        _enforce(False)


def test_owning_org_session_bypasses_token(client):
    _enforce(True)
    try:
        org_id, slug = _make_match(token="secret-xyz")
        with client.session_transaction() as sess:
            sess["org_id"] = org_id
        assert client.post(f"/setup?match={slug}", json=_setup_body()).status_code == 200
        assert client.post(f"/ball?match={slug}", json={"type": "4"}).status_code == 200
    finally:
        _enforce(False)


def test_mint_token_is_org_scoped(client):
    _org_a, slug = _make_match(token=None)
    org_b, _slug_b = _make_match(token=None)
    # org B logs in and tries to mint a token for org A's match -> rejected.
    with client.session_transaction() as sess:
        sess["org_id"] = org_b
    client.post("/dashboard/relay/scorer-token", data={"score_match_slug": slug}, follow_redirects=True)
    with app.app_context():
        row = RelayMatch.query.filter_by(score_match_slug=slug).first()
        assert row.scorer_token is None  # unchanged by the other org
