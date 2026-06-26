"""Persistence adapters for the CricRelay scoring core.

Depends on cricrelay_core (the framework-free domain) plus database drivers.
The core never imports this package — storage is plugged in here, behind the
EventStore port, so the scoring rules and their tests stay storage-agnostic.
"""
from __future__ import annotations

from .postgres import PostgresEventStore
from .redis_state import RedisLiveState

__all__ = ["PostgresEventStore", "RedisLiveState"]
