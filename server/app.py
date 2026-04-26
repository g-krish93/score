import copy
import json
import os
import threading
from pathlib import Path

from dotenv import load_dotenv
from flask import Flask, jsonify, render_template, request
from flask_cors import CORS

load_dotenv()

app = Flask(__name__, template_folder="../templates", static_folder="../static")
CORS(app, resources={r"/*": {"origins": "*"}})

STATE_PATH = Path("/tmp/cricket_state.json")
state_lock = threading.Lock()
last_action = None
action_history = []
redo_history = []


def blank_state():
    return {
        "team1": "",
        "team2": "",
        "toss_winner": "",
        "toss_decision": "bat",
        "innings": 1,
        "batting_team": "",
        "bowling_team": "",
        "total_overs": 20,
        "target": None,
        "runs": 0,
        "wickets": 0,
        "overs": 0,
        "balls": 0,
        "extras": 0,
        "current_over": [],
        "batting_squad": [],
        "bowling_squad": [],
        "striker": "",
        "non_striker": "",
        "current_bowler": "",
        "active_panel": "score",
        "match_started": False,
        "match_ended": False,
    }


state = blank_state()


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
    return [{"name": p, "overs": 0, "balls": 0, "runs": 0, "wickets": 0, "maidens": 0} for p in players]


def save_state():
    try:
        with STATE_PATH.open("w", encoding="utf-8") as fh:
            json.dump(state, fh)
    except Exception:
        pass


def restore_state():
    global state
    if not STATE_PATH.exists():
        return False
    with STATE_PATH.open("r", encoding="utf-8") as fh:
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
    return data


def get_batter(name):
    if not name:
        return None
    return next((p for p in state["batting_squad"] if p["name"] == name), None)


def get_bowler(name):
    if not name:
        return None
    return next((p for p in state["bowling_squad"] if p["name"] == name), None)


def end_over():
    if state["balls"] != 6:
        return
    state["overs"] += 1
    state["balls"] = 0
    state["current_over"] = []
    state["striker"], state["non_striker"] = state["non_striker"], state["striker"]


@app.get("/")
def overlay():
    return render_template("overlay.html")


@app.get("/input")
def input_page():
    return render_template("input.html")


@app.get("/score")
def score():
    with state_lock:
        return jsonify(with_calculated_values(state))


@app.post("/setup")
def setup():
    global state, last_action, action_history, redo_history
    data = request.get_json(silent=True) or {}
    batting_names = [p.strip() for p in data.get("batting_squad", []) if str(p).strip()]
    bowling_names = [p.strip() for p in data.get("bowling_squad", []) if str(p).strip()]
    with state_lock:
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
        state["toss_winner"] = toss_winner
        state["toss_decision"] = toss_decision
        state["batting_team"] = batting_team
        state["bowling_team"] = bowling_team
        state["total_overs"] = safe_num(data.get("total_overs", 20), 20)
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
    with state_lock:
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
    valid = {".", "1", "2", "3", "4", "6", "W", "Wd", "Nb", "Bye", "Lb"}
    if ball_type not in valid:
        return jsonify({"error": "invalid ball type"}), 400

    with state_lock:
        push_history()
        last_action = {"state_snapshot": copy.deepcopy(state)}
        striker = get_batter(state["striker"])
        bowler = get_bowler(state["current_bowler"])

        if ball_type == "Wd":
            total = 1 + run_bonus
            state["runs"] += total
            state["extras"] += total
            state["current_over"].append(f"Wd+{run_bonus}" if run_bonus else "Wd")
            if bowler:
                bowler["runs"] += total
            save_state()
            return jsonify(with_calculated_values(state))

        if ball_type == "Nb":
            total = 1 + run_bonus
            state["runs"] += total
            state["extras"] += 1
            state["current_over"].append(f"Nb+{run_bonus}" if run_bonus else "Nb")
            if striker and run_bonus:
                striker["runs"] += run_bonus
            if bowler:
                bowler["runs"] += total
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
            bowler["runs"] += run
            if ball_type == "W":
                bowler["wickets"] += 1
            if bowler["balls"] == 6:
                bowler["overs"] += 1
                bowler["balls"] = 0

        if ball_type == "W":
            state["wickets"] += 1
            if striker:
                striker["status"] = "out"
            state["striker"] = ""
        elif ball_type in {"1", "2", "3"} or (ball_type in {"Bye", "Lb"} and run % 2 == 1):
            state["striker"], state["non_striker"] = state["non_striker"], state["striker"]

        end_over()
        save_state()
        return jsonify(with_calculated_values(state))


@app.post("/undo")
def undo():
    global state, last_action, redo_history
    with state_lock:
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
    with state_lock:
        if not redo_history:
            return jsonify({"error": "nothing to redo"}), 400
        action_history.append(snapshot_state())
        state = redo_history.pop()
        save_state()
        return jsonify(with_calculated_values(state))


@app.post("/edit")
def edit():
    data = request.get_json(silent=True) or {}
    with state_lock:
        for key in ("runs", "wickets", "overs", "balls", "extras"):
            if key in data:
                state[key] = safe_num(data[key], state[key])
        save_state()
        return jsonify(with_calculated_values(state))


@app.post("/set-players")
def set_players():
    data = request.get_json(silent=True) or {}
    with state_lock:
        for key in ("striker", "non_striker", "current_bowler"):
            if key in data:
                state[key] = str(data[key] or "").strip()
        for name in (state["striker"], state["non_striker"]):
            batter = get_batter(name)
            if batter and batter["status"] != "out":
                batter["status"] = "batting"
        save_state()
        return jsonify(with_calculated_values(state))


@app.post("/set-panel")
def set_panel():
    data = request.get_json(silent=True) or {}
    panel = str(data.get("panel", "")).strip()
    if panel not in {"score", "batting", "bowling", "chase"}:
        return jsonify({"error": "invalid panel"}), 400
    with state_lock:
        state["active_panel"] = panel
        return jsonify({"active_panel": state["active_panel"]})


@app.post("/end-over")
def manual_end_over():
    with state_lock:
        bowler = get_bowler(state["current_bowler"])
        if bowler:
            bowler["overs"] += 1
            bowler["balls"] = 0
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
    with state_lock:
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
    with state_lock:
        save_state()
    return jsonify({"saved": True})


@app.post("/restore")
def restore():
    with state_lock:
        if not STATE_PATH.exists():
            return jsonify({"error": "state file not found"}), 404
        restore_state()
        return jsonify(with_calculated_values(state))


@app.get("/health")
def health():
    with state_lock:
        return jsonify(
            {"status": "ok", "innings": state["innings"], "match_started": state["match_started"]}
        )


with state_lock:
    try:
        restore_state()
    except Exception:
        state = blank_state()


if __name__ == "__main__":
    port = safe_num(os.getenv("PORT", "5000"), 5000)
    app.run(host="0.0.0.0", port=port)
