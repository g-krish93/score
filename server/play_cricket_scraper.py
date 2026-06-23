"""Parse Play-Cricket HTML into snapshots and fixture lists."""
import re
from dataclasses import asdict, dataclass
from typing import Optional
from urllib.parse import parse_qs, urljoin, urlparse

import requests
from bs4 import BeautifulSoup

from .models_cricrelay import (
    build_play_cricket_results_url,
    canonicalize_play_cricket_scrape_url,
)

DEFAULT_TIMEOUT = 15
SCORE_LINE_RE = re.compile(
    r"^(?P<team>.+?)\s+(?P<runs>\d+)\s*/\s*(?P<wkts>\d+)\s*\((?P<overs>\d+(?:\.\d+)?)\)$"
)
# Second line of split scorecard row: "/ 3 (20)" split from runs (Play-Cricket HTML often breaks across lines)
RUN_FRAG_TAIL_RE = re.compile(r"^/\s*(?P<wkts>\d+)\s*\((?P<overs>[\d.]+)\)\s*$")
# Score alone on one line (team often on the previous line)
COMPACT_SCORE_ONLY_RE = re.compile(
    r"^(?P<runs>\d+)\s*/\s*(?P<wkts>\d+)\s*\((?P<overs>[\d.]+)\)\s*$"
)
# "Total: 218 ( 20 Overs, 3 Wickets )" appears twice on scorecard pages
TOTAL_SUMMARY_RE = re.compile(
    r"Total:\s*(?P<runs>\d+)\s*\(\s*(?P<overs>[\d.]+)\s*Overs?,\s*(?P<wkts>\d+)\s*Wickets?\s*\)",
    re.I,
)


@dataclass
class InningsScore:
    team: str
    runs: int
    wickets: int
    overs: str


@dataclass
class MatchSnapshot:
    source_url: str
    status: Optional[str]
    toss_note: Optional[str]
    fixture_title: Optional[str]
    fixture_date: Optional[str]
    fixture_start_time: Optional[str]
    fixture_ground: Optional[str]
    fixture_competition: Optional[str]
    innings_1: Optional[InningsScore]
    innings_2: Optional[InningsScore]

    def to_dict(self) -> dict:
        data = asdict(self)
        for key in ("innings_1", "innings_2"):
            if data[key] is None:
                continue
            data[key]["score"] = f"{data[key]['runs']}/{data[key]['wickets']}"
            data[key]["overs_display"] = data[key]["overs"]
        return data


def fetch_page_html(url: str, timeout: int = DEFAULT_TIMEOUT) -> str:
    headers = {
        "User-Agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36"
        )
    }
    response = requests.get(url, timeout=timeout, headers=headers)
    response.raise_for_status()
    return response.text


def parse_score_line(line: str) -> Optional[InningsScore]:
    match = SCORE_LINE_RE.match(line.strip())
    if not match:
        return None
    return InningsScore(
        team=match.group("team").strip(),
        runs=int(match.group("runs")),
        wickets=int(match.group("wkts")),
        overs=match.group("overs"),
    )


def parse_fragmented_innings(text_lines: list[str]) -> list[InningsScore]:
    innings_found: list[InningsScore] = []
    stop_words = {
        "W",
        "L",
        "RUNS",
        "SCORECARD",
        "BALL BY BALL",
        "MATCH STREAM",
        "STATISTICS",
        "VIDEOS",
    }
    for i, line in enumerate(text_lines):
        fragment = RUN_FRAG_TAIL_RE.match(re.sub(r"\s+", " ", line).strip())
        if not fragment:
            continue
        if i < 1 or not text_lines[i - 1].isdigit():
            continue

        runs = int(text_lines[i - 1])
        wickets = int(fragment.group("wkts"))
        overs = fragment.group("overs")

        team_parts = []
        back = i - 2
        while back >= 0 and len(team_parts) < 3:
            candidate = text_lines[back].strip()
            upper_candidate = candidate.upper()
            if (
                not candidate
                or candidate.isdigit()
                or "WON THE TOSS" in upper_candidate
                or upper_candidate in stop_words
                or "@" in candidate
            ):
                break
            team_parts.append(candidate)
            back -= 1

        team = " ".join(reversed(team_parts)).strip()
        if not team:
            continue

        innings_found.append(
            InningsScore(
                team=team,
                runs=runs,
                wickets=wickets,
                overs=overs,
            )
        )
        if len(innings_found) >= 2:
            break

    return innings_found


def _looks_like_team_name(line: str) -> bool:
    s = line.strip()
    if len(s) < 2 or len(s) > 120:
        return False
    low = s.lower()
    skip = (
        "please wait",
        "won by",
        "won the toss",
        "scorecard",
        "ball by ball",
        "statistics",
        "points breakdown",
        "share",
        "extras:",
        "total:",
        "game points",
        "penalty points",
    )
    if any(x in low for x in skip):
        return False
    if s.startswith("|") or s.startswith("---"):
        return False
    if re.match(r"^\d+$", s):
        return False
    if COMPACT_SCORE_ONLY_RE.match(s) or SCORE_LINE_RE.match(s):
        return False
    return True


def normalize_split_score_lines(lines: list[str]) -> list[str]:
    """Merge ``218`` + ``/ 3 (20)`` split across two lines (common on Play-Cricket result pages)."""
    out: list[str] = []
    i = 0
    while i < len(lines):
        a = lines[i].strip()
        if i + 1 < len(lines):
            b = lines[i + 1].strip()
            if re.match(r"^\d+$", a) and RUN_FRAG_TAIL_RE.match(b):
                merged = re.sub(r"\s+", " ", f"{a} {b}".strip())
                out.append(merged)
                i += 2
                continue
        out.append(lines[i])
        i += 1
    return out


def normalize_total_lines(lines: list[str]) -> list[str]:
    """Merge ``Total:`` with the following ``218 ( 20 Overs, 3 Wickets )`` line."""
    out: list[str] = []
    i = 0
    while i < len(lines):
        s = lines[i].strip()
        if i + 1 < len(lines):
            nxt = lines[i + 1].strip()
            if s.lower() in {"total", "total:"} and re.search(r"overs", nxt, re.I):
                out.append(f"Total: {nxt}")
                i += 2
                continue
        out.append(lines[i])
        i += 1
    return out


def _is_squad_label(line: str) -> bool:
    t = line.lower()
    hints = ("twenty20", "t20", "midweek", "1st xi", "2nd xi", "3rd xi", "sunday xi")
    return any(h in t for h in hints)


def resolve_team_label(lines: list[str], idx: int) -> str:
    """Pick club (+ squad) label for a compact score line at ``idx``."""
    if idx < 1:
        return "Batting"
    prev = lines[idx - 1].strip()
    prev2 = lines[idx - 2].strip() if idx >= 2 else ""
    if prev2 and _looks_like_team_name(prev2) and _is_squad_label(prev):
        return f"{prev2} ({prev})"
    if _looks_like_team_name(prev):
        return prev
    if prev2 and _looks_like_team_name(prev2):
        return prev2
    return prev or "Batting"


def parse_compact_scores_prev_team(text_lines: list[str]) -> list[InningsScore]:
    """Lines like ``218 / 3 (20)`` with club/squad name on the previous line."""
    out: list[InningsScore] = []
    for i, line in enumerate(text_lines):
        m = COMPACT_SCORE_ONLY_RE.match(line.strip())
        if not m:
            continue
        team = resolve_team_label(text_lines, i)
        out.append(
            InningsScore(
                team=team,
                runs=int(m.group("runs")),
                wickets=int(m.group("wkts")),
                overs=m.group("overs"),
            )
        )
        if len(out) >= 2:
            break
    return out


def parse_totals_summary_lines(full_text: str) -> list[InningsScore]:
    """Fallback from ``Total: 218 ( 20 Overs, 3 Wickets )`` blocks."""
    out: list[InningsScore] = []
    for m in TOTAL_SUMMARY_RE.finditer(full_text):
        out.append(
            InningsScore(
                team=f"Innings {len(out) + 1}",
                runs=int(m.group("runs")),
                wickets=int(m.group("wkts")),
                overs=m.group("overs"),
            )
        )
        if len(out) >= 2:
            break
    return out


def batting_order_runs(full_text: str) -> list[int]:
    """Runs for each innings in true batting order (1st innings first).

    Play-Cricket's header summary lists the *currently batting* (2nd) innings
    first while a chase is live, but the detailed scorecard body always renders
    the ``Total:`` blocks in batting order. Those totals are the authoritative
    signal for which innings batted first.
    """
    return [int(m.group("runs")) for m in TOTAL_SUMMARY_RE.finditer(full_text)]


def order_innings_by_batting(
    innings_found: list[InningsScore], full_text: str
) -> tuple[Optional[InningsScore], Optional[InningsScore]]:
    """Return (innings_1, innings_2) in batting order.

    The header parsers preserve team names but can list the innings in reverse
    (live-chase) order, which makes the chase target collapse to the batting
    side's own score + 1. Re-rank the parsed innings against the scorecard
    ``Total:`` blocks so the first-batting side is always ``innings_1``. A single
    detected total is enough to pin the first innings; if none are found we keep
    the original parse order rather than guess.
    """
    i1 = innings_found[0] if len(innings_found) >= 1 else None
    i2 = innings_found[1] if len(innings_found) >= 2 else None
    if not (i1 and i2):
        return i1, i2

    order = batting_order_runs(full_text)
    if len(order) < 1:
        return i1, i2

    last = len(order)

    def rank(inv: InningsScore) -> int:
        try:
            return order.index(inv.runs)
        except ValueError:
            return last

    # Stable sort keeps the original order on ties (e.g. both innings equal).
    ranked = sorted([i1, i2], key=rank)
    return ranked[0], ranked[1]


def parse_match_snapshot(url: str, html: str) -> MatchSnapshot:
    soup = BeautifulSoup(html, "html.parser")
    raw_lines = [
        line.strip()
        for line in soup.get_text("\n").splitlines()
        if line.strip() and line.strip() != "Please wait..."
    ]
    lines = normalize_total_lines(normalize_split_score_lines(raw_lines))

    status = None
    for i, line in enumerate(raw_lines):
        upper_line = line.upper()
        if "WON BY" in upper_line:
            parts = [line]
            if i + 1 < len(raw_lines) and raw_lines[i + 1].isalpha():
                parts.append(raw_lines[i + 1])
            status = " ".join(parts)
            break
    toss_note = next((line for line in raw_lines if "won the toss" in line.lower()), None)
    fixture_title = next((line for line in raw_lines if " vs. " in line.lower() or " vs " in line.lower()), None)
    if not fixture_title:
        for i, line in enumerate(raw_lines):
            if line.strip().lower() in {"vs", "vs."} and 0 < i < len(raw_lines) - 1:
                left = raw_lines[i - 1].strip()
                right = raw_lines[i + 1].strip()
                if left and right and left.lower() not in {"fixture"} and right.lower() not in {"share"}:
                    fixture_title = f"{left} vs {right}"
                    break

    # Parse detail rows commonly shown on Play-Cricket match_details pages.
    known_labels = {
        "date",
        "start time",
        "ground",
        "match type",
        "match rules",
        "umpires",
        "referee",
        "scorers",
        "meeting place",
        "meeting time",
    }

    def value_after(label: str) -> Optional[str]:
        low = label.lower()
        for i, line in enumerate(raw_lines):
            if line.strip().lower() == low:
                for j in range(i + 1, min(i + 6, len(raw_lines))):
                    val = raw_lines[j].strip()
                    if not val:
                        continue
                    if val.lower() in known_labels:
                        break
                    return val or None
                return None
            if line.lower().startswith(low + ":"):
                val = line.split(":", 1)[1].strip()
                return val or None
        return None

    fixture_date = value_after("Date")
    fixture_start_time = value_after("Start Time")
    fixture_ground = value_after("Ground")
    fixture_competition = value_after("Match Type")

    innings_found: list[InningsScore] = []
    seen_sig: set = set()

    def push_innings(inv: InningsScore) -> None:
        sig = (inv.runs, inv.wickets, inv.overs)
        if sig in seen_sig:
            return
        seen_sig.add(sig)
        innings_found.append(inv)

    for line in lines:
        innings = parse_score_line(line)
        if innings:
            push_innings(innings)

    if len(innings_found) < 2:
        for innings in parse_compact_scores_prev_team(lines):
            push_innings(innings)

    if len(innings_found) < 2:
        for innings in parse_fragmented_innings(lines):
            push_innings(innings)

    if len(innings_found) < 2:
        blob = "\n".join(lines)
        for innings in parse_totals_summary_lines(blob):
            push_innings(innings)

    innings_1, innings_2 = order_innings_by_batting(innings_found, "\n".join(lines))

    return MatchSnapshot(
        source_url=url,
        status=status,
        toss_note=toss_note,
        fixture_title=fixture_title,
        fixture_date=fixture_date,
        fixture_start_time=fixture_start_time,
        fixture_ground=fixture_ground,
        fixture_competition=fixture_competition,
        innings_1=innings_1,
        innings_2=innings_2,
    )


def _merge_snapshot_fields(target: dict, source: dict, keys: tuple[str, ...]) -> None:
    for key in keys:
        if not target.get(key) and source.get(key):
            target[key] = source.get(key)


def scrape_match(url: str) -> dict:
    url = canonicalize_play_cricket_scrape_url(url)
    html = fetch_page_html(url)
    snapshot = parse_match_snapshot(url, html)
    data = snapshot.to_dict()

    missing_fixture = not any(
        data.get(k)
        for k in ("fixture_title", "fixture_date", "fixture_start_time", "fixture_ground", "fixture_competition")
    )
    missing_scores = not data.get("innings_1") and not data.get("innings_2")

    parsed = urlparse(url)
    mid = re.search(r"/website/results/(\d+)\b", url.lower())
    match_id = mid.group(1) if mid else None
    if not match_id:
        raw_id = (parse_qs(parsed.query).get("id") or [None])[0]
        if raw_id and str(raw_id).strip().isdigit():
            match_id = str(raw_id).strip()

    # Scores usually live on …/website/results/<id>; match_details often has metadata only.
    if missing_scores and match_id and parsed.netloc:
        results_url = build_play_cricket_results_url(
            f"{parsed.scheme or 'https'}://{parsed.netloc}", match_id
        )
        if results_url and results_url.rstrip("/") != url.rstrip("/"):
            try:
                html_scores = fetch_page_html(results_url)
                snap_scores = parse_match_snapshot(results_url, html_scores).to_dict()
                for inn in ("innings_1", "innings_2"):
                    if not data.get(inn) and snap_scores.get(inn):
                        data[inn] = snap_scores[inn]
                _merge_snapshot_fields(
                    data,
                    snap_scores,
                    ("status", "toss_note", "fixture_title", "fixture_date", "fixture_start_time", "fixture_ground", "fixture_competition"),
                )
                data["source_url"] = results_url
            except Exception:
                pass
            missing_scores = not data.get("innings_1") and not data.get("innings_2")

    # Pre-match results pages may omit fixture rows; match_details still has title/ground.
    if missing_fixture and missing_scores and match_id:
        fallback = f"{parsed.scheme or 'https'}://{parsed.netloc}/match_details?id={match_id}"
        try:
            html2 = fetch_page_html(fallback)
            snap2 = parse_match_snapshot(fallback, html2).to_dict()
            _merge_snapshot_fields(
                data,
                snap2,
                (
                    "fixture_title",
                    "fixture_date",
                    "fixture_start_time",
                    "fixture_ground",
                    "fixture_competition",
                    "status",
                    "toss_note",
                ),
            )
        except Exception:
            pass
    return data


def scrape_fixtures(base_url: str, limit: int = 24) -> list[dict]:
    """Scrape fixture IDs from a club results page for quick stream setup."""
    base = (base_url or "").strip().rstrip("/")
    if not base:
        return []
    html = fetch_page_html(base)
    soup = BeautifulSoup(html, "html.parser")
    out: list[dict] = []
    seen: set[str] = set()

    for a in soup.find_all("a", href=True):
        href = a.get("href") or ""
        full = urljoin(base + "/", href)
        full = canonicalize_play_cricket_scrape_url(full)
        m = re.search(r"/website/results/(\d+)\b", full)
        if not m:
            m = re.search(r"match_details\?id=(\d+)\b", full)
        if not m:
            continue
        mid = m.group(1)
        if mid in seen:
            continue
        seen.add(mid)
        text = " ".join((a.get_text(" ", strip=True) or "").split())
        if text.lower() in {"", "search", "location_on"}:
            # On /Matches pages the clickable icon often has text "search";
            # use nearby row content to derive a meaningful fixture label.
            parent = a
            row_text = ""
            for _ in range(6):
                parent = parent.parent
                if not parent:
                    break
                candidate = " ".join(parent.get_text(" ", strip=True).split())
                if " vs " in candidate.lower() or " v " in candidate.lower():
                    row_text = candidate
                    break
            if row_text:
                row_text = re.sub(r"\b(Form Guide|search|location_on)\b", "", row_text, flags=re.I)
                row_text = re.sub(r"(?:\b[WLDT]\b\s*){2,}", "", row_text)
                row_text = re.sub(r"\s+", " ", row_text).strip(" -|")
                m_vs = re.search(r"([A-Za-z0-9 ,.'&()-]{8,}?)\s+Vs\s+([A-Za-z0-9 ,.'&()-]{8,})", row_text, flags=re.I)
                if m_vs:
                    text = f"{m_vs.group(1).strip()} vs {m_vs.group(2).strip()}"[:140]
                else:
                    text = row_text[:140]
        out.append(
            {
                "match_id": mid,
                "label": text or f"Fixture {mid}",
                "url": full,
            }
        )
        if len(out) >= max(1, int(limit)):
            break
    return out
