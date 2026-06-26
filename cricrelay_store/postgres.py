"""Postgres implementation of cricrelay_core.ports.EventStore.

Append-only event log: one row per scoring event, ordered by a serial id. The
live scoreboard is never stored here — it is recomputed by folding the events
through cricrelay_core.reduce(). This adapter depends on the core and on
psycopg2 (already the project's Postgres driver); the core depends on neither,
so swapping storage never touches the scoring rules.
"""
from __future__ import annotations

from contextlib import closing

import psycopg2
from psycopg2.extras import Json

from cricrelay_core import Event
from cricrelay_core.codec import from_dict, to_dict

_DDL = """
CREATE TABLE IF NOT EXISTS match_event (
    id         BIGSERIAL PRIMARY KEY,
    match_id   TEXT        NOT NULL,
    payload    JSONB       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_match_event_match ON match_event (match_id, id);
"""


class PostgresEventStore:
    """Implements the EventStore port against Postgres (psycopg2)."""

    def __init__(self, dsn: str) -> None:
        self._dsn = dsn

    def _conn(self):
        return psycopg2.connect(self._dsn)

    def _execute(self, sql: str, params: tuple = ()) -> None:
        # `with conn` commits on success / rolls back on error;
        # closing() guarantees the socket is released either way.
        with closing(self._conn()) as conn, conn, conn.cursor() as cur:
            cur.execute(sql, params)

    def init_schema(self) -> None:
        self._execute(_DDL)

    def append(self, match_id: str, event: Event) -> None:
        self._execute(
            "INSERT INTO match_event (match_id, payload) VALUES (%s, %s)",
            (match_id, Json(to_dict(event))),
        )

    def load(self, match_id: str) -> list[Event]:
        with closing(self._conn()) as conn, conn.cursor() as cur:
            cur.execute(
                "SELECT payload FROM match_event WHERE match_id = %s ORDER BY id",
                (match_id,),
            )
            return [from_dict(row[0]) for row in cur.fetchall()]

    def delete_match(self, match_id: str) -> None:
        """Remove all events for a match (used by tests; not a routine op)."""
        self._execute("DELETE FROM match_event WHERE match_id = %s", (match_id,))
