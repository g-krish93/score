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


def test_dashboard_sponsor_logo_upload(client, monkeypatch, tmp_path):
    c, _fake = client
    org_id, _token, _slug = _seed_org_and_match()
    static_root = tmp_path / "static"
    static_root.mkdir()
    monkeypatch.setattr("server.app.app.static_folder", str(static_root))
    monkeypatch.setattr("server.app._public_base_url", lambda: "https://test.example")

    with app.app_context():
        org = db.session.get(Organization, org_id)

    with c.session_transaction() as sess:
        sess["org_id"] = org_id

    # Minimal 1×1 PNG
    png = bytes.fromhex(
        "89504e470d0a1a0a0000000d49484452"
        "000000010000000108060000001f15c489"
        "0000000a49444154789c630001000000050001"
        "0d0a2db40000000049454e44ae426082"
    )
    from io import BytesIO

    data = {
        "sponsor_name": "Local Brew Co",
        "link_url": "https://localbrew.example",
        "logo": (BytesIO(png), "logo.png"),
    }
    resp = c.post("/dashboard/sponsors/add", data=data, content_type="multipart/form-data")
    assert resp.status_code == 302

    with app.app_context():
        rows = Sponsor.query.filter_by(organization_id=org_id).all()
        assert len(rows) == 1
        assert rows[0].name == "Local Brew Co"
        assert rows[0].logo_url.startswith("https://test.example/static/sponsors/")
        assert (static_root / "sponsors" / org_id).is_dir()


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
        data=json.dumps({
            "sponsor_enabled": True,
            "active_sponsor_id": sponsor_id,
            "sponsor_display_mode": "scroll_above_board",
            "sponsor_position_x": 0.1,
            "sponsor_size_scale": 1.5,
            "sponsor_opacity": 0.8,
            "sponsor_scroll_speed": 2.0,
        }),
    )
    assert overlay.status_code == 200
    body = overlay.get_json()
    assert body["sponsor_enabled"] is True
    assert body["active_sponsor_id"] == sponsor_id
    assert body["sponsor_display_mode"] == "scroll_above_board"
    assert body["sponsor_size_scale"] == 1.5

    relay = c.get(f"/m/{slug}/overlay-data")
    assert relay.status_code == 200
    sponsor = relay.get_json().get("sponsor")
    assert sponsor is not None
    assert sponsor["layout_mode"] == "single"
    assert sponsor["display_mode"] == "scroll_above_board"
    assert len(sponsor.get("logos") or []) == 1
    assert sponsor["size_scale"] == 1.5
    assert sponsor["scroll_speed"] == 2.0

    deleted = c.delete(f"/api/sponsors/{sponsor_id}", headers=headers)
    assert deleted.status_code == 200
    assert c.get("/api/sponsors", headers=headers).get_json()["sponsors"] == []


def test_overlay_theme_presets_and_bowling_island(client):
    """Floodlight board rollout: preset theme ids validate, unknown ids fall back to
    barlow, and the new bowling_island_enabled pref round-trips (default True)."""
    c, _fake = client
    _org_id, token, slug = _seed_org_and_match()
    headers = _auth_headers(token)

    # Default: island on, without any write
    initial = c.get(f"/api/match/{slug}/overlay", headers=headers)
    assert initial.status_code == 200
    assert initial.get_json()["bowling_island_enabled"] is True

    # Every new preset id (and legacy barlow) validates and round-trips
    for theme in ("floodlight", "chalk", "club-green", "broadcast-blue", "mono", "barlow"):
        resp = c.post(
            f"/api/match/{slug}/overlay",
            headers=headers,
            data=json.dumps({"theme": theme}),
        )
        assert resp.status_code == 200
        assert resp.get_json()["theme"] == theme

    # Unknown ids sanitize to barlow (graceful degrade on old clients)
    resp = c.post(
        f"/api/match/{slug}/overlay",
        headers=headers,
        data=json.dumps({"theme": "neon-zebra"}),
    )
    assert resp.status_code == 200
    assert resp.get_json()["theme"] == "barlow"

    # Island flag: bool-coerced on write, persists, and survives a follow-up GET
    resp = c.post(
        f"/api/match/{slug}/overlay",
        headers=headers,
        data=json.dumps({"bowling_island_enabled": 0}),
    )
    assert resp.status_code == 200
    assert resp.get_json()["bowling_island_enabled"] is False

    fetched = c.get(f"/api/match/{slug}/overlay", headers=headers)
    assert fetched.status_code == 200
    assert fetched.get_json()["bowling_island_enabled"] is False

    resp = c.post(
        f"/api/match/{slug}/overlay",
        headers=headers,
        data=json.dumps({"bowling_island_enabled": True}),
    )
    assert resp.get_json()["bowling_island_enabled"] is True


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


def test_remote_sponsor_context_and_overlay_command(client):
    c, _fake = client
    _org_id, token, slug = _seed_org_and_match()
    headers = _auth_headers(token)

    create = c.post(
        "/api/sponsors",
        headers=headers,
        data=json.dumps({"name": "Remote Brew", "logo_url": "https://example.com/rb.png"}),
    )
    sponsor_id = create.get_json()["sponsor"]["id"]
    c.post(
        f"/api/match/{slug}/overlay",
        headers=headers,
        data=json.dumps({"sponsor_enabled": True, "active_sponsor_id": sponsor_id}),
    )

    pair = c.post(f"/api/match/{slug}/pair", headers=headers)
    companion_token = c.post(
        f"/stream/{slug}/pair/redeem",
        data=json.dumps({"pair_token": pair.get_json()["pair_token"]}),
        content_type="application/json",
    ).get_json()["companion_token"]
    cmd_headers = {
        "Authorization": f"Bearer {companion_token}",
        "Content-Type": "application/json",
    }

    ctx = c.get(f"/api/match/{slug}/remote/context", headers=cmd_headers)
    assert ctx.status_code == 200
    body = ctx.get_json()
    assert body["sponsor_prefs"]["sponsor_enabled"] is True
    assert len(body["sponsors"]) == 1

    sent = c.post(
        f"/api/match/{slug}/remote/command",
        headers=cmd_headers,
        data=json.dumps(
            {
                "type": "overlay",
                "prefs": {
                    "sponsor_display_mode": "scroll_top",
                    "sponsor_size_scale": 1.8,
                },
            }
        ),
    )
    assert sent.status_code == 200

    polled = c.get(f"/api/match/{slug}/remote/commands", headers=headers)
    cmd = polled.get_json()["commands"][0]
    assert cmd["type"] == "overlay"
    assert cmd["prefs"]["sponsor_display_mode"] == "scroll_top"
    assert cmd["prefs"]["sponsor_size_scale"] == 1.8
