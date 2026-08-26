"""Saved RTMP destinations vault: CRUD, assign to match, encrypt at rest."""
from __future__ import annotations

import os
import tempfile
import uuid

import pytest

_TMP = tempfile.mkdtemp(prefix="cr_destinations_test_")
os.environ["STATE_DIR"] = _TMP
os.environ["DATABASE_URL"] = f"sqlite:///{os.path.join(_TMP, 'test.db')}"
os.environ.setdefault("SECRET_KEY", "test-secret-destinations")

import server.app as app_mod  # noqa: E402
from server.app import app  # noqa: E402
from server.models_cricrelay import Organization, RelayMatch, StreamDestination, db  # noqa: E402
from server.stream_api import issue_stream_token  # noqa: E402
from server import youtube_stream as yt  # noqa: E402


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
            slug=f"dest-club-{_seq}",
            name=f"Dest Club {_seq}",
            email=f"dest-{_seq}@example.com",
        )
        org.set_password("pw")
        db.session.add(org)
        db.session.commit()
        return org.id, issue_stream_token(org)


def _seed_match(org_id: str) -> str:
    global _seq
    _seq += 1
    slug = f"dest-stream-{_seq}"
    with app.app_context():
        match = RelayMatch(
            id=str(uuid.uuid4()),
            organization_id=org_id,
            play_cricket_match_id=f"7777{_seq}",
            full_scrape_url=f"https://example.play-cricket.com/website/results/7777{_seq}",
            score_match_slug=slug,
            label=f"XI {_seq}",
        )
        db.session.add(match)
        db.session.commit()
    return slug


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}


def test_create_list_get_destination(client):
    org_id, token = _seed_org()
    resp = client.post(
        "/api/stream/destinations",
        headers=_auth(token),
        json={
            "label": "1st XI YouTube",
            "rtmp_url": "rtmp://a.rtmp.youtube.com/live2",
            "stream_key": "xxxx-secret-key-1234",
            "watch_url": "https://youtube.com/watch?v=abc",
        },
    )
    assert resp.status_code == 201, resp.get_json()
    dest = resp.get_json()["destination"]
    assert dest["label"] == "1st XI YouTube"
    assert dest["stream_key_masked"].endswith("1234")
    assert "stream_key" not in dest

    listed = client.get("/api/stream/destinations", headers=_auth(token))
    assert listed.status_code == 200
    assert len(listed.get_json()["destinations"]) == 1

    full = client.get(f"/api/stream/destinations/{dest['id']}", headers=_auth(token))
    assert full.status_code == 200
    body = full.get_json()["destination"]
    assert body["stream_key"] == "xxxx-secret-key-1234"

    with app.app_context():
        row = StreamDestination.query.filter_by(id=dest["id"]).first()
        assert row is not None
        assert row.stream_key_enc != "xxxx-secret-key-1234"
        assert yt.decrypt_token(row.stream_key_enc) == "xxxx-secret-key-1234"
        assert row.organization_id == org_id


def test_assign_destination_to_stream(client):
    org_id, token = _seed_org()
    slug = _seed_match(org_id)
    created = client.post(
        "/api/stream/destinations",
        headers=_auth(token),
        json={
            "label": "2nd XI",
            "rtmp_url": "rtmps://a.rtmp.youtube.com/live2",
            "stream_key": "second-xi-key",
        },
    )
    dest_id = created.get_json()["destination"]["id"]

    patch = client.patch(
        f"/api/streams/{slug}",
        headers=_auth(token),
        json={"stream_destination_id": dest_id},
    )
    assert patch.status_code == 200, patch.get_json()
    stream = patch.get_json()["stream"]
    assert stream["stream_destination_id"] == dest_id
    assert stream["destination"]["label"] == "2nd XI"

    listed = client.get("/api/streams", headers=_auth(token))
    row = next(s for s in listed.get_json()["streams"] if s["slug"] == slug)
    assert row["destination"]["id"] == dest_id


def test_delete_destination_clears_match_fk(client):
    org_id, token = _seed_org()
    slug = _seed_match(org_id)
    created = client.post(
        "/api/stream/destinations",
        headers=_auth(token),
        json={
            "label": "Temp",
            "rtmp_url": "rtmp://a.rtmp.youtube.com/live2",
            "stream_key": "temp-key",
        },
    )
    dest_id = created.get_json()["destination"]["id"]
    client.patch(
        f"/api/streams/{slug}",
        headers=_auth(token),
        json={"stream_destination_id": dest_id},
    )
    deleted = client.delete(f"/api/stream/destinations/{dest_id}", headers=_auth(token))
    assert deleted.status_code == 200

    listed = client.get("/api/streams", headers=_auth(token))
    row = next(s for s in listed.get_json()["streams"] if s["slug"] == slug)
    assert row.get("stream_destination_id") in (None, "")
    assert row.get("destination") is None
