"""
Built-in Play-Cricket scraper worker (same process as the score app).

HTTP API under ``/relay-worker/`` — scrape, cached live snapshot, optional push to
``/relay/ingest`` for a match slug (internal, no second server).

Requires ``RELAY_INGEST_TOKEN`` when using ``push_match`` / ``push`` if that env is set
(same rule as ``POST /relay/ingest``).
"""
import json
import os
import threading
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Optional
from urllib.parse import quote_plus

import requests
from flask import Blueprint, jsonify, request

from .models_cricrelay import canonicalize_play_cricket_scrape_url
from .play_cricket_scraper import scrape_match

relay_worker_bp = Blueprint("relay_worker", __name__, url_prefix="/relay-worker")

cache_lock = threading.Lock()
live_cache: dict = {}

_ingest_fn: Optional[Callable[[str, dict], tuple[dict, int]]] = None


def set_relay_ingest_handler(fn: Callable[[str, dict], tuple[dict, int]]) -> None:
    global _ingest_fn
    _ingest_fn = fn


def _snapshot_dir() -> Path:
    base = Path(os.getenv("STATE_DIR", "/tmp")).expanduser()
    try:
        base.mkdir(parents=True, exist_ok=True)
    except OSError:
        pass
    d = base / "relay_snapshots"
    d.mkdir(parents=True, exist_ok=True)
    return d


def now_ts() -> float:
    return time.time()


def get_cache_key(url: str) -> str:
    return quote_plus(url.strip())


def get_live_snapshot(
    url: str, min_interval_sec: int = 8, stale_after_sec: int = 45
) -> dict:
    url = canonicalize_play_cricket_scrape_url((url or "").strip())
    cache_key = get_cache_key(url)
    with cache_lock:
        row = live_cache.get(cache_key, {})
        last_fetch_at = float(row.get("last_fetch_at", 0))
        age = now_ts() - last_fetch_at if last_fetch_at else 10**9
        should_refresh = age >= max(2, int(min_interval_sec))

    if should_refresh:
        try:
            fresh = scrape_match(url)
            with cache_lock:
                prev = live_cache.get(cache_key, {})
                previous_data = prev.get("data")
                changed = fresh != previous_data
                live_cache[cache_key] = {
                    "url": url,
                    "data": fresh,
                    "last_fetch_at": now_ts(),
                    "last_ok_at": now_ts(),
                    "last_error": None,
                    "last_changed_at": now_ts() if changed else prev.get("last_changed_at", now_ts()),
                }
        except Exception as exc:
            with cache_lock:
                prev = live_cache.get(cache_key, {})
                live_cache[cache_key] = {
                    "url": url,
                    "data": prev.get("data"),
                    "last_fetch_at": now_ts(),
                    "last_ok_at": prev.get("last_ok_at"),
                    "last_error": str(exc),
                    "last_changed_at": prev.get("last_changed_at"),
                }

    with cache_lock:
        row = live_cache.get(cache_key, {})
        last_ok_at = row.get("last_ok_at")
        stale = (now_ts() - float(last_ok_at)) > float(stale_after_sec) if last_ok_at else True
        return {
            "source_url": url,
            "stale": stale,
            "last_fetch_at": row.get("last_fetch_at"),
            "last_ok_at": row.get("last_ok_at"),
            "last_changed_at": row.get("last_changed_at"),
            "last_error": row.get("last_error"),
            "snapshot": row.get("data"),
        }


def utc_stamp() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def persist_snapshot(source_url: str, snapshot_payload: dict) -> str:
    SNAPSHOT_DIR = _snapshot_dir()
    safe_key = get_cache_key(source_url)[:120]
    filename = f"{utc_stamp()}_{safe_key}.json"
    path = SNAPSHOT_DIR / filename
    with path.open("w", encoding="utf-8") as fh:
        json.dump(snapshot_payload, fh, ensure_ascii=False, indent=2)
    return str(path.resolve())


def _relay_push_authorized() -> bool:
    expected = os.getenv("RELAY_INGEST_TOKEN", "").strip()
    if not expected:
        return True
    auth = (request.headers.get("Authorization") or "").strip()
    return auth == f"Bearer {expected}"


def push_to_remote(
    payload: dict,
    target_url: Optional[str] = None,
    token: Optional[str] = None,
) -> dict:
    url = (target_url or os.getenv("PUSH_TARGET_URL", "") or "").strip()
    if not url:
        return {"ok": False, "error": "Missing PUSH_TARGET_URL (env or query/body param)"}

    headers = {"Content-Type": "application/json"}
    use_token = (token or os.getenv("PUSH_AUTH_TOKEN", "") or "").strip()
    if use_token:
        headers["Authorization"] = f"Bearer {use_token}"

    res = requests.post(url, json=payload, headers=headers, timeout=20)
    body_text = res.text[:400]
    return {
        "ok": res.ok,
        "status_code": res.status_code,
        "response_preview": body_text,
    }


def push_match_internal(match_slug: str, live_payload: dict) -> dict:
    if _ingest_fn is None:
        return {"ok": False, "error": "Relay ingest handler not registered"}
    body, code = _ingest_fn(match_slug, live_payload)
    ok = 200 <= code < 300 and body.get("ok") is not False
    return {"ok": ok, "status_code": code, "response": body}


@relay_worker_bp.get("/health")
def worker_health():
    return jsonify({"status": "ok", "component": "relay-worker"})


@relay_worker_bp.get("/scrape")
def scrape_one():
    url = (request.args.get("url") or "").strip()
    if not url:
        return jsonify({"error": "Missing required query param: url"}), 400
    try:
        return jsonify(scrape_match(url))
    except Exception as exc:
        return jsonify({"error": str(exc)}), 502


@relay_worker_bp.get("/live")
def live():
    url = (request.args.get("url") or "").strip()
    if not url:
        return jsonify({"error": "Missing required query param: url"}), 400
    min_interval_sec = int(request.args.get("interval", "8"))
    stale_after_sec = int(request.args.get("stale_after", "45"))
    payload = get_live_snapshot(url, min_interval_sec=min_interval_sec, stale_after_sec=stale_after_sec)

    if not payload.get("snapshot"):
        return jsonify(payload), 502

    push_match = (request.args.get("push_match") or "").strip()
    if push_match:
        if not _relay_push_authorized():
            return jsonify({"error": "unauthorized"}), 401
        payload["push_match_result"] = push_match_internal(push_match, payload)

    if (request.args.get("store") or "").strip() in {"1", "true", "yes"}:
        payload["stored_file"] = persist_snapshot(url, payload)

    if (request.args.get("push") or "").strip() in {"1", "true", "yes"}:
        payload["push_result"] = push_to_remote(
            payload,
            target_url=(request.args.get("push_url") or "").strip() or None,
            token=(request.args.get("push_token") or "").strip() or None,
        )
    return jsonify(payload)


@relay_worker_bp.post("/sync")
def sync_now():
    data = request.get_json(silent=True) or {}
    url = (data.get("url") or "").strip()
    if not url:
        return jsonify({"error": "Missing required field: url"}), 400

    push_match = (data.get("push_match") or "").strip()
    if push_match and not _relay_push_authorized():
        return jsonify({"error": "unauthorized"}), 401

    interval = int(data.get("interval", 8))
    stale_after = int(data.get("stale_after", 45))
    payload = get_live_snapshot(url, min_interval_sec=interval, stale_after_sec=stale_after)
    if not payload.get("snapshot"):
        return jsonify(payload), 502

    stored_file = persist_snapshot(url, payload)
    out: dict[str, Any] = {
        "stored_file": stored_file,
        "payload": payload,
    }

    if push_match:
        out["push_match_result"] = push_match_internal(push_match, payload)

    push_url = (data.get("push_url") or "").strip()
    token = (data.get("push_token") or "").strip()
    if push_url or os.getenv("PUSH_TARGET_URL"):
        out["push_result"] = push_to_remote(payload, target_url=push_url or None, token=token or None)

    code = 200
    if push_match and not out.get("push_match_result", {}).get("ok"):
        code = 502
    elif out.get("push_result") and not out["push_result"].get("ok"):
        code = 502

    return jsonify(out), code

def _relay_worker_http_enabled() -> bool:
    v = (os.getenv("RELAY_WORKER_HTTP") or "").strip().lower()
    return v in {"1", "true", "yes", "on"}


def register_relay_worker(app, ingest_fn) -> None:
    set_relay_ingest_handler(ingest_fn)
    if _relay_worker_http_enabled():
        app.register_blueprint(relay_worker_bp)
