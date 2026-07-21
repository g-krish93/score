import json
import os
import threading
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional
from urllib.parse import quote_plus

import requests
from flask import Flask, jsonify, request

from scraper import scrape_match

app = Flask(__name__)

cache_lock = threading.Lock()
live_cache = {}
SNAPSHOT_DIR = Path(os.getenv("SNAPSHOT_DIR", "./data/snapshots"))
PUSH_TARGET_URL = os.getenv("PUSH_TARGET_URL", "").strip()
PUSH_AUTH_TOKEN = os.getenv("PUSH_AUTH_TOKEN", "").strip()


def now_ts() -> float:
    return time.time()


def get_cache_key(url: str) -> str:
    return quote_plus(url.strip())


def get_live_snapshot(url: str, min_interval_sec: int = 8, stale_after_sec: int = 45) -> dict:
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
    SNAPSHOT_DIR.mkdir(parents=True, exist_ok=True)
    safe_key = get_cache_key(source_url)[:120]
    filename = f"{utc_stamp()}_{safe_key}.json"
    path = SNAPSHOT_DIR / filename
    with path.open("w", encoding="utf-8") as fh:
        json.dump(snapshot_payload, fh, ensure_ascii=False, indent=2)
    return str(path.resolve())


def push_to_remote(payload: dict, target_url: Optional[str] = None, token: Optional[str] = None) -> dict:
    url = (target_url or PUSH_TARGET_URL or "").strip()
    if not url:
        return {"ok": False, "error": "Missing PUSH_TARGET_URL (env or query param)"}

    headers = {"Content-Type": "application/json"}
    use_token = (token or PUSH_AUTH_TOKEN or "").strip()
    if use_token:
        headers["Authorization"] = f"Bearer {use_token}"

    res = requests.post(url, json=payload, headers=headers, timeout=20)
    body_text = res.text[:400]
    return {
        "ok": res.ok,
        "status_code": res.status_code,
        "response_preview": body_text,
    }


@app.get("/health")
def health():
    return jsonify({"status": "ok"})


@app.get("/scrape")
def scrape():
    url = (request.args.get("url") or "").strip()
    if not url:
        return jsonify({"error": "Missing required query param: url"}), 400
    try:
        return jsonify(scrape_match(url))
    except Exception as exc:
        return jsonify({"error": str(exc)}), 502


@app.get("/live")
def live():
    url = (request.args.get("url") or "").strip()
    if not url:
        return jsonify({"error": "Missing required query param: url"}), 400
    min_interval_sec = int(request.args.get("interval", "8"))
    stale_after_sec = int(request.args.get("stale_after", "45"))
    payload = get_live_snapshot(url, min_interval_sec=min_interval_sec, stale_after_sec=stale_after_sec)
    if not payload.get("snapshot"):
        return jsonify(payload), 502
    if (request.args.get("store") or "").strip() in {"1", "true", "yes"}:
        payload["stored_file"] = persist_snapshot(url, payload)
    if (request.args.get("push") or "").strip() in {"1", "true", "yes"}:
        payload["push_result"] = push_to_remote(
            payload,
            target_url=(request.args.get("push_url") or "").strip() or None,
            token=(request.args.get("push_token") or "").strip() or None,
        )
    return jsonify(payload)


@app.post("/sync")
def sync_now():
    data = request.get_json(silent=True) or {}
    url = (data.get("url") or "").strip()
    if not url:
        return jsonify({"error": "Missing required field: url"}), 400

    interval = int(data.get("interval", 8))
    stale_after = int(data.get("stale_after", 45))
    payload = get_live_snapshot(url, min_interval_sec=interval, stale_after_sec=stale_after)
    if not payload.get("snapshot"):
        return jsonify(payload), 502

    stored_file = persist_snapshot(url, payload)
    push_result = push_to_remote(
        payload,
        target_url=(data.get("push_url") or "").strip() or None,
        token=(data.get("push_token") or "").strip() or None,
    )
    out = {
        "stored_file": stored_file,
        "push_result": push_result,
        "payload": payload,
    }
    return jsonify(out), (200 if push_result.get("ok") else 502)


@app.get("/overlay")
def overlay():
    url = (request.args.get("url") or "").strip()
    if not url:
        return (
            "<h3>Missing query param: url</h3>"
            "<p>Example: /overlay?url=https://bmacc.play-cricket.com/website/results/7681278</p>",
            400,
        )
    return f"""<!doctype html>
<html><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Play-Cricket Live Overlay</title>
<style>
body {{ margin:0; background:transparent; font-family:Segoe UI, Arial, sans-serif; color:#fff; }}
#ov {{ position:fixed; left:0; right:0; bottom:0; padding:8px 12px; background:rgba(7,17,41,.92); border-top:3px solid #5fa8ff; }}
.top {{ display:flex; justify-content:space-between; gap:10px; align-items:center; margin-bottom:6px; font-size:12px; }}
.muted {{ color:#a9bbd9; }}
.warn {{ color:#ff6b6b; font-weight:700; }}
.main {{ display:grid; grid-template-columns:1fr auto 1fr; gap:8px; align-items:center; }}
.box {{ background:rgba(255,255,255,.08); border:1px solid rgba(255,255,255,.17); border-radius:8px; padding:6px 8px; }}
.score {{ font-size:34px; font-weight:900; text-align:center; background:rgba(255,255,255,.16); border-radius:8px; padding:5px 10px; }}
.team {{ font-size:13px; font-weight:800; text-transform:uppercase; }}
.meta {{ font-size:11px; color:#c8d7f0; margin-top:3px; }}
</style>
</head>
<body>
<div id="ov">
  <div class="top"><span class="muted" id="status">Loading...</span><span id="stale"></span></div>
  <div class="main">
    <div class="box"><div class="team" id="t1">-</div><div class="meta" id="i1">-</div></div>
    <div class="score" id="score">-</div>
    <div class="box"><div class="team" id="t2">-</div><div class="meta" id="i2">-</div></div>
  </div>
</div>
<script>
const SRC = {url!r};
const api = () => `/live?url=${{encodeURIComponent(SRC)}}&interval=8&stale_after=45`;
function tx(v) {{ return (v===null||v===undefined||v==='') ? '-' : String(v); }}
async function tick() {{
  try {{
    const r = await fetch(api());
    const d = await r.json();
    const s = d.snapshot || {{}};
    const a = s.innings_1 || {{}};
    const b = s.innings_2 || {{}};
    document.getElementById('status').textContent = tx(s.status || 'Live scrape');
    document.getElementById('stale').textContent = d.stale ? 'STALE' : '';
    document.getElementById('stale').className = d.stale ? 'warn' : 'muted';
    document.getElementById('t1').textContent = tx(a.team);
    document.getElementById('i1').textContent = `${{tx(a.score)}} (${{tx(a.overs_display)}} ov)`;
    document.getElementById('t2').textContent = tx(b.team);
    document.getElementById('i2').textContent = `${{tx(b.score)}} (${{tx(b.overs_display)}} ov)`;
    document.getElementById('score').textContent = a.score && b.score ? `${{a.score}}  |  ${{b.score}}` : tx(a.score || b.score);
  }} catch (e) {{
    document.getElementById('stale').textContent = 'ERROR';
    document.getElementById('stale').className = 'warn';
  }}
}}
tick();
setInterval(tick, 2000);
</script>
</body></html>"""


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8080, debug=False)
