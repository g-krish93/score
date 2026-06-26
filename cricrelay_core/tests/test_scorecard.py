"""Tests for the scorecard projection (Task F).

Runs under pytest, or standalone:
  python -m cricrelay_core.tests.test_scorecard
"""
from __future__ import annotations

from cricrelay_core import Delivery, Outcome, StartInnings, reduce, scorecard


def _start(order=("A", "B", "C", "D"), overs=20):
    return StartInnings(
        batting_team="Home",
        bowling_team="Away",
        total_overs=overs,
        batting_order=order,
        bowling_order=("X",),
    )


def test_scorecard_batting_and_bowling_tables():
    m = reduce([_start(), Delivery(Outcome.FOUR), Delivery(Outcome.ONE), Delivery(Outcome.DOT)])
    sc = scorecard(m)
    by_name = {r["name"]: r for r in sc["batting"]}
    # A (on strike) scores the 4 and the single (=5), the single rotates strike to B,
    # who then faces the dot.
    assert by_name["A"]["runs"] == 5
    assert by_name["B"]["runs"] == 0
    assert by_name["B"]["balls"] == 1
    assert sc["bowling"][0]["name"] == "X"
    assert sc["bowling"][0]["runs"] == 5
    assert sc["bowling"][0]["wickets"] == 0


def test_scorecard_records_fall_of_wickets():
    m = reduce([
        _start(),
        Delivery(Outcome.FOUR),
        Delivery(Outcome.WICKET, dismissal_kind="bowled"),
        Delivery(Outcome.TWO),
    ])
    sc = scorecard(m)
    assert sc["wickets"] == 1
    assert len(sc["fall_of_wickets"]) == 1
    fow = sc["fall_of_wickets"][0]
    assert fow["wickets"] == 1 and fow["runs"] == 4 and fow["batter"] == "A"
    # bowler credited the wicket
    assert sc["bowling"][0]["wickets"] == 1


def test_scorecard_partnerships_sum_to_total():
    m = reduce([
        _start(),
        Delivery(Outcome.SIX),
        Delivery(Outcome.WICKET),
        Delivery(Outcome.FOUR),
    ])
    sc = scorecard(m)
    assert sum(p["runs"] for p in sc["partnerships"]) == sc["runs"] == 10


def test_empty_match_has_no_scorecard():
    assert scorecard(reduce([])) == {}


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
