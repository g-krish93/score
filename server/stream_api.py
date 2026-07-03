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
    "toggle_sponsor",
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
        "manual_scorer_url": f"{base}/m/{slug}/score" if base else f"/m/{slug}/score",
        "overlay_embed_url": f"{base}/m/{slug}/stream?embed=1" if base else f"/m/{slug}/stream?embed=1",
    }


def stream_dict_for_relay_match(org: Organization, m: RelayMatch) -> dict[str, Any]:
    slug = m.score_match_slug
    base = (os.getenv("PUBLIC_BASE_URL") or "").strip().rstrip("/")
    overlay = f"{base}/m/{slug}/stream?embed=1" if base else f"/m/{slug}/stream?embed=1"
    scoring = scoring_status_for_slug(slug)
    broadcast = broadcast_status_for_match(org, slug)
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
