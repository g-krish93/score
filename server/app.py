import copy
import json
import os
import re
import secrets
import threading
from contextlib import contextmanager
from datetime import datetime, timezone
from functools import wraps
from pathlib import Path

from dotenv import load_dotenv
from flask import Flask, flash, jsonify, redirect, render_template, request, session, url_for
from flask_cors import CORS
from sqlalchemy.exc import IntegrityError

from .models_cricrelay import (
    ClubTeam,
    Organization,
    RelayMatch,
    build_play_cricket_scrape_url,
    db,
    slugify_org_name,
)
from .scraper_worker import register_relay_worker

load_dotenv()

STATE_DIR = Path(os.getenv("STATE_DIR", "/tmp")).expanduser()
try:
    STATE_DIR.mkdir(parents=True, exist_ok=True)
except OSError:
    pass

app = Flask(__name__, template_folder="../templates", static_folder="../static")
CORS(app, resources={r"/*": {"origins": "*"}})

app.config["SECRET_KEY"] = os.getenv("SECRET_KEY", "dev-insecure-change-me")
_db_url = (os.getenv("DATABASE_URL") or "").strip()
if not _db_url:
    _db_path = (STATE_DIR / "cricrelay.db").resolve()
    _db_url = f"sqlite:///{_db_path.as_posix()}"
app.config["SQLALCHEMY_DATABASE_URI"] = _db_url
app.config["SQLALCHEMY_TRACK_MODIFICATIONS"] = False
db.init_app(app)
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
        "over_only_checkpoints": [],
        "relay_mode": "manual",
        "relay_play_cricket_url": "",
        "relay_wrapper": None,
        "relay_last_ok_at": None,
        "relay_last_error": None,
    }


state = blank_state()


def merge_missing_state_keys(loaded):
    defaults = blank_state()
    for key, default in defaults.items():
        if key not in loaded:
            loaded[key] = copy.deepcopy(default)
    return loaded


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
                ctx["state"] = merge_missing_state_keys(json.load(fh))
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


def apply_relay_to_score_match(match_slug: str, full_url: str):
    url = (full_url or "").strip()
    with match_context(match_slug):
        merge_missing_state_keys(state)
        state["relay_mode"] = "play_cricket"
        state["relay_play_cricket_url"] = url
        state["relay_wrapper"] = None
        state["relay_last_error"] = None
        save_state()


def manual_scoring_blocked_response():
    if (state.get("relay_mode") or "manual") == "play_cricket":
        return (
            jsonify(
                {
                    "error": (
                        "Manual scoring is disabled while Play-Cricket relay is active. "
                        "Scores come from your scraper. Switch the relay to manual on the operator page "
                        "or POST /relay/config if you need to score by hand again."
                    )
                }
            ),
            400,
        )
    return None


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
    data["scoring_locked"] = (
        total_balls >= (data["total_overs"] * 6)
        or data["wickets"] >= 10
        or (
            data["innings"] == 2
            and data["target"] is not None
            and data["runs"] >= data["target"]
        )
    )
    cps = data.get("over_only_checkpoints")
    if not isinstance(cps, list):
        cps = []
    data["over_only_checkpoints"] = cps
    if data.get("scoring_mode") == "over_only":
        pop = compute_over_only_per_over(cps)
        data["over_only_per_over"] = pop
        if pop:
            last = pop[-1]
            o = max(1, safe_num(last.get("over"), 1))
            data["over_only_overs_completed"] = o
            data["over_only_run_rate"] = round(
                max(0, safe_num(last.get("innings_runs"), 0)) / o, 2
            )
        else:
            data["over_only_overs_completed"] = 0
            data["over_only_run_rate"] = 0.0
    else:
        data["over_only_per_over"] = []
        data["over_only_overs_completed"] = 0
        data["over_only_run_rate"] = 0.0
    wrapper = data.get("relay_wrapper")
    if not isinstance(wrapper, dict):
        wrapper = {}
    snap = wrapper.get("snapshot") if isinstance(wrapper.get("snapshot"), dict) else None
    url = (data.get("relay_play_cricket_url") or "").strip()
    mode = (data.get("relay_mode") or "manual").strip().lower()
    enabled = mode == "play_cricket" and bool(url)
    data["relay_bundle"] = {
        "mode": mode,
        "url": url,
        "enabled": enabled,
        "stale": bool(wrapper.get("stale")) if wrapper else False,
        "snapshot": snap,
        "last_ok_at": data.get("relay_last_ok_at"),
        "last_error": data.get("relay_last_error"),
    }
    return data


def compute_over_only_per_over(checkpoints):
    if not checkpoints:
        return []
    sorted_cp = sorted(checkpoints, key=lambda x: x["after_over"])
    out = []
    prev_r, prev_w = 0, 0
    for c in sorted_cp:
        r = max(0, safe_num(c.get("runs"), 0))
        w = max(0, min(10, safe_num(c.get("wickets"), 0)))
        o = max(1, safe_num(c.get("after_over"), 1))
        out.append(
            {
                "over": o,
                "runs_in_over": r - prev_r,
                "wkts_in_over": w - prev_w,
                "innings_runs": r,
                "innings_wickets": w,
            }
        )
        prev_r, prev_w = r, w
    return out


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


def login_required(view):
    @wraps(view)
    def wrapped(*args, **kwargs):
        if not session.get("org_id"):
            return redirect(url_for("login_page"))
        return view(*args, **kwargs)

    return wrapped


def _org_from_session():
    oid = session.get("org_id")
    if not oid:
        return None
    return db.session.get(Organization, oid)


def read_relay_overlay_prefs(slug):
    safe = sanitize_match_id(slug)
    path = state_path_for(safe)
    if not path.exists():
        return {"active_panel": "score", "overlay_density": "expanded", "overlay_scale": 1.0}
    try:
        with path.open("r", encoding="utf-8") as fh:
            s = json.load(fh)
        return {
            "active_panel": s.get("active_panel") or "score",
            "overlay_density": s.get("overlay_density") or "expanded",
            "overlay_scale": float(s.get("overlay_scale") or 1.0),
        }
    except Exception:
        return {"active_panel": "score", "overlay_density": "expanded", "overlay_scale": 1.0}


@app.get("/")
def cricrelay_home():
    return render_template("cricrelay_home.html", logged_in=bool(session.get("org_id")))


@app.route("/register", methods=["GET", "POST"])
def register_page():
    if request.method == "GET":
        return render_template("cricrelay_register.html")
    name = (request.form.get("name") or "").strip()
    email = (request.form.get("email") or "").strip().lower()
    password = request.form.get("password") or ""
    password2 = request.form.get("password2") or ""
    base_url = (request.form.get("play_cricket_base_url") or "").strip().rstrip("/")
    if not name or not email or not password:
        flash("Please fill in club name, email, and password.", "error")
        return render_template("cricrelay_register.html"), 400
    if password != password2:
        flash("Passwords do not match.", "error")
        return render_template("cricrelay_register.html"), 400
    if len(password) < 8:
        flash("Use a password of at least 8 characters.", "error")
        return render_template("cricrelay_register.html"), 400
    if "play-cricket.com" not in base_url.lower():
        flash("Play-Cricket URL must include play-cricket.com.", "error")
        return render_template("cricrelay_register.html"), 400
    base_slug = slugify_org_name(name)
    slug = base_slug
    for _ in range(12):
        if not Organization.query.filter_by(slug=slug).first():
            break
        slug = f"{base_slug}-{secrets.token_hex(2)}"
    org = Organization(
        slug=slug,
        name=name,
        email=email,
        play_cricket_base_url=base_url,
    )
    org.set_password(password)
    db.session.add(org)
    try:
        db.session.commit()
    except IntegrityError:
        db.session.rollback()
        flash("That email or club URL slug is already in use.", "error")
        return render_template("cricrelay_register.html"), 400
    session["org_id"] = org.id
    flash("Welcome to CricRelay — add squads under Club setup, then open Live relays when you stream.", "success")
    return redirect(url_for("dashboard"))


@app.route("/login", methods=["GET", "POST"])
def login_page():
    if request.method == "GET":
        return render_template("cricrelay_login.html")
    email = (request.form.get("email") or "").strip().lower()
    password = request.form.get("password") or ""
    org = Organization.query.filter_by(email=email).first()
    if not org or not org.check_password(password):
        flash("Invalid email or password.", "error")
        return render_template("cricrelay_login.html"), 401
    session["org_id"] = org.id
    return redirect(url_for("dashboard"))


@app.post("/logout")
def logout():
    session.pop("org_id", None)
    return redirect(url_for("cricrelay_home"))


@app.get("/dashboard")
@login_required
def dashboard():
    org = _org_from_session()
    teams = ClubTeam.query.filter_by(organization_id=org.id).order_by(ClubTeam.name).all()
    relay_count = RelayMatch.query.filter_by(organization_id=org.id).count()
    return render_template(
        "cricrelay_dashboard.html",
        org=org,
        teams=teams,
        relay_count=relay_count,
    )


@app.get("/dashboard/relays")
@login_required
def dashboard_relays():
    org = _org_from_session()
    teams = ClubTeam.query.filter_by(organization_id=org.id).order_by(ClubTeam.name).all()
    matches = RelayMatch.query.filter_by(organization_id=org.id).order_by(RelayMatch.created_at.desc()).all()
    relay_rows = [{"match": m, "appearance": read_relay_overlay_prefs(m.score_match_slug)} for m in matches]
    return render_template(
        "cricrelay_dashboard_relays.html",
        org=org,
        teams=teams,
        relay_rows=relay_rows,
    )


@app.post("/dashboard/teams")
@login_required
def dashboard_add_team():
    org = _org_from_session()
    team_name = (request.form.get("name") or "").strip()
    if not team_name:
        flash("Squad name is required.", "error")
        return redirect(url_for("dashboard"))
    row = ClubTeam(organization_id=org.id, name=team_name)
    db.session.add(row)
    try:
        db.session.commit()
    except IntegrityError:
        db.session.rollback()
        flash("You already have a squad with that name.", "error")
    return redirect(url_for("dashboard"))


@app.post("/dashboard/matches")
@login_required
def dashboard_add_match():
    org = _org_from_session()
    mid = (request.form.get("play_cricket_match_id") or "").strip()
    if not mid or not re.fullmatch(r"\d+", mid):
        flash(
            "Match ID must be the numeric fixture id (from …/website/results/7560599 or …match_details?id=7560599).",
            "error",
        )
        return redirect(url_for("dashboard_relays"))
    base_override = (request.form.get("play_cricket_base_url") or "").strip().rstrip("/")
    base = base_override or org.play_cricket_base_url
    if "play-cricket.com" not in base.lower():
        flash("Play-Cricket base URL must include play-cricket.com.", "error")
        return redirect(url_for("dashboard_relays"))
    full_url = build_play_cricket_scrape_url(base, mid)
    existing = RelayMatch.query.filter_by(organization_id=org.id, play_cricket_match_id=mid).first()
    if existing:
        flash("This Play-Cricket match is already linked for your club.", "error")
        return redirect(url_for("dashboard_relays"))
    club_team_id = (request.form.get("club_team_id") or "").strip() or None
    if club_team_id:
        team_ok = ClubTeam.query.filter_by(id=club_team_id, organization_id=org.id).first()
        if not team_ok:
            club_team_id = None
    base_slug = sanitize_match_id(f"{org.slug}-{mid}")
    score_slug = base_slug
    for _ in range(16):
        taken = RelayMatch.query.filter_by(score_match_slug=score_slug).first()
        if not taken:
            break
        score_slug = sanitize_match_id(f"{org.slug}-{mid}-{secrets.token_hex(3)}")
    row = RelayMatch(
        organization_id=org.id,
        club_team_id=club_team_id,
        play_cricket_match_id=mid,
        full_scrape_url=full_url,
        score_match_slug=score_slug,
    )
    db.session.add(row)
    try:
        db.session.commit()
    except IntegrityError:
        db.session.rollback()
        flash("Could not create that relay (duplicate or conflict).", "error")
        return redirect(url_for("dashboard_relays"))
    apply_relay_to_score_match(score_slug, full_url)
    flash("Relay match created — use the Prism overlay URL below.", "success")
    return redirect(url_for("dashboard_relays"))


@app.post("/dashboard/relay-appearance")
@login_required
def dashboard_relay_appearance():
    org = _org_from_session()
    slug = sanitize_match_id(request.form.get("score_match_slug", ""))
    if not slug:
        flash("Missing match.", "error")
        return redirect(url_for("dashboard_relays"))
    row = RelayMatch.query.filter_by(organization_id=org.id, score_match_slug=slug).first()
    if not row:
        flash("Unknown relay for your club.", "error")
        return redirect(url_for("dashboard_relays"))
    panel = (request.form.get("active_panel") or "score").strip().lower()
    if panel not in {"score", "batting", "bowling", "chase", "fullscore", "chart"}:
        panel = "score"
    density = (request.form.get("overlay_density") or "expanded").strip().lower()
    if density not in {"compact", "expanded"}:
        density = "expanded"
    try:
        scale = float(request.form.get("overlay_scale", "1") or 1)
    except (TypeError, ValueError):
        scale = 1.0
    scale = max(0.8, min(1.8, scale))
    with match_context(slug):
        merge_missing_state_keys(state)
        state["active_panel"] = panel
        state["overlay_density"] = density
        state["overlay_scale"] = round(scale, 2)
        save_state()
    flash("Overlay layout updated for that stream.", "success")
    return redirect(url_for("dashboard_relays"))


@app.get("/stream")
def stream_overlay_default():
    return render_template("overlay.html", match_id=DEFAULT_MATCH_ID)


@app.get("/m/<match_id>/stream")
def stream_overlay_scoped(match_id):
    return render_template("overlay.html", match_id=sanitize_match_id(match_id))


@app.get("/m/<match_id>")
def legacy_overlay_redirect(match_id):
    slug = sanitize_match_id(match_id)
    return redirect(f"/m/{slug}/stream", code=301)


@app.get("/input")
def input_page():
    return render_template("input.html", match_id=DEFAULT_MATCH_ID)


@app.get("/m/<match_id>/input")
def input_page_scoped(match_id):
    return render_template("input.html", match_id=sanitize_match_id(match_id))


@app.get("/cricrelay")
def cricrelay_landing():
    mid = sanitize_match_id(request.args.get("match", DEFAULT_MATCH_ID))
    return render_template("cricrelay.html", match_id=mid)


@app.get("/m/<match_id>/cricrelay")
def cricrelay_landing_scoped(match_id):
    return render_template("cricrelay.html", match_id=sanitize_match_id(match_id))


def _relay_ingest_authorized() -> bool:
    expected = os.getenv("RELAY_INGEST_TOKEN", "").strip()
    if not expected:
        return True
    auth = (request.headers.get("Authorization") or "").strip()
    return auth == f"Bearer {expected}"


@app.post("/relay/config")
def relay_config():
    data = request.get_json(silent=True) or {}
    mode = str(data.get("relay_mode", "manual")).strip().lower()
    if mode not in {"manual", "play_cricket"}:
        return jsonify({"error": "relay_mode must be manual or play_cricket"}), 400
    url = str(data.get("relay_play_cricket_url", "")).strip()
    if mode == "play_cricket":
        if not url:
            return jsonify({"error": "relay_play_cricket_url required when relay_mode is play_cricket"}), 400
        if "play-cricket.com" not in url.lower():
            return jsonify({"error": "URL must be a play-cricket.com page"}), 400
    with match_context():
        state["relay_mode"] = mode
        state["relay_play_cricket_url"] = url if mode == "play_cricket" else ""
        if mode == "manual":
            state["relay_wrapper"] = None
            state["relay_last_error"] = None
        save_state()
        return jsonify(with_calculated_values(state))


def apply_relay_ingest_payload(match_id: str, payload: dict) -> tuple[dict, int]:
    """Apply JSON ingest for a match slug. Used by ``/relay/ingest`` and the in-app relay worker."""
    mid = sanitize_match_id(match_id)
    with match_context(mid):
        if (state.get("relay_mode") or "manual") != "play_cricket":
            return ({"error": "relay_mode is not play_cricket for this match"}, 400)
        if isinstance(payload.get("snapshot"), dict):
            wrapper = payload
        elif (
            isinstance(payload.get("innings_1"), dict)
            or isinstance(payload.get("innings_2"), dict)
            or payload.get("status") is not None
            or payload.get("source_url")
        ):
            wrapper = {
                "snapshot": payload,
                "stale": bool(payload.get("stale", False)),
                "source_url": payload.get("source_url") or state.get("relay_play_cricket_url", ""),
            }
        else:
            wrapper = {
                "snapshot": payload,
                "stale": False,
                "source_url": state.get("relay_play_cricket_url", ""),
            }
        snap = wrapper.get("snapshot")
        if not isinstance(snap, dict):
            return ({"error": "payload must include snapshot object"}, 400)
        state["relay_wrapper"] = {
            "source_url": wrapper.get("source_url") or state.get("relay_play_cricket_url", ""),
            "stale": bool(wrapper.get("stale")),
            "snapshot": snap,
            "last_fetch_at": wrapper.get("last_fetch_at"),
            "last_ok_at": wrapper.get("last_ok_at"),
            "last_changed_at": wrapper.get("last_changed_at"),
            "last_error": wrapper.get("last_error"),
        }
        state["relay_last_ok_at"] = datetime.now(timezone.utc).isoformat()
        state["relay_last_error"] = None
        save_state()
        return (
            {"ok": True, "relay_bundle": with_calculated_values(state)["relay_bundle"]},
            200,
        )


@app.post("/relay/ingest")
def relay_ingest():
    if not _relay_ingest_authorized():
        return jsonify({"error": "unauthorized"}), 401
    payload = request.get_json(silent=True)
    if not isinstance(payload, dict):
        return jsonify({"error": "JSON body required"}), 400
    body, code = apply_relay_ingest_payload(get_request_match_id(), payload)
    return jsonify(body), code


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
        blocked = manual_scoring_blocked_response()
        if blocked is not None:
            return blocked
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
        if scoring_mode == "over_only":
            state["active_panel"] = "score"
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
        blocked = manual_scoring_blocked_response()
        if blocked is not None:
            return blocked
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
        blocked = manual_scoring_blocked_response()
        if blocked is not None:
            return blocked
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
    with match_context():
        blocked = manual_scoring_blocked_response()
        if blocked is not None:
            return blocked
        if state.get("scoring_mode") != "over_only":
            return jsonify({"error": "over-update only allowed in over-only mode"}), 400
        if innings_done():
            return jsonify({"error": "innings already complete"}), 400
        after_over = safe_num(data.get("after_over"), 0)
        inn_r = max(0, safe_num(data.get("innings_runs"), -1))
        inn_w = max(0, min(10, safe_num(data.get("innings_wickets"), -1)))
        if after_over < 1 or inn_r < 0 or inn_w < 0:
            return jsonify({"error": "after_over (1+), innings_runs and innings_wickets required"}), 400
        total_overs = max(1, safe_num(state.get("total_overs"), 20))
        if after_over > total_overs:
            return jsonify({"error": "after_over cannot exceed total_overs"}), 400
        checkpoints = state.get("over_only_checkpoints") or []
        if not isinstance(checkpoints, list):
            checkpoints = []
        prev_r, prev_w = 0, 0
        if after_over > 1:
            prev_cp = next((c for c in checkpoints if c.get("after_over") == after_over - 1), None)
            if not prev_cp:
                return jsonify(
                    {"error": f"record score after over {after_over - 1} before after over {after_over}"}
                ), 400
            prev_r = max(0, safe_num(prev_cp.get("runs"), 0))
            prev_w = max(0, min(10, safe_num(prev_cp.get("wickets"), 0)))
        if inn_r < prev_r or inn_w < prev_w:
            return jsonify(
                {"error": f"innings total must be >= after over {after_over - 1} ({prev_r}/{prev_w})"}
            ), 400
        push_history()
        new_cp = [c for c in checkpoints if safe_num(c.get("after_over"), 0) < after_over]
        new_cp.append({"after_over": after_over, "runs": inn_r, "wickets": inn_w})
        state["over_only_checkpoints"] = sorted(new_cp, key=lambda x: x["after_over"])
        last = state["over_only_checkpoints"][-1]
        state["overs"] = min(total_overs, safe_num(last.get("after_over"), 0))
        state["runs"] = max(0, safe_num(last.get("runs"), 0))
        state["wickets"] = max(0, min(10, safe_num(last.get("wickets"), 0)))
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
        blocked = manual_scoring_blocked_response()
        if blocked is not None:
            return blocked
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
        blocked = manual_scoring_blocked_response()
        if blocked is not None:
            return blocked
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
        blocked = manual_scoring_blocked_response()
        if blocked is not None:
            return blocked
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
        blocked = manual_scoring_blocked_response()
        if blocked is not None:
            return blocked
        log_event(f"Dead ball: {note}")
        save_state()
        return jsonify(with_calculated_values(state))


@app.post("/undo")
def undo():
    global state, last_action, redo_history
    with match_context():
        blocked = manual_scoring_blocked_response()
        if blocked is not None:
            return blocked
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
        blocked = manual_scoring_blocked_response()
        if blocked is not None:
            return blocked
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
        blocked = manual_scoring_blocked_response()
        if blocked is not None:
            return blocked
        for key in ("runs", "wickets", "overs", "balls", "extras"):
            if key in data:
                state[key] = safe_num(data[key], state[key])
        if state.get("scoring_mode") == "over_only":
            state["over_only_checkpoints"] = []
        save_state()
        return jsonify(with_calculated_values(state))


@app.post("/set-players")
def set_players():
    data = request.get_json(silent=True) or {}
    with match_context():
        blocked = manual_scoring_blocked_response()
        if blocked is not None:
            return blocked
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
    if panel not in {"score", "batting", "bowling", "chase", "fullscore", "chart"}:
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
        blocked = manual_scoring_blocked_response()
        if blocked is not None:
            return blocked
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
        blocked = manual_scoring_blocked_response()
        if blocked is not None:
            return blocked
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
        state["over_only_checkpoints"] = []
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
        blocked = manual_scoring_blocked_response()
        if blocked is not None:
            return blocked
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


register_relay_worker(app, apply_relay_ingest_payload)


with app.app_context():
    db.create_all()

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
