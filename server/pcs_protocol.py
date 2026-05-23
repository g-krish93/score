"""Parse PCS external scoreboard BLE packets → overlay snapshot + capture log for R&D."""
from __future__ import annotations

import re
from copy import deepcopy
from datetime import datetime, timezone
from typing import Any

_BTS_RE = re.compile(r"^(\d+)\s*/\s*(\d+)$")
_OVERS_RE = re.compile(r"^(\d+)(?:\.(\d+))?$")
_INT_RE = re.compile(r"^-?\d+$")

_PACKET_LOG_MAX = 250

_TEAM_OPS = frozenset({"BTN", "FTN", "HTN", "ATN", "VTN", "HTA", "VTA"})
_BTS_OPS = frozenset({"BTS"})
_BATTER_OPS = frozenset({"B1N", "B2N", "B1S", "B2S", "B1B", "B2B", "B1K", "B2K"})
_BOWL_OPS = frozenset({"F1N", "F1S", "F2N", "F2S"})
_OVER_OPS = frozenset({"OVB", "OVR", "OVO"})
_STATUS_OPS = frozenset({"STS", "STA", "MSG"})
_TARGET_OPS = frozenset({"TGT", "RRQ", "FTS", "TRG", "RRR", "DLT", "DLP"})
_EXTRA_OPS = frozenset({"COV", "LWK"})
_KNOWN_OPS = (
    _TEAM_OPS
    | _BTS_OPS
    | _BATTER_OPS
    | _BOWL_OPS
    | _OVER_OPS
    | _STATUS_OPS
    | _TARGET_OPS
    | _EXTRA_OPS
)

_OPCODE_CATALOG: dict[str, str] = {
    "BTN": "Batting team name",
    "FTN": "Fielding team name",
    "BTS": "Batting team total (runs/wickets)",
    "B1N": "Batsman 1 name",
    "B2N": "Batsman 2 name",
    "B1S": "Batsman 1 runs",
    "B2S": "Batsman 2 runs",
    "B1B": "Batsman 1 balls faced",
    "B2B": "Batsman 2 balls faced",
    "B1K": "Batsman 1 on strike",
    "B2K": "Batsman 2 on strike",
    "F1N": "Current bowler name",
    "F1S": "Current bowler figures",
    "F2N": "Previous bowler name",
    "F2S": "Previous bowler figures",
    "OVB": "Overs bowled (current innings)",
    "OVR": "Overs remaining",
    "OVO": "Over display variant",
    "COV": "Commence over / innings boundary",
    "STS": "Match status text",
    "STA": "Status alternate",
    "MSG": "Message line",
    "TGT": "Target runs",
    "RRQ": "Runs required to win",
    "RRR": "Required run rate",
    "FTS": "First innings total (fielding team score)",
    "TRG": "Target",
    "DLT": "Duckworth-Lewis target",
    "DLP": "Duckworth-Lewis par",
    "LWK": "Last wicket",
}


def _empty_batsman() -> dict[str, Any]:
    return {"name": "", "runs": None, "balls": None, "on_strike": False}


def _empty_pcs_state() -> dict[str, Any]:
    return {
        "team_names": [],
        "batting_team_name": "",
        "fielding_team_name": "",
        "innings_1": None,
        "innings_2": None,
        "batting_index": 0,
        "innings_phase": 1,
        "batsmen": [_empty_batsman(), _empty_batsman()],
        "bowler": {"name": "", "figures": ""},
        "target_runs": None,
        "runs_required": None,
        "status": None,
        "target_note": None,
        "last_packet": None,
        "packet_count": 0,
        "recent_packets": [],
        "packet_log": [],
        "opcode_counts": {},
        "unknown_opcodes": [],
        "updated_at": None,
        "_last_bts_runs": None,
        "cricket_notes": [],
    }


def classify_opcode(op: str) -> dict[str, Any]:
    op = (op or "").upper()[:3]
    known = op in _KNOWN_OPS
    return {
        "opcode": op,
        "known": known,
        "category": (
            "team"
            if op in _TEAM_OPS
            else "score"
            if op in _BTS_OPS
            else "batter"
            if op in _BATTER_OPS
            else "bowler"
            if op in _BOWL_OPS
            else "overs"
            if op in _OVER_OPS
            else "status"
            if op in _STATUS_OPS
            else "target"
            if op in _TARGET_OPS
            else "innings"
            if op in _EXTRA_OPS
            else "unknown"
        ),
        "description": _OPCODE_CATALOG.get(op, "Unknown opcode — add to parser next iteration"),
    }


def normalize_pcs_line(raw: str) -> str:
    s = (raw or "").strip()
    if len(s) < 3:
        return ""
    if s[:3].isalpha():
        return s[:3].upper() + s[3:]
    return s


def _innings_entry(team: str, runs: int, wkts: int, overs: str = "0.0") -> dict[str, Any]:
    return {
        "team": team[:120] if team else "—",
        "runs": runs,
        "wickets": wkts,
        "overs": overs,
        "score": f"{runs}/{wkts}",
        "overs_display": overs,
    }


def _team_for_index(state: dict[str, Any], idx: int) -> str:
    teams = state.get("team_names") or []
    if idx < len(teams) and teams[idx]:
        return teams[idx]
    if idx == 0 and state.get("batting_team_name"):
        return state["batting_team_name"]
    if idx == 1 and state.get("fielding_team_name"):
        return state["fielding_team_name"]
    return f"Team {idx + 1}"


def _active_innings_key(state: dict[str, Any]) -> str:
    idx = int(state.get("batting_index") or 0)
    if idx > 1:
        idx = 1
    return "innings_1" if idx == 0 else "innings_2"


def _set_active_innings(state: dict[str, Any], entry: dict[str, Any]) -> None:
    state[_active_innings_key(state)] = entry


def _active_innings(state: dict[str, Any]) -> dict[str, Any] | None:
    inn = state.get(_active_innings_key(state))
    return inn if isinstance(inn, dict) else None


def _parse_display_int(val: str) -> int | None:
    v = (val or "").strip().replace("-", "")
    if not v or not v.isdigit():
        return None
    return int(v)


def _parse_bts_value(val: str) -> tuple[int, int] | None:
    part = (val or "").split(" &", 1)[0].strip()
    m = _BTS_RE.match(part)
    if not m:
        return None
    runs = int(m.group(1))
    wkts_raw = m.group(2)
    wkts = 10 if wkts_raw == "10" else int(wkts_raw)
    return runs, wkts


def _parse_overs(val: str) -> str | None:
    v = (val or "").strip()
    if not v:
        return None
    m = _OVERS_RE.match(v)
    if m:
        balls = m.group(2) or "0"
        return f"{int(m.group(1))}.{int(balls)}"
    digits = re.sub(r"\D", "", v)
    if not digits:
        return None
    if len(digits) >= 2:
        return f"{int(digits[0])}.{int(digits[1:])}"
    return f"0.{int(digits)}"


def _overs_to_balls(overs_display: str) -> int | None:
    try:
        parts = str(overs_display).split(".")
        complete = int(parts[0])
        balls = int(parts[1]) if len(parts) > 1 else 0
        return complete * 6 + min(balls, 5)
    except (ValueError, IndexError):
        return None


def _batsman_index(op: str) -> int | None:
    if op.startswith("B1"):
        return 0
    if op.startswith("B2"):
        return 1
    return None


def _maybe_switch_innings(state: dict[str, Any], new_runs: int) -> str:
    """Detect innings break when PCS sends a much lower BTS (no FTS yet)."""
    if int(state.get("innings_phase") or 1) != 1:
        return ""
    prev = state.get("_last_bts_runs")
    if (
        prev is not None
        and new_runs < int(prev) - 10
        and state.get("innings_1")
    ):
        state["batting_index"] = 1
        state["innings_phase"] = 2
        state["_last_bts_runs"] = None
        state["batsmen"] = [_empty_batsman(), _empty_batsman()]
        return "Second innings (score reset)"
    return ""


def _sync_team_names(state: dict[str, Any]) -> None:
    names: list[str] = []
    bt = (state.get("batting_team_name") or "").strip()
    ft = (state.get("fielding_team_name") or "").strip()
    if bt:
        names.append(bt[:120])
    if ft and ft not in names:
        names.append(ft[:120])
    if names:
        state["team_names"] = names[:2]


def _log_packet(
    state: dict[str, Any],
    raw: str,
    op: str,
    val: str,
    applied: bool,
    note: str,
) -> None:
    ts = datetime.now(timezone.utc).isoformat()
    info = classify_opcode(op)
    entry = {
        "ts": ts,
        "raw": raw[:240],
        "opcode": op,
        "value": val[:120],
        "known": info["known"],
        "category": info["category"],
        "description": info["description"],
        "applied_to_overlay": applied,
        "parser_note": note[:200],
    }
    log: list[dict] = list(state.get("packet_log") or [])
    log.append(entry)
    state["packet_log"] = log[-_PACKET_LOG_MAX:]

    counts: dict[str, int] = dict(state.get("opcode_counts") or {})
    counts[op] = counts.get(op, 0) + 1
    state["opcode_counts"] = counts

    if not info["known"]:
        unknown: list[str] = list(state.get("unknown_opcodes") or [])
        if op not in unknown:
            unknown.append(op)
        state["unknown_opcodes"] = unknown[:32]

    recent: list[str] = list(state.get("recent_packets") or [])
    recent.append(raw[:200])
    state["recent_packets"] = recent[-40:]

    notes: list[str] = list(state.get("cricket_notes") or [])
    if applied and note:
        notes.append(f"{op}: {note}")
        state["cricket_notes"] = notes[-20:]


def apply_pcs_packet(pcs_state: dict[str, Any] | None, packet: str) -> dict[str, Any]:
    state = deepcopy(pcs_state) if isinstance(pcs_state, dict) else _empty_pcs_state()
    for key, default in _empty_pcs_state().items():
        if key not in state:
            state[key] = deepcopy(default) if isinstance(default, (list, dict)) else default

    raw = normalize_pcs_line(packet)
    if len(raw) < 3:
        if (packet or "").strip():
            _log_packet(state, (packet or "").strip()[:240], "???", "", False, "Too short to parse as PCS opcode")
        return state

    op = raw[:3].upper()
    val = raw[3:].strip()
    state["last_packet"] = raw
    state["packet_count"] = int(state.get("packet_count") or 0) + 1
    state["updated_at"] = datetime.now(timezone.utc).isoformat()

    applied = False
    note = ""

    if op in _TEAM_OPS and val:
        if op == "BTN":
            state["batting_team_name"] = val[:120]
            inn = _active_innings(state)
            if isinstance(inn, dict):
                inn["team"] = val[:120]
            else:
                _set_active_innings(state, _innings_entry(val, 0, 0))
        elif op == "FTN":
            state["fielding_team_name"] = val[:120]
        else:
            names: list[str] = state.get("team_names") or []
            if val not in names:
                names.append(val[:120])
            state["team_names"] = names[:2]
        _sync_team_names(state)
        applied = True
        note = f"Team name recorded ({op})"

    if op in _BTS_OPS and val:
        parsed = _parse_bts_value(val)
        if parsed:
            runs, wkts = parsed
            switch_note = _maybe_switch_innings(state, runs)
            state["_last_bts_runs"] = runs
            idx = int(state.get("batting_index") or 0)
            team = (state.get("batting_team_name") or "").strip() or _team_for_index(state, idx)
            prev_inn = _active_innings(state)
            overs = "0.0"
            if isinstance(prev_inn, dict) and prev_inn.get("overs"):
                overs = str(prev_inn.get("overs_display") or prev_inn.get("overs"))
            _set_active_innings(state, _innings_entry(team, runs, wkts, overs))
            applied = True
            note = switch_note or f"Innings {idx + 1} score {runs}/{wkts}"

    if op in _BATTER_OPS:
        bi = _batsman_index(op)
        if bi is not None:
            bats: list = list(state.get("batsmen") or [_empty_batsman(), _empty_batsman()])
            while len(bats) < 2:
                bats.append(_empty_batsman())
            b = dict(bats[bi])
            if op.endswith("N") and val:
                b["name"] = val[:40]
            elif op.endswith("S"):
                n = _parse_display_int(val)
                if n is not None:
                    b["runs"] = n
            elif op.endswith("B"):
                n = _parse_display_int(val)
                if n is not None:
                    b["balls"] = n
            elif op.endswith("K") and val:
                b["on_strike"] = val.strip() in {"1", "Y", "y", "T", "t", "true", "True"}
            bats[bi] = b
            state["batsmen"] = bats
            applied = True
            note = f"Batsman {bi + 1} updated ({op})"

    if op in _BOWL_OPS and val:
        bowler = dict(state.get("bowler") or {"name": "", "figures": ""})
        if op.endswith("N"):
            bowler["name"] = val[:40]
        elif op.endswith("S"):
            bowler["figures"] = val[:40]
        state["bowler"] = bowler
        applied = True
        note = f"Bowler updated ({op})"

    if op in _OVER_OPS and val:
        overs = _parse_overs(val) or _parse_overs(re.sub(r"^[A-Za-z]+", "", val))
        if overs:
            inn = _active_innings(state)
            if isinstance(inn, dict):
                inn["overs"] = overs
                inn["overs_display"] = overs
            else:
                idx = int(state.get("batting_index") or 0)
                team = (state.get("batting_team_name") or "").strip() or _team_for_index(state, idx)
                _set_active_innings(state, _innings_entry(team, 0, 0, overs))
            applied = True
            note = f"Overs set to {overs}"

    if op == "COV":
        note = "Over commenced"
        applied = True

    if op in _STATUS_OPS and val:
        state["status"] = val[:200]
        applied = True
        note = "Status updated"

    if op in _TARGET_OPS and val:
        if op == "RRQ" and _INT_RE.match(val):
            n = int(val)
            state["runs_required"] = n
            state["target_note"] = f"Need {n} runs"
            applied = True
            note = f"Runs required: {n}"
        elif op == "FTS":
            parsed = _parse_bts_value(val)
            if parsed:
                runs, wkts = parsed
                state["target_runs"] = runs + 1
                state["innings_phase"] = 2
                state["batting_index"] = 1
                state["_last_bts_runs"] = None
                team = (state.get("fielding_team_name") or "").strip() or _team_for_index(state, 0)
                prev_i1 = state.get("innings_1") if isinstance(state.get("innings_1"), dict) else None
                overs = str((prev_i1 or {}).get("overs_display") or "0.0")
                state["innings_1"] = _innings_entry(team, runs, wkts, overs)
                state["target_note"] = f"Target {runs + 1}"
                applied = True
                note = f"First innings {runs}/{wkts} → target {runs + 1}"
        elif op in {"TGT", "TRG", "DLT"} and _INT_RE.match(val):
            n = int(val)
            state["target_runs"] = n
            state["target_note"] = f"Target {n}"
            applied = True
            note = f"Target {n}"
        elif op == "RRR" and val:
            state["target_note"] = f"RRR {val[:40]}"
            applied = True
            note = "Run rate required"
        elif not applied:
            state["target_note"] = val[:120]
            applied = True
            note = f"Chase/target: {state.get('target_note')}"

    if not applied and op not in _KNOWN_OPS:
        note = "Unknown opcode — captured for next parser version"
    elif not applied:
        note = "Known opcode but value not parsed"

    _log_packet(state, raw, op, val, applied, note)
    return state


def _apply_cricket_rules_to_innings(state: dict[str, Any]) -> list[str]:
    notes: list[str] = []
    i1 = state.get("innings_1")
    i2 = state.get("innings_2")
    if not isinstance(i1, dict):
        active = _active_innings(state)
        if isinstance(active, dict) and active.get("runs") is not None:
            return ["First innings in progress"]
        return ["Waiting for BTS (innings score)"]

    r1 = i1.get("runs")
    if r1 is None:
        return ["Innings 1 has no runs yet"]

    target = state.get("target_runs")
    if target is None:
        target = int(r1) + 1

    if isinstance(i2, dict) and i2.get("runs") is not None:
        r2 = int(i2["runs"])
        need = state.get("runs_required")
        if need is None:
            need = max(0, int(target) - r2)
        if not state.get("target_note"):
            state["target_note"] = f"Need {need} off ? (target {target})"
        notes.append(f"Chase: {need} required (target {target})")
        w2 = int(i2.get("wickets") or 0)
        if w2 >= 10:
            notes.append("Innings 2 complete (all out)")

    return notes


def _build_live_scoreboard(state: dict[str, Any]) -> dict[str, Any]:
    active = _active_innings(state) or {}
    bats = state.get("batsmen") or [_empty_batsman(), _empty_batsman()]
    b1 = bats[0] if len(bats) > 0 else _empty_batsman()
    b2 = bats[1] if len(bats) > 1 else _empty_batsman()
    target = state.get("target_runs")
    need = state.get("runs_required")
    if need is None and isinstance(state.get("innings_1"), dict) and active.get("runs") is not None:
        i1 = state["innings_1"]
        if i1.get("runs") is not None and target is not None:
            need = max(0, int(target) - int(active["runs"]))
    return {
        "batting_team": active.get("team") or state.get("batting_team_name") or "",
        "fielding_team": state.get("fielding_team_name") or "",
        "runs": active.get("runs"),
        "wickets": active.get("wickets"),
        "overs": active.get("overs_display") or active.get("overs") or "",
        "score": active.get("score"),
        "batsman_1": deepcopy(b1),
        "batsman_2": deepcopy(b2),
        "bowler": deepcopy(state.get("bowler") or {}),
        "target": target,
        "runs_required": need,
        "innings_phase": int(state.get("innings_phase") or 1),
    }


def pcs_state_to_snapshot(pcs_state: dict[str, Any] | None, label: str = "") -> dict[str, Any]:
    state = deepcopy(pcs_state) if isinstance(pcs_state, dict) else _empty_pcs_state()
    _apply_cricket_rules_to_innings(state)

    teams = state.get("team_names") or []
    i1 = deepcopy(state.get("innings_1")) if isinstance(state.get("innings_1"), dict) else None
    i2 = deepcopy(state.get("innings_2")) if isinstance(state.get("innings_2"), dict) else None

    title = (label or "").strip()
    if not title and len(teams) >= 2:
        title = f"{teams[0]} vs {teams[1]}"
    elif not title and teams:
        title = teams[0]

    status = state.get("status")
    note = state.get("target_note")
    if note and status:
        status = f"{status} · {note}"
    elif note:
        status = note

    return {
        "source_url": "pcs-ble",
        "status": status,
        "fixture_title": title or None,
        "fixture_date": None,
        "fixture_start_time": None,
        "fixture_ground": None,
        "fixture_competition": "PCS BLE",
        "innings_1": i1,
        "innings_2": i2,
        "live": _build_live_scoreboard(state),
    }


def apply_pcs_events(pcs_state: dict[str, Any] | None, events: list[str]) -> dict[str, Any]:
    state = pcs_state
    for ev in events:
        if isinstance(ev, str) and ev.strip():
            state = apply_pcs_packet(state, ev.strip())
    return state


def overlay_readiness(snapshot: dict[str, Any] | None) -> dict[str, Any]:
    snap = snapshot if isinstance(snapshot, dict) else {}
    live = snap.get("live") or {}
    i1 = snap.get("innings_1") or {}
    i2 = snap.get("innings_2") or {}
    missing: list[str] = []
    if live.get("runs") is None and not i1.get("score") and i1.get("runs") is None:
        missing.append("Innings score (need BTS)")
    if not live.get("batting_team") and not i1.get("team"):
        missing.append("Team names (need BTN/FTN)")
    b1 = live.get("batsman_1") or {}
    if b1.get("runs") is None:
        missing.append("Batsman scores (need B1S/B2S from PCS)")
    has_score = live.get("runs") is not None or bool(i1.get("score") or i1.get("runs") is not None)
    return {
        "can_show_score": has_score,
        "missing_for_full_overlay": missing,
        "has_chase": live.get("target") is not None or bool(i2.get("runs") is not None and i1.get("runs") is not None),
        "has_batsmen": b1.get("runs") is not None,
        "ball_by_ball": False,
        "note": "PCS BLE sends scoreboard summary (totals, overs, batsmen) — not ball-by-ball",
    }


def pcs_capture_report(pcs_state: dict[str, Any] | None, relay_wrapper: dict | None) -> dict[str, Any]:
    state = pcs_state if isinstance(pcs_state, dict) else {}
    snap = {}
    if isinstance(relay_wrapper, dict) and isinstance(relay_wrapper.get("snapshot"), dict):
        snap = relay_wrapper["snapshot"]

    counts = state.get("opcode_counts") or {}
    opcode_table = [
        {
            "opcode": op,
            "count": n,
            **classify_opcode(op),
        }
        for op, n in sorted(counts.items(), key=lambda x: (-x[1], x[0]))
    ]

    log = list(state.get("packet_log") or [])
    unknown_samples = [e for e in log if not e.get("known")][-15:]

    return {
        "packet_count": int(state.get("packet_count") or 0),
        "last_packet": state.get("last_packet"),
        "updated_at": state.get("updated_at"),
        "opcode_table": opcode_table,
        "unknown_opcodes": list(state.get("unknown_opcodes") or []),
        "unknown_samples": unknown_samples,
        "recent_packets": list(state.get("recent_packets") or [])[-12:],
        "packet_log_tail": log[-30:],
        "cricket_notes": list(state.get("cricket_notes") or []),
        "overlay": overlay_readiness(snap),
        "catalog": dict(_OPCODE_CATALOG),
    }


def pcs_live_summary(pcs_state: dict[str, Any] | None, relay_wrapper: dict | None) -> dict[str, Any]:
    state = pcs_state if isinstance(pcs_state, dict) else {}
    snap = {}
    if isinstance(relay_wrapper, dict) and isinstance(relay_wrapper.get("snapshot"), dict):
        snap = relay_wrapper["snapshot"]
    live = snap.get("live") or {}
    i1 = snap.get("innings_1") or {}
    i2 = snap.get("innings_2") or {}
    score_line = ""
    if live.get("runs") is not None:
        w = live.get("wickets", 0)
        score_line = f"{live.get('batting_team', '—')}: {live['runs']}/{w}"
        if live.get("overs"):
            score_line += f" ({live['overs']} ov)"
        b1 = live.get("batsman_1") or {}
        b2 = live.get("batsman_2") or {}
        if b1.get("runs") is not None or b2.get("runs") is not None:
            parts = []
            for bx in (b1, b2):
                if bx.get("runs") is not None:
                    nm = (bx.get("name") or "?")[:8]
                    balls = f" ({bx['balls']})" if bx.get("balls") is not None else ""
                    parts.append(f"{nm} {bx['runs']}{balls}")
            if parts:
                score_line += " · " + " | ".join(parts)
    elif i1.get("score"):
        score_line = f"{i1.get('team', '—')}: {i1['score']}"
        if i1.get("overs_display"):
            score_line += f" ({i1['overs_display']} ov)"
    if i2.get("score"):
        part = f"{i2.get('team', '—')}: {i2['score']}"
        if i2.get("overs_display"):
            part += f" ({i2['overs_display']} ov)"
        score_line = f"{score_line}  |  {part}" if score_line else part

    capture = pcs_capture_report(state, relay_wrapper)
    return {
        "packet_count": int(state.get("packet_count") or 0),
        "last_packet": state.get("last_packet"),
        "recent_packets": list(state.get("recent_packets") or [])[-8:],
        "score_line": score_line.strip() or "No score yet — waiting for BTS/OVB from PCS",
        "updated_at": state.get("updated_at"),
        "unknown_opcodes": capture.get("unknown_opcodes") or [],
        "overlay_ready": (capture.get("overlay") or {}).get("can_show_score"),
        "capture": capture,
    }
