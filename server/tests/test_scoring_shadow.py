"""Tests for the legacy-vs-core shadow comparison (pure, no I/O)."""
from __future__ import annotations

from server.scoring_shadow import diffs

_LEGACY = {
    "runs": 10, "wickets": 1, "extras": 2,
    "overs": 2, "balls": 3, "striker": "A", "non_striker": "B",
}
_CORE_MATCH = {
    "runs": 10, "wickets": 1, "extras": 2,
    "overs": "2.3", "striker": "A", "non_striker": "B",
}


def test_no_diffs_when_engines_agree():
    assert diffs(_LEGACY, _CORE_MATCH) == []


def test_detects_runs_and_strike_divergence():
    core = dict(_CORE_MATCH, runs=11, striker="B", non_striker="A")
    out = diffs(_LEGACY, core)
    assert any("runs" in line for line in out)
    assert any("striker" in line for line in out)
    assert not any("wickets" in line for line in out)


def test_detects_overs_divergence():
    core = dict(_CORE_MATCH, overs="3.0")
    out = diffs(_LEGACY, core)
    assert any("overs" in line for line in out)


def test_empty_core_view_reports_nothing():
    assert diffs(_LEGACY, {}) == []


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
