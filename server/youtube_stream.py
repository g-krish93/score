"""YouTube OAuth + Live Streaming API helpers for CricRelay Stream."""
from __future__ import annotations

import base64
import hashlib
import json
import os
import secrets
import urllib.parse
from datetime import datetime, timezone
from typing import Any

import requests
from cryptography.fernet import Fernet, InvalidToken

YOUTUBE_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
YOUTUBE_TOKEN_URL = "https://oauth2.googleapis.com/token"
YOUTUBE_API = "https://www.googleapis.com/youtube/v3"

# Full account management (required for liveBroadcasts + liveStreams). Read-only is not enough.
OAUTH_SCOPE_MANAGE = "https://www.googleapis.com/auth/youtube"
SCOPES = OAUTH_SCOPE_MANAGE


def oauth_scopes() -> list[str]:
    return [OAUTH_SCOPE_MANAGE]


def _fernet() -> Fernet | None:
    raw = (os.getenv("YOUTUBE_TOKEN_ENCRYPTION_KEY") or os.getenv("SECRET_KEY") or "").strip()
    if not raw:
        return None
    digest = hashlib.sha256(raw.encode("utf-8")).digest()
    key = base64.urlsafe_b64encode(digest)
    return Fernet(key)


def encrypt_token(plain: str) -> str:
    if not plain:
        return ""
    f = _fernet()
    if not f:
        return plain
    return f.encrypt(plain.encode("utf-8")).decode("ascii")


def decrypt_token(cipher: str) -> str:
    if not cipher:
        return ""
    f = _fernet()
    if not f:
        return cipher
    try:
        return f.decrypt(cipher.encode("ascii")).decode("utf-8")
    except InvalidToken:
        return ""


def _client_id() -> str:
    for key in ("YOUTUBE_CLIENT_ID", "GOOGLE_CLIENT_ID", "OAUTH_CLIENT_ID"):
        val = (os.getenv(key) or "").strip()
        if val:
            return val
    return ""


def _client_secret() -> str:
    for key in ("YOUTUBE_CLIENT_SECRET", "GOOGLE_CLIENT_SECRET", "OAUTH_CLIENT_SECRET"):
        val = (os.getenv(key) or "").strip()
        if val:
            return val
    return ""


def oauth_configured() -> bool:
    return bool(_client_id() and _client_secret())


def build_authorize_url(redirect_uri: str, state: str) -> str:
    params = {
        "client_id": _client_id(),
        "redirect_uri": redirect_uri,
        "response_type": "code",
        "scope": SCOPES,
        "access_type": "offline",
        "prompt": "consent",
        "state": state,
    }
    return f"{YOUTUBE_AUTH_URL}?{urllib.parse.urlencode(params)}"


def exchange_code(code: str, redirect_uri: str) -> dict[str, Any]:
    resp = requests.post(
        YOUTUBE_TOKEN_URL,
        data={
            "code": code,
            "client_id": _client_id(),
            "client_secret": _client_secret(),
            "redirect_uri": redirect_uri,
            "grant_type": "authorization_code",
        },
        timeout=30,
    )
    resp.raise_for_status()
    return resp.json()


def refresh_access_token(refresh_token: str) -> dict[str, Any]:
    resp = requests.post(
        YOUTUBE_TOKEN_URL,
        data={
            "client_id": _client_id(),
            "client_secret": _client_secret(),
            "refresh_token": refresh_token,
            "grant_type": "refresh_token",
        },
        timeout=30,
    )
    resp.raise_for_status()
    return resp.json()


def youtube_get(access_token: str, path: str, params: dict | None = None) -> dict[str, Any]:
    q = dict(params or {})
    q["access_token"] = access_token
    url = f"{YOUTUBE_API}/{path.lstrip('/')}"
    resp = requests.get(url, params=q, timeout=30)
    resp.raise_for_status()
    return resp.json()


def youtube_post(access_token: str, path: str, body: dict, params: dict | None = None) -> dict[str, Any]:
    q = dict(params or {})
    q["access_token"] = access_token
    q["part"] = q.get("part", "snippet,status,contentDetails")
    url = f"{YOUTUBE_API}/{path.lstrip('/')}"
    resp = requests.post(url, params=q, json=body, timeout=30)
    resp.raise_for_status()
    return resp.json()


def youtube_put(access_token: str, path: str, body: dict, params: dict | None = None) -> dict[str, Any]:
    q = dict(params or {})
    q["access_token"] = access_token
    url = f"{YOUTUBE_API}/{path.lstrip('/')}"
    resp = requests.put(url, params=q, json=body, timeout=30)
    resp.raise_for_status()
    return resp.json()


def fetch_channel_for_token(access_token: str) -> tuple[str, str]:
    data = youtube_get(access_token, "channels", {"part": "snippet", "mine": "true"})
    items = data.get("items") or []
    if not items:
        raise ValueError("No YouTube channel found for this Google account")
    ch = items[0]
    return ch["id"], (ch.get("snippet") or {}).get("title") or "YouTube channel"


def _youtube_error_message(exc: requests.HTTPError) -> str:
    try:
        body = exc.response.json()
        err = body.get("error") if isinstance(body, dict) else None
        if isinstance(err, dict):
            return str(err.get("message") or err.get("status") or exc)
    except Exception:
        pass
    return str(exc)


def verify_live_streaming_access(access_token: str) -> dict[str, Any]:
    """Confirm token can call YouTube Live APIs (not just read channel name)."""
    try:
        youtube_get(
            access_token,
            "liveStreams",
            {"part": "id", "mine": "true", "maxResults": 1},
        )
        return {"ok": True, "message": ""}
    except requests.HTTPError as exc:
        msg = _youtube_error_message(exc)
        hint = (
            "Disconnect YouTube in the app, revoke CricRelay at "
            "https://myaccount.google.com/permissions, then Connect YouTube again. "
            "On Google's screen, allow access to manage your YouTube account (live streaming)."
        )
        if exc.response is not None and exc.response.status_code == 403:
            return {"ok": False, "message": f"{msg}. {hint}"}
        return {"ok": False, "message": msg or hint}


def create_live_broadcast(access_token: str, title: str, description: str = "") -> dict[str, Any]:
    now = datetime.now(timezone.utc).isoformat()
    body = {
        "snippet": {
            "title": title[:100],
            "description": (description or "Live cricket via CricRelay")[:5000],
            "scheduledStartTime": now,
        },
        "status": {
            "privacyStatus": "public",
            "selfDeclaredMadeForKids": False,
        },
        "contentDetails": {
            "enableAutoStart": True,
            "enableAutoStop": True,
            "enableDvr": True,
            "recordFromStart": True,
            "latencyPreference": "normal",
        },
    }
    return youtube_post(
        access_token,
        "liveBroadcasts",
        body,
        {"part": "snippet,status,contentDetails"},
    )


def create_live_stream(access_token: str, title: str) -> dict[str, Any]:
    body = {
        "snippet": {"title": title[:100]},
        "cdn": {
            "frameRate": "30fps",
            "ingestionType": "rtmp",
            "resolution": "1080p",
        },
    }
    return youtube_post(access_token, "liveStreams", body, {"part": "snippet,cdn,status"})


def bind_broadcast(access_token: str, broadcast_id: str, stream_id: str) -> dict[str, Any]:
    return youtube_post(
        access_token,
        "liveBroadcasts/bind",
        {},
        {"id": broadcast_id, "streamId": stream_id, "part": "id,contentDetails"},
    )


def transition_broadcast(access_token: str, broadcast_id: str, status: str) -> dict[str, Any]:
    return youtube_post(
        access_token,
        "liveBroadcasts/transition",
        {},
        {"id": broadcast_id, "broadcastStatus": status, "part": "status"},
    )


def go_live_bundle(access_token: str, title: str) -> dict[str, Any]:
    broadcast = create_live_broadcast(access_token, title)
    stream = create_live_stream(access_token, title)
    b_id = broadcast["id"]
    s_id = stream["id"]
    bind_broadcast(access_token, b_id, s_id)
    try:
        transition_broadcast(access_token, b_id, "testing")
    except requests.HTTPError:
        pass
    cdn = (stream.get("cdn") or {})
    ingestion = cdn.get("ingestionInfo") or {}
    return {
        "broadcast_id": b_id,
        "stream_id": s_id,
        "ingestion_address": ingestion.get("ingestionAddress") or "rtmp://a.rtmp.youtube.com/live2",
        "stream_name": ingestion.get("streamName") or "",
        "watch_url": f"https://www.youtube.com/watch?v={b_id}",
    }


def stop_live_bundle(access_token: str, broadcast_id: str, stream_id: str | None) -> None:
    if broadcast_id:
        try:
            transition_broadcast(access_token, broadcast_id, "complete")
        except requests.HTTPError:
            pass
    if stream_id:
        try:
            youtube_post(access_token, "liveStreams", {}, {"id": stream_id, "part": "id"})
        except requests.HTTPError:
            pass


def new_oauth_state() -> str:
    return secrets.token_urlsafe(24)
