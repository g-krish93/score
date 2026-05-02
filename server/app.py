import copy
import json
import os
import threading
import re
from contextlib import contextmanager
from pathlib import Path

from dotenv import load_dotenv
from flask import Flask, jsonify, render_template, request
from flask_cors import CORS

load_dotenv()

app = Flask(__name__, template_folder="../templates", static_folder="../static")
CORS(app, resources={r"/*": {"origins": "*"}})

STATE_DIR = Path("/tmp")
DEFAULT_MATCH_ID = "default"
state_lock = threading.Lock()
last_action = None
action_history = []
redo_history = []
current_match_id = DEFAULT_MATCH_ID
match_contexts = {}


def blank_state():
    return {
        "team1": "",
        "team2": "",
        "team1_color": "#2dd4bf",
        "team2_color": "#f59e0b",
        "theme": "classic",
        "overlay_density": "expanded",
        "overlay_scale": 1.0,
        "toss_winner": "",
        "toss_decision": "bat",
        "innings": 1,
        "batting_team": "",
        "bowling_team": "",
        "total_overs": 20,
        "target": None,
        "scoring_mode": "ball_by_ball",
        "runs": 0,
        "wickets": 0,
        "overs": 0,
        "balls": 0,
        "extras": 0,
        "penalty_runs": 0,
        "current_over": [],
        "batting_squad": [],
        "bowling_squad": [],
        "striker": "",
        "non_striker": "",
        "current_bowler": "",
        "active_panel": "score",
        "match_started": False,
        "match_ended": False,
        "event_log": [],
    }


state = blank_state()


def sanitize_match_id(raw):
    slug = re.sub(r"[^a-zA-Z0-9_-]+", "-", str(raw or DEFAULT_MATCH_ID).strip().lower()).strip("-")
    return slug or DEFAULT_MATCH_ID


def state_path_for(match_id):
    safe = sanitize_match_id(match_id)
    if safe == DEFAULT_MATCH_ID:
        return STATE_DIR / "cricket_state.json"
    return STATE_DIR / f"cricket_state_{safe}.json"


def get_request_match_id():
    return sanitize_match_id(request.args.get("match", DEFAULT_MATCH_ID))


def get_or_create_context(match_id):
    safe = sanitize_match_id(match_id)
    if safe in match_contexts:
        return match_contexts[safe]
    ctx = {
        "state": blank_state(),
        "last_action": None,
        "action_history": [],
        "redo_history": [],
    }
    path = state_path_for(safe)
    if path.exists():
        try:
            with path.open("r", encoding="utf-8") as fh:
                ctx["state"] = json.load(fh)
        except Exception:
            ctx["state"] = blank_state()
    match_contexts[safe] = ctx
    return ctx


def activate_context(match_id):
    global state, last_action, action_history, redo_history, current_match_id
    safe = sanitize_match_id(match_id)
    ctx = get_or_create_context(safe)
    state = ctx["state"]
    last_action = ctx["last_action"]
    action_history = ctx["action_history"]
    redo_history = ctx["redo_history"]
    current_match_id = safe


def persist_active_context():
    ctx = get_or_create_context(current_match_id)
    ctx["state"] = state
    ctx["last_action"] = last_action
    ctx["action_history"] = action_history
    ctx["redo_history"] = redo_history


@contextmanager
def match_context(match_id=None):
    with state_lock:
        activate_context(match_id or get_request_match_id())
        try:
            yield
        finally:
            persist_active_context()


def snapshot_state():
    return copy.deepcopy(state)


def push_history():
    global action_history, redo_history
    action_history.append(snapshot_state())
    if len(action_history) > 12:
        action_history = action_history[-12:]
    redo_history = []


def build_batting_squad(players):
    return [{"name": p, "runs": 0, "balls": 0, "status": "yet to bat"} for p in players]


def build_bowling_squad(players):
    return [
        {"name": p, "overs": 0, "balls": 0, "runs": 0, "wickets": 0, "maidens": 0, "over_runs": 0}
        for p in players
    ]


def save_state():
    try:
        with state_path_for(current_match_id).open("w", encoding="utf-8") as fh:
            json.dump(state, fh)
    except Exception:
        pass


def restore_state(match_id=None):
    global state
    path = state_path_for(match_id or current_match_id)
    if not path.exists():
        return False
    with path.open("r", encoding="utf-8") as fh:
        loaded = json.load(fh)
    state = loaded
    return True


def safe_num(value, default=0):
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def with_calculated_values(snapshot):
    data = copy.deepcopy(snapshot)
    total_balls = (data["overs"] * 6) + data["balls"]
    overs_float = total_balls / 6 if total_balls > 0 else 0
    crr = (data["runs"] / overs_float) if overs_float > 0 else 0.0

    data["crr"] = round(crr, 2)
    data["overs_display"] = f"{data['overs']}.{data['balls']}"

    if data["innings"] == 2 and data["target"] is not None:
        balls_remaining = max((data["total_overs"] * 6) - total_balls, 0)
        runs_needed = max(data["target"] - data["runs"], 0)
        rrr = (runs_needed / (balls_remaining / 6)) if balls_remaining > 0 else 0.0
        data["rrr"] = round(rrr, 2)
        data["runs_needed"] = runs_needed
        data["balls_remaining"] = balls_remaining
    else:
        data["rrr"] = None
        data["runs_needed"] = None
        data["balls_remaining"] = None
    data["match_complete"] = False
    data["match_result"] = None
    if data["innings"] == 2 and data["target"] is not None:
        innings_done = (
            data["runs"] >= data["target"]
            or data["wickets"] >= 10
            or total_balls >= (data["total_overs"] * 6)
        )
        if innings_done:
            data["match_complete"] = True
            first_innings_total = max(data["target"] - 1, 0)
            if data["runs"] >= data["target"]:
                wickets_left = max(10 - data["wickets"], 0)
                data["match_result"] = f"{data['batting_team']} won by {wickets_left} wicket(s)"
            elif data["runs"] == first_innings_total:
                data["match_result"] = "Match tied"
            else:
                margin_runs = max(first_innings_total - data["runs"], 0)
                data["match_result"] = f"{data['bowling_team']} won by {margin_runs} run(s)"
    return data


def get_batter(name):
    if not name:
        return None
    return next((p for p in state["batting_squad"] if p["name"] == name), None)


def get_bowler(name):
    if not name:
        return None
    return next((p for p in state["bowling_squad"] if p["name"] == name), None)


def log_event(event):
    state["event_log"].append(event)
    if len(state["event_log"]) > 50:
        state["event_log"] = state["event_log"][-50:]


def get_batter_by_selector(selector):
    sel = (selector or "").strip().lower()
    if sel == "striker":
        return get_batter(state["striker"])
    if sel == "non_striker":
        return get_batter(state["non_striker"])
    return get_batter(selector)


def clear_if_current_batter(name):
    if state["striker"] == name:
        state["striker"] = ""
    if state["non_striker"] == name:
        state["non_striker"] = ""


def end_over():
    if state["balls"] != 6:
        return
    state["overs"] += 1
    state["balls"] = 0
    state["current_over"] = []
    state["striker"], state["non_striker"] = state["non_striker"], state["striker"]


def innings_done(snapshot=None):
    data = snapshot or state
    total_balls = (data["overs"] * 6) + data["balls"]
    if total_balls >= (data["total_overs"] * 6):
        return True
    if data["wickets"] >= 10:
        return True
    if data["innings"] == 2 and data["target"] is not None and data["runs"] >= data["target"]:
        return True
    return False


def finalize_bowler_over(bowler):
    if not bowler:
        return
    if bowler.get("over_runs", 0) == 0:
        bowler["maidens"] = bowler.get("maidens", 0) + 1
    bowler["over_runs"] = 0


@app.get("/")
def overlay():
    return render_template("overlay.html", match_id=DEFAULT_MATCH_ID)


@app.get("/m/<match_id>")
def overlay_scoped(match_id):
    return render_template("overlay.html", match_id=sanitize_match_id(match_id))


@app.get("/input")
def input_page():
    return render_template("input.html", match_id=DEFAULT_MATCH_ID)


@app.get("/m/<match_id>/input")
def input_page_scoped(match_id):
    return render_template("input.html", match_id=sanitize_match_id(match_id))


@app.get("/score")
def score():
    with match_context():
        return jsonify(with_calculated_values(state))


@app.post("/setup")
def setup():
    global state, last_action, action_history, redo_history
    data = request.get_json(silent=True) or {}
    batting_names = [p.strip() for p in data.get("batting_squad", []) if str(p).strip()]
    bowling_names = [p.strip() for p in data.get("bowling_squad", []) if str(p).strip()]
    with match_context():
        state = blank_state()
        team1 = str(data.get("team1", data.get("batting_team", ""))).strip()
        team2 = str(data.get("team2", data.get("bowling_team", ""))).strip()
        toss_winner = str(data.get("toss_winner", team1)).strip() or team1
        toss_decision = str(data.get("toss_decision", "bat")).strip().lower()
        if toss_decision not in {"bat", "bowl"}:
            toss_decision = "bat"
        if toss_winner == team1:
            other_team = team2
        else:
            other_team = team1
        if toss_decision == "bat":
            batting_team = toss_winner
            bowling_team = other_team
        else:
            batting_team = other_team
            bowling_team = toss_winner
        state["team1"] = team1
        state["team2"] = team2
        state["team1_color"] = str(data.get("team1_color", "#2dd4bf")).strip() or "#2dd4bf"
        state["team2_color"] = str(data.get("team2_color", "#f59e0b")).strip() or "#f59e0b"
        theme = str(data.get("theme", "classic")).strip().lower()
        if theme not in {"classic", "neon", "minimal"}:
            theme = "classic"
        state["theme"] = theme
        state["toss_winner"] = toss_winner
        state["toss_decision"] = toss_decision
        scoring_mode = str(data.get("scoring_mode", "ball_by_ball")).strip()
        if scoring_mode not in {"ball_by_ball", "over_only"}:
            scoring_mode = "ball_by_ball"
        state["scoring_mode"] = scoring_mode
        state["batting_team"] = batting_team
        state["bowling_team"] = bowling_team
        state["total_overs"] = safe_num(data.get("total_overs", 20), 20)
        if scoring_mode == "over_only" and (len(batting_names) < 2 or len(bowling_names) < 2):
            batting_names = [f"{batting_team} {i}" for i in range(1, 12)]
            bowling_names = [f"{bowling_team} {i}" for i in range(1, 12)]
        state["batting_squad"] = build_batting_squad(batting_names)
        state["bowling_squad"] = build_bowling_squad(bowling_names)
        state["match_started"] = True
        last_action = None
        action_history = []
        redo_history = []
        save_state()
        return jsonify(with_calculated_values(state))


@app.post("/reset-match")
def reset_match():
    global state, last_action, action_history, redo_history
    with match_context():
        state = blank_state()
        last_action = None
        action_history = []
        redo_history = []
        save_state()
        return jsonify(with_calculated_values(state))


@app.post("/ball")
def ball():
    global last_action
    data = request.get_json(silent=True) or {}
    ball_type = str(data.get("type", "")).strip()
    run_bonus = max(0, safe_num(data.get("runs", 0), 0))
    dismissal_kind = str(data.get("dismissal_kind", "")).strip().lower()
    out_batter = str(data.get("out_batter", "striker")).strip().lower()
    valid = {".", "1", "2", "3", "4", "6", "W", "Wd", "Nb", "Bye", "Lb"}
    if ball_type not in valid:
        return jsonify({"error": "invalid ball type"}), 400
    if out_batter not in {"striker", "non_striker"}:
        return jsonify({"error": "out_batter must be striker or non_striker"}), 400

    with match_context():
        if state.get("scoring_mode") == "over_only":
            return jsonify({"error": "ball-by-ball disabled in over-only mode"}), 400
        if innings_done():
            return jsonify({"error": "innings already complete"}), 400
        push_history()
        last_action = {"state_snapshot": copy.deepcopy(state)}
        striker = get_batter(state["striker"])
        non_striker = get_batter(state["non_striker"])
        bowler = get_bowler(state["current_bowler"])

        if ball_type == "Wd":
            total = 1 + run_bonus
            state["runs"] += total
            state["extras"] += total
            state["current_over"].append(f"Wd+{run_bonus}" if run_bonus else "Wd")
            if bowler:
                bowler["runs"] += total
                bowler["over_runs"] += total
            if run_bonus % 2 == 1:
                state["striker"], state["non_striker"] = state["non_striker"], state["striker"]
            if dismissal_kind in {"run_out", "stumped"}:
                state["wickets"] += 1
                if out_batter == "non_striker":
                    if non_striker:
                        non_striker["status"] = "out"
                    state["non_striker"] = ""
                else:
                    if striker:
                        striker["status"] = "out"
                    state["striker"] = ""
            save_state()
            return jsonify(with_calculated_values(state))

        if ball_type == "Nb":
            total = 1 + run_bonus
            state["runs"] += total
            state["extras"] += 1
            state["current_over"].append(f"Nb+{run_bonus}" if run_bonus else "Nb")
            if striker:
                striker["balls"] += 1
            if striker and run_bonus:
                striker["runs"] += run_bonus
            if bowler:
                bowler["runs"] += total
                bowler["over_runs"] += total
            if run_bonus % 2 == 1:
                state["striker"], state["non_striker"] = state["non_striker"], state["striker"]
            if dismissal_kind == "run_out":
                state["wickets"] += 1
                if out_batter == "non_striker":
                    if non_striker:
                        non_striker["status"] = "out"
                    state["non_striker"] = ""
                else:
                    if striker:
                        striker["status"] = "out"
                    state["striker"] = ""
            save_state()
            return jsonify(with_calculated_values(state))

        runs_map = {".": 0, "1": 1, "2": 2, "3": 3, "4": 4, "6": 6, "W": 0, "Bye": run_bonus, "Lb": run_bonus}
        run = runs_map[ball_type]
        state["runs"] += run
        if ball_type in {"Bye", "Lb"}:
            state["extras"] += run
        state["balls"] += 1
        if ball_type in {"Bye", "Lb"}:
            state["current_over"].append(f"{ball_type}+{run}")
        else:
            state["current_over"].append(ball_type)

        if striker and ball_type not in {"Bye", "Lb"}:
            striker["balls"] += 1
            striker["runs"] += run
        elif striker and ball_type in {"Bye", "Lb"}:
            striker["balls"] += 1

        if bowler:
            bowler["balls"] += 1
            if ball_type not in {"Bye", "Lb"}:
                bowler["runs"] += run
                bowler["over_runs"] += run
            if ball_type == "W":
                bowler["wickets"] += 1
            if bowler["balls"] == 6:
                bowler["overs"] += 1
                bowler["balls"] = 0
                finalize_bowler_over(bowler)

        if ball_type == "W":
            state["wickets"] += 1
            if out_batter == "non_striker":
                if non_striker:
                    non_striker["status"] = "out"
                state["non_striker"] = ""
            else:
                if striker:
                    striker["status"] = "out"
                state["striker"] = ""
        elif ball_type in {"1", "3"} or (ball_type in {"Bye", "Lb"} and run % 2 == 1):
            state["striker"], state["non_striker"] = state["non_striker"], state["striker"]

        end_over()
        save_state()
        return jsonify(with_calculated_values(state))


@app.post("/over-update")
def over_update():
    data = request.get_json(silent=True) or {}
    runs = max(0, safe_num(data.get("runs", 0), 0))
    wickets = max(0, safe_num(data.get("wickets", 0), 0))
    with match_context():
        if state.get("scoring_mode") != "over_only":
            return jsonify({"error": "over-update only allowed in over-only mode"}), 400
        if innings_done():
            return jsonify({"error": "innings already complete"}), 400
        push_history()
        state["runs"] += runs
        state["wickets"] = min(10, state["wickets"] + wickets)
        state["overs"] = min(state["total_overs"], state["overs"] + 1)
        state["balls"] = 0
        state["current_over"] = []
        save_state()
        return jsonify(with_calculated_values(state))


@app.post("/retire-batter")
def retire_batter():
    data = request.get_json(silent=True) or {}
    selector = str(data.get("batter", "striker")).strip()
    retire_type = str(data.get("type", "hurt")).strip().lower()
    if retire_type not in {"hurt", "unhurt"}:
        return jsonify({"error": "type must be hurt or unhurt"}), 400
    with match_context():
        batter = get_batter_by_selector(selector)
        if not batter:
            return jsonify({"error": "batter not found"}), 400
        batter["status"] = "retired hurt" if retire_type == "hurt" else "retired out"
        clear_if_current_batter(batter["name"])
        log_event(f"{batter['name']} retired {retire_type}")
        save_state()
        return jsonify(with_calculated_values(state))


@app.post("/record-dismissal")
def record_dismissal():
    data = request.get_json(silent=True) or {}
    kind = str(data.get("kind", "run_out")).strip().lower()
    selector = str(data.get("batter", "striker")).strip()
    legal_delivery = bool(data.get("legal_delivery", True))
    add_ball = bool(data.get("add_ball", legal_delivery))
    credited_to_bowler = bool(data.get("credited_to_bowler", kind not in {"run_out", "obstructing_field"}))
    valid_kinds = {
        "run_out",
        "stumped",
        "hit_wicket",
        "obstructing_field",
        "timed_out",
        "handled_ball",
    }
    if kind not in valid_kinds:
        return jsonify({"error": "invalid dismissal kind"}), 400
    with match_context():
        if innings_done():
            return jsonify({"error": "innings already complete"}), 400
        batter = get_batter_by_selector(selector)
        if not batter:
            return jsonify({"error": "batter not found"}), 400
        push_history()
        state["wickets"] = min(10, state["wickets"] + 1)
        batter["status"] = "out"
        clear_if_current_batter(batter["name"])
        bowler = get_bowler(state["current_bowler"])
        if add_ball:
            state["balls"] += 1
            if bowler:
                bowler["balls"] += 1
        if credited_to_bowler and bowler:
            bowler["wickets"] += 1
            if bowler["balls"] == 6:
                bowler["overs"] += 1
                bowler["balls"] = 0
                finalize_bowler_over(bowler)
        state["current_over"].append(f"W({kind})")
        end_over()
        log_event(f"{batter['name']} out: {kind}")
        save_state()
        return jsonify(with_calculated_values(state))


@app.post("/penalty-runs")
def penalty_runs():
    data = request.get_json(silent=True) or {}
    runs = max(0, safe_num(data.get("runs", 5), 5))
    side = str(data.get("side", "batting")).strip().lower()
    reason = str(data.get("reason", "penalty")).strip()
    if side not in {"batting", "fielding"}:
        return jsonify({"error": "side must be batting or fielding"}), 400
    with match_context():
        push_history()
        if side == "batting":
            state["runs"] += runs
            state["extras"] += runs
            state["penalty_runs"] += runs
            state["current_over"].append(f"P{runs}")
        log_event(f"Penalty runs {runs} to {side}: {reason}")
        save_state()
        return jsonify(with_calculated_values(state))


@app.post("/dead-ball")
def dead_ball():
    data = request.get_json(silent=True) or {}
    note = str(data.get("note", "dead ball")).strip()
    with match_context():
        log_event(f"Dead ball: {note}")
        save_state()
        return jsonify(with_calculated_values(state))


@app.post("/undo")
def undo():
    global state, last_action, redo_history
    with match_context():
        if not action_history:
            return jsonify({"error": "nothing to undo"}), 400
        redo_history.append(snapshot_state())
        state = action_history.pop()
        last_action = None
        save_state()
        return jsonify(with_calculated_values(state))


@app.post("/redo")
def redo():
    global state, action_history
    with match_context():
        if not redo_history:
            return jsonify({"error": "nothing to redo"}), 400
        action_history.append(snapshot_state())
        state = redo_history.pop()
        save_state()
        return jsonify(with_calculated_values(state))


@app.post("/edit")
def edit():
    data = request.get_json(silent=True) or {}
    with match_context():
        for key in ("runs", "wickets", "overs", "balls", "extras"):
            if key in data:
                state[key] = safe_num(data[key], state[key])
        save_state()
        return jsonify(with_calculated_values(state))


@app.post("/set-players")
def set_players():
    data = request.get_json(silent=True) or {}
    with match_context():
        for key in ("striker", "non_striker", "current_bowler"):
            if key in data:
                state[key] = str(data[key] or "").strip()
        for name in (state["striker"], state["non_striker"]):
            batter = get_batter(name)
            if batter and batter["status"] not in {"out", "retired out"}:
                batter["status"] = "batting"
        save_state()
        return jsonify(with_calculated_values(state))


@app.post("/set-panel")
def set_panel():
    data = request.get_json(silent=True) or {}
    panel = str(data.get("panel", "")).strip()
    if panel not in {"score", "batting", "bowling", "chase", "fullscore"}:
        return jsonify({"error": "invalid panel"}), 400
    with match_context():
        state["active_panel"] = panel
        return jsonify({"active_panel": state["active_panel"]})


@app.post("/set-overlay-density")
def set_overlay_density():
    data = request.get_json(silent=True) or {}
    density = str(data.get("density", "")).strip().lower()
    if density not in {"compact", "expanded"}:
        return jsonify({"error": "invalid density"}), 400
    with match_context():
        state["overlay_density"] = density
        save_state()
        return jsonify({"overlay_density": state["overlay_density"]})


@app.post("/set-overlay-scale")
def set_overlay_scale():
    data = request.get_json(silent=True) or {}
    try:
        scale = float(data.get("scale", 1.0))
    except (TypeError, ValueError):
        return jsonify({"error": "invalid scale"}), 400
    scale = max(0.8, min(1.8, scale))
    with match_context():
        state["overlay_scale"] = round(scale, 2)
        save_state()
        return jsonify({"overlay_scale": state["overlay_scale"]})


@app.post("/end-over")
def manual_end_over():
    with match_context():
        if innings_done():
            return jsonify({"error": "innings already complete"}), 400
        if state.get("scoring_mode") == "over_only":
            return jsonify({"error": "use /over-update in over-only mode"}), 400
        bowler = get_bowler(state["current_bowler"])
        if bowler:
            if bowler["balls"] > 0:
                bowler["overs"] += 1
                bowler["balls"] = 0
                finalize_bowler_over(bowler)
        state["overs"] += 1
        state["balls"] = 0
        state["current_over"] = []
        state["striker"], state["non_striker"] = state["non_striker"], state["striker"]
        save_state()
        return jsonify(with_calculated_values(state))


@app.post("/start-second-innings")
def start_second_innings():
    data = request.get_json(silent=True) or {}
    batting_names = [p.strip() for p in data.get("batting_squad", []) if str(p).strip()]
    bowling_names = [p.strip() for p in data.get("bowling_squad", []) if str(p).strip()]
    with match_context():
        if not batting_names:
            batting_names = [p["name"] for p in state["bowling_squad"] if p.get("name")]
        if not bowling_names:
            bowling_names = [p["name"] for p in state["batting_squad"] if p.get("name")]
        previous_batting_team = state["batting_team"]
        first_innings_runs = state["runs"]
        if state["team1"] == state["batting_team"]:
            state["team1"] = state["bowling_team"]
            state["team2"] = state["batting_team"]
        else:
            state["team1"] = state["batting_team"]
            state["team2"] = state["bowling_team"]
        state["innings"] = 2
        state["target"] = first_innings_runs + 1
        state["runs"] = 0
        state["wickets"] = 0
        state["overs"] = 0
        state["balls"] = 0
        state["extras"] = 0
        state["current_over"] = []
        state["batting_team"] = str(data.get("batting_team", state["bowling_team"])).strip()
        state["bowling_team"] = previous_batting_team
        state["batting_squad"] = build_batting_squad(batting_names)
        state["bowling_squad"] = build_bowling_squad(bowling_names)
        state["striker"] = ""
        state["non_striker"] = ""
        state["current_bowler"] = ""
        state["active_panel"] = "score"
        save_state()
        return jsonify(with_calculated_values(state))


@app.post("/save")
def save():
    with match_context():
        save_state()
    return jsonify({"saved": True})


@app.post("/restore")
def restore():
    with match_context():
        if not state_path_for(current_match_id).exists():
            return jsonify({"error": "state file not found"}), 404
        restore_state()
        return jsonify(with_calculated_values(state))


@app.get("/health")
def health():
    with match_context():
        return jsonify(
            {"status": "ok", "innings": state["innings"], "match_started": state["match_started"]}
        )


with state_lock:
    try:
        activate_context(DEFAULT_MATCH_ID)
        restore_state(DEFAULT_MATCH_ID)
        persist_active_context()
    except Exception:
        state = blank_state()


if __name__ == "__main__":
    port = safe_num(os.getenv("PORT", "5000"), 5000)
    app.run(host="0.0.0.0", port=port)
