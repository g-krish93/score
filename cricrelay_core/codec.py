"""Serialize scoring events to/from plain dicts (JSON-ready). Pure, no I/O.

The wire format lives *with* the events so every layer — the event store, the
API, the realtime pusher — encodes and decodes them identically. Add a case
here when a new event type is added to events.Event.
"""
from __future__ import annotations

from .events import Delivery, Event, Outcome, Penalty, Retire, StartInnings


def to_dict(event: Event) -> dict:
    if isinstance(event, StartInnings):
        return {
            "t": "StartInnings",
            "batting_team": event.batting_team,
            "bowling_team": event.bowling_team,
            "total_overs": event.total_overs,
            "batting_order": list(event.batting_order),
            "bowling_order": list(event.bowling_order),
            "target": event.target,
        }
    if isinstance(event, Delivery):
        return {
            "t": "Delivery",
            "outcome": event.outcome.value,
            "runs": event.runs,
            "out_batter": event.out_batter,
            "dismissal_kind": event.dismissal_kind,
            "extra_wicket": event.extra_wicket,
        }
    if isinstance(event, Penalty):
        return {"t": "Penalty", "runs": event.runs, "to_batting": event.to_batting}
    if isinstance(event, Retire):
        return {"t": "Retire", "batter": event.batter, "out": event.out}
    raise ValueError(f"cannot serialize event: {type(event).__name__}")


def from_dict(d: dict) -> Event:
    kind = d.get("t")
    if kind == "StartInnings":
        return StartInnings(
            batting_team=d["batting_team"],
            bowling_team=d["bowling_team"],
            total_overs=d["total_overs"],
            batting_order=tuple(d.get("batting_order", [])),
            bowling_order=tuple(d.get("bowling_order", [])),
            target=d.get("target"),
        )
    if kind == "Delivery":
        return Delivery(
            outcome=Outcome(d["outcome"]),
            runs=d.get("runs", 0),
            out_batter=d.get("out_batter", "striker"),
            dismissal_kind=d.get("dismissal_kind", ""),
            extra_wicket=d.get("extra_wicket", False),
        )
    if kind == "Penalty":
        return Penalty(runs=d["runs"], to_batting=d.get("to_batting", True))
    if kind == "Retire":
        return Retire(batter=d.get("batter", "striker"), out=d.get("out", False))
    raise ValueError(f"unknown event payload type: {kind!r}")
