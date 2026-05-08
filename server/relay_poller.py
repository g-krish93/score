"""
Automatic Play-Cricket relay polling — SaaS-style match day UX.

Every RELAY_POLL_INTERVAL_SEC seconds, loads all RelayMatch rows from the DB,
scrapes each full_scrape_url, and applies ingest to the corresponding score slug.
No manual curl or cron required on match day (single Gunicorn worker recommended).

Disable with RELAY_AUTO_POLL=0 (e.g. tests).
"""
import os
import threading
import time
import traceback
from typing import Any, Callable

from .models_cricrelay import RelayMatch


def _truthy_auto_poll() -> bool:
    v = (os.getenv("RELAY_AUTO_POLL") or "1").strip().lower()
    return v not in {"0", "false", "no", "off"}


def _poller_loop(
    app,
    apply_fn: Callable[[str, dict], tuple[dict, int]],
    get_snapshot_fn: Callable[..., dict],
) -> None:
    interval = max(5, int(os.getenv("RELAY_POLL_INTERVAL_SEC", "10")))
    stale_after = max(30, int(os.getenv("RELAY_STALE_AFTER_SEC", "120")))
    # Allow scraper cache to refresh often enough inside the outer interval
    inner_interval = max(2, min(8, interval // 2 or 2))

    time.sleep(3.0)
    while True:
        try:
            with app.app_context():
                rows = RelayMatch.query.order_by(RelayMatch.created_at).all()
                for rm in rows:
                    url = (rm.full_scrape_url or "").strip()
                    slug = (rm.score_match_slug or "").strip()
                    if not url or not slug:
                        continue
                    payload = get_snapshot_fn(
                        url,
                        min_interval_sec=inner_interval,
                        stale_after_sec=stale_after,
                    )
                    if not payload.get("snapshot"):
                        continue
                    apply_fn(slug, payload)
        except Exception:
            print("[relay_poller] tick failed:\n" + traceback.format_exc(), flush=True)
        time.sleep(float(interval))


def start_relay_poller(
    app,
    apply_fn: Callable[[str, dict], tuple[dict, int]],
    get_snapshot_fn: Callable[..., dict],
) -> None:
    if not _truthy_auto_poll():
        return
    t = threading.Thread(
        target=_poller_loop,
        args=(app, apply_fn, get_snapshot_fn),
        name="relay-poller",
        daemon=True,
    )
    t.start()
    print(
        f"[relay_poller] started (interval={os.getenv('RELAY_POLL_INTERVAL_SEC', '10')}s)",
        flush=True,
    )
