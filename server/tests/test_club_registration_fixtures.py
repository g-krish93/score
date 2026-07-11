"""Multi-user club accounts: club-code inheritance, fixture self-heal, recovery endpoints.

Second/third volunteers register with their own email (the mobile app has no
club-code field), so their Organization used to end up with no Play-Cricket
site and a silently empty fixture list. These tests pin the fixes.
"""
from __future__ import annotations

import os
import tempfile
import uuid

import pytest

_TMP = tempfile.mkdtemp(prefix="cr_club_reg_test_")
os.environ.setdefault("STATE_DIR", _TMP)
os.environ.setdefault("DATABASE_URL", f"sqlite:///{os.path.join(_TMP, 'test.db')}")
os.environ.setdefault("SECRET_KEY", "test-secret")

from server.app import app  # noqa: E402
from server.models_cricrelay import Organization, db  # noqa: E402
from server.stream_api import issue_stream_token  # noqa: E402


@pytest.fixture()
def client():
    app.config["TESTING"] = True
    with app.test_client() as c:
        yield c


_ip_seq = 0


def _fresh_ip() -> str:
    """Unique client IP per request so the auth rate limiter never trips."""
    global _ip_seq
    _ip_seq += 1
    return f"10.9.{_ip_seq // 250}.{_ip_seq % 250 + 1}"


def _register(client, name: str, *, base_url: str | None = None):
    payload = {
        "name": name,
        "email": f"user-{uuid.uuid4().hex[:10]}@example.com",
        "password": "longenough1",
        "consent": True,
    }
    if base_url is not None:
        payload["play_cricket_base_url"] = base_url
    return client.post(
        "/api/auth/register",
        json=payload,
        headers={"X-Forwarded-For": _fresh_ip()},
    )


def _seed_org(name: str, base_url: str | None) -> str:
    with app.app_context():
        org = Organization(
            id=str(uuid.uuid4()),
            slug=f"seed-{uuid.uuid4().hex[:8]}",
            name=name,
            email=f"seed-{uuid.uuid4().hex[:10]}@example.com",
            play_cricket_base_url=base_url,
        )
        org.set_password("pw")
        db.session.add(org)
        db.session.commit()
        return org.id


def _org_token(org_id: str) -> str:
    with app.app_context():
        return issue_stream_token(db.session.get(Organization, org_id))


def _org_base_url(org_id: str) -> str:
    with app.app_context():
        return db.session.get(Organization, org_id).play_cricket_base_url or ""


def test_api_register_rejects_unrecognised_club_code(client):
    resp = _register(client, f"Bad Code CC {uuid.uuid4().hex[:6]}", base_url="https://bmacc.pitchero.com")
    assert resp.status_code == 400
    assert "not recognised" in resp.get_json()["error"]


def test_api_register_inherits_club_site_from_same_name_account(client):
    name = f"Inherit CC {uuid.uuid4().hex[:6]}"
    _seed_org(name, "https://inheritcc.play-cricket.com")
    resp = _register(client, name)
    assert resp.status_code == 201
    body = resp.get_json()
    assert body["play_cricket_base_url"] == "https://inheritcc.play-cricket.com"
    assert _org_base_url(body["org_id"]) == "https://inheritcc.play-cricket.com"


def test_api_register_does_not_inherit_when_same_name_accounts_disagree(client):
    name = f"Split CC {uuid.uuid4().hex[:6]}"
    _seed_org(name, "https://splitone.play-cricket.com")
    _seed_org(name, "https://splittwo.play-cricket.com")
    resp = _register(client, name)
    assert resp.status_code == 201
    assert resp.get_json()["play_cricket_base_url"] == ""


def test_api_fixtures_self_heals_from_same_name_account(client, monkeypatch):
    name = f"Heal CC {uuid.uuid4().hex[:6]}"
    _seed_org(name, "https://healcc.play-cricket.com")
    org_b = _seed_org(name, None)

    scraped_urls: list[str] = []

    def fake_scrape(url, limit=24):
        scraped_urls.append(url)
        return [{"match_id": "7560599", "label": "Heal CC vs Rivals", "url": url}]

    monkeypatch.setattr("server.app.scrape_fixtures", fake_scrape)

    resp = client.get(
        "/api/fixtures", headers={"Authorization": f"Bearer {_org_token(org_b)}"}
    )
    assert resp.status_code == 200
    body = resp.get_json()
    assert body["fixtures"] and body["fixtures"][0]["match_id"] == "7560599"
    assert all("healcc.play-cricket.com" in u for u in scraped_urls)
    # The club site is persisted so match-id stream creation works from now on.
    assert _org_base_url(org_b) == "https://healcc.play-cricket.com"


def test_api_fixtures_reports_missing_club_site(client, monkeypatch):
    org_id = _seed_org(f"Orphan CC {uuid.uuid4().hex[:6]}", None)
    monkeypatch.setattr("server.app.scrape_fixtures", lambda url, limit=24: [])

    resp = client.get(
        "/api/fixtures", headers={"Authorization": f"Bearer {_org_token(org_id)}"}
    )
    assert resp.status_code == 200
    body = resp.get_json()
    assert body["fixtures"] == []
    assert "club code" in (body["error"] or "")


def test_patch_account_links_club_site(client):
    org_id = _seed_org(f"Patch CC {uuid.uuid4().hex[:6]}", None)
    headers = {"Authorization": f"Bearer {_org_token(org_id)}"}

    bad = client.patch(
        "/api/auth/account", json={"play_cricket_base_url": "https://x.pitchero.com"}, headers=headers
    )
    assert bad.status_code == 400

    ok = client.patch(
        "/api/auth/account", json={"play_cricket_base_url": "bmacc"}, headers=headers
    )
    assert ok.status_code == 200
    assert ok.get_json()["play_cricket_base_url"] == "https://bmacc.play-cricket.com"
    assert _org_base_url(org_id) == "https://bmacc.play-cricket.com"


def test_dashboard_form_links_club_site(client):
    org_id = _seed_org(f"Dash CC {uuid.uuid4().hex[:6]}", None)
    with client.session_transaction() as sess:
        sess["org_id"] = org_id

    resp = client.post("/dashboard/play-cricket", data={"play_cricket_base_url": "dashcc"})
    assert resp.status_code == 302
    assert _org_base_url(org_id) == "https://dashcc.play-cricket.com"
