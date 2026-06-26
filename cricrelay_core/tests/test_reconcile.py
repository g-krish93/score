"""Reconciliation tests for the additive core events (Task B / #2).

Covers dismissals on extras (run-out/stumping off a wide/no-ball), penalty runs,
batter retirements, and codec round-tripping of the new event types.

Runs under pytest, or standalone:
  python -m cricrelay_core.tests.test_reconcile
"""
from __future__ import annotations

from cricrelay_core import (
    Delivery,
    Outcome,
    Penalty,
    Retire,
    StartInnings,
    from_dict,
    reduce,
    to_dict,
)


def _start(order=("A", "B", "C", "D"), overs=20, target=None):
    return StartInnings(
        batting_team="Home",
        bowling_team="Away",
        total_overs=overs,
        batting_order=order,
        bowling_order=("X",),
        target=target,
    )


def test_run_out_off_a_wide_counts_extra_and_wicket_no_bowler_credit():
    # Wide with one run, and the non-striker run out going for a second.
    m = reduce([
        _start(),
        Delivery(Outcome.WIDE, runs=1, out_batter="non_striker",
                 dismissal_kind="run_out", extra_wicket=True),
    ])
    inn = m.current
    assert inn.runs == 2  # 1 wide penalty + 1 ran
    assert inn.extras == 2
    assert inn.wickets == 1
    assert inn.legal_balls == 0  # a wide is not a legal ball
    assert inn.bowlers["X"].wickets == 0  # run-out is not the bowler's wicket


def test_stumping_off_a_wide_brings_next_batter():
    m = reduce([
        _start(),
        Delivery(Outcome.WIDE, out_batter="striker",
                 dismissal_kind="stumped", extra_wicket=True),
    ])
    inn = m.current
    assert inn.wickets == 1
    assert inn.batters["A"].out is True
    assert inn.striker == "C"  # next batter after A & B are at the crease


def test_penalty_runs_add_to_total_and_extras():
    m = reduce([_start(), Delivery(Outcome.ONE), Penalty(5)])
    inn = m.current
    assert inn.runs == 6
    assert inn.extras == 5


def test_retire_brings_next_batter_without_a_wicket():
    m = reduce([_start(), Retire("striker", out=False)])
    inn = m.current
    assert inn.wickets == 0
    assert inn.striker == "C"
    assert inn.batters["A"].dismissal == "retired hurt"


def test_retired_out_is_marked_but_not_a_wicket():
    m = reduce([_start(), Retire("non_striker", out=True)])
    inn = m.current
    assert inn.wickets == 0
    assert inn.batters["B"].out is True
    assert inn.batters["B"].dismissal == "retired out"
    assert inn.non_striker == "C"


def test_codec_round_trips_new_events():
    events = [
        Delivery(Outcome.NO_BALL, runs=2, dismissal_kind="run_out", extra_wicket=True),
        Penalty(5, to_batting=True),
        Retire("striker", out=True),
    ]
    for e in events:
        assert from_dict(to_dict(e)) == e


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
