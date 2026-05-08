"""Parse Play-Cricket HTML into a JSON snapshot (runs/wickets/overs per innings)."""
import re
from dataclasses import asdict, dataclass
from typing import Optional

import requests
from bs4 import BeautifulSoup

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

    innings_1 = innings_found[0] if len(innings_found) >= 1 else None
    innings_2 = innings_found[1] if len(innings_found) >= 2 else None

    return MatchSnapshot(
        source_url=url,
        status=status,
        toss_note=toss_note,
        innings_1=innings_1,
        innings_2=innings_2,
    )


def scrape_match(url: str) -> dict:
    html = fetch_page_html(url)
    snapshot = parse_match_snapshot(url, html)
    return snapshot.to_dict()
