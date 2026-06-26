"""Domain events for the CricRelay scoring core.

A cricket innings is naturally a *log of deliveries*. We model it that way: the
live scoreboard is a projection computed by folding these events. Storing the
events (not just the latest score) gives us free undo/redo, replay, audit, and
— crucially — lets future analytics (wagon wheel, Manhattan, MVP) be new
projections over the same log without ever touching the write path.

This module is FRAMEWORK-FREE: no Flask, no SQLAlchemy, no I/O. It is the single
canonical home of cricket scoring rules, so the web app, background workers and
the realtime pusher all compute identical results.
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


class Outcome(str, Enum):
    """The result of a single delivery."""

    DOT = "."
    ONE = "1"
    TWO = "2"
    THREE = "3"
    FOUR = "4"
    SIX = "6"
    WICKET = "W"
    WIDE = "Wd"
    NO_BALL = "Nb"
    BYE = "Bye"
    LEG_BYE = "Lb"


# Deliveries that consume a legal ball of the over (Wd/Nb are re-bowled).
LEGAL_BALLS: frozenset[Outcome] = frozenset(
    {
        Outcome.DOT,
        Outcome.ONE,
        Outcome.TWO,
        Outcome.THREE,
        Outcome.FOUR,
        Outcome.SIX,
        Outcome.WICKET,
        Outcome.BYE,
        Outcome.LEG_BYE,
    }
)

# Runs scored off the bat, by outcome. Extras (Wd/Nb/Bye/Lb) are handled apart.
BAT_RUNS: dict[Outcome, int] = {
    Outcome.ONE: 1,
    Outcome.TWO: 2,
    Outcome.THREE: 3,
    Outcome.FOUR: 4,
    Outcome.SIX: 6,
}


@dataclass(frozen=True)
class StartInnings:
    """Opens an innings. The second StartInnings in a match begins the chase."""

    batting_team: str
    bowling_team: str
    total_overs: int
    batting_order: tuple[str, ...] = ()
    bowling_order: tuple[str, ...] = ()
    target: int | None = None

    def __post_init__(self) -> None:
        if self.total_overs <= 0:
            raise ValueError("total_overs must be positive")
        if self.target is not None and self.target <= 0:
            raise ValueError("target must be positive when set")


@dataclass(frozen=True)
class Delivery:
    """A single delivery.

    `runs` carries the *extra* runs for Wd/Nb/Bye/Lb (e.g. a wide that ran for
    two = Outcome.WIDE with runs=2). For runs off the bat use the run outcomes
    (ONE..SIX) and leave `runs` at 0.

    `extra_wicket` represents a dismissal that happens *on* an extra (a run-out or
    stumping off a wide/no-ball): the extra runs still count and the named batter
    is out, but the bowler is not credited (matching the legacy engine). For a
    normal wicket use Outcome.WICKET instead.
    """

    outcome: Outcome
    runs: int = 0
    out_batter: str = "striker"  # "striker" | "non_striker"
    dismissal_kind: str = ""  # bowled | caught | run_out | stumped | ...
    extra_wicket: bool = False

    def __post_init__(self) -> None:
        if not isinstance(self.outcome, Outcome):
            raise ValueError(f"unknown outcome: {self.outcome!r}")
        if self.runs < 0:
            raise ValueError("runs cannot be negative")
        if self.out_batter not in ("striker", "non_striker"):
            raise ValueError("out_batter must be 'striker' or 'non_striker'")
        if self.extra_wicket and self.outcome not in (
            Outcome.WIDE,
            Outcome.NO_BALL,
            Outcome.BYE,
            Outcome.LEG_BYE,
        ):
            raise ValueError("extra_wicket is only valid on an extra delivery")


@dataclass(frozen=True)
class Penalty:
    """Penalty runs awarded to a side without a ball being bowled.

    `to_batting` True adds the runs to the batting team's total (counted as
    extras); False adds them to the bowling team's *other* innings is not modelled
    here — penalties against the batting side are recorded by the opposing
    innings. Most club cases are 5 penalty runs to the batting side.
    """

    runs: int
    to_batting: bool = True

    def __post_init__(self) -> None:
        if self.runs <= 0:
            raise ValueError("penalty runs must be positive")


@dataclass(frozen=True)
class Retire:
    """A batter leaves the crease without being dismissed (retired hurt/out).

    The next batter from the order comes in. `out` marks "retired out" (cannot
    return and counts toward the all-out total is *not* applied — retirements
    never add to the wicket tally); a retired-hurt batter may return via a later
    correction, which is outside this event's scope.
    """

    batter: str = "striker"  # "striker" | "non_striker"
    out: bool = False

    def __post_init__(self) -> None:
        if self.batter not in ("striker", "non_striker"):
            raise ValueError("batter must be 'striker' or 'non_striker'")


# The closed set of events the reducer understands. New event types slot in here
# and in scoring.apply — nothing else in the codebase needs to change.
Event = StartInnings | Delivery | Penalty | Retire
