"""Integration tests for the persistence adapters.

- Redis adapter runs against in-process fakeredis (no server needed).
- Postgres adapter runs against a real database from CR_PG_DSN. If that DB is
  unreachable, the Postgres test is SKIPPED (so the suite still proves the rest).

Run standalone:  python -m cricrelay_store.tests.test_integration
"""
from __future__ import annotations

import os

from cricrelay_core import Delivery, Outcome, StartInnings, derived, reduce
from cricrelay_store import PostgresEventStore, RedisLiveState

PG_DSN = os.environ.get("CR_PG_DSN", "")
MATCH_ID = "itest-match-1"


class _Skip(Exception):
    """Signals a test was skipped (e.g. no database available)."""


def test_redis_live_state_caches_and_serves_view():
    import fakeredis

    live = RedisLiveState(client=fakeredis.FakeRedis(decode_responses=True))
    view = derived(
        reduce([
            StartInnings("Home", "Away", total_overs=20,
                         batting_order=("A", "B"), bowling_order=("X",)),
            Delivery(Outcome.SIX),
        ])
    )
    live.publish_scoreboard(MATCH_ID, view)
    cached = live.get_scoreboard(MATCH_ID)
    assert cached is not None
    assert cached["runs"] == 6
    assert cached["overs"] == "0.1"


def test_postgres_event_store_roundtrip_through_core():
    if not PG_DSN:
        raise _Skip("CR_PG_DSN not set")
    import psycopg2

    store = PostgresEventStore(PG_DSN)
    try:
        store.init_schema()
    except psycopg2.OperationalError as exc:
        raise _Skip(f"postgres unreachable: {exc}")

    store.delete_match(MATCH_ID)  # idempotent re-runs
    store.append(
        MATCH_ID,
        StartInnings("Home", "Away", total_overs=20,
                     batting_order=("A", "B", "C"), bowling_order=("X",)),
    )
    store.append(MATCH_ID, Delivery(Outcome.FOUR))
    store.append(MATCH_ID, Delivery(Outcome.ONE))
    store.append(MATCH_ID, Delivery(Outcome.WIDE))
    store.append(MATCH_ID, Delivery(Outcome.WICKET))

    events = store.load(MATCH_ID)
    assert len(events) == 5

    match = reduce(events)
    # 4 + 1 + 1 (wide) = 6 runs, 1 wicket — proves the events survived the
    # DB round-trip and fold to the same scoreboard.
    assert match.current.runs == 6
    assert match.current.wickets == 1
    store.delete_match(MATCH_ID)


def _run_standalone() -> int:
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    failures = 0
    for t in tests:
        try:
            t()
            print(f"  PASS  {t.__name__}")
        except _Skip as s:
            print(f"  SKIP  {t.__name__}: {s}")
        except Exception as exc:  # noqa: BLE001
            failures += 1
            print(f"  FAIL  {t.__name__}: {type(exc).__name__}: {exc}")
    print(f"\n{failures} failures")
    return failures


if __name__ == "__main__":
    import sys

    sys.exit(1 if _run_standalone() else 0)
