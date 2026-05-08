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
FRAGMENT_WKTS_OVERS_RE = re.compile(r"^/\s*(?P<wkts>\d+)\s*\((?P<overs>\d+(?:\.\d+)?)\)$")


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
        fragment = FRAGMENT_WKTS_OVERS_RE.match(re.sub(r"\s+", " ", line).strip())
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


def parse_match_snapshot(url: str, html: str) -> MatchSnapshot:
    soup = BeautifulSoup(html, "html.parser")
    text_lines = [
        line.strip()
        for line in soup.get_text("\n").splitlines()
        if line.strip() and line.strip() != "Please wait..."
    ]

    status = None
    for i, line in enumerate(text_lines):
        upper_line = line.upper()
        if "WON BY" in upper_line:
            parts = [line]
            if i + 1 < len(text_lines) and text_lines[i + 1].isalpha():
                parts.append(text_lines[i + 1])
            status = " ".join(parts)
            break
    toss_note = next((line for line in text_lines if "won the toss" in line.lower()), None)

    innings_found = []
    for line in text_lines:
        innings = parse_score_line(line)
        if innings:
            innings_found.append(innings)

    if len(innings_found) < 2:
        innings_found = parse_fragmented_innings(text_lines)

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
