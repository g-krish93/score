"""
Transform a Play Cricket scraper snapshot into the overlay-ready JSON schema
consumed by cricket_overlay.html.

Overlay schema contract (all keys always present, safe defaults):
{
    home_team, away_team, total_overs, batting_team,
    match: { date, competition, status, toss },
    innings: [ { number, batting_team, runs, wickets, overs, extras, batters, bowlers } ],
    striker: { name, runs, balls, sr },
    non_striker: { name, runs, balls },
    current_bowler: { name, overs, runs, wickets, econ },
    current_partnership: { runs, balls },
    recent_over: [],    # always empty for Play Cricket HTML relay
    target: int|None,
    stale: bool,
    last_updated: str|None
}
"""
from __future__ import annotations

import re
from typing import Any

# Strips format/squad suffixes like "(Twenty20)", "(Midweek XI)", "(2nd XI)" from team names
_TEAM_SUFFIX_RE = re.compile(
    r"\s*\([^)]*(?:XI|T20|Twenty|Midweek|Sunday|Saturday|Wednesday|Thursday|Friday|Seconds|2nds?)\s*[^)]*\)\s*$",
    re.I,
)


def _clean_team_name(name: str) -> str:
    return _TEAM_SUFFIX_RE.sub("", name).strip()


# ── Helpers ───────────────────────────────────────────────────────────────────

def _overs_to_float(overs: Any) -> float:
    """Convert "4" or "4.2" overs string to float balls-equivalent decimal."""
    try:
        parts = str(overs or "0").split(".")
        complete = int(parts[0])
        balls = int(parts[1]) if len(parts) > 1 else 0
        return complete + balls / 6
    except (ValueError, IndexError):
        return 0.0


def _batter_to_overlay(b: dict) -> dict:
    return {
        "name": b.get("name", ""),
        "runs": b.get("runs", 0),
        "balls": b.get("balls", 0),
        "sr": b.get("sr"),
    }


def _bowler_to_overlay(b: dict) -> dict:
    return {
        "name": b.get("name", ""),
        "overs": b.get("overs", "0"),
        "runs": b.get("runs", 0),
        "wickets": b.get("wickets", 0),
        "econ": b.get("economy", 0.0),
    }


def _empty_batter() -> dict:
    return {"name": "", "runs": 0, "balls": 0, "sr": None}


def _empty_bowler() -> dict:
    return {"name": "", "overs": "0", "runs": 0, "wickets": 0, "econ": 0.0}


def _format_batters(batters: list) -> list:
    return [
        {
            "name": b["name"],
            "runs": b.get("runs", 0),
            "balls": b.get("balls", 0),
            "fours": b.get("fours", 0),
            "sixes": b.get("sixes", 0),
            "sr": b.get("sr"),
            "dismissal": b.get("dismissal", ""),
            "status": b.get("status", "out"),
        }
        for b in batters
        if b.get("name")
    ]


def _format_bowlers(bowlers: list) -> list:
    return [
        {
            "name": b["name"],
            "overs": b.get("overs", "0"),
            "maidens": b.get("maidens", 0),
            "runs": b.get("runs", 0),
            "wickets": b.get("wickets", 0),
            "economy": b.get("economy", 0.0),
        }
        for b in bowlers
        if b.get("name")
    ]


# ── Core business logic ───────────────────────────────────────────────────────

def _derive_live_state(batting: list, bowling: list) -> dict:
    """
    Identify striker, non-striker, current bowler, and partnership from
    the active innings batting/bowling lists.

    Striker  = first batter with status 'not_out' (by batting order)
    Non-striker = second batter with status 'not_out'
    Current bowler = last bowler in list who has bowled at least 1 ball
    Partnership runs/balls ≈ sum of not-out batters (approximate for openers;
    accurate after first wicket falls)
    """
    not_out = [b for b in batting if b.get("status") == "not_out"]
    striker = _batter_to_overlay(not_out[0]) if len(not_out) >= 1 else _empty_batter()
    non_striker = _batter_to_overlay(not_out[1]) if len(not_out) >= 2 else _empty_batter()

    # Current bowler: last in list with overs > 0
    current_bowler = _empty_bowler()
    for b in reversed(bowling):
        if _overs_to_float(b.get("overs", "0")) > 0:
            current_bowler = _bowler_to_overlay(b)
            break

    partner_runs = striker["runs"] + non_striker["runs"]
    partner_balls = striker["balls"] + non_striker["balls"]

    return {
        "striker": striker,
        "non_striker": non_striker,
        "current_bowler": current_bowler,
        "current_partnership": {"runs": partner_runs, "balls": partner_balls},
    }


def _parse_home_away(snapshot: dict) -> tuple[str, str]:
    """Extract home and away team names, stripping format/squad suffixes."""
    title = (snapshot.get("fixture_title") or "").strip()
    if title:
        parts = re.split(r"\s+vs?\.?\s+", title, maxsplit=1, flags=re.I)
        if len(parts) == 2:
            return _clean_team_name(parts[0]), _clean_team_name(parts[1])
    # Fall back to tab team names (has format suffix) or basic innings team names
    tab1 = snapshot.get("innings_1_tab_team") or (snapshot.get("innings_1") or {}).get("team", "")
    tab2 = snapshot.get("innings_2_tab_team") or (snapshot.get("innings_2") or {}).get("team", "")
    return _clean_team_name(tab1), _clean_team_name(tab2)


def _build_match_meta(snapshot: dict) -> dict:
    """Build the match metadata object for the overlay."""
    status = (snapshot.get("status") or "").strip()
    if not status:
        status = "IN PROGRESS" if snapshot.get("innings_1") else "NOT STARTED"

    # Combine date + start time into "DD Month YYYY @ HH:MM" if available
    date_raw = (snapshot.get("fixture_date") or "").strip()
    time_raw = (snapshot.get("fixture_start_time") or "").strip()
    if date_raw and time_raw:
        date_combined = f"{date_raw} @ {time_raw}"
    elif date_raw:
        date_combined = date_raw
    else:
        date_combined = ""

    return {
        "date": date_combined,
        "competition": (snapshot.get("fixture_competition") or "").strip(),
        "status": status.upper(),
        "toss": (snapshot.get("toss_note") or "").strip(),
    }


def _infer_total_overs(snapshot: dict, innings: list) -> int:
    """
    Infer match format (overs per side) from scraper data.
    Priority: tab team name text (contains format like "(Twenty20)") → fixture title → default 40.
    """
    candidates = [
        snapshot.get("innings_1_tab_team", ""),
        snapshot.get("innings_2_tab_team", ""),
        snapshot.get("fixture_title", ""),
        snapshot.get("fixture_competition", ""),
    ]
    combined = " ".join(c for c in candidates if c).lower()
    if "twenty20" in combined or " t20" in combined:
        return 20
    # Fallback: if both innings completed at ≤ 20 complete overs → T20
    all_overs = []
    for inn in innings:
        try:
            all_overs.append(int(str(inn.get("overs", "0")).split(".")[0]))
        except ValueError:
            pass
    if all_overs and max(all_overs) <= 20 and all(o > 0 for o in all_overs):
        return 20
    return 40


# ── Public API ────────────────────────────────────────────────────────────────

def snapshot_to_overlay(
    snapshot: dict,
    stale: bool = False,
    last_ok_at: Any = None,
) -> dict:
    """
    Convert a raw Play Cricket scraper snapshot (from scrape_match()) into the
    overlay-ready JSON schema expected by cricket_overlay.html.

    Handles all match phases: pre-match, 1st innings live, 2nd innings live,
    complete. All output keys are always present with safe defaults.
    """
    inn1_raw = snapshot.get("innings_1")
    inn2_raw = snapshot.get("innings_2")

    bat1: list = snapshot.get("innings_1_batting") or []
    bowl1: list = snapshot.get("innings_1_bowling") or []
    ext1: dict = snapshot.get("innings_1_extras") or {}
    bat2: list = snapshot.get("innings_2_batting") or []
    bowl2: list = snapshot.get("innings_2_bowling") or []
    ext2: dict = snapshot.get("innings_2_extras") or {}

    home, away = _parse_home_away(snapshot)
    match_meta = _build_match_meta(snapshot)

    _empty_extras: dict = {
        "total": 0, "byes": 0, "leg_byes": 0, "wides": 0, "no_balls": 0,
    }

    # ── Pre-match: no innings data yet ───────────────────────────────────────
    if not inn1_raw:
        return {
            "home_team": home,
            "away_team": away,
            "total_overs": 0,
            "batting_team": "",
            "match": match_meta,
            "innings": [],
            "striker": _empty_batter(),
            "non_striker": _empty_batter(),
            "current_bowler": _empty_bowler(),
            "current_partnership": {"runs": 0, "balls": 0},
            "recent_over": [],
            "target": None,
            "stale": stale,
            "last_updated": last_ok_at,
        }

    # ── Build innings array ───────────────────────────────────────────────────
    innings: list = []
    innings.append({
        "number": 1,
        "batting_team": _clean_team_name(inn1_raw.get("team", "")),
        "runs": inn1_raw.get("runs", 0),
        "wickets": inn1_raw.get("wickets", 0),
        "overs": inn1_raw.get("overs", "0"),
        "extras": ext1 if ext1 else dict(_empty_extras),
        "batters": _format_batters(bat1),
        "bowlers": _format_bowlers(bowl1),
    })
    if inn2_raw:
        innings.append({
            "number": 2,
            "batting_team": _clean_team_name(inn2_raw.get("team", "")),
            "runs": inn2_raw.get("runs", 0),
            "wickets": inn2_raw.get("wickets", 0),
            "overs": inn2_raw.get("overs", "0"),
            "extras": ext2 if ext2 else dict(_empty_extras),
            "batters": _format_batters(bat2),
            "bowlers": _format_bowlers(bowl2),
        })

    total_overs = _infer_total_overs(snapshot, innings)

    # ── Derive live state from active innings ─────────────────────────────────
    active_bat = bat2 if inn2_raw else bat1
    active_bowl = bowl2 if inn2_raw else bowl1
    live = _derive_live_state(active_bat, active_bowl)

    # ── Target for 2nd innings ────────────────────────────────────────────────
    target = (inn1_raw.get("runs", 0) + 1) if inn2_raw else None

    # ── Batting team (currently active) ──────────────────────────────────────
    active_inn_raw = inn2_raw or inn1_raw
    batting_team = _clean_team_name(active_inn_raw.get("team", ""))

    return {
        "home_team": home,
        "away_team": away,
        "total_overs": total_overs,
        "batting_team": batting_team,
        "match": match_meta,
        "innings": innings,
        "striker": live["striker"],
        "non_striker": live["non_striker"],
        "current_bowler": live["current_bowler"],
        "current_partnership": live["current_partnership"],
        "recent_over": [],   # Play Cricket HTML has no ball-by-ball; PCS BLE has it
        "target": target,
        "stale": stale,
        "last_updated": last_ok_at,
    }
