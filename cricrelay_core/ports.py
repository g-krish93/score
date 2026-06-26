"""Ports (interfaces) the core depends on, so persistence stays pluggable.

The core never imports a database. It declares *what* it needs (an append-only
event log) as a Protocol; adapters in the infra/web layers provide the *how*
(in-memory now, Postgres + Redis later). This is the seam that lets the storage
technology change without the scoring rules — or their tests — changing at all.
"""
from __future__ import annotations

from typing import Protocol, runtime_checkable

from .events import Event


@runtime_checkable
class EventStore(Protocol):
    """An append-only log of scoring events, keyed by match id."""

    def append(self, match_id: str, event: Event) -> None: ...

    def load(self, match_id: str) -> list[Event]: ...


class InMemoryEventStore:
    """Reference adapter for tests and local dev. Not for production."""

    def __init__(self) -> None:
        self._logs: dict[str, list[Event]] = {}

    def append(self, match_id: str, event: Event) -> None:
        self._logs.setdefault(match_id, []).append(event)

    def load(self, match_id: str) -> list[Event]:
        return list(self._logs.get(match_id, []))
