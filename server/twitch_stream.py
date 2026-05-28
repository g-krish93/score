"""Twitch OAuth + Helix API helpers for CricRelay Stream."""
from __future__ import annotations

import os
import secrets
import urllib.parse
from typing import Any

import requests

from .youtube_stream import decrypt_token, encrypt_token

TWITCH_AUTH_URL = "https://id.twitch.tv/oauth2/authorize"
TWITCH_TOKEN_URL = "https://id.twitch.tv/oauth2/token"
TWITCH_API = "https://api.twitch.tv/helix"
TWITCH_RTMP_INGEST = "rtmp://live.twitch.tv/app"

OAUTH_SCOPE_STREAM_KEY = "channel:read:stream_key"
OAUTH_SCOPE_MANAGE_BROADCAST = "channel:manage:broadcast"
SCOPES = [OAUTH_SCOPE_STREAM_KEY, OAUTH_SCOPE_MANAGE_BROADCAST]


def oauth_scopes() -> list[str]:
    return list(SCOPES)


def _client_id() -> str:
    return (os.getenv("TWITCH_CLIENT_ID") or "").strip()


def _client_secret() -> str:
    return (os.getenv("TWITCH_CLIENT_SECRET") or "").strip()


def oauth_configured() -> bool:
    return bool(_client_id() and _client_secret())


def build_authorize_url(redirect_uri: str, state: str) -> str:
    params = {
        "client_id": _client_id(),
        "redirect_uri": redirect_uri,
        "response_type": "code",
        "scope": " ".join(SCOPES),
        "state": state,
        "force_verify": "true",
    }
    return f"{TWITCH_AUTH_URL}?{urllib.parse.urlencode(params)}"


def exchange_code(code: str, redirect_uri: str) -> dict[str, Any]:
    resp = requests.post(
        TWITCH_TOKEN_URL,
        data={
            "client_id": _client_id(),
            "client_secret": _client_secret(),
            "code": code,
            "grant_type": "authorization_code",
            "redirect_uri": redirect_uri,
        },
        timeout=30,
    )
    resp.raise_for_status()
    return resp.json()


def refresh_access_token(refresh_token: str) -> dict[str, Any]:
    resp = requests.post(
        TWITCH_TOKEN_URL,
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


def _helix_headers(access_token: str) -> dict[str, str]:
    return {
        "Authorization": f"Bearer {access_token}",
        "Client-Id": _client_id(),
    }


def helix_get(access_token: str, path: str, params: dict | None = None) -> dict[str, Any]:
    url = f"{TWITCH_API}/{path.lstrip('/')}"
    resp = requests.get(url, headers=_helix_headers(access_token), params=params or {}, timeout=30)
    resp.raise_for_status()
    return resp.json()


def helix_patch(access_token: str, path: str, body: dict) -> dict[str, Any]:
    url = f"{TWITCH_API}/{path.lstrip('/')}"
    resp = requests.patch(url, headers=_helix_headers(access_token), json=body, timeout=30)
    resp.raise_for_status()
    return resp.json() if resp.content else {}


def fetch_user_for_token(access_token: str) -> tuple[str, str, str]:
    """Returns (broadcaster_id, login, display_name)."""
    data = helix_get(access_token, "users")
    items = data.get("data") or []
    if not items:
        raise ValueError("No Twitch user found for this account")
    user = items[0]
    return (
        str(user.get("id") or ""),
        str(user.get("login") or ""),
        str(user.get("display_name") or user.get("login") or "Twitch"),
    )


def verify_streaming_access(access_token: str, broadcaster_id: str) -> dict[str, Any]:
    hint = (
        "Ensure this Twitch account can go live (no active suspension) and reconnect, "
        "allowing stream key + channel management scopes."
    )
    try:
        helix_get(
            access_token,
            "streams/key",
            {"broadcaster_id": broadcaster_id},
        )
        return {"ok": True, "message": ""}
    except requests.HTTPError as exc:
        msg = str(exc)
        try:
            body = exc.response.json()
            msg = str((body.get("message") or body.get("error") or msg))
        except Exception:
            pass
        if exc.response is not None and exc.response.status_code == 403:
            return {"ok": False, "message": f"{msg}. {hint}"}
        return {"ok": False, "message": msg or hint}


def get_stream_key(access_token: str, broadcaster_id: str) -> str:
    data = helix_get(access_token, "streams/key", {"broadcaster_id": broadcaster_id})
    items = data.get("data") or []
    if not items:
        raise ValueError("Twitch did not return a stream key")
    key = (items[0].get("stream_key") or "").strip()
    if not key:
        raise ValueError("Empty stream key from Twitch")
    return key


def update_channel_title(access_token: str, broadcaster_id: str, title: str) -> None:
    helix_patch(
        access_token,
        "channels",
        {
            "broadcaster_id": broadcaster_id,
            "title": (title or "Live cricket via CricRelay")[:140],
        },
    )


def go_live_bundle(access_token: str, broadcaster_id: str, login: str, title: str) -> dict[str, Any]:
    update_channel_title(access_token, broadcaster_id, title)
    stream_key = get_stream_key(access_token, broadcaster_id)
    return {
        "ingestion_address": TWITCH_RTMP_INGEST,
        "stream_name": stream_key,
        "watch_url": f"https://www.twitch.tv/{login}",
    }


def new_oauth_state() -> str:
    return secrets.token_urlsafe(24)
