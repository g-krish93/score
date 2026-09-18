"""Mobile Stream app API helpers (session tokens)."""
from __future__ import annotations

import json
import os
from datetime import datetime, timedelta, timezone
from functools import wraps
from typing import Any, Callable

import redis as _redis_lib
from flask import jsonify, request
from itsdangerous import BadSignature, SignatureExpired, URLSafeTimedSerializer

from .models_cricrelay import Organization, RelayMatch, db

_redis_client = None


def redis_client():
    global _redis_client
    if _redis_client is None:
        _redis_client = _redis_lib.from_url(os.getenv("REDIS_URL", "redis://localhost:6379/0"))
    return _redis_client


def _stream_token_serializer():
    from flask import current_app

    return URLSafeTimedSerializer(current_app.config["SECRET_KEY"], salt="cricrelay-stream-api")


def stream_token_ttl_sec() -> int:
    try:
        return max(3600, int(os.getenv("STREAM_API_TOKEN_TTL_SEC", "1209600")))
    except ValueError:
        return 1209600


def issue_stream_token(org: Organization) -> str:
    return _stream_token_serializer().dumps({"oid": org.id})


def org_from_stream_token(token: str) -> Organization | None:
    try:
        payload = _stream_token_serializer().loads(token, max_age=stream_token_ttl_sec())
    except (SignatureExpired, BadSignature):
        return None
    oid = str((payload or {}).get("oid") or "").strip()
    if not oid:
        return None
    return db.session.get(Organization, oid)


def bearer_org_from_request() -> Organization | None:
    auth = (request.headers.get("Authorization") or "").strip()
    if auth.lower().startswith("bearer "):
        token = auth[7:].strip()
        return org_from_stream_token(token)
    return None


def stream_api_auth_required(view: Callable):
    @wraps(view)
    def wrapped(*args, **kwargs):
        org = bearer_org_from_request()
        if not org:
            return jsonify({"error": "unauthorized"}), 401
        return view(org, *args, **kwargs)

    return wrapped


def _youtube_oauth_serializer():
    from flask import current_app

    return URLSafeTimedSerializer(current_app.config["SECRET_KEY"], salt="cricrelay-youtube-oauth")


def issue_youtube_oauth_state(org_id: str) -> str:
    return _youtube_oauth_serializer().dumps({"oid": org_id})


def org_id_from_youtube_oauth_state(state: str) -> str | None:
    try:
        payload = _youtube_oauth_serializer().loads(state, max_age=900)
    except (SignatureExpired, BadSignature):
        return None
    oid = str((payload or {}).get("oid") or "").strip()
    return oid or None


def _remote_pair_serializer():
    from flask import current_app

    return URLSafeTimedSerializer(current_app.config["SECRET_KEY"], salt="cricrelay-remote-pair")


def _companion_session_serializer():
    from flask import current_app

    return URLSafeTimedSerializer(current_app.config["SECRET_KEY"], salt="cricrelay-companion-session")


REMOTE_PAIR_TOKEN_MAX_AGE = 300
COMPANION_TOKEN_MAX_AGE = 6 * 60 * 60

REMOTE_CONTROL_COMMANDS = {
    "start_broadcast",
    "stop_broadcast",
    "mute_mic",
    "toggle_focus_lock",
    "set_mute",
    "set_focus_lock",
    "toggle_sponsor",
    "set_zoom",
    "tap_focus",
    "set_stabilization",
    "pause_broadcast",
    "resume_broadcast",
    "take_live",
    "handoff_release",
    "handoff_take",
}

# Commands that require a validated payload object (others ignore payload).
REMOTE_CONTROL_PAYLOAD_COMMANDS = {
    "set_zoom",
    "tap_focus",
    "set_stabilization",
    "set_mute",
    "set_focus_lock",
    "take_live",
}

REMOTE_PREVIEW_MAX_BYTES = 80 * 1024
REMOTE_PREVIEW_TTL_SEC = 5
REMOTE_CAMERA_IDS = ("end_a", "end_b")
REMOTE_CAMERA_TTL_SEC = 45
REMOTE_HANDOFF_LOCK_TTL_SEC = 20
REMOTE_METRICS_TTL_SEC = 24 * 60 * 60
REMOTE_METRICS_MAX_EVENTS = 200
REMOTE_METRICS_ALLOWED = frozenset(
    {
        "pair_ok",
        "pair_fail",
        "preview_first_frame_ms",
        "command_ack_ms",
        "command_ack_timeout",
        "take_live",
        "share_watch",
    }
)
REMOTE_INGEST_TTL_SEC = 6 * 60 * 60


def companion_is_paired(slug: str) -> bool:
    """True when Redis still holds an active companion pairing for this match."""
    s = (slug or "").strip()
    if not s:
        return False
    return bool(redis_client().get(f"cricrelay:companion:{s}"))


def _coerce_bool(raw: Any) -> bool | None:
    if isinstance(raw, bool):
        return raw
    if isinstance(raw, (int, float)) and raw in (0, 1):
        return bool(raw)
    if isinstance(raw, str):
        v = raw.strip().lower()
        if v in {"1", "true", "yes", "on"}:
            return True
        if v in {"0", "false", "no", "off"}:
            return False
    return None


def sanitize_remote_control_payload(command: str, raw: Any) -> tuple[dict[str, Any] | None, str | None]:
    """Validate optional control payload. Returns (payload_or_None, error_or_None).

    Commands without a required payload may omit it (back-compat). Payload-bearing
    commands must supply a dict with the fields documented below.
    """
    if command not in REMOTE_CONTROL_PAYLOAD_COMMANDS:
        return None, None
    if not isinstance(raw, dict):
        return None, "payload required"
    if command == "set_zoom":
        try:
            level = float(raw.get("level"))
        except (TypeError, ValueError):
            return None, "level must be a number"
        if not (0.1 <= level <= 20.0):
            return None, "level out of range"
        return {"level": level}, None
    if command == "tap_focus":
        try:
            nx = float(raw.get("nx"))
            ny = float(raw.get("ny"))
        except (TypeError, ValueError):
            return None, "nx and ny must be numbers"
        if not (0.0 <= nx <= 1.0 and 0.0 <= ny <= 1.0):
            return None, "nx and ny must be in 0..1"
        return {"nx": nx, "ny": ny}, None
    if command == "set_stabilization":
        try:
            level = int(raw.get("level"))
        except (TypeError, ValueError):
            return None, "level must be an integer"
        if level not in (0, 1, 2):
            return None, "level must be 0, 1, or 2"
        return {"level": level}, None
    if command == "set_mute":
        muted = _coerce_bool(raw.get("muted"))
        if muted is None:
            return None, "muted must be a boolean"
        return {"muted": muted}, None
    if command == "set_focus_lock":
        locked = _coerce_bool(raw.get("locked"))
        if locked is None:
            return None, "locked must be a boolean"
        return {"locked": locked}, None
    if command == "take_live":
        cam = sanitize_camera_id(raw.get("camera_id"))
        if not cam:
            return None, "camera_id must be end_a or end_b"
        return {"camera_id": cam}, None
    return None, "invalid command"


def sanitize_camera_id(raw: Any) -> str | None:
    cam = str(raw or "").strip().lower()
    return cam if cam in REMOTE_CAMERA_IDS else None


def register_remote_camera(slug: str, camera_id: str, meta: dict[str, Any] | None = None) -> dict[str, Any]:
    """Heartbeat a tripod phone into the dual-end camera registry."""
    import time as _t

    s = (slug or "").strip()
    cam = sanitize_camera_id(camera_id)
    if not s or not cam:
        raise ValueError("slug and camera_id required")
    meta = dict(meta or {})
    row = {
        "camera_id": cam,
        "label": str(meta.get("label") or ("End A" if cam == "end_a" else "End B"))[:40],
        "streaming": bool(meta.get("streaming")),
        "last_seen": _t.time(),
    }
    r = redis_client()
    key = f"cricrelay:remote:cameras:{s}"
    r.hset(key, cam, json.dumps(row))
    r.expire(key, REMOTE_CAMERA_TTL_SEC * 4)
    return row


def list_remote_cameras(slug: str) -> list[dict[str, Any]]:
    import time as _t

    s = (slug or "").strip()
    if not s:
        return []
    r = redis_client()
    raw = r.hgetall(f"cricrelay:remote:cameras:{s}") or {}
    decoded: dict[str, Any] = {}
    for k, v in raw.items():
        key = k.decode() if isinstance(k, (bytes, bytearray)) else str(k)
        decoded[key] = v
    now = _t.time()
    live = get_live_camera(s)
    out: list[dict[str, Any]] = []
    for cam_id in REMOTE_CAMERA_IDS:
        blob = decoded.get(cam_id)
        if blob is None:
            continue
        try:
            row = json.loads(blob.decode() if isinstance(blob, (bytes, bytearray)) else blob)
        except (TypeError, json.JSONDecodeError):
            continue
        last_seen = float(row.get("last_seen") or 0)
        stale = (now - last_seen) > REMOTE_CAMERA_TTL_SEC
        out.append(
            {
                "camera_id": cam_id,
                "label": row.get("label") or cam_id,
                "streaming": bool(row.get("streaming")) and not stale,
                "stale": stale,
                "last_seen": last_seen,
                "is_live": live == cam_id,
            }
        )
    return out


def get_live_camera(slug: str) -> str | None:
    s = (slug or "").strip()
    if not s:
        return None
    raw = redis_client().get(f"cricrelay:remote:live:{s}")
    if not raw:
        return None
    cam = sanitize_camera_id(raw.decode() if isinstance(raw, (bytes, bytearray)) else raw)
    return cam


def set_live_camera(slug: str, camera_id: str | None) -> None:
    s = (slug or "").strip()
    if not s:
        return
    key = f"cricrelay:remote:live:{s}"
    r = redis_client()
    if not camera_id:
        r.delete(key)
        return
    cam = sanitize_camera_id(camera_id)
    if not cam:
        return
    r.setex(key, REMOTE_INGEST_TTL_SEC, cam)


def store_live_ingest(slug: str, ingest: dict[str, Any]) -> dict[str, Any]:
    """Persist shared RTMP ingest so a second end can take over the same destination."""
    s = (slug or "").strip()
    if not s:
        raise ValueError("slug required")
    rtmp_url = str(ingest.get("rtmp_url") or "").strip()
    stream_key = str(ingest.get("stream_key") or "").strip()
    if not rtmp_url or not stream_key:
        raise ValueError("rtmp_url and stream_key required")
    payload = {
        "rtmp_url": rtmp_url[:500],
        "stream_key": stream_key[:500],
        "watch_url": str(ingest.get("watch_url") or "").strip()[:500],
        "platform": str(ingest.get("platform") or "custom").strip().lower()[:32],
        "overlay_embed_url": str(ingest.get("overlay_embed_url") or "").strip()[:500],
    }
    redis_client().setex(
        f"cricrelay:remote:ingest:{s}",
        REMOTE_INGEST_TTL_SEC,
        json.dumps(payload),
    )
    return payload


def get_live_ingest(slug: str) -> dict[str, Any] | None:
    s = (slug or "").strip()
    if not s:
        return None
    raw = redis_client().get(f"cricrelay:remote:ingest:{s}")
    if not raw:
        return None
    try:
        return json.loads(raw.decode() if isinstance(raw, (bytes, bytearray)) else raw)
    except (TypeError, json.JSONDecodeError):
        return None


def clear_live_ingest(slug: str) -> None:
    s = (slug or "").strip()
    if not s:
        return
    r = redis_client()
    r.delete(f"cricrelay:remote:ingest:{s}")
    r.delete(f"cricrelay:remote:live:{s}")


def store_remote_metrics(slug: str, events: list[dict[str, Any]]) -> int:
    """Append privacy-safe companion metrics. Returns count accepted."""
    import time as _t

    s = (slug or "").strip()
    if not s or not events:
        return 0
    accepted: list[str] = []
    now = _t.time()
    for raw in events[:40]:
        if not isinstance(raw, dict):
            continue
        name = str(raw.get("name") or "").strip().lower()
        if name not in REMOTE_METRICS_ALLOWED:
            continue
        value = raw.get("value")
        row: dict[str, Any] = {"name": name, "ts": now}
        if value is not None:
            try:
                row["value"] = max(0, min(600_000, int(value)))
            except (TypeError, ValueError):
                pass
        accepted.append(json.dumps(row))
    if not accepted:
        return 0
    key = f"cricrelay:remote:metrics:{s}"
    r = redis_client()
    pipe = r.pipeline()
    pipe.rpush(key, *accepted)
    pipe.ltrim(key, -REMOTE_METRICS_MAX_EVENTS, -1)
    pipe.expire(key, REMOTE_METRICS_TTL_SEC)
    pipe.execute()
    return len(accepted)


def enqueue_remote_control(slug: str, command: str, payload: dict[str, Any] | None = None) -> None:
    import time as _t

    s = (slug or "").strip()
    if not s or not command:
        return
    envelope: dict[str, Any] = {"type": "control", "command": command, "ts": _t.time()}
    if payload:
        envelope["payload"] = payload
        cam = sanitize_camera_id(payload.get("camera_id"))
        if cam:
            envelope["camera_id"] = cam
    else:
        cam = None
    body = json.dumps(envelope)
    r = redis_client()
    if cam:
        key = f"cricrelay:remote:cmds:{s}:{cam}"
        r.rpush(key, body)
        r.expire(key, 600)
        return
    # Untargeted: deliver to both ends + legacy single-phone queue.
    for cid in REMOTE_CAMERA_IDS:
        key = f"cricrelay:remote:cmds:{s}:{cid}"
        r.rpush(key, body)
        r.expire(key, 600)
    legacy = f"cricrelay:remote:cmds:{s}"
    r.rpush(legacy, body)
    r.expire(legacy, 600)


def drain_remote_commands(slug: str, camera_id: str | None = None) -> list[dict[str, Any]]:
    """Pop pending remote commands for a tripod phone.

    Dual-end phones pass camera_id and read their dedicated queue. Legacy single-phone
    callers omit it and read the shared queue.
    """
    s = (slug or "").strip()
    if not s:
        return []
    cam = sanitize_camera_id(camera_id) if camera_id else None
    key = f"cricrelay:remote:cmds:{s}:{cam}" if cam else f"cricrelay:remote:cmds:{s}"
    r = redis_client()
    pipe = r.pipeline()
    pipe.lrange(key, 0, -1)
    pipe.delete(key)
    raw_list, _ = pipe.execute()
    out: list[dict[str, Any]] = []
    for item in raw_list:
        try:
            raw = item.decode() if isinstance(item, (bytes, bytearray)) else item
            out.append(json.loads(raw))
        except (TypeError, json.JSONDecodeError):
            continue
    return out


def enqueue_take_live(slug: str, target_camera_id: str) -> tuple[bool, str | None]:
    """Orchestrate dual-end handoff: soft-stop current publisher, start target on same ingest.

    Returns (ok, error_message).
    """
    s = (slug or "").strip()
    target = sanitize_camera_id(target_camera_id)
    if not s or not target:
        return False, "invalid camera"
    r = redis_client()
    lock_key = f"cricrelay:remote:handoff:{s}"
    # SET NX — reject overlapping Take Live taps.
    got = r.set(lock_key, target, nx=True, ex=REMOTE_HANDOFF_LOCK_TTL_SEC)
    if not got:
        return False, "handoff already in progress"
    try:
        current = get_live_camera(s)
        if current == target:
            return True, None
        if current and current != target:
            enqueue_remote_control(
                s,
                "handoff_release",
                {"camera_id": current},
            )
        enqueue_remote_control(
            s,
            "handoff_take",
            {"camera_id": target},
        )
        set_live_camera(s, target)
        return True, None
    finally:
        # Allow a follow-up take shortly; release early so a failed phone can retry.
        r.delete(lock_key)


def preview_redis_keys(slug: str, camera_id: str | None) -> tuple[str, str]:
    """JPEG + state Redis keys. Legacy (no camera) keys kept for single-phone back-compat."""
    s = (slug or "").strip()
    cam = sanitize_camera_id(camera_id) if camera_id else None
    if cam:
        return (
            f"cricrelay:remote:preview:{s}:{cam}",
            f"cricrelay:remote:camera:{s}:{cam}",
        )
    return (
        f"cricrelay:remote:preview:{s}",
        f"cricrelay:remote:camera:{s}",
    )


def sanitize_remote_camera_state(raw: Any) -> dict[str, Any]:
    """Clamp tripod-reported camera state for Redis sidecar / companion UI."""
    if not isinstance(raw, dict):
        raw = {}

    def _f(key: str, default: float, lo: float, hi: float) -> float:
        try:
            return max(lo, min(hi, float(raw.get(key, default))))
        except (TypeError, ValueError):
            return default

    def _b(key: str, default: bool = False) -> bool:
        val = raw.get(key, default)
        coerced = _coerce_bool(val)
        return default if coerced is None else coerced

    stab = 1
    try:
        stab = int(raw.get("stab", raw.get("stabilization_level", 1)))
    except (TypeError, ValueError):
        stab = 1
    stab = max(0, min(2, stab))

    thermal = 0
    try:
        thermal = int(raw.get("thermal", 0))
    except (TypeError, ValueError):
        thermal = 0
    thermal = max(0, min(6, thermal))

    bitrate = None
    try:
        if raw.get("bitrate_kbps") is not None:
            bitrate = max(0, min(50_000, int(raw.get("bitrate_kbps"))))
    except (TypeError, ValueError):
        bitrate = None

    zoom_min = _f("zoom_min", 1.0, 0.1, 20.0)
    zoom_max = _f("zoom_max", 8.0, zoom_min, 20.0)
    zoom = _f("zoom", 1.0, zoom_min, zoom_max)
    return {
        "zoom_min": zoom_min,
        "zoom_max": zoom_max,
        "zoom": zoom,
        "locked": _b("locked"),
        "muted": _b("muted"),
        "paused": _b("paused"),
        "streaming": _b("streaming"),
        "stab": stab,
        "reconnecting": _b("reconnecting"),
        "thermal": thermal,
        "bitrate_kbps": bitrate,
    }

REMOTE_SPONSOR_OVERLAY_KEYS = {
    "sponsor_enabled",
    "active_sponsor_id",
    "active_sponsor_ids",
    "sponsor_layout_mode",
    "sponsor_carousel_interval_sec",
    "sponsor_display_mode",
    "sponsor_position_x",
    "sponsor_position_y",
    "sponsor_size_scale",
    "sponsor_opacity",
    "sponsor_scroll_speed",
}


def issue_remote_pair_token(org: Organization, match_slug: str) -> str:
    return _remote_pair_serializer().dumps({"oid": org.id, "slug": match_slug})


def build_remote_pair_urls(
    public_base: str,
    match_slug: str,
    pair_token: str,
    api_base: str | None = None,
) -> dict[str, str]:
    """HTTPS App Link + custom-scheme deep link for companion QR payloads.

    System cameras open https more reliably than custom schemes; the /pair landing
    page then handsoff into ``cricrelay://pair`` when Universal/App Links are not yet verified.
    """
    from urllib.parse import urlencode

    slug = (match_slug or "").strip()
    token = (pair_token or "").strip()
    site = (public_base or "").strip().rstrip("/")
    api = (api_base or public_base or "").strip().rstrip("/")
    query = urlencode({"slug": slug, "token": token, "base": api})
    return {
        "pair_url": f"{site}/pair?{query}" if site else f"/pair?{query}",
        "deep_link": f"cricrelay://pair?{query}",
    }


def redeem_remote_pair_token(pair_token: str) -> dict | None:
    """Validates a scanned pairing token and issues a scoped companion session token.
    Overwrites any prior companion pairing for this match (one-active-companion policy)."""
    try:
        payload = _remote_pair_serializer().loads(pair_token, max_age=REMOTE_PAIR_TOKEN_MAX_AGE)
    except (SignatureExpired, BadSignature):
        return None
    org_id = str(payload.get("oid") or "").strip()
    slug = str(payload.get("slug") or "").strip()
    if not org_id or not slug:
        return None
    import uuid as _uuid

    jti = _uuid.uuid4().hex
    companion_token = _companion_session_serializer().dumps({"oid": org_id, "slug": slug, "jti": jti})
    redis_client().setex(f"cricrelay:companion:{slug}", COMPANION_TOKEN_MAX_AGE, jti)
    return {"companion_token": companion_token, "slug": slug}


def companion_token_required(view: Callable):
    @wraps(view)
    def wrapped(*args, **kwargs):
        auth = (request.headers.get("Authorization") or "").strip()
        if not auth.lower().startswith("bearer "):
            return jsonify({"error": "unauthorized"}), 401
        token = auth[7:].strip()
        try:
            payload = _companion_session_serializer().loads(token, max_age=COMPANION_TOKEN_MAX_AGE)
        except (SignatureExpired, BadSignature):
            return jsonify({"error": "unauthorized"}), 401
        slug = str(payload.get("slug") or "").strip()
        jti = str(payload.get("jti") or "").strip()
        current = redis_client().get(f"cricrelay:companion:{slug}")
        if not current or current.decode() != jti:
            return jsonify({"error": "pairing_superseded"}), 410
        return view(slug, str(payload.get("oid") or ""), *args, **kwargs)

    return wrapped


def _manual_scorer_serializer():
    from flask import current_app

    return URLSafeTimedSerializer(current_app.config["SECRET_KEY"], salt="cricrelay-manual-scorer")


def _manual_scorer_default_ttl() -> int:
    try:
        return int(os.getenv("MANUAL_SCORER_TOKEN_TTL_SEC", str(12 * 60 * 60)))
    except ValueError:
        return 12 * 60 * 60


# Long enough for a full match day; the app re-mints a fresh link every time
# the QR screen opens, so expiry only has to bound how long a leaked URL lives.
MANUAL_SCORER_TOKEN_MAX_AGE = _manual_scorer_default_ttl()

# Scorers legitimately pause between overs and at drinks; 10 minutes balances
# that against detecting a dead scorer page.
MANUAL_STALE_AFTER_SEC = int(os.getenv("MANUAL_STALE_AFTER_SEC", "600"))


def issue_manual_scorer_token(org: Organization, match_slug: str) -> str:
    return _manual_scorer_serializer().dumps({"oid": org.id, "slug": match_slug, "v": 1})


def manual_scorer_org_id(token: str, match_slug: str) -> str | None:
    try:
        payload = _manual_scorer_serializer().loads(token, max_age=MANUAL_SCORER_TOKEN_MAX_AGE)
    except (SignatureExpired, BadSignature):
        return None
    oid = str((payload or {}).get("oid") or "").strip()
    slug = str((payload or {}).get("slug") or "").strip()
    if not oid or not slug or slug != (match_slug or "").strip():
        return None
    return oid


def manual_scorer_match_for_token(token: str, match_slug: str) -> RelayMatch | None:
    """Resolve the manual-stream RelayMatch a scorer token grants access to."""
    oid = manual_scorer_org_id(token, match_slug) if token else None
    if not oid:
        return None
    row = RelayMatch.query.filter_by(organization_id=oid, score_match_slug=match_slug).first()
    if row is None or (row.relay_source or "") != "manual":
        return None
    return row


def manual_scorer_token_required(view: Callable):
    """Auth for the QR scorer state endpoints. Token from Bearer header or ?token=.

    Stateless by design (no Redis jti): match-day resilience beats
    single-active-scorer enforcement; the seq guard handles two-phone races.
    """

    @wraps(view)
    def wrapped(*args, **kwargs):
        match_slug = str(kwargs.pop("match_slug", "") or "")
        auth = (request.headers.get("Authorization") or "").strip()
        token = auth[7:].strip() if auth.lower().startswith("bearer ") else ""
        if not token:
            token = (request.args.get("token") or "").strip()
        row = manual_scorer_match_for_token(token, match_slug)
        if row is None:
            return jsonify({"error": "unauthorized"}), 403
        return view(row, match_slug, *args, **kwargs)

    return wrapped


def _twitch_oauth_serializer():
    from flask import current_app

    return URLSafeTimedSerializer(current_app.config["SECRET_KEY"], salt="cricrelay-twitch-oauth")


def issue_twitch_oauth_state(org_id: str) -> str:
    return _twitch_oauth_serializer().dumps({"oid": org_id})


def org_id_from_twitch_oauth_state(state: str) -> str | None:
    try:
        payload = _twitch_oauth_serializer().loads(state, max_age=900)
    except (SignatureExpired, BadSignature):
        return None
    oid = str((payload or {}).get("oid") or "").strip()
    return oid or None


def relay_match_for_org(org: Organization, match_slug: str) -> RelayMatch | None:
    slug = (match_slug or "").strip()
    if not slug:
        return None
    return RelayMatch.query.filter_by(organization_id=org.id, score_match_slug=slug).first()


def _parse_iso_ts(value: Any) -> datetime | None:
    if not value:
        return None
    try:
        if isinstance(value, str):
            ts = datetime.fromisoformat(value.replace("Z", "+00:00"))
        elif isinstance(value, datetime):
            ts = value if value.tzinfo else value.replace(tzinfo=timezone.utc)
        else:
            return None
        if ts.tzinfo is None:
            ts = ts.replace(tzinfo=timezone.utc)
        return ts
    except Exception:
        return None


def _load_match_state(slug: str) -> dict[str, Any]:
    try:
        from .app import state_path_for

        path = state_path_for(slug)
        if not path.is_file():
            return {}
        with path.open("r", encoding="utf-8") as fh:
            data = json.load(fh)
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}


def _relay_mode_to_app_mode(relay_mode: str) -> str:
    mode_map = {"play_cricket": "auto", "cricheroes": "auto", "manual": "manual", "pcs_ble": "ble"}
    return mode_map.get((relay_mode or "manual").strip().lower(), "manual")


def _last_scoring_at(state: dict[str, Any]) -> datetime | None:
    candidates = [
        _parse_iso_ts(state.get("relay_last_ok_at")),
        _parse_iso_ts(state.get("last_manual_at")),
    ]
    valid = [ts for ts in candidates if ts is not None]
    return max(valid) if valid else None


def _match_scoring_recently_active(slug: str, *, within_minutes: int = 8) -> bool:
    """True when scoring has updated recently."""
    state = _load_match_state(slug)
    relay_mode = (state.get("relay_mode") or "manual").strip().lower()
    last = _last_scoring_at(state)
    if not last:
        return False
    if relay_mode == "manual":
        return datetime.now(timezone.utc) - last <= timedelta(minutes=2)
    return datetime.now(timezone.utc) - last <= timedelta(minutes=within_minutes)


def scoring_status_for_slug(slug: str) -> dict[str, Any]:
    state = _load_match_state(slug)
    relay_mode = (state.get("relay_mode") or "manual").strip().lower()
    app_mode = _relay_mode_to_app_mode(relay_mode)
    relay_ts = _parse_iso_ts(state.get("relay_last_ok_at"))
    manual_ts = _parse_iso_ts(state.get("last_manual_at"))
    last = _last_scoring_at(state)
    now = datetime.now(timezone.utc)

    scoring_active = False
    if relay_mode == "manual":
        scoring_active = manual_ts is not None and now - manual_ts <= timedelta(minutes=2)
    elif relay_mode in {"play_cricket", "cricheroes", "pcs_ble"}:
        scoring_active = relay_ts is not None and now - relay_ts <= timedelta(minutes=8)

    scoring_stale = False
    if relay_mode in {"play_cricket", "cricheroes"}:
        if relay_ts is None:
            scoring_stale = True
        else:
            scoring_stale = now - relay_ts > timedelta(minutes=3)
    elif relay_mode == "manual" and state.get("manual_totals"):
        # Only QR-scored streams carry manual_totals; before setup there is
        # nothing to be stale relative to.
        scoring_stale = manual_ts is None or now - manual_ts > timedelta(
            seconds=MANUAL_STALE_AFTER_SEC
        )

    return {
        "scoring_mode": app_mode,
        "relay_mode": relay_mode,
        "scoring_active": scoring_active,
        "scoring_stale": scoring_stale,
        "last_scoring_at": last.isoformat() if last else None,
    }


def broadcast_status_for_match(org: Organization, slug: str, state: dict[str, Any] | None = None) -> dict[str, Any]:
    st = state if state is not None else _load_match_state(slug)
    status = str(st.get("broadcast_status") or "idle").strip().lower()
    if status not in {"idle", "streaming", "paused"}:
        status = "idle"
    platform = st.get("broadcast_platform")
    watch_url = st.get("broadcast_watch_url")
    updated_at = st.get("broadcast_updated_at")

    yt_slug = (org.youtube_active_match_slug or "").strip()
    tw_slug = (org.twitch_active_match_slug or "").strip()
    if status == "idle":
        if yt_slug == slug and org.youtube_active_broadcast_id:
            status = "streaming"
            platform = platform or "youtube"
        elif tw_slug == slug:
            status = "streaming"
            platform = platform or "twitch"

    return {
        "status": status,
        "platform": platform,
        "watch_url": watch_url,
        "updated_at": updated_at,
    }


def match_day_status(org: Organization, row: RelayMatch) -> dict[str, Any]:
    slug = row.score_match_slug
    state = _load_match_state(slug)
    scoring = scoring_status_for_slug(slug)
    broadcast = broadcast_status_for_match(org, slug, state)
    base = (os.getenv("PUBLIC_BASE_URL") or "").strip().rstrip("/")
    return {
        "slug": slug,
        "label": row.label or row.play_cricket_match_id,
        "relay_source": getattr(row, "relay_source", None) or "scraper",
        "relay_paused": bool(row.paused),
        "paused": bool(row.paused),
        **scoring,
        "broadcast": broadcast,
        "companion_paired": companion_is_paired(slug),
        "manual_scorer_url": f"{base}/m/{slug}/score" if base else f"/m/{slug}/score",
        "overlay_embed_url": f"{base}/m/{slug}/stream?embed=1" if base else f"/m/{slug}/stream?embed=1",
    }


def stream_dict_for_relay_match(org: Organization, m: RelayMatch) -> dict[str, Any]:
    slug = m.score_match_slug
    base = (os.getenv("PUBLIC_BASE_URL") or "").strip().rstrip("/")
    overlay = f"{base}/m/{slug}/stream?embed=1" if base else f"/m/{slug}/stream?embed=1"
    scoring = scoring_status_for_slug(slug)
    broadcast = broadcast_status_for_match(org, slug)
    dest_id = getattr(m, "stream_destination_id", None)
    destination = None
    if dest_id:
        from .models_cricrelay import StreamDestination

        dest = StreamDestination.query.filter_by(id=dest_id, organization_id=org.id).first()
        if dest:
            destination = {"id": dest.id, "label": dest.label or ""}
    return {
        "id": m.id,
        "slug": slug,
        "label": m.label or m.play_cricket_match_id,
        "play_cricket_match_id": m.play_cricket_match_id,
        "relay_source": getattr(m, "relay_source", None) or "scraper",
        "paused": bool(m.paused),
        "relay_paused": bool(m.paused),
        "overlay_embed_url": overlay,
        "is_live": _match_scoring_recently_active(slug),
        "stream_destination_id": dest_id or None,
        "destination": destination,
        **scoring,
        "broadcast": broadcast,
    }


def relay_matches_for_org(org: Organization) -> list[dict[str, Any]]:
    rows = (
        RelayMatch.query.filter_by(organization_id=org.id)
        .order_by(RelayMatch.created_at.desc())
        .all()
    )
    return [stream_dict_for_relay_match(org, m) for m in rows]
