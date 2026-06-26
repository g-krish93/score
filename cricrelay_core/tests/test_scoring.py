"""Unit tests for the scoring core.

Runs under pytest, or standalone:  python -m cricrelay_core.tests.test_scoring
The standalone runner means we can prove the core works even where pytest is
not installed.
"""
from __future__ import annotations

from cricrelay_core import (
    Delivery,
    InvalidEvent,
    Outcome,
    StartInnings,
    derived,
    reduce,
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


def test_dot_ball_consumes_a_ball_scores_nothing():
    m = reduce([_start(), Delivery(Outcome.DOT)])
    inn = m.current
    assert inn.runs == 0
    assert inn.legal_balls == 1
    assert inn.striker == "A"  # no rotation on a dot


def test_single_rotates_strike():
    m = reduce([_start(), Delivery(Outcome.ONE)])
    inn = m.current
    assert inn.runs == 1
    assert inn.striker == "B" and inn.non_striker == "A"
    assert inn.batters["A"].runs == 1 and inn.batters["A"].balls == 1


def test_boundary_four_no_rotation():
    m = reduce([_start(), Delivery(Outcome.FOUR)])
    inn = m.current
    assert inn.runs == 4
    assert inn.striker == "A"
    assert inn.batters["A"].runs == 4


def test_wide_adds_run_without_consuming_a_ball():
    m = reduce([_start(), Delivery(Outcome.WIDE)])
    inn = m.current
    assert inn.runs == 1 and inn.extras == 1
    assert inn.legal_balls == 0
    assert inn.batters["A"].balls == 0  # striker did not face a legal ball


def test_wide_with_extra_runs():
    m = reduce([_start(), Delivery(Outcome.WIDE, runs=2)])
    inn = m.current
    assert inn.runs == 3 and inn.extras == 3
    assert inn.legal_balls == 0
    assert inn.striker == "A"  # ran 2 (even) -> no cross


def test_no_ball_adds_run_without_consuming_a_ball():
    m = reduce([_start(), Delivery(Outcome.NO_BALL)])
    inn = m.current
    assert inn.runs == 1 and inn.extras == 1
    assert inn.legal_balls == 0


def test_bye_counts_as_a_ball_not_charged_to_bowler():
    m = reduce([_start(), Delivery(Outcome.BYE, runs=2)])
    inn = m.current
    assert inn.runs == 2 and inn.extras == 2
    assert inn.legal_balls == 1
    assert inn.bowlers["X"].runs == 0  # byes are not the bowler's fault
    assert inn.bowlers["X"].balls == 1


def test_over_completes_and_changes_ends():
    events = [_start()] + [Delivery(Outcome.DOT) for _ in range(6)]
    inn = reduce(events).current
    assert inn.legal_balls == 6
    assert derived(reduce(events))["overs"] == "1.0"
    assert inn.striker == "B" and inn.non_striker == "A"  # change of ends
    assert inn.current_over == []  # fresh over


def test_wicket_brings_in_next_batter():
    m = reduce([_start(), Delivery(Outcome.WICKET)])
    inn = m.current
    assert inn.wickets == 1
    assert inn.batters["A"].out is True
    assert inn.striker == "C"  # next in the order
    assert inn.bowlers["X"].wickets == 1


def test_no_ball_with_runs_credits_striker_and_a_ball_faced():
    inn = reduce([_start(), Delivery(Outcome.NO_BALL, runs=4)]).current
    assert inn.runs == 5  # 1 penalty + 4 off the bat
    assert inn.extras == 1  # only the penalty is an extra
    assert inn.legal_balls == 0  # a no-ball is re-bowled
    assert inn.batters["A"].runs == 4
    assert inn.batters["A"].balls == 1


def test_innings_closes_when_all_out():
    order = ("A", "B", "C")  # all out at 2 wickets
    events = [_start(order=order), Delivery(Outcome.WICKET), Delivery(Outcome.WICKET)]
    inn = reduce(events).current
    assert inn.wickets == 2 and inn.closed is True


def test_innings_closes_at_over_limit():
    events = [_start(overs=1)] + [Delivery(Outcome.ONE) for _ in range(6)]
    inn = reduce(events).current
    assert inn.legal_balls == 6 and inn.closed is True


def test_chase_decides_the_match():
    first = [_start(order=("A", "B"), overs=1)] + [
        Delivery(Outcome.SIX)
    ] + [Delivery(Outcome.DOT) for _ in range(5)]
    second = [
        StartInnings(
            batting_team="Away",
            bowling_team="Home",
            total_overs=1,
            batting_order=("X", "Y"),
            bowling_order=("A",),
            target=7,
        ),
        Delivery(Outcome.SIX),
        Delivery(Outcome.ONE),
    ]
    m = reduce(first + second)
    view = derived(m)
    assert m.first_innings_runs == 6
    assert m.current.runs == 7 and m.current.closed is True
    assert "won by" in view["result"]


def test_cannot_bowl_after_innings_closed():
    events = [_start(overs=1)] + [Delivery(Outcome.ONE) for _ in range(6)]
    m = reduce(events)
    try:
        from cricrelay_core.scoring import apply_delivery

        apply_delivery(m.current, Delivery(Outcome.ONE))
    except InvalidEvent:
        pass
    else:  # pragma: no cover
        raise AssertionError("expected InvalidEvent after innings closed")


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
