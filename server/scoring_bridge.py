"""Translate the legacy /ball + /setup inputs into cricrelay_core events.

This is the seam of the strangler cut-over: the existing routes keep their
behaviour, but (when dual-write is on) each action is also expressed as a core
event so the new engine can be shadow-compared against the legacy one.

Coverage:
- Standard deliveries (., 1-6, W, Wd, Nb, Bye, Lb) map directly.
- A Wd/Nb/Bye/Lb that ALSO carries a run_out/stumped dismissal is expressed as a
  single Delivery with ``extra_wicket=True`` (the extra runs count, the named
  batter is out, the bowler is not credited) — matching the legacy engine.
"""
from __future__ import annotations

from cricrelay_core import Delivery, Outcome, Penalty, Retire, StartInnings

_BALL_OUTCOME = {
    ".": Outcome.DOT,
    "1": Outcome.ONE,
    "2": Outcome.TWO,
    "3": Outcome.THREE,
    "4": Outcome.FOUR,
    "6": Outcome.SIX,
    "W": Outcome.WICKET,
    "Wd": Outcome.WIDE,
    "Nb": Outcome.NO_BALL,
    "Bye": Outcome.BYE,
    "Lb": Outcome.LEG_BYE,
}

_EXTRA_OUTCOMES = (Outcome.WIDE, Outcome.NO_BALL, Outcome.BYE, Outcome.LEG_BYE)


def ball_to_delivery(
    ball_type: str,
    run_bonus: int = 0,
    out_batter: str = "striker",
    dismissal_kind: str = "",
) -> Delivery:
    outcome = _BALL_OUTCOME[ball_type]  # KeyError on unknown type -> caller logs
    is_extra = outcome in _EXTRA_OUTCOMES
    runs = int(run_bonus) if is_extra else 0
    # A run-out/stumping recorded on an extra is a wicket on that extra.
    extra_wicket = bool(is_extra and dismissal_kind)
    return Delivery(
        outcome=outcome,
        runs=runs,
        out_batter=out_batter or "striker",
        dismissal_kind=dismissal_kind or "",
        extra_wicket=extra_wicket,
    )


def penalty_event(runs: int, to_batting: bool = True) -> Penalty:
    """Map a legacy penalty action to a core Penalty event."""
    return Penalty(runs=int(runs), to_batting=bool(to_batting))


def retire_event(batter: str = "striker", out: bool = False) -> Retire:
    """Map a legacy retire action to a core Retire event."""
    return Retire(batter=batter or "striker", out=bool(out))


def setup_to_start_innings(state: dict) -> StartInnings:
    batting = tuple(
        p.get("name", "") for p in state.get("batting_squad", []) if p.get("name")
    )
    bowling = tuple(
        p.get("name", "") for p in state.get("bowling_squad", []) if p.get("name")
    )
    return StartInnings(
        batting_team=state.get("batting_team", ""),
        bowling_team=state.get("bowling_team", ""),
        total_overs=int(state.get("total_overs", 20) or 20),
        batting_order=batting,
        bowling_order=bowling,
        target=state.get("target"),
    )
