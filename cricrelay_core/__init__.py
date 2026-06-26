"""CricRelay scoring core — the framework-free domain heart.

Public interface (the *only* things other modules should import). Treat
everything else as internal so the core can be refactored freely behind this
boundary — this is what keeps changes here from rippling outward.
"""
from __future__ import annotations

from .codec import from_dict, to_dict
from .events import (
    BAT_RUNS,
    LEGAL_BALLS,
    Delivery,
    Event,
    Outcome,
    StartInnings,
)
from .ports import EventStore, InMemoryEventStore
from .scoring import (
    BatterStat,
    BowlerStat,
    InningsState,
    InvalidEvent,
    MatchState,
    reduce,
)
from .stats import derived

__all__ = [
    "Outcome",
    "Delivery",
    "StartInnings",
    "Event",
    "LEGAL_BALLS",
    "BAT_RUNS",
    "reduce",
    "derived",
    "to_dict",
    "from_dict",
    "MatchState",
    "InningsState",
    "BatterStat",
    "BowlerStat",
    "InvalidEvent",
    "EventStore",
    "InMemoryEventStore",
]
