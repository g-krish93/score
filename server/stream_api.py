"""Mobile Stream app API helpers (session tokens)."""
from __future__ import annotations

import json
import os
from datetime import datetime, timedelta, timezone
from functools import wraps
from typing import Any, Callable

from flask import jsonify, request
from itsdangerous import BadSignature, SignatureExpired, URLSafeTimedSerializer

from .models_cricrelay import Organization, RelayMatch, db


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
