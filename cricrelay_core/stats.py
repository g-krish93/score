"""Derived match values — computed, never stored.

Keeping these out of the persisted state means we can change how a figure is
calculated without a migration, and clients always see consistent numbers.
"""
from __future__ import annotations

from .scoring import InningsState, MatchState, _all_out_at


def overs_display(legal_balls: int) -> str:
    return f"{legal_balls // 6}.{legal_balls % 6}"


def run_rate(runs: int, legal_balls: int) -> float:
    return round(runs / (legal_balls / 6), 2) if legal_balls else 0.0


def _result(inn: InningsState) -> str | None:
    """Result string once a chase has been decided, else None."""
    if inn.target is None or not inn.closed:
        return None
    if inn.runs >= inn.target:
        wkts_left = _all_out_at(inn) - inn.wickets
        return f"{inn.batting_team} won by {wkts_left} wkt"
    margin = (inn.target - 1) - inn.runs
    if margin == 0:
        return "Match tied"
    return f"{inn.bowling_team} won by {margin} run{'s' if margin != 1 else ''}"


def derived(match: MatchState) -> dict:
    """Return the non-persisted view fields for the current innings."""
    inn = match.current
    if inn is None:
        return {}

    out: dict = {
        "innings": match.innings_no,
        "batting_team": inn.batting_team,
        "bowling_team": inn.bowling_team,
        "runs": inn.runs,
        "wickets": inn.wickets,
        "extras": inn.extras,
        "overs": overs_display(inn.legal_balls),
        "crr": run_rate(inn.runs, inn.legal_balls),
        "striker": inn.striker,
        "non_striker": inn.non_striker,
        "current_over": list(inn.current_over),
        "closed": inn.closed,
    }

    if inn.target is not None:
        balls_remaining = inn.total_overs * 6 - inn.legal_balls
        runs_needed = max(0, inn.target - inn.runs)
        out["target"] = inn.target
        out["runs_needed"] = runs_needed
        out["balls_remaining"] = balls_remaining
        out["rrr"] = (
            round(runs_needed / (balls_remaining / 6), 2) if balls_remaining > 0 else None
        )
        out["result"] = _result(inn)

    return out
