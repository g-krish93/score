"""The scoring reducer: fold a log of events into a match scoreboard.

Pure and deterministic — `reduce(events)` always yields the same `MatchState`
for the same input, which is exactly what makes it trivially unit-testable and
safe to run identically in a request, a worker, or the realtime pusher.
"""
from __future__ import annotations

from dataclasses import dataclass, field

from .events import BAT_RUNS, LEGAL_BALLS, Delivery, Event, Outcome, StartInnings


class InvalidEvent(Exception):
    """Raised when an event cannot be applied to the current state."""


@dataclass
class BatterStat:
    name: str
    runs: int = 0
    balls: int = 0
    out: bool = False
    dismissal: str = ""


@dataclass
class BowlerStat:
    name: str
    balls: int = 0  # legal balls bowled
    runs: int = 0  # runs conceded (byes/leg-byes excluded)
    wickets: int = 0


@dataclass
class InningsState:
    batting_team: str
    bowling_team: str
    total_overs: int
    target: int | None = None
    runs: int = 0
    wickets: int = 0
    legal_balls: int = 0
    extras: int = 0
    striker: str = ""
    non_striker: str = ""
    bowler: str = ""
    batting_order: tuple[str, ...] = ()
    next_batter_idx: int = 0
    batters: dict[str, BatterStat] = field(default_factory=dict)
    bowlers: dict[str, BowlerStat] = field(default_factory=dict)
    current_over: list[str] = field(default_factory=list)
    closed: bool = False


@dataclass
class MatchState:
    innings: list[InningsState] = field(default_factory=list)
    innings_no: int = 0
    current: InningsState | None = None
    first_innings_runs: int | None = None


# --- helpers ---------------------------------------------------------------


def _all_out_at(inn: InningsState) -> int:
    """Wicket count at which the innings is all out (squad size - 1, or 10)."""
    return (len(inn.batting_order) - 1) if inn.batting_order else 10


def _ensure_batter(inn: InningsState, name: str) -> None:
    if name and name not in inn.batters:
        inn.batters[name] = BatterStat(name)


def _ensure_bowler(inn: InningsState, name: str) -> None:
    if name and name not in inn.bowlers:
        inn.bowlers[name] = BowlerStat(name)


def _next_batter(inn: InningsState) -> str:
    """Return the next yet-to-bat name from the order, or '' if none left."""
    if inn.next_batter_idx < len(inn.batting_order):
        name = inn.batting_order[inn.next_batter_idx]
        inn.next_batter_idx += 1
        return name
    return ""


def _maybe_close(inn: InningsState) -> None:
    if inn.wickets >= _all_out_at(inn):
        inn.closed = True
    if inn.legal_balls >= inn.total_overs * 6:
        inn.closed = True
    if inn.target is not None and inn.runs >= inn.target:
        inn.closed = True


def new_innings(start: StartInnings) -> InningsState:
    inn = InningsState(
        batting_team=start.batting_team,
        bowling_team=start.bowling_team,
        total_overs=start.total_overs,
        target=start.target,
        batting_order=start.batting_order,
    )
    for name in start.batting_order:
        inn.batters[name] = BatterStat(name)
    inn.striker = start.batting_order[0] if len(start.batting_order) > 0 else ""
    inn.non_striker = start.batting_order[1] if len(start.batting_order) > 1 else ""
    inn.next_batter_idx = min(2, len(start.batting_order))
    inn.bowler = start.bowling_order[0] if start.bowling_order else "Bowler"
    _ensure_bowler(inn, inn.bowler)
    return inn


# --- the reducer -----------------------------------------------------------


def apply_delivery(inn: InningsState, d: Delivery) -> None:
    """Mutate `inn` by applying a single delivery, enforcing the laws."""
    if inn.closed:
        raise InvalidEvent("cannot bowl: innings is closed")

    _ensure_batter(inn, inn.striker)
    _ensure_bowler(inn, inn.bowler)

    o = d.outcome
    legal = o in LEGAL_BALLS
    runs_to_bat = 0
    runs_to_team = 0
    extra = 0
    cross = False

    if o in BAT_RUNS:
        runs_to_bat = BAT_RUNS[o]
        runs_to_team = runs_to_bat
        cross = runs_to_bat % 2 == 1
    elif o is Outcome.DOT or o is Outcome.WICKET:
        pass
    elif o is Outcome.WIDE:
        extra = 1 + d.runs
        runs_to_team = extra
        cross = d.runs % 2 == 1
    elif o is Outcome.NO_BALL:
        # No-ball: a 1-run penalty (extra) plus any runs off the bat credited to
        # the striker, who faces the delivery (it is not a legal ball of the over
        # but does count as a ball faced). Matches the legacy engine.
        runs_to_bat = d.runs
        extra = 1
        runs_to_team = 1 + d.runs
        cross = d.runs % 2 == 1
    elif o is Outcome.BYE or o is Outcome.LEG_BYE:
        extra = d.runs
        runs_to_team = d.runs
        cross = d.runs % 2 == 1

    inn.runs += runs_to_team
    inn.extras += extra

    striker_stat = inn.batters[inn.striker]
    striker_stat.runs += runs_to_bat
    # The striker faces every legal ball and also a no-ball (a ball faced), but
    # not a wide.
    if legal or o is Outcome.NO_BALL:
        striker_stat.balls += 1

    bowler_stat = inn.bowlers[inn.bowler]
    if legal:
        bowler_stat.balls += 1
    # Byes and leg-byes are not charged to the bowler.
    if o not in (Outcome.BYE, Outcome.LEG_BYE):
        bowler_stat.runs += runs_to_team

    if o is Outcome.WICKET:
        inn.wickets += 1
        bowler_stat.wickets += 1
        out_name = inn.striker if d.out_batter == "striker" else inn.non_striker
        _ensure_batter(inn, out_name)
        inn.batters[out_name].out = True
        inn.batters[out_name].dismissal = d.dismissal_kind or "bowled"
        replacement = _next_batter(inn)
        _ensure_batter(inn, replacement)
        if d.out_batter == "striker":
            inn.striker = replacement
        else:
            inn.non_striker = replacement

    if cross:
        inn.striker, inn.non_striker = inn.non_striker, inn.striker

    inn.current_over.append(o.value)
    if legal:
        inn.legal_balls += 1
        if inn.legal_balls % 6 == 0:
            # Over complete: change of ends, fresh over display.
            inn.striker, inn.non_striker = inn.non_striker, inn.striker
            inn.current_over = []

    _maybe_close(inn)


def reduce(events: list[Event]) -> MatchState:
    """Fold an ordered event log into the current match scoreboard."""
    match = MatchState()
    for e in events:
        if isinstance(e, StartInnings):
            if match.current is not None:
                match.first_innings_runs = match.current.runs
            inn = new_innings(e)
            match.innings.append(inn)
            match.innings_no = len(match.innings)
            match.current = inn
        elif isinstance(e, Delivery):
            if match.current is None:
                raise InvalidEvent("delivery recorded before any innings started")
            apply_delivery(match.current, e)
        else:  # pragma: no cover - guarded by the Event union
            raise InvalidEvent(f"unknown event type: {type(e).__name__}")
    return match
