"""Parse CricHeroes scorecard pages into snapshots (Playwright + BeautifulSoup).

Status: selector discovery pending — run a spike from production EC2 before enabling
auto-poll (``CRICHEROES_AUTO_POLL=1``). Until then, stream rows can be created but the
relay poller will not scrape CricHeroes URLs.
"""
from __future__ import annotations

import re
from dataclasses import asdict, dataclass
from typing import Optional
from urllib.parse import urlparse

from bs4 import BeautifulSoup

from .models_cricrelay import canonicalize_cricheroes_scrape_url

DEFAULT_TIMEOUT = 20
SCORE_LINE_RE = re.compile(
    r"^(?P<team>.+?)\s+(?P<runs>\d+)\s*/\s*(?P<wkts>\d+)\s*\((?P<overs>[\d.]+)\s*ov"
    r"(?:ers?)?\)",
    re.I,
)
INNINGS_SCORE_RE = re.compile(
    r"(?P<runs>\d+)\s*/\s*(?P<wkts>\d+)\s*\((?P<overs>[\d.]+)\)",
)


class CricHeroesBlockedError(RuntimeError):
    """Raised when CricHeroes WAF or bot protection blocks the fetch."""


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
    try:
        from playwright.sync_api import TimeoutError as PlaywrightTimeoutError
        from playwright.sync_api import sync_playwright
    except ImportError as exc:
        raise RuntimeError(
            "playwright is not installed — run: pip install playwright && playwright install chromium"
        ) from exc

    headers = {
        "User-Agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
        )
    }
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        try:
            page = browser.new_page(
                user_agent=headers["User-Agent"],
                viewport={"width": 1280, "height": 720},
            )
            response = page.goto(url, wait_until="networkidle", timeout=timeout * 1000)
            if response and response.status in {403, 429, 503}:
                raise CricHeroesBlockedError(f"CricHeroes returned HTTP {response.status}")
            html = page.content()
            low = html.lower()
            if "access denied" in low or "captcha" in low or "cloudflare" in low:
                raise CricHeroesBlockedError("CricHeroes page appears blocked by WAF")
            return html
        except PlaywrightTimeoutError as exc:
            raise CricHeroesBlockedError(f"CricHeroes page load timed out: {exc}") from exc
        finally:
            browser.close()


def _parse_innings_score(text: str, team: str) -> Optional[InningsScore]:
    m = INNINGS_SCORE_RE.search(text or "")
    if not m:
        return None
    return InningsScore(
        team=team.strip(),
        runs=int(m.group("runs")),
        wickets=int(m.group("wkts")),
        overs=m.group("overs"),
    )


def _parse_batting_table(table) -> list:
    rows = []
    if not table:
        return rows
    for tr in table.find_all("tr")[1:]:
        cells = [c.get_text(" ", strip=True) for c in tr.find_all(["td", "th"])]
        if len(cells) < 3:
            continue
        name = cells[0]
        if not name or name.lower() in {"batter", "batsman", "name"}:
            continue
        dismissal = cells[1] if len(cells) > 1 else ""
        runs = 0
        balls = 0
        for cell in cells[2:]:
            if cell.isdigit():
                if runs == 0:
                    runs = int(cell)
                elif balls == 0:
                    balls = int(cell)
        status = "not_out" if dismissal.lower() in {"", "not out", "not-out", "*"} else "out"
        rows.append({
            "name": name,
            "runs": runs,
            "balls": balls,
            "dismissal": dismissal,
            "status": status,
            "fours": 0,
            "sixes": 0,
            "sr": round(runs * 100 / balls, 1) if balls else None,
        })
    return rows


def _parse_bowling_table(table) -> list:
    rows = []
    if not table:
        return rows
    for tr in table.find_all("tr")[1:]:
        cells = [c.get_text(" ", strip=True) for c in tr.find_all(["td", "th"])]
        if len(cells) < 4:
            continue
        name = cells[0]
        if not name or name.lower() in {"bowler", "name"}:
            continue
        overs = cells[1] if len(cells) > 1 else "0"
        maidens = 0
        runs = 0
        wickets = 0
        for cell in cells[2:]:
            if cell.isdigit():
                if runs == 0:
                    runs = int(cell)
                elif wickets == 0:
                    wickets = int(cell)
        econ = 0.0
        try:
            ov_parts = str(overs).split(".")
            complete = int(ov_parts[0])
            balls = int(ov_parts[1]) if len(ov_parts) > 1 else 0
            total_balls = complete * 6 + balls
            if total_balls:
                econ = round(runs * 6 / total_balls, 2)
        except (ValueError, IndexError):
            pass
        rows.append({
            "name": name,
            "overs": overs,
            "maidens": maidens,
            "runs": runs,
            "wickets": wickets,
            "economy": econ,
        })
    return rows


def parse_match_snapshot(url: str, html: str) -> dict:
    """Best-effort parse of rendered CricHeroes HTML. Selectors may need tuning on live pages."""
    soup = BeautifulSoup(html, "html.parser")
    title = (soup.find("h1") or soup.find("title"))
    fixture_title = title.get_text(" ", strip=True) if title else None

    status = None
    for tag in soup.find_all(string=re.compile(r"live|completed|abandon", re.I)):
        status = str(tag).strip()
        break

    team_names: list[str] = []
    for el in soup.select("[class*='team'], [class*='Team']"):
        t = el.get_text(" ", strip=True)
        if t and 2 < len(t) < 80 and t not in team_names:
            team_names.append(t)
    if len(team_names) < 2 and fixture_title:
        parts = re.split(r"\s+vs\.?\s+", fixture_title, maxsplit=1, flags=re.I)
        if len(parts) == 2:
            team_names = [parts[0].strip(), parts[1].strip()]

    innings_blocks = soup.select("[class*='innings'], [class*='Innings'], section")
    inn1: Optional[InningsScore] = None
    inn2: Optional[InningsScore] = None

    score_texts = []
    for el in soup.find_all(string=INNINGS_SCORE_RE):
        score_texts.append(str(el).strip())
    if score_texts:
        inn1 = _parse_innings_score(score_texts[0], team_names[0] if team_names else "Team 1")
        if len(score_texts) > 1:
            inn2 = _parse_innings_score(
                score_texts[1],
                team_names[1] if len(team_names) > 1 else "Team 2",
            )

    tables = soup.find_all("table")
    bat1, bowl1, bat2, bowl2 = [], [], [], []
    if tables:
        bat1 = _parse_batting_table(tables[0])
        if len(tables) > 1:
            bowl1 = _parse_bowling_table(tables[1])
        if len(tables) > 2:
            bat2 = _parse_batting_table(tables[2])
        if len(tables) > 3:
            bowl2 = _parse_bowling_table(tables[3])

    snap = MatchSnapshot(
        source_url=url,
        status=status,
        toss_note=None,
        fixture_title=fixture_title,
        fixture_date=None,
        fixture_start_time=None,
        fixture_ground=None,
        fixture_competition=None,
        innings_1=inn1,
        innings_2=inn2,
    )
    data = snap.to_dict()
    data["innings_1_batting"] = bat1
    data["innings_1_bowling"] = bowl1
    data["innings_2_batting"] = bat2
    data["innings_2_bowling"] = bowl2
    data["innings_1_extras"] = {"total": 0, "byes": 0, "leg_byes": 0, "wides": 0, "no_balls": 0}
    data["innings_2_extras"] = {"total": 0, "byes": 0, "leg_byes": 0, "wides": 0, "no_balls": 0}
    return data


def scrape_match(url: str) -> dict:
    url = canonicalize_cricheroes_scrape_url(url)
    html = fetch_page_html(url)
    return parse_match_snapshot(url, html)
