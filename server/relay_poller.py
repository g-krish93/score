"""
Automatic relay polling — Play-Cricket and (optionally) CricHeroes scrapers.

Each provider can have its own poll interval (RELAY_POLL_INTERVAL_SEC vs
CRICHEROES_POLL_INTERVAL_SEC). The outer loop ticks at the fastest interval
among *enabled* providers; per-match last-tick timestamps decide when each row
is due.

Disable all polling with RELAY_AUTO_POLL=0 (e.g. tests).
CricHeroes polling is OFF by default until verified on EC2 — set
CRICHEROES_AUTO_POLL=1 after the selector/WAF spike.
"""
import os
import threading
import time
import traceback
from typing import Callable

from .models_cricrelay import (
    RELAY_PROVIDERS,
    provider_poll_interval_sec,
    relay_source_to_provider,
    resolve_provider_callable,
)
from .models_cricrelay import RelayMatch


def _truthy_auto_poll() -> bool:
    v = (os.getenv("RELAY_AUTO_POLL") or "1").strip().lower()
    return v not in {"0", "false", "no", "off"}


def _cricheroes_poll_enabled() -> bool:
    v = (os.getenv("CRICHEROES_AUTO_POLL") or "0").strip().lower()
    return v in {"1", "true", "yes", "on"}


def _active_poll_providers() -> list[str]:
    providers = ["play_cricket"]
    if _cricheroes_poll_enabled():
        providers.append("cricheroes")
    return providers


def _min_poll_interval() -> int:
    intervals = [provider_poll_interval_sec(p) for p in _active_poll_providers()]
    return max(5, min(intervals) if intervals else 10)


def _poller_loop(
    app,
    apply_fn: Callable[[str, dict], tuple[dict, int]],
    get_snapshot_fn: Callable[..., dict],
) -> None:
    stale_after = max(30, int(os.getenv("RELAY_STALE_AFTER_SEC", "120")))
    tick_interval = _min_poll_interval()
    inner_interval = max(2, min(8, tick_interval // 2 or 2))
    last_tick_by_slug: dict[str, float] = {}

    time.sleep(3.0)
    while True:
        try:
            with app.app_context():
                rows = RelayMatch.query.order_by(RelayMatch.created_at).all()
                now = time.time()
                for rm in rows:
                    if getattr(rm, "paused", False):
                        continue
                    provider = relay_source_to_provider(
                        getattr(rm, "relay_source", None) or "scraper"
                    )
                    if not provider:
                        continue
                    if provider == "cricheroes" and not _cricheroes_poll_enabled():
                        continue
                    url = (rm.full_scrape_url or "").strip()
                    slug = (rm.score_match_slug or "").strip()
                    if not url or not slug:
                        continue
                    provider_interval = provider_poll_interval_sec(provider)
                    last_tick = last_tick_by_slug.get(slug, 0.0)
                    if now - last_tick < provider_interval:
                        continue
                    cfg = RELAY_PROVIDERS[provider]
                    canonicalize = resolve_provider_callable(cfg["canonicalize_fn"])
                    scrape_fn = resolve_provider_callable(cfg["scrape_fn"])
                    payload = get_snapshot_fn(
                        url,
                        min_interval_sec=inner_interval,
                        stale_after_sec=stale_after,
                        canonicalize_fn=canonicalize,
                        scrape_fn=scrape_fn,
                    )
                    last_tick_by_slug[slug] = now
                    if not payload.get("snapshot"):
                        continue
                    apply_fn(slug, payload)
        except Exception:
            print("[relay_poller] tick failed:\n" + traceback.format_exc(), flush=True)
        time.sleep(float(tick_interval))


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
    ch = "on" if _cricheroes_poll_enabled() else "off (set CRICHEROES_AUTO_POLL=1 after EC2 spike)"
    print(
        f"[relay_poller] started (tick={_min_poll_interval()}s, "
        f"providers={_active_poll_providers()}, cricheroes_poll={ch})",
        flush=True,
    )
