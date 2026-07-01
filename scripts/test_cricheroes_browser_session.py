#!/usr/bin/env python3
"""Endurance test for the persistent CricHeroes browser session spike.

Connects via CDP, scrapes a live scorecard URL on an interval, logs challenge
detections, optionally restarts systemd mid-run, and prints a summary report.

Run on EC2 after manual Cloudflare solve (see docs/CRICHEROES_BROWSER_SPIKE.md).

Examples:
  # Quick smoke (one scrape):
  python3 scripts/test_cricheroes_browser_session.py --smoke

  # Full 2-hour endurance (default):
  python3 scripts/test_cricheroes_browser_session.py

  # Include mid-run service restart test at 30 minutes:
  python3 scripts/test_cricheroes_browser_session.py --restart-after-min 30
"""
from __future__ import annotations

import argparse
import json
import logging
import subprocess
import sys
import time
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

# Allow running as `python3 scripts/test_cricheroes_browser_session.py` from /app.
_SCRIPT_DIR = Path(__file__).resolve().parent
if str(_SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(_SCRIPT_DIR))

from cricheroes_cdp_client import (  # noqa: E402
    DEFAULT_CDP_URL,
    cdp_host_is_localhost,
    fetch_page_html_via_cdp,
    save_html_fixture,
)

LOG = logging.getLogger("cricheroes-spike-test")

# Placeholder only — pass --url for a match that is actually live during your test window.
DEFAULT_URL = (
    "https://cricheroes.com/scorecard/25903161/"
    "telangana-talent-hunt-u-16-cricket-championship-2026-season-i/"
    "vediri-cricket-academy-vs-mvrca/live"
)
DEFAULT_INTERVAL_SEC = 300
DEFAULT_DURATION_SEC = 7200
MANUAL_SOLVE_MARKER = Path.home() / ".cricheroes-last-manual-solve"
REPORT_PATH = Path.home() / "cricheroes-spike-report.json"


@dataclass
class AttemptRecord:
    index: int
    timestamp: str
    ok: bool
    challenged: bool
    scorecard: bool
    http_status: Optional[int]
    elapsed_sec: float
    title: Optional[str]
    error: Optional[str] = None
    note: Optional[str] = None


@dataclass
class TestReport:
    started_at: str
    ended_at: str
    url: str
    cdp_url: str
    interval_sec: int
    duration_sec: int
    attempts: list[AttemptRecord] = field(default_factory=list)
    restart_tested: bool = False
    restart_success: Optional[bool] = None
    restart_error: Optional[str] = None
    manual_solve_marker: Optional[str] = None

    @property
    def success_count(self) -> int:
        return sum(1 for a in self.attempts if a.ok)

    @property
    def challenge_count(self) -> int:
        return sum(1 for a in self.attempts if a.challenged)

    def success_rate(self) -> float:
        if not self.attempts:
            return 0.0
        return self.success_count / len(self.attempts)

    def summary_lines(self) -> list[str]:
        lines = [
            "",
            "=" * 60,
            "CricHeroes browser session spike — summary",
            "=" * 60,
            f"Window: {self.started_at} → {self.ended_at}",
            f"Attempts: {len(self.attempts)}",
            f"Successes: {self.success_count} ({self.success_rate() * 100:.1f}%)",
            f"Re-challenges detected: {self.challenge_count}",
        ]
        if self.restart_tested:
            status = "PASS" if self.restart_success else "FAIL"
            lines.append(f"Post-restart scrape: {status}")
            if self.restart_error:
                lines.append(f"Restart error: {self.restart_error}")
        if self.manual_solve_marker:
            lines.append(f"Last manual solve marker: {self.manual_solve_marker}")
        first_challenge = next((a for a in self.attempts if a.challenged), None)
        if first_challenge and self.manual_solve_marker:
            lines.append(
                f"First re-challenge after manual solve: attempt #{first_challenge.index} "
                f"at {first_challenge.timestamp}"
            )
        elif not first_challenge:
            lines.append("No re-challenges observed during test window.")
        lines.append("=" * 60)
        return lines


def _utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _read_manual_solve_marker() -> Optional[str]:
    if MANUAL_SOLVE_MARKER.is_file():
        try:
            return MANUAL_SOLVE_MARKER.read_text(encoding="utf-8").strip() or None
        except OSError:
            return None
    return None


def _run_one_attempt(
    index: int,
    url: str,
    cdp_url: str,
    timeout_sec: int,
    *,
    save_html: Optional[Path] = None,
) -> AttemptRecord:
    ts = _utc_now_iso()
    try:
        result = fetch_page_html_via_cdp(url, cdp_url=cdp_url, timeout_sec=timeout_sec)
        if save_html and result.html and not result.challenged:
            save_html_fixture(result.html, save_html)
        return AttemptRecord(
            index=index,
            timestamp=ts,
            ok=result.ok,
            challenged=result.challenged,
            scorecard=result.scorecard,
            http_status=result.http_status,
            elapsed_sec=round(result.elapsed_sec, 2),
            title=result.title,
            error=result.error,
        )
    except Exception as exc:
        LOG.exception("Attempt %s failed", index)
        return AttemptRecord(
            index=index,
            timestamp=ts,
            ok=False,
            challenged=True,
            scorecard=False,
            http_status=None,
            elapsed_sec=0.0,
            title=None,
            error=str(exc),
        )


def _restart_systemd_service(service: str) -> None:
    LOG.warning("Restarting systemd service %s (disk persistence test)...", service)
    result = subprocess.run(
        ["sudo", "-n", "systemctl", "restart", service],
        capture_output=True,
        text=True,
        timeout=120,
    )
    if result.returncode != 0:
        hint = (
            "sudo failed — run deploy/cricheroes-browser-setup.sh to install NOPASSWD rule, "
            "or restart manually: sudo systemctl restart cricheroes-browser.service"
        )
        detail = (result.stderr or result.stdout or "").strip()
        raise RuntimeError(f"{hint}. {detail}".strip())
    time.sleep(5)


def run_test(
    *,
    url: str,
    cdp_url: str,
    interval_sec: int,
    duration_sec: int,
    timeout_sec: int,
    restart_after_min: Optional[int],
    systemd_service: str,
    smoke: bool,
    save_html: Optional[Path] = None,
) -> TestReport:
    if not cdp_host_is_localhost(cdp_url):
        raise SystemExit(f"Refusing non-localhost CDP URL: {cdp_url}")

    report = TestReport(
        started_at=_utc_now_iso(),
        ended_at="",
        url=url,
        cdp_url=cdp_url,
        interval_sec=interval_sec,
        duration_sec=duration_sec if not smoke else 0,
        manual_solve_marker=_read_manual_solve_marker(),
    )

    if smoke:
        LOG.info("Smoke mode — single scrape")
        attempt = _run_one_attempt(1, url, cdp_url, timeout_sec, save_html=save_html)
        report.attempts.append(attempt)
        _log_attempt(attempt)
        report.ended_at = _utc_now_iso()
        return report

    deadline = time.monotonic() + duration_sec
    restart_deadline: Optional[float] = None
    restart_done = False
    if restart_after_min is not None:
        restart_deadline = time.monotonic() + restart_after_min * 60

    index = 0
    while time.monotonic() < deadline:
        index += 1
        attempt = _run_one_attempt(index, url, cdp_url, timeout_sec, save_html=save_html)
        report.attempts.append(attempt)
        _log_attempt(attempt)

        if (
            restart_deadline is not None
            and not restart_done
            and time.monotonic() >= restart_deadline
        ):
            restart_done = True
            report.restart_tested = True
            try:
                _restart_systemd_service(systemd_service)
                from cricheroes_cdp_client import wait_for_cdp

                if not wait_for_cdp(cdp_url, timeout_sec=120):
                    report.restart_success = False
                    LOG.error("CDP not back after service restart")
                else:
                    index += 1
                    post = _run_one_attempt(index, url, cdp_url, timeout_sec, save_html=save_html)
                    post.note = "post-systemd-restart"
                    report.attempts.append(post)
                    report.restart_success = post.ok
                    _log_attempt(post)
            except Exception as exc:
                report.restart_success = False
                report.restart_error = str(exc)
                LOG.error("Service restart test failed: %s", exc)

        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        sleep_for = min(interval_sec, remaining)
        LOG.info("Sleeping %.0fs until next attempt...", sleep_for)
        time.sleep(sleep_for)

    report.ended_at = _utc_now_iso()
    return report


def _log_attempt(attempt: AttemptRecord) -> None:
    status = "OK" if attempt.ok else ("CHALLENGE" if attempt.challenged else "FAIL")
    extra = f" note={attempt.note}" if attempt.note else ""
    LOG.info(
        "[%s] attempt=%s http=%s elapsed=%.1fs title=%r%s",
        status,
        attempt.index,
        attempt.http_status,
        attempt.elapsed_sec,
        attempt.title,
        extra,
    )
    if attempt.challenged:
        LOG.error(
            "[cricheroes-spike] Session re-challenged — human must VNC in and re-solve"
        )


def _write_report(report: TestReport, path: Path) -> None:
    payload = {
        **asdict(report),
        "success_rate": report.success_rate(),
        "success_count": report.success_count,
        "challenge_count": report.challenge_count,
    }
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    LOG.info("Wrote report to %s", path)


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="CricHeroes persistent browser spike test")
    parser.add_argument("--url", default=DEFAULT_URL)
    parser.add_argument("--cdp-url", default=DEFAULT_CDP_URL)
    parser.add_argument("--interval-sec", type=int, default=DEFAULT_INTERVAL_SEC)
    parser.add_argument("--duration-sec", type=int, default=DEFAULT_DURATION_SEC)
    parser.add_argument("--timeout-sec", type=int, default=30)
    parser.add_argument(
        "--restart-after-min",
        type=int,
        default=None,
        help="Restart cricheroes-browser.service after N minutes and scrape again",
    )
    parser.add_argument("--systemd-service", default="cricheroes-browser.service")
    parser.add_argument("--smoke", action="store_true", help="Single scrape only")
    parser.add_argument(
        "--save-html",
        type=Path,
        default=None,
        help="Save first non-challenge HTML to this path (for DOM/fixture inspection)",
    )
    parser.add_argument("--report", type=Path, default=REPORT_PATH)
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args(argv)

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
    )

    report = run_test(
        url=args.url,
        cdp_url=args.cdp_url,
        interval_sec=args.interval_sec,
        duration_sec=args.duration_sec,
        timeout_sec=args.timeout_sec,
        restart_after_min=args.restart_after_min,
        systemd_service=args.systemd_service,
        smoke=args.smoke,
        save_html=args.save_html,
    )

    for line in report.summary_lines():
        print(line)

    _write_report(report, args.report)
    return 0 if report.success_count > 0 and report.challenge_count == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
