"""Translate the legacy /ball + /setup inputs into cricrelay_core events.

This is the seam of the strangler cut-over: the existing routes keep their
behaviour, but (when dual-write is on) each action is also expressed as a core
event so the new engine can be shadow-compared against the legacy one.

Coverage:
- Standard deliveries (., 1-6, W, Wd, Nb, Bye, Lb) map directly.

Known gaps (documented so shadow-compare surfaces them rather than silently
recording wrong data):
- A Wd/Nb that ALSO carries a run_out/stumped dismissal: the wicket is not yet
  representable as a single core event, so only the extra runs are captured.
  TODO: extend cricrelay_core.events to carry a dismissal alongside an extra.
"""
from __future__ import annotations

from cricrelay_core import Delivery, Outcome, StartInnings

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
    runs = int(run_bonus) if outcome in _EXTRA_OUTCOMES else 0
    return Delivery(
        outcome=outcome,
        runs=runs,
        out_batter=out_batter or "striker",
        dismissal_kind=dismissal_kind or "",
    )


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
