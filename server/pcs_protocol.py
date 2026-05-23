"""Best-effort parser for Play Cricket Scorer BLE scoreboard packets (R&D).

Packet shape: 3-letter opcode + value, e.g. ``BTS120/3``, ``FTNOpposition``.
"""
from __future__ import annotations

import re
from copy import deepcopy
from datetime import datetime, timezone
from typing import Any

_BTS_RE = re.compile(r"^(\d+)\s*/\s*(\d+)$")
_TEAM_OPS = frozenset({"BTN", "FTN", "HTN", "ATN", "VTN"})


def _empty_pcs_state() -> dict[str, Any]:
    return {
        "team_names": [],
        "innings_1": None,
        "innings_2": None,
        "batting_index": 0,
        "status": None,
        "last_packet": None,
        "packet_count": 0,
        "updated_at": None,
    }


def apply_pcs_packet(pcs_state: dict[str, Any] | None, packet: str) -> dict[str, Any]:
    """Apply one PCS BLE packet and return updated accumulator state."""
    state = deepcopy(pcs_state) if isinstance(pcs_state, dict) else _empty_pcs_state()
    for key, default in _empty_pcs_state().items():
        if key not in state:
            state[key] = deepcopy(default) if isinstance(default, list) else default

    raw = (packet or "").strip()
    if len(raw) < 3:
        return state

    op = raw[:3].upper()
    val = raw[3:].strip()
    state["last_packet"] = raw
    state["packet_count"] = int(state.get("packet_count") or 0) + 1
    state["updated_at"] = datetime.now(timezone.utc).isoformat()

    if op in _TEAM_OPS and val:
        names: list[str] = state.get("team_names") or []
        if val not in names:
            names.append(val[:120])
        state["team_names"] = names[:2]

    if op == "BTS" and val:
        m = _BTS_RE.match(val)
        if m:
            runs, wkts = int(m.group(1)), int(m.group(2))
            idx = int(state.get("batting_index") or 0)
            if idx > 1:
                idx = 1
            teams = state.get("team_names") or []
            team = teams[idx] if idx < len(teams) else f"Team {idx + 1}"
            entry = {
                "team": team,
                "runs": runs,
                "wickets": wkts,
                "overs": "0.0",
                "score": f"{runs}/{wkts}",
                "overs_display": "0.0",
            }
            if idx == 0:
                state["innings_1"] = entry
            else:
                state["innings_2"] = entry

    if op == "COV":
        # Commence over — if we already have innings 1, next scores likely innings 2
        if state.get("innings_1") and not state.get("innings_2"):
            state["batting_index"] = 1

    if op == "OVB" and val:
        # Over.ball in value, e.g. OVB12 → over 1 ball 2 (heuristic)
        digits = re.sub(r"\D", "", val)
        if digits:
            if len(digits) >= 2:
                overs = f"{int(digits[0])}.{int(digits[1:])}"
            else:
                overs = f"0.{int(digits)}"
            for key in ("innings_1", "innings_2"):
                inn = state.get(key)
                if isinstance(inn, dict):
                    inn["overs"] = overs
                    inn["overs_display"] = overs

    if op in {"STS", "STA"} and val:
        state["status"] = val[:200]

    return state


def pcs_state_to_snapshot(pcs_state: dict[str, Any] | None, label: str = "") -> dict[str, Any]:
    """Build overlay ``snapshot`` dict from PCS accumulator state."""
    state = pcs_state if isinstance(pcs_state, dict) else _empty_pcs_state()
    teams = state.get("team_names") or []
    i1 = state.get("innings_1")
    i2 = state.get("innings_2")
    title = (label or "").strip()
    if not title and len(teams) >= 2:
        title = f"{teams[0]} vs {teams[1]}"
    elif not title and teams:
        title = teams[0]

    snap: dict[str, Any] = {
        "source_url": "pcs-ble",
        "status": state.get("status"),
        "fixture_title": title or None,
        "fixture_date": None,
        "fixture_start_time": None,
        "fixture_ground": None,
        "fixture_competition": "PCS BLE (R&D)",
        "innings_1": deepcopy(i1) if isinstance(i1, dict) else None,
        "innings_2": deepcopy(i2) if isinstance(i2, dict) else None,
    }
    return snap


def apply_pcs_events(pcs_state: dict[str, Any] | None, events: list[str]) -> dict[str, Any]:
    state = pcs_state
    for ev in events:
        if isinstance(ev, str) and ev.strip():
            state = apply_pcs_packet(state, ev.strip())
    return state
