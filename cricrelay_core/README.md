# cricrelay_core — the scoring domain heart

Framework-free, I/O-free cricket scoring. This is the one canonical place the
laws of scoring live, so the web app, background workers and the realtime
pusher all compute **identical** results.

## The isolation contract (why this package keeps changes from rippling)

1. **No outward dependencies.** This package imports *nothing* from `server/`,
   Flask, or SQLAlchemy. It can be unit-tested and reused anywhere.
2. **Persistence is a port, not a dependency.** The core declares what it needs
   (`ports.EventStore`) and never how it is stored. Swapping in-memory for
   Postgres + Redis later changes an adapter, not this package.
3. **Import only the public surface.** Other modules import from
   `cricrelay_core` (the `__init__`), never from submodules. Everything else is
   internal and may be refactored freely.
4. **Events are the source of truth.** State is a *projection*. New features
   (analytics, undo, replay) are new projections over the same log — they never
   alter the write path.

As long as the public surface in `__init__.py` holds, work in any other track
cannot break this one, and changes here cannot break them.

## Public API

```python
from cricrelay_core import StartInnings, Delivery, Outcome, reduce, derived

events = [
    StartInnings("Home", "Away", total_overs=20,
                 batting_order=("Rohit", "Virat"), bowling_order=("Starc",)),
    Delivery(Outcome.FOUR),
    Delivery(Outcome.ONE),
]
match = reduce(events)        # fold the log into a scoreboard
view = derived(match)         # CRR, overs, chase math, result
```

## Run the tests

```bash
python -m cricrelay_core.tests.test_scoring   # standalone, no deps
python -m pytest cricrelay_core/tests/        # or under pytest
```

## Extension points (already designed for)

- New event types (Penalty, BatterRetired, BowlerChange, Correction) slot into
  `events.Event` and `scoring.apply` — nothing else changes.
- New analytics (wagon wheel, Manhattan, MVP) are new functions in a `projections`
  module reading the same events; the reducer is untouched.
