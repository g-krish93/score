"""Shadow-compare the legacy scoreboard against the new core's view.

Part of the strangler cut-over: when dual-write + shadow-compare are on, each
/score read folds the event log through cricrelay_core and reports any field
that diverges from the legacy engine. Those differences are the worklist for
reconciling the core's rules before any cut-over — they are logged, never shown
to users, and never alter the response.
"""
from __future__ import annotations

# Fields compared between the legacy state dict and the core's derived() view.
# (Legacy tracks overs + balls separately; the core renders "overs" as "O.B".)
_SCALAR_FIELDS = ("runs", "wickets", "extras", "striker", "non_striker")


def diffs(legacy_state: dict, core_view: dict) -> list[str]:
    """Return human-readable 'field: legacy=.. core=..' lines for mismatches."""
    if not core_view:  # core has no live innings yet — nothing to compare
        return []

    out: list[str] = []
    for field in _SCALAR_FIELDS:
        legacy_val = legacy_state.get(field)
        core_val = core_view.get(field)
        if legacy_val != core_val:
            out.append(f"{field}: legacy={legacy_val!r} core={core_val!r}")

    legacy_overs = f"{legacy_state.get('overs', 0)}.{legacy_state.get('balls', 0)}"
    core_overs = core_view.get("overs")
    if legacy_overs != core_overs:
        out.append(f"overs: legacy={legacy_overs!r} core={core_overs!r}")

    return out
