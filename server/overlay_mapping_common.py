"""Shared overlay mapping helpers for scraper snapshots (Play-Cricket, CricHeroes, etc.)."""
from __future__ import annotations

import re
from typing import Any


_TEAM_SUFFIX_RE = re.compile(
    r"\s*(?:"
    r"\([^)]*(?:XI|T20|Twenty|Midweek|Sunday|Saturday|Wednesday|Thursday|Friday|Seconds?|2nds?|Ladies)[^)]*\)"
    r"|"
    r"-\s*(?:Twenty20?|T20|(?:Midweek|Sunday|Saturday|Wednesday|Thursday|Friday|Ladies|Seconds?|2nds?)(?:\s+XI)?|(?:[\w]+\s+)?XI)"
    r")\s*$",
    re.I,
)


def clean_team_name(name: str) -> str:
    return _TEAM_SUFFIX_RE.sub("", name).strip()


def overs_to_float(overs: Any) -> float:
    try:
        parts = str(overs or "0").split(".")
        complete = int(parts[0])
        balls = int(parts[1]) if len(parts) > 1 else 0
        return complete + balls / 6
    except (ValueError, IndexError):
        return 0.0


def batter_to_overlay(b: dict) -> dict:
    return {
        "name": b.get("name", ""),
        "runs": b.get("runs", 0),
        "balls": b.get("balls", 0),
        "sr": b.get("sr"),
    }


def bowler_to_overlay(b: dict) -> dict:
    return {
        "name": b.get("name", ""),
        "overs": b.get("overs", "0"),
        "runs": b.get("runs", 0),
        "wickets": b.get("wickets", 0),
        "econ": b.get("economy", 0.0),
    }


def empty_batter() -> dict:
    return {"name": "", "runs": 0, "balls": 0, "sr": None}


def empty_bowler() -> dict:
    return {"name": "", "overs": "0", "runs": 0, "wickets": 0, "econ": 0.0}


def format_batters(batters: list) -> list:
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


def format_bowlers(bowlers: list) -> list:
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


def last_over_balls(snapshot: dict, active_inn_raw: dict) -> list[str]:
    """
    Return the ball sequence for the last completed over, sourced from the
    ball_by_ball dict that play_cricket_scraper populates via Playwright.
    Only shown from the 2nd over onwards (so the first completed over is visible).
    """
    bbb: dict = snapshot.get("ball_by_ball") or {}
    if not bbb:
        return []
    try:
        completed = int(str(active_inn_raw.get("overs", "0")).split(".")[0])
    except (ValueError, TypeError):
        return []
    if completed < 2:
        return []
    # Try exact match first, then one over earlier as fallback
    balls = bbb.get(completed) or bbb.get(str(completed))
    if not balls:
        balls = bbb.get(completed - 1) or bbb.get(str(completed - 1))
    return balls or []


def derive_live_state(batting: list, bowling: list) -> dict:
    not_out = [b for b in batting if b.get("status") == "not_out"]
    striker = batter_to_overlay(not_out[0]) if len(not_out) >= 1 else empty_batter()
    non_striker = batter_to_overlay(not_out[1]) if len(not_out) >= 2 else empty_batter()

    current_bowler = empty_bowler()
    for b in reversed(bowling):
        if overs_to_float(b.get("overs", "0")) > 0:
            current_bowler = bowler_to_overlay(b)
            break

    partner_runs = striker["runs"] + non_striker["runs"]
    partner_balls = striker["balls"] + non_striker["balls"]

    return {
        "striker": striker,
        "non_striker": non_striker,
        "current_bowler": current_bowler,
        "current_partnership": {"runs": partner_runs, "balls": partner_balls},
    }


def parse_home_away(snapshot: dict) -> tuple[str, str]:
    title = (snapshot.get("fixture_title") or "").strip()
    if title:
        parts = re.split(r"\s+vs?\.?\s+", title, maxsplit=1, flags=re.I)
        if len(parts) == 2:
            return clean_team_name(parts[0]), clean_team_name(parts[1])
    tab1 = snapshot.get("innings_1_tab_team") or (snapshot.get("innings_1") or {}).get("team", "")
    tab2 = snapshot.get("innings_2_tab_team") or (snapshot.get("innings_2") or {}).get("team", "")
    return clean_team_name(tab1), clean_team_name(tab2)


def build_match_meta(snapshot: dict) -> dict:
    status = (snapshot.get("status") or "").strip()
    if not status:
        status = "IN PROGRESS" if snapshot.get("innings_1") else "NOT STARTED"

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


def infer_total_overs(snapshot: dict, innings: list) -> int:
    candidates = [
        snapshot.get("innings_1_tab_team", ""),
        snapshot.get("innings_2_tab_team", ""),
        snapshot.get("fixture_title", ""),
        snapshot.get("fixture_competition", ""),
        (snapshot.get("innings_1") or {}).get("team", ""),
        (snapshot.get("innings_2") or {}).get("team", ""),
        snapshot.get("home_team_raw", ""),
        snapshot.get("away_team_raw", ""),
    ]
    combined = " ".join(c for c in candidates if c).lower()
    if "twenty20" in combined or " t20" in combined or combined.startswith("t20"):
        return 20
    all_overs = []
    for inn in innings:
        try:
            all_overs.append(int(str(inn.get("overs", "0")).split(".")[0]))
        except ValueError:
            pass
    if all_overs and max(all_overs) <= 20 and all(o > 0 for o in all_overs):
        return 20
    return 40


def snapshot_to_overlay(
    snapshot: dict,
    stale: bool = False,
    last_ok_at: Any = None,
) -> dict:
    """Convert a scraper snapshot dict into cricket_overlay.html JSON."""
    inn1_raw = snapshot.get("innings_1")
    inn2_raw = snapshot.get("innings_2")

    bat1: list = snapshot.get("innings_1_batting") or []
    bowl1: list = snapshot.get("innings_1_bowling") or []
    ext1: dict = snapshot.get("innings_1_extras") or {}
    bat2: list = snapshot.get("innings_2_batting") or []
    bowl2: list = snapshot.get("innings_2_bowling") or []
    ext2: dict = snapshot.get("innings_2_extras") or {}

    home, away = parse_home_away(snapshot)
    match_meta = build_match_meta(snapshot)

    _empty_extras: dict = {
        "total": 0, "byes": 0, "leg_byes": 0, "wides": 0, "no_balls": 0,
    }

    if not inn1_raw:
        return {
            "home_team": home,
            "away_team": away,
            "total_overs": infer_total_overs(snapshot, []),
            "batting_team": "",
            "match": match_meta,
            "innings": [],
            "striker": empty_batter(),
            "non_striker": empty_batter(),
            "current_bowler": empty_bowler(),
            "current_partnership": {"runs": 0, "balls": 0},
            "recent_over": last_over_balls(snapshot, {}),
            "target": None,
            "stale": stale,
            "last_updated": last_ok_at,
        }

    innings: list = []
    innings.append({
        "number": 1,
        "batting_team": clean_team_name(inn1_raw.get("team", "")),
        "runs": inn1_raw.get("runs", 0),
        "wickets": inn1_raw.get("wickets", 0),
        "overs": inn1_raw.get("overs", "0"),
        "extras": ext1 if ext1 else dict(_empty_extras),
        "batters": format_batters(bat1),
        "bowlers": format_bowlers(bowl1),
    })
    if inn2_raw:
        innings.append({
            "number": 2,
            "batting_team": clean_team_name(inn2_raw.get("team", "")),
            "runs": inn2_raw.get("runs", 0),
            "wickets": inn2_raw.get("wickets", 0),
            "overs": inn2_raw.get("overs", "0"),
            "extras": ext2 if ext2 else dict(_empty_extras),
            "batters": format_batters(bat2),
            "bowlers": format_bowlers(bowl2),
        })

    total_overs = infer_total_overs(snapshot, innings)

    active_bat = bat2 if inn2_raw else bat1
    active_bowl = bowl2 if inn2_raw else bowl1
    live = derive_live_state(active_bat, active_bowl)

    target = (inn1_raw.get("runs", 0) + 1) if inn2_raw else None

    active_inn_raw = inn2_raw or inn1_raw
    batting_team = clean_team_name(active_inn_raw.get("team", ""))

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
        "recent_over": last_over_balls(snapshot, active_inn_raw),
        "target": target,
        "stale": stale,
        "last_updated": last_ok_at,
    }
