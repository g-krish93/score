"""Pure round-trip tests for event serialization (no external services)."""
from __future__ import annotations

from cricrelay_core import (
    Delivery,
    Outcome,
    StartInnings,
    from_dict,
    reduce,
    to_dict,
)


def test_delivery_roundtrips():
    for d in (
        Delivery(Outcome.DOT),
        Delivery(Outcome.FOUR),
        Delivery(Outcome.WIDE, runs=2),
        Delivery(Outcome.WICKET, out_batter="non_striker", dismissal_kind="run_out"),
    ):
        assert from_dict(to_dict(d)) == d


def test_start_innings_roundtrips():
    s = StartInnings(
        "Home", "Away", total_overs=20,
        batting_order=("X", "Y"), bowling_order=("Z",), target=151,
    )
    assert from_dict(to_dict(s)) == s


def test_full_log_roundtrip_preserves_score():
    events = [
        StartInnings("Home", "Away", total_overs=20,
                     batting_order=("X", "Y", "Z"), bowling_order=("P",)),
        Delivery(Outcome.SIX),
        Delivery(Outcome.ONE),
        Delivery(Outcome.WICKET),
    ]
    decoded = [from_dict(to_dict(e)) for e in events]
    assert reduce(decoded).current.runs == reduce(events).current.runs == 7
    assert reduce(decoded).current.wickets == 1


def _run_standalone() -> int:
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    failures = 0
    for t in tests:
        try:
            t()
            print(f"  PASS  {t.__name__}")
        except Exception as exc:  # noqa: BLE001
            failures += 1
            print(f"  FAIL  {t.__name__}: {exc}")
    print(f"\n{len(tests) - failures}/{len(tests)} passed")
    return failures


if __name__ == "__main__":
    import sys

    sys.exit(1 if _run_standalone() else 0)
