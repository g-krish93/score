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


def _match_scoring_recently_active(slug: str, *, within_minutes: int = 8) -> bool:
    """True when Play-Cricket relay has updated recently (proxy for an active match day)."""
    try:
        from .app import state_path_for

        path = state_path_for(slug)
        if not path.is_file():
            return False
        with path.open("r", encoding="utf-8") as fh:
            state = json.load(fh)
        ok_at = state.get("relay_last_ok_at")
        if not ok_at:
            return False
        if isinstance(ok_at, str):
            ts = datetime.fromisoformat(ok_at.replace("Z", "+00:00"))
        elif isinstance(ok_at, datetime):
            ts = ok_at if ok_at.tzinfo else ok_at.replace(tzinfo=timezone.utc)
        else:
            return False
        if ts.tzinfo is None:
            ts = ts.replace(tzinfo=timezone.utc)
        return datetime.now(timezone.utc) - ts <= timedelta(minutes=within_minutes)
    except Exception:
        return False


def relay_matches_for_org(org: Organization) -> list[dict[str, Any]]:
    rows = (
        RelayMatch.query.filter_by(organization_id=org.id)
        .order_by(RelayMatch.created_at.desc())
        .all()
    )
    base = (os.getenv("PUBLIC_BASE_URL") or "").strip().rstrip("/")
    out = []
    for m in rows:
        slug = m.score_match_slug
        overlay = f"{base}/m/{slug}/stream?embed=1" if base else f"/m/{slug}/stream?embed=1"
        out.append(
            {
                "id": m.id,
                "slug": slug,
                "label": m.label or m.play_cricket_match_id,
                "play_cricket_match_id": m.play_cricket_match_id,
                "relay_source": getattr(m, "relay_source", None) or "scraper",
                "paused": bool(m.paused),
                "overlay_embed_url": overlay,
                "is_live": _match_scoring_recently_active(slug),
            }
        )
    return out
