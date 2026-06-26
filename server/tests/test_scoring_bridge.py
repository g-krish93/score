"""Tests for the legacy -> core event bridge."""
from __future__ import annotations

from cricrelay_core import Outcome, reduce
from server.scoring_bridge import ball_to_delivery, setup_to_start_innings


def test_bat_run_outcomes_carry_no_extra():
    d = ball_to_delivery("4")
    assert d.outcome is Outcome.FOUR and d.runs == 0


def test_extra_outcomes_carry_run_bonus():
    assert ball_to_delivery("Wd", 2).outcome is Outcome.WIDE
    assert ball_to_delivery("Wd", 2).runs == 2
    assert ball_to_delivery("Bye", 3).runs == 3


def test_wicket_carries_out_batter():
    d = ball_to_delivery("W", out_batter="non_striker")
    assert d.outcome is Outcome.WICKET and d.out_batter == "non_striker"


def test_setup_builds_start_innings_from_state():
    state = {
        "batting_team": "Home",
        "bowling_team": "Away",
        "total_overs": 10,
        "batting_squad": [{"name": "A"}, {"name": "B"}],
        "bowling_squad": [{"name": "X"}],
        "target": None,
    }
    ev = setup_to_start_innings(state)
    assert ev.batting_team == "Home"
    assert ev.total_overs == 10
    assert ev.batting_order == ("A", "B")


def test_bridge_feeds_core_to_a_consistent_score():
    state = {
        "batting_team": "Home", "bowling_team": "Away", "total_overs": 20,
        "batting_squad": [{"name": "A"}, {"name": "B"}, {"name": "C"}],
        "bowling_squad": [{"name": "X"}], "target": None,
    }
    events = [
        setup_to_start_innings(state),
        ball_to_delivery("6"),
        ball_to_delivery("Wd", 1),
        ball_to_delivery("1"),
    ]
    m = reduce(events)
    # 6 (six) + 2 (wide: 1 penalty + 1 bonus) + 1 (single) = 9
    assert m.current.runs == 9


def test_extra_with_dismissal_maps_to_extra_wicket():
    d = ball_to_delivery("Wd", 1, out_batter="non_striker", dismissal_kind="run_out")
    assert d.outcome is Outcome.WIDE and d.runs == 1
    assert d.extra_wicket is True and d.dismissal_kind == "run_out"


def test_extra_without_dismissal_is_not_a_wicket():
    assert ball_to_delivery("Nb", 2).extra_wicket is False


def test_penalty_and_retire_mappers():
    from server.scoring_bridge import penalty_event, retire_event

    assert penalty_event(5).runs == 5
    r = retire_event("non_striker", out=True)
    assert r.batter == "non_striker" and r.out is True


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
