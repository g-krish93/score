"""Sponsor CRUD, overlay prefs, and remote-control pairing routes."""
from __future__ import annotations

import json
import os
import tempfile
import uuid

import pytest

_TMP = tempfile.mkdtemp(prefix="cr_sponsor_remote_test_")
os.environ["STATE_DIR"] = _TMP
os.environ["DATABASE_URL"] = f"sqlite:///{os.path.join(_TMP, 'test.db')}"
os.environ.setdefault("SECRET_KEY", "test-secret")

from server.app import app  # noqa: E402
from server.models_cricrelay import Organization, RelayMatch, Sponsor, db  # noqa: E402
from server.stream_api import issue_stream_token  # noqa: E402


class _FakeRedis:
    def __init__(self) -> None:
        self._kv: dict[str, str] = {}
        self._lists: dict[str, list[str]] = {}

    def setex(self, key: str, _ttl: int, value: str) -> None:
        self._kv[key] = value

    def get(self, key: str) -> bytes | None:
        val = self._kv.get(key)
        return val.encode() if val is not None else None

    def rpush(self, key: str, value: str) -> None:
        self._lists.setdefault(key, []).append(value)

    def expire(self, _key: str, _ttl: int) -> None:
        return None

    def pipeline(self) -> "_FakePipeline":
        return _FakePipeline(self)


class _FakePipeline:
    def __init__(self, redis: _FakeRedis) -> None:
        self._redis = redis
        self._ops: list[tuple[str, str]] = []

    def lrange(self, key: str, _start: int, _end: int) -> "_FakePipeline":
        self._ops.append(("lrange", key))
        return self

    def delete(self, key: str) -> "_FakePipeline":
        self._ops.append(("delete", key))
        return self

    def execute(self) -> list:
        results = []
        for op, key in self._ops:
            if op == "lrange":
                results.append(self._redis._lists.get(key, []))
            elif op == "delete":
                self._redis._lists.pop(key, None)
                results.append(1)
        return results


@pytest.fixture()
def client(monkeypatch):
    fake = _FakeRedis()
    monkeypatch.setattr("server.stream_api._redis_client", fake)
    app.config["TESTING"] = True
    with app.test_client() as c:
        yield c, fake


_seq = 0


def _seed_org_and_match() -> tuple[str, str, str]:
    global _seq
    _seq += 1
    slug = f"test-stream-{_seq}"
    with app.app_context():
        org = Organization(
            id=str(uuid.uuid4()),
            slug=f"sponsor-club-{_seq}",
            name=f"Sponsor Club {_seq}",
            email=f"sponsor-{_seq}@example.com",
        )
        org.set_password("pw")
        match = RelayMatch(
            id=str(uuid.uuid4()),
            organization_id=org.id,
            play_cricket_match_id=f"9999{_seq}",
            full_scrape_url=f"https://example.play-cricket.com/website/results/9999{_seq}",
            score_match_slug=slug,
        )
        db.session.add_all([org, match])
        db.session.commit()
        return org.id, issue_stream_token(org), slug


def _auth_headers(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}


def test_sponsor_crud_and_overlay_prefs(client):
    c, _fake = client
    _org_id, token, slug = _seed_org_and_match()
    headers = _auth_headers(token)

    create = c.post(
        "/api/sponsors",
        headers=headers,
        data=json.dumps({"name": "Acme", "logo_url": "https://example.com/logo.png"}),
    )
    assert create.status_code == 200
    sponsor_id = create.get_json()["sponsor"]["id"]

    listed = c.get("/api/sponsors", headers=headers)
    assert listed.status_code == 200
    assert len(listed.get_json()["sponsors"]) == 1

    patched = c.patch(
        f"/api/sponsors/{sponsor_id}",
        headers=headers,
        data=json.dumps({"name": "Acme Ltd"}),
    )
    assert patched.status_code == 200
    assert patched.get_json()["sponsor"]["name"] == "Acme Ltd"

    overlay = c.post(
        f"/api/match/{slug}/overlay",
        headers=headers,
        data=json.dumps({"sponsor_enabled": True, "active_sponsor_id": sponsor_id}),
    )
    assert overlay.status_code == 200
    body = overlay.get_json()
    assert body["sponsor_enabled"] is True
    assert body["active_sponsor_id"] == sponsor_id

    deleted = c.delete(f"/api/sponsors/{sponsor_id}", headers=headers)
    assert deleted.status_code == 200
    assert c.get("/api/sponsors", headers=headers).get_json()["sponsors"] == []


def test_remote_pair_redeem_command_poll(client):
    c, fake = client
    _org_id, token, slug = _seed_org_and_match()
    headers = _auth_headers(token)

    pair = c.post(f"/api/match/{slug}/pair", headers=headers)
    assert pair.status_code == 200
    pair_token = pair.get_json()["pair_token"]

    redeem = c.post(
        f"/stream/{slug}/pair/redeem",
        data=json.dumps({"pair_token": pair_token}),
        content_type="application/json",
    )
    assert redeem.status_code == 200
    companion_token = redeem.get_json()["companion_token"]

    cmd_headers = {
        "Authorization": f"Bearer {companion_token}",
        "Content-Type": "application/json",
    }
    sent = c.post(
        f"/api/match/{slug}/remote/command",
        headers=cmd_headers,
        data=json.dumps({"type": "control", "command": "mute_mic"}),
    )
    assert sent.status_code == 200

    polled = c.get(f"/api/match/{slug}/remote/commands", headers=headers)
    assert polled.status_code == 200
    commands = polled.get_json()["commands"]
    assert len(commands) == 1
    assert commands[0]["command"] == "mute_mic"

    polled_again = c.get(f"/api/match/{slug}/remote/commands", headers=headers)
    assert polled_again.get_json()["commands"] == []

    pair2 = c.post(f"/api/match/{slug}/pair", headers=headers)
    redeem2 = c.post(
        f"/stream/{slug}/pair/redeem",
        data=json.dumps({"pair_token": pair2.get_json()["pair_token"]}),
        content_type="application/json",
    )
    assert redeem2.status_code == 200

    stale = c.post(
        f"/api/match/{slug}/remote/command",
        headers=cmd_headers,
        data=json.dumps({"type": "control", "command": "mute_mic"}),
    )
    assert stale.status_code == 410
