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
        self._hashes: dict[str, dict[str, str]] = {}

    def setex(self, key: str, _ttl: int, value: str) -> None:
        self._kv[key] = value

    def set(self, key: str, value: str, nx: bool = False, ex: int | None = None) -> bool:
        if nx and key in self._kv:
            return False
        self._kv[key] = value
        return True

    def get(self, key: str) -> bytes | None:
        val = self._kv.get(key)
        return val.encode() if val is not None else None

    def delete(self, *keys: str) -> int:
        n = 0
        for key in keys:
            if key in self._kv:
                del self._kv[key]
                n += 1
            if key in self._lists:
                del self._lists[key]
                n += 1
            if key in self._hashes:
                del self._hashes[key]
                n += 1
        return n

    def rpush(self, key: str, *values: str) -> None:
        self._lists.setdefault(key, []).extend(values)

    def ltrim(self, key: str, start: int, end: int) -> None:
        lst = self._lists.get(key, [])
        if not lst:
            return
        # Redis LTRIM is inclusive; negative indexes count from the end.
        n = len(lst)
        if start < 0:
            start = max(0, n + start)
        if end < 0:
            end = n + end
        end = min(end, n - 1)
        if start > end or start >= n:
            self._lists[key] = []
        else:
            self._lists[key] = lst[start : end + 1]

    def lrange(self, key: str, start: int, end: int) -> list[str]:
        lst = self._lists.get(key, [])
        if not lst:
            return []
        n = len(lst)
        if start < 0:
            start = max(0, n + start)
        if end < 0:
            end = n + end
        end = min(end, n - 1)
        if start > end or start >= n:
            return []
        return lst[start : end + 1]

    def expire(self, _key: str, _ttl: int) -> None:
        return None

    def hset(self, key: str, field: str, value: str) -> None:
        self._hashes.setdefault(key, {})[field] = value

    def hgetall(self, key: str) -> dict[str, str]:
        return dict(self._hashes.get(key) or {})

    def pipeline(self) -> "_FakePipeline":
        return _FakePipeline(self)


class _FakePipeline:
    def __init__(self, redis: _FakeRedis) -> None:
        self._redis = redis
        self._ops: list[tuple] = []

    def lrange(self, key: str, _start: int, _end: int) -> "_FakePipeline":
        self._ops.append(("lrange", key))
        return self

    def delete(self, key: str) -> "_FakePipeline":
        self._ops.append(("delete", key))
        return self

    def rpush(self, key: str, *values: str) -> "_FakePipeline":
        self._ops.append(("rpush", key, values))
        return self

    def ltrim(self, key: str, start: int, end: int) -> "_FakePipeline":
        self._ops.append(("ltrim", key, start, end))
        return self

    def expire(self, key: str, _ttl: int) -> "_FakePipeline":
        self._ops.append(("expire", key))
        return self

    def execute(self) -> list:
        results = []
        for op in self._ops:
            kind = op[0]
            if kind == "lrange":
                results.append(self._redis._lists.get(op[1], []))
            elif kind == "delete":
                self._redis._lists.pop(op[1], None)
                self._redis._kv.pop(op[1], None)
                results.append(1)
            elif kind == "rpush":
                _, key, values = op
                self._redis.rpush(key, *values)
                results.append(len(self._redis._lists.get(key, [])))
            elif kind == "ltrim":
                _, key, start, end = op
                self._redis.ltrim(key, start, end)
                results.append(True)
            elif kind == "expire":
                results.append(True)
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


def test_remote_payload_commands_and_preview(client):
    """Payload-bearing camera commands + JPEG preview put/get auth boundaries."""
    import base64

    c, fake = client
    _org_id, token, slug = _seed_org_and_match()
    headers = _auth_headers(token)

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

    # Unknown command still 400
    bad_cmd = c.post(
        f"/api/match/{slug}/remote/command",
        headers=cmd_headers,
        data=json.dumps({"type": "control", "command": "explode"}),
    )
    assert bad_cmd.status_code == 400

    # set_zoom requires payload
    missing = c.post(
        f"/api/match/{slug}/remote/command",
        headers=cmd_headers,
        data=json.dumps({"type": "control", "command": "set_zoom"}),
    )
    assert missing.status_code == 400

    # set_zoom with valid payload
    zoom = c.post(
        f"/api/match/{slug}/remote/command",
        headers=cmd_headers,
        data=json.dumps(
            {"type": "control", "command": "set_zoom", "payload": {"level": 2.5}}
        ),
    )
    assert zoom.status_code == 200

    # tap_focus out of range
    bad_tap = c.post(
        f"/api/match/{slug}/remote/command",
        headers=cmd_headers,
        data=json.dumps(
            {"type": "control", "command": "tap_focus", "payload": {"nx": 1.5, "ny": 0.2}}
        ),
    )
    assert bad_tap.status_code == 400

    tap = c.post(
        f"/api/match/{slug}/remote/command",
        headers=cmd_headers,
        data=json.dumps(
            {"type": "control", "command": "tap_focus", "payload": {"nx": 0.4, "ny": 0.6}}
        ),
    )
    assert tap.status_code == 200

    stab = c.post(
        f"/api/match/{slug}/remote/command",
        headers=cmd_headers,
        data=json.dumps(
            {"type": "control", "command": "set_stabilization", "payload": {"level": 2}}
        ),
    )
    assert stab.status_code == 200

    pause = c.post(
        f"/api/match/{slug}/remote/command",
        headers=cmd_headers,
        data=json.dumps({"type": "control", "command": "pause_broadcast"}),
    )
    assert pause.status_code == 200

    mute = c.post(
        f"/api/match/{slug}/remote/command",
        headers=cmd_headers,
        data=json.dumps(
            {"type": "control", "command": "set_mute", "payload": {"muted": True}}
        ),
    )
    assert mute.status_code == 200

    bad_mute = c.post(
        f"/api/match/{slug}/remote/command",
        headers=cmd_headers,
        data=json.dumps({"type": "control", "command": "set_mute", "payload": {}}),
    )
    assert bad_mute.status_code == 400

    lock = c.post(
        f"/api/match/{slug}/remote/command",
        headers=cmd_headers,
        data=json.dumps(
            {"type": "control", "command": "set_focus_lock", "payload": {"locked": False}}
        ),
    )
    assert lock.status_code == 200

    polled = c.get(f"/api/match/{slug}/remote/commands", headers=headers)
    assert polled.status_code == 200
    body = polled.get_json()
    assert body["companion_paired"] is True
    commands = body["commands"]
    by_cmd = {cmd["command"]: cmd for cmd in commands}
    assert by_cmd["set_mute"]["payload"]["muted"] is True
    assert by_cmd["set_focus_lock"]["payload"]["locked"] is False
    assert by_cmd["set_zoom"]["payload"]["level"] == 2.5
    assert by_cmd["tap_focus"]["payload"] == {"nx": 0.4, "ny": 0.6}
    assert by_cmd["set_stabilization"]["payload"]["level"] == 2
    assert "payload" not in by_cmd["pause_broadcast"]

    # Match-day reports companion_paired
    day = c.get(f"/api/match/{slug}/match-day", headers=headers)
    assert day.status_code == 200
    assert day.get_json()["companion_paired"] is True


def test_dual_end_take_live_handoff(client):
    """take_live soft-stops the live end and starts the other on dedicated queues."""
    c, fake = client
    _org_id, token, slug = _seed_org_and_match()
    headers = _auth_headers(token)

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

    # Seed ingest + live end A
    ingest = c.put(
        f"/api/match/{slug}/remote/ingest",
        headers=headers,
        data=json.dumps(
            {
                "rtmp_url": "rtmp://a.rtmp.youtube.com/live2",
                "stream_key": "secret-key",
                "watch_url": "https://youtube.com/watch?v=abc",
                "platform": "youtube",
                "camera_id": "end_a",
            }
        ),
        content_type="application/json",
    )
    assert ingest.status_code == 200

    take = c.post(
        f"/api/match/{slug}/remote/command",
        headers=cmd_headers,
        data=json.dumps(
            {
                "type": "control",
                "command": "take_live",
                "payload": {"camera_id": "end_b"},
            }
        ),
    )
    assert take.status_code == 200
    assert take.get_json()["live_camera_id"] == "end_b"

    # End A receives handoff_release; End B receives handoff_take
    a_poll = c.get(f"/api/match/{slug}/remote/commands?camera_id=end_a", headers=headers)
    assert a_poll.status_code == 200
    a_cmds = [x["command"] for x in a_poll.get_json()["commands"]]
    assert "handoff_release" in a_cmds

    b_poll = c.get(f"/api/match/{slug}/remote/commands?camera_id=end_b", headers=headers)
    assert b_poll.status_code == 200
    b_cmds = [x["command"] for x in b_poll.get_json()["commands"]]
    assert "handoff_take" in b_cmds

    got = c.get(f"/api/match/{slug}/remote/ingest", headers=headers)
    assert got.status_code == 200
    assert got.get_json()["ingest"]["stream_key"] == "secret-key"
    assert got.get_json()["live_camera_id"] == "end_b"

    cams = c.get(f"/api/match/{slug}/remote/cameras", headers=cmd_headers)
    assert cams.status_code == 200
    assert cams.get_json()["live_camera_id"] == "end_b"


def test_remote_preview_per_camera(client):
    import base64

    c, _fake = client
    _org_id, token, slug = _seed_org_and_match()
    headers = _auth_headers(token)
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

    jpeg = bytes([0xFF, 0xD8, 0xFF, 0xD9])
    put = c.put(
        f"/api/match/{slug}/remote/preview",
        headers=headers,
        data=json.dumps(
            {
                "jpeg_b64": base64.b64encode(jpeg).decode("ascii"),
                "camera_id": "end_b",
                "state": {"streaming": False, "zoom": 1.5},
            }
        ),
        content_type="application/json",
    )
    assert put.status_code == 200
    assert put.get_json()["camera_id"] == "end_b"

    got = c.get(f"/api/match/{slug}/remote/preview?camera_id=end_b", headers=cmd_headers)
    assert got.status_code == 200
    assert got.get_json()["stale"] is False
    assert got.get_json()["state"]["zoom"] == 1.5
    assert got.get_json()["camera_id"] == "end_b"

    cams = c.get(f"/api/match/{slug}/remote/cameras", headers=cmd_headers)
    assert cams.status_code == 200
    ids = {row["camera_id"] for row in cams.get_json()["cameras"]}
    assert "end_b" in ids

    # Legacy / default put still works (defaults to end_a)
    put_a = c.put(
        f"/api/match/{slug}/remote/preview",
        headers=headers,
        data=json.dumps(
            {
                "jpeg_b64": base64.b64encode(jpeg).decode("ascii"),
                "state": {
                    "zoom_min": 0.5,
                    "zoom_max": 8,
                    "zoom": 2.0,
                    "locked": True,
                    "muted": False,
                    "paused": False,
                    "streaming": True,
                    "stab": 1,
                },
            }
        ),
        content_type="application/json",
    )
    assert put_a.status_code == 200

    # Companion can GET default (live/end_a)
    got_default = c.get(f"/api/match/{slug}/remote/preview", headers=cmd_headers)
    assert got_default.status_code == 200
    body = got_default.get_json()
    assert body["stale"] is False
    assert body["state"]["zoom"] == 2.0
    assert body["state"]["locked"] is True

    # Companion cannot PUT preview (stream-auth required)
    forbidden = c.put(
        f"/api/match/{slug}/remote/preview",
        headers=cmd_headers,
        data=json.dumps({"jpeg_b64": base64.b64encode(jpeg).decode("ascii"), "state": {}}),
        content_type="application/json",
    )
    assert forbidden.status_code == 401

    # Oversized preview rejected
    big = bytes([0xFF, 0xD8]) + (b"\x00" * (80 * 1024)) + bytes([0xFF, 0xD9])
    too_big = c.put(
        f"/api/match/{slug}/remote/preview",
        headers=headers,
        data=json.dumps({"jpeg_b64": base64.b64encode(big).decode("ascii"), "state": {}}),
        content_type="application/json",
    )
    assert too_big.status_code == 413

    # Mute without payload still works (back-compat)
    mute = c.post(
        f"/api/match/{slug}/remote/command",
        headers=cmd_headers,
        data=json.dumps({"type": "control", "command": "mute_mic"}),
    )
    assert mute.status_code == 200


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


def test_pair_landing_and_well_known(client):
    c, _fake = client
    missing = c.get("/pair")
    assert missing.status_code == 400

    landing = c.get("/pair?slug=demo-match&token=tok123&base=https://cricrelay.co.uk")
    assert landing.status_code == 200
    body = landing.get_data(as_text=True)
    assert "cricrelay://pair?" in body
    assert "slug=demo-match" in body
    assert "Open in CricRelay" in body

    assets = c.get("/.well-known/assetlinks.json")
    assert assets.status_code == 200
    assert assets.is_json
    rows = assets.get_json()
    assert rows[0]["target"]["package_name"] == "uk.co.cricrelay.stream"

    aasa = c.get("/.well-known/apple-app-site-association")
    assert aasa.status_code == 200
    assert aasa.is_json
    details = aasa.get_json()["applinks"]["details"]
    assert "/pair" in details[0]["paths"]


def test_pair_api_returns_https_url(client):
    c, _fake = client
    _org_id, token, slug = _seed_org_and_match()
    headers = _auth_headers(token)
    pair = c.post(f"/api/match/{slug}/pair", headers=headers)
    assert pair.status_code == 200
    data = pair.get_json()
    assert data["pair_token"]
    assert data["pair_url"].startswith("http")
    assert "/pair?" in data["pair_url"]
    assert data["deep_link"].startswith("cricrelay://pair?")


def test_take_live_handoff_race_returns_conflict(client):
    """Overlapping Take Live taps must not double-publish — second call gets 409."""
    c, fake = client
    _org_id, token, slug = _seed_org_and_match()
    headers = _auth_headers(token)
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

    # Seed ingest + live end_a
    put = c.put(
        f"/api/match/{slug}/remote/ingest",
        headers=headers,
        data=json.dumps(
            {
                "rtmp_url": "rtmps://a.rtmp.youtube.com/live2",
                "stream_key": "secret-key",
                "watch_url": "https://youtu.be/demo",
                "platform": "youtube",
                "camera_id": "end_a",
            }
        ),
        content_type="application/json",
    )
    assert put.status_code == 200

    # Hold the handoff lock so a concurrent take_live fails closed.
    fake.set(f"cricrelay:remote:handoff:{slug}", "end_b", nx=True, ex=20)

    conflict = c.post(
        f"/api/match/{slug}/remote/command",
        headers=cmd_headers,
        data=json.dumps(
            {"type": "control", "command": "take_live", "payload": {"camera_id": "end_b"}}
        ),
    )
    assert conflict.status_code == 409
    assert "handoff" in conflict.get_json()["error"].lower()


def test_remote_metrics_accepts_privacy_safe_events(client):
    c, fake = client
    _org_id, token, slug = _seed_org_and_match()
    headers = _auth_headers(token)
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

    ok = c.post(
        f"/api/match/{slug}/remote/metrics",
        headers=cmd_headers,
        data=json.dumps(
            {
                "events": [
                    {"name": "pair_ok"},
                    {"name": "preview_first_frame_ms", "value": 842},
                    {"name": "user_email", "value": "nope"},  # rejected
                ]
            }
        ),
    )
    assert ok.status_code == 200
    assert ok.get_json()["accepted"] == 2

    raw = fake.lrange(f"cricrelay:remote:metrics:{slug}", 0, -1)
    assert len(raw) == 2
    names = {json.loads(row)["name"] for row in raw}
    assert names == {"pair_ok", "preview_first_frame_ms"}

    bad = c.post(
        f"/api/match/{slug}/remote/metrics",
        headers=cmd_headers,
        data=json.dumps({"events": "nope"}),
    )
    assert bad.status_code == 400
