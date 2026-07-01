#!/usr/bin/env python3
"""CDP client for scraping CricHeroes via a persistent Chrome session.

Standalone spike — not wired into server/cricheroes_scraper.py.
Attaches to an already-running Chrome via Playwright connect_over_cdp().
"""
from __future__ import annotations

import logging
import re
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Optional
from urllib.parse import urlparse

LOG = logging.getLogger("cricheroes-spike")

DEFAULT_CDP_URL = "http://127.0.0.1:9222"
DEFAULT_TIMEOUT_SEC = 30

CHALLENGE_MARKERS = (
    "just a moment",
    "access denied",
    "captcha",
    "cloudflare",
    "cf-challenge",
    "turnstile",
    "checking your browser",
    "enable javascript and cookies",
)

INNINGS_SCORE_RE = re.compile(r"\d+\s*/\s*\d+\s*\([\d.]+\s*ov", re.I)


class CricHeroesChallengeError(RuntimeError):
    """Raised when Cloudflare or WAF challenge HTML is detected."""


class CricHeroesCdpError(RuntimeError):
    """Raised when CDP connection or navigation fails."""


@dataclass
class ScrapeResult:
    url: str
    html: str
    ok: bool
    challenged: bool
    scorecard: bool
    http_status: Optional[int]
    elapsed_sec: float
    title: Optional[str] = None
    error: Optional[str] = None


def is_challenge_html(html: str, http_status: Optional[int] = None) -> bool:
    """Mirror server/cricheroes_scraper.fetch_page_html block checks + Turnstile markers."""
    if http_status in {403, 429, 503}:
        return True
    low = (html or "").lower()
    return any(marker in low for marker in CHALLENGE_MARKERS)


def is_scorecard_html(html: str) -> bool:
    """Heuristic: real CricHeroes live scorecard vs challenge/empty shell.

    NOTE: Never validated against real post-challenge CricHeroes HTML. The production
    parser (cricheroes_scraper.py) also assumes <table> markup. If the live page is a
    div/CSS-grid SPA, both this check and production parsing may need revisiting.
    Capture the first successful fetch with save_html_fixture() and inspect the DOM.
    """
    if not html or is_challenge_html(html):
        return False
    low = html.lower()
    has_score_pattern = bool(INNINGS_SCORE_RE.search(html))
    has_tables = "<table" in low
    has_scorecard_hint = any(
        token in low for token in ("scorecard", "innings", "batting", "bowling", "striker", "bowler")
    )
    # Strong signal: innings score text + scorecard vocabulary (works without <table>).
    if has_score_pattern and has_scorecard_hint:
        return True
    return has_tables and (has_score_pattern or has_scorecard_hint)


def save_html_fixture(html: str, path: Path) -> Path:
    """Persist fetched HTML for offline DOM inspection (first successful fetch)."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(html, encoding="utf-8")
    LOG.info("Saved HTML fixture to %s (%d bytes)", path, len(html))
    return path


def wait_for_cdp(cdp_url: str = DEFAULT_CDP_URL, timeout_sec: float = 60) -> bool:
    """Poll Chrome DevTools /json/version until CDP is accepting connections."""
    try:
        import urllib.request

        deadline = time.monotonic() + timeout_sec
        version_url = cdp_url.rstrip("/") + "/json/version"
        while time.monotonic() < deadline:
            try:
                with urllib.request.urlopen(version_url, timeout=2) as resp:
                    if resp.status == 200:
                        return True
            except OSError:
                pass
            time.sleep(0.5)
    except Exception:
        return False
    return False


def fetch_page_html_via_cdp(
    url: str,
    *,
    cdp_url: str = DEFAULT_CDP_URL,
    timeout_sec: int = DEFAULT_TIMEOUT_SEC,
) -> ScrapeResult:
    """Open a new tab in the persistent browser, fetch HTML, close the tab."""
    started = time.monotonic()
    try:
        from playwright.sync_api import TimeoutError as PlaywrightTimeoutError
        from playwright.sync_api import sync_playwright
    except ImportError as exc:
        raise CricHeroesCdpError(
            "playwright is not installed — run: pip install playwright"
        ) from exc

    if not wait_for_cdp(cdp_url, timeout_sec=min(timeout_sec, 30)):
        raise CricHeroesCdpError(
            f"CDP not reachable at {cdp_url} — is cricheroes-browser.service running?"
        )

    http_status: Optional[int] = None
    html = ""
    title: Optional[str] = None

    with sync_playwright() as p:
        try:
            browser = p.chromium.connect_over_cdp(cdp_url)
        except Exception as exc:
            raise CricHeroesCdpError(f"connect_over_cdp failed: {exc}") from exc

        page = None
        try:
            # Default Chrome profile uses a single persistent context; Incognito would add another.
            context = browser.contexts[0] if browser.contexts else browser.new_context()
            page = context.new_page()
            response = page.goto(
                url,
                wait_until="domcontentloaded",
                timeout=timeout_sec * 1000,
            )
            if response:
                http_status = response.status
            # Allow SPA / lazy scorecard widgets to render.
            try:
                page.wait_for_load_state("networkidle", timeout=timeout_sec * 1000)
            except PlaywrightTimeoutError:
                pass
            title = page.title()
            html = page.content()
        except PlaywrightTimeoutError as exc:
            elapsed = time.monotonic() - started
            return ScrapeResult(
                url=url,
                html=html,
                ok=False,
                challenged=True,
                scorecard=False,
                http_status=http_status,
                elapsed_sec=elapsed,
                title=title,
                error=f"navigation timed out: {exc}",
            )
        except Exception as exc:
            raise CricHeroesCdpError(f"page navigation failed: {exc}") from exc
        finally:
            if page is not None:
                try:
                    page.close()
                except Exception:
                    pass
            # Never close the browser — it is the long-lived session.

    challenged = is_challenge_html(html, http_status)
    scorecard = is_scorecard_html(html)
    ok = scorecard and not challenged
    elapsed = time.monotonic() - started

    if challenged:
        LOG.error(
            "[cricheroes-spike] Session re-challenged — human must VNC in and re-solve "
            "(ssh -L 5900:localhost:5900 ec2-user@<host>, then VNC to localhost:5900)"
        )
    elif not scorecard:
        LOG.warning(
            "[cricheroes-spike] Not challenged but scorecard heuristic failed — "
            "page may be pre-match, ended, or non-<table> SPA markup; save HTML fixture"
        )

    return ScrapeResult(
        url=url,
        html=html,
        ok=ok,
        challenged=challenged,
        scorecard=scorecard,
        http_status=http_status,
        elapsed_sec=elapsed,
        title=title,
    )


def cdp_host_is_localhost(cdp_url: str = DEFAULT_CDP_URL) -> bool:
    """Safety check: CDP must never be exposed on a public interface."""
    host = (urlparse(cdp_url).hostname or "").lower()
    return host in {"127.0.0.1", "localhost", "::1"}
