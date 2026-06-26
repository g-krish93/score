"""Read-model projections over a folded match — never part of the write path.

A scorecard is just another *view* of the same event log: it reads the reduced
`MatchState` and shapes it for display (full batting/bowling tables, fall of
wickets, partnerships). New analytics belong here, not in the reducer, so the
write path stays untouched.
"""
from __future__ import annotations

from .scoring import InningsState, MatchState
from .stats import overs_display


def _batting_rows(inn: InningsState) -> list[dict]:
    """Batters in batting order, then any others who appeared (e.g. via extras)."""
    seen: list[str] = []
    rows: list[dict] = []
    order = list(inn.batting_order) + [
        n for n in inn.batters.keys() if n not in inn.batting_order
    ]
    for name in order:
        if not name or name in seen:
            continue
        seen.append(name)
        b = inn.batters.get(name)
        if b is None:
            continue
        at_crease = name in (inn.striker, inn.non_striker)
        rows.append(
            {
                "name": name,
                "runs": b.runs,
                "balls": b.balls,
                "out": b.out,
                "dismissal": b.dismissal,
                "on_strike": name == inn.striker,
                "at_crease": at_crease and not b.out,
            }
        )
    return rows


def _bowling_rows(inn: InningsState) -> list[dict]:
    rows: list[dict] = []
    for name, b in inn.bowlers.items():
        if not name:
            continue
        rows.append(
            {
                "name": name,
                "overs": overs_display(b.balls),
                "balls": b.balls,
                "runs": b.runs,
                "wickets": b.wickets,
                "econ": round(b.runs / (b.balls / 6), 2) if b.balls else 0.0,
            }
        )
    return rows


def _partnerships(inn: InningsState) -> list[dict]:
    """Runs added between successive wickets (and the unbroken stand, if any)."""
    out: list[dict] = []
    prev = 0
    for i, fow in enumerate(inn.fall_of_wickets, start=1):
        out.append({"wicket": i, "runs": fow["runs"] - prev})
        prev = fow["runs"]
    if inn.runs > prev or not inn.fall_of_wickets:
        out.append({"wicket": len(inn.fall_of_wickets) + 1, "runs": inn.runs - prev, "unbroken": True})
    return out


def scorecard(match: MatchState) -> dict:
    """Full scorecard for the current innings, computed from the folded state."""
    inn = match.current
    if inn is None:
        return {}
    return {
        "innings": match.innings_no,
        "batting_team": inn.batting_team,
        "bowling_team": inn.bowling_team,
        "runs": inn.runs,
        "wickets": inn.wickets,
        "extras": inn.extras,
        "overs": overs_display(inn.legal_balls),
        "batting": _batting_rows(inn),
        "bowling": _bowling_rows(inn),
        "fall_of_wickets": list(inn.fall_of_wickets),
        "partnerships": _partnerships(inn),
    }
