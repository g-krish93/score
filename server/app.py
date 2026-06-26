from __future__ import annotations

import copy
import json
import os
import re
import secrets
import smtplib
import threading
from contextlib import contextmanager
from datetime import datetime, timezone
from email.message import EmailMessage
from functools import wraps
from pathlib import Path
from typing import Optional
from urllib.parse import urlparse

from dotenv import load_dotenv
from flask import (
    Flask,
    abort,
    flash,
    has_request_context,
    jsonify,
    redirect,
    render_template,
    request,
    Response,
    send_file,
    session,
    url_for,
)
from flask_cors import CORS
from itsdangerous import BadSignature, SignatureExpired, URLSafeTimedSerializer
from sqlalchemy.exc import IntegrityError
from sqlalchemy import inspect, text

from .models_cricrelay import (
    ClubUser,
    Organization,
    RelayMatch,
    Sponsor,
    StreamSession,
    build_play_cricket_scrape_url,
    canonicalize_play_cricket_scrape_url,
    db,
    normalize_play_cricket_club_root,
    slugify_org_name,
)
from .pcs_protocol import (
    apply_pcs_events,
    apply_pcs_packet,
    pcs_capture_report,
    pcs_live_summary,
    pcs_state_to_snapshot,
)
from .play_cricket_scraper import scrape_fixtures
from .scraper_worker import get_live_snapshot, register_relay_worker
from .stream_api import (
    bearer_org_from_request,
    issue_stream_token,
    issue_twitch_oauth_state,
    issue_youtube_oauth_state,
    match_day_status,
    org_id_from_twitch_oauth_state,
    org_id_from_youtube_oauth_state,
    relay_match_for_org,
    relay_matches_for_org,
    stream_api_auth_required,
)
from . import twitch_stream as tw
from . import youtube_stream as yt
from .rate_limit import auth_limiter

load_dotenv()

STATE_DIR = Path(os.getenv("STATE_DIR", "/tmp")).expanduser()
try:
    STATE_DIR.mkdir(parents=True, exist_ok=True)
except OSError:
    pass

_DEFAULT_CORS_ORIGINS = [
    "https://cricrelay.co.uk",
    "https://www.cricrelay.co.uk",
    r"http://localhost:\d+",
    r"http://127\.0\.0\.1:\d+",
]


def _parse_cors_origins() -> list[str]:
    raw = (os.getenv("CORS_ORIGINS") or "").strip()
    if raw:
        return [origin.strip() for origin in raw.split(",") if origin.strip()]
    return list(_DEFAULT_CORS_ORIGINS)


def _is_production_https() -> bool:
    base = (os.getenv("PUBLIC_BASE_URL") or "").strip().lower()
    if base.startswith("https://"):
        return True
    return os.getenv("FLASK_ENV", "").lower() == "production"


app = Flask(__name__, template_folder="../templates", static_folder="../static")
CORS(app, resources={r"/*": {"origins": _parse_cors_origins()}})

app.config["SECRET_KEY"] = os.getenv("SECRET_KEY", "dev-insecure-change-me")
app.config["SESSION_COOKIE_HTTPONLY"] = True
app.config["SESSION_COOKIE_SAMESITE"] = "Lax"
app.config["SESSION_COOKIE_SECURE"] = _is_production_https()
_db_url = (os.getenv("DATABASE_URL") or "").strip()
if not _db_url:
    _db_path = (STATE_DIR / "cricrelay.db").resolve()
    _db_url = f"sqlite:///{_db_path.as_posix()}"
app.config["SQLALCHEMY_DATABASE_URI"] = _db_url
app.config["SQLALCHEMY_TRACK_MODIFICATIONS"] = False
db.init_app(app)


@app.context_processor
def inject_seo_context():
    year = datetime.now(timezone.utc).year
    if not has_request_context():
        return {"canonical_url": "", "current_year": year}
    env_base = (os.getenv("PUBLIC_BASE_URL") or "").strip().rstrip("/")
    req_base = request.url_root.rstrip("/")
    root = env_base or req_base
    path = request.path or "/"
    canonical_url = f"{root}{path}"
    ui_theme = "original"
    oid = session.get("org_id")
    if oid:
        org = db.session.get(Organization, oid)
        if org:
            raw_theme = (getattr(org, "ui_theme", "original") or "original").strip().lower()
            if raw_theme in {"original", "light", "dark"}:
                ui_theme = raw_theme
    return {"canonical_url": canonical_url, "current_year": year, "ui_theme": ui_theme}


DEFAULT_MATCH_ID = "default"
MAX_LIVE_STREAMS_PER_CLUB = 6
state_lock = threading.Lock()
last_action = None
action_history = []
redo_history = []
current_match_id = DEFAULT_MATCH_ID
match_contexts = {}


# --- Strangler cut-over: dual-write scoring events to the new core/store ---
# Default OFF. When SCORING_DUAL_WRITE is enabled and EVENT_STORE_DSN is set,
# each scored ball is ALSO recorded as an event in the new Postgres event store
# (cricrelay_core + cricrelay_store), so the new engine can be shadow-compared
# against the legacy engine before any cut-over. This NEVER affects the live
# response: every failure is swallowed and the legacy engine stays authoritative.
SCORING_DUAL_WRITE = (os.getenv("SCORING_DUAL_WRITE", "") or "").strip().lower() in {
    "1", "true", "yes", "on",
}
# When on (and the event log is being dual-written), each /score read folds the
# log through the core and logs any field that diverges from the legacy engine.
SCORING_SHADOW_COMPARE = (os.getenv("SCORING_SHADOW_COMPARE", "") or "").strip().lower() in {
    "1", "true", "yes", "on",
}
_event_store = None


def _scoring_event_store():
    global _event_store
    if _event_store is None:
        from cricrelay_store import PostgresEventStore

        store = PostgresEventStore(os.environ["EVENT_STORE_DSN"])
        store.init_schema()
        _event_store = store
    return _event_store


def _dual_write_setup(match_id, snapshot):
    if not SCORING_DUAL_WRITE:
        return
    try:
        from server.scoring_bridge import setup_to_start_innings

        _scoring_event_store().append(match_id, setup_to_start_innings(snapshot))
    except Exception:
        app.logger.exception("scoring dual-write (setup) failed; ignored")


def _dual_write_ball(match_id, ball_type, run_bonus, out_batter, dismissal_kind):
    if not SCORING_DUAL_WRITE:
        return
    try:
        from server.scoring_bridge import ball_to_delivery

        _scoring_event_store().append(
            match_id,
            ball_to_delivery(ball_type, run_bonus, out_batter, dismissal_kind),
        )
    except Exception:
        app.logger.exception("scoring dual-write (ball) failed; ignored")


def _shadow_compare(match_id, legacy_state):
    if not SCORING_SHADOW_COMPARE:
        return
    try:
        from cricrelay_core import derived, reduce
        from server.scoring_shadow import diffs

        events = _scoring_event_store().load(match_id)
        if not events:
            return
        divergences = diffs(legacy_state, derived(reduce(events)))
        if divergences:
            app.logger.warning(
                "scoring shadow diff [%s]: %s", match_id, "; ".join(divergences)
            )
    except Exception:
        app.logger.exception("scoring shadow-compare failed; ignored")


def blank_state():
    return {
        "team1": "",
        "team2": "",
        "team1_color": "#3b82f6",
        "team2_color": "#facc15",
        "theme": "classic",
        "overlay_density": "expanded",
        "overlay_scale": 1.0,
        "overlay_size": 3,
        "overlay_box_color": "#101f45",
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
        "relay_mode": "manual",
        "relay_play_cricket_url": "",
        "relay_wrapper": None,
        "relay_last_ok_at": None,
        "relay_last_error": None,
        "pcs_ingest_token": "",
        "pcs_ble_state": None,
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


def normalize_public_club_slug(raw):
    """URL segment for /club/<slug> — must match stored Organization.slug charset."""
    s = re.sub(r"[^a-zA-Z0-9_-]+", "-", str(raw or "").strip().lower()).strip("-")
    return (s or "")[:64]


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


def migrate_relay_match_columns():
    """Add RelayMatch.label / .paused for databases created before those columns existed."""
    insp = inspect(db.engine)
    if not insp.has_table("cricrelay_match"):
        return
    cols = {c["name"] for c in insp.get_columns("cricrelay_match")}
    dialect = db.engine.dialect.name
    altered = False
    if "label" not in cols:
        db.session.execute(text("ALTER TABLE cricrelay_match ADD COLUMN label VARCHAR(120)"))
        altered = True
    if "paused" not in cols:
        if dialect == "sqlite":
            db.session.execute(text("ALTER TABLE cricrelay_match ADD COLUMN paused BOOLEAN DEFAULT 0"))
        else:
            db.session.execute(text("ALTER TABLE cricrelay_match ADD COLUMN paused BOOLEAN DEFAULT FALSE"))
        altered = True
    if altered:
        db.session.commit()


def migrate_relay_source_column():
    """Add RelayMatch.relay_source for scraper vs PCS BLE streams."""
    insp = inspect(db.engine)
    if not insp.has_table("cricrelay_match"):
        return
    cols = {c["name"] for c in insp.get_columns("cricrelay_match")}
    if "relay_source" in cols:
        return
    db.session.execute(text("ALTER TABLE cricrelay_match ADD COLUMN relay_source VARCHAR(24) DEFAULT 'scraper'"))
    db.session.commit()


def migrate_organization_brand_columns():
    """Add Organization public branding columns for older databases."""
    insp = inspect(db.engine)
    if not insp.has_table("cricrelay_org"):
        return
    cols = {c["name"] for c in insp.get_columns("cricrelay_org")}
    altered = False
    if "public_logo_url" not in cols:
        db.session.execute(text("ALTER TABLE cricrelay_org ADD COLUMN public_logo_url VARCHAR(1000)"))
        altered = True
    if "public_primary_color" not in cols:
        db.session.execute(
            text("ALTER TABLE cricrelay_org ADD COLUMN public_primary_color VARCHAR(7) DEFAULT '#22d3a8'")
        )
        altered = True
    if "public_accent_color" not in cols:
        db.session.execute(
            text("ALTER TABLE cricrelay_org ADD COLUMN public_accent_color VARCHAR(7) DEFAULT '#38bdf8'")
        )
        altered = True
    if "ui_theme" not in cols:
        db.session.execute(text("ALTER TABLE cricrelay_org ADD COLUMN ui_theme VARCHAR(16) DEFAULT 'original'"))
        altered = True
    if altered:
        db.session.commit()


def migrate_play_cricket_base_url_nullable():
    """App registrations may not have a Play-Cricket URL at sign-up."""
    insp = inspect(db.engine)
    if not insp.has_table("cricrelay_org"):
        return
    cols = {c["name"]: c for c in insp.get_columns("cricrelay_org")}
    col = cols.get("play_cricket_base_url")
    if col and not col["nullable"]:
        if db.engine.dialect.name == "postgresql":
            db.session.execute(text(
                "ALTER TABLE cricrelay_org ALTER COLUMN play_cricket_base_url DROP NOT NULL"
            ))
            db.session.commit()


def migrate_consent_given_at_column():
    """GDPR consent timestamp for account registration."""
    insp = inspect(db.engine)
    if not insp.has_table("cricrelay_org"):
        return
    cols = {c["name"] for c in insp.get_columns("cricrelay_org")}
    if "consent_given_at" not in cols:
        ts = _sql_timestamp_type()
        db.session.execute(text(f"ALTER TABLE cricrelay_org ADD COLUMN consent_given_at {ts}"))
        db.session.commit()


def _sql_timestamp_type() -> str:
    if db.engine.dialect.name == "postgresql":
        return "TIMESTAMP WITHOUT TIME ZONE"
    return "DATETIME"


def migrate_youtube_columns():
    """Add YouTube Stream columns on Organization."""
    insp = inspect(db.engine)
    if not insp.has_table("cricrelay_org"):
        return
    cols = {c["name"] for c in insp.get_columns("cricrelay_org")}
    ts = _sql_timestamp_type()
    specs = [
        ("youtube_refresh_token_enc", "TEXT"),
        ("youtube_channel_id", "VARCHAR(64)"),
        ("youtube_channel_title", "VARCHAR(200)"),
        ("youtube_connected_at", ts),
        ("youtube_active_broadcast_id", "VARCHAR(64)"),
        ("youtube_active_stream_id", "VARCHAR(64)"),
        ("youtube_active_match_slug", "VARCHAR(120)"),
    ]
    altered = False
    for name, typ in specs:
        if name not in cols:
            db.session.execute(text(f"ALTER TABLE cricrelay_org ADD COLUMN {name} {typ}"))
            altered = True
    if altered:
        db.session.commit()


def migrate_twitch_columns():
    """Add Twitch Stream columns on Organization."""
    insp = inspect(db.engine)
    if not insp.has_table("cricrelay_org"):
        return
    cols = {c["name"] for c in insp.get_columns("cricrelay_org")}
    ts = _sql_timestamp_type()
    specs = [
        ("twitch_refresh_token_enc", "TEXT"),
        ("twitch_user_id", "VARCHAR(32)"),
        ("twitch_login", "VARCHAR(64)"),
        ("twitch_display_name", "VARCHAR(200)"),
        ("twitch_connected_at", ts),
        ("twitch_active_match_slug", "VARCHAR(120)"),
    ]
    altered = False
    for name, typ in specs:
        if name not in cols:
            db.session.execute(text(f"ALTER TABLE cricrelay_org ADD COLUMN {name} {typ}"))
            altered = True
    if altered:
        db.session.commit()


def _youtube_redirect_uri() -> str:
    env = (os.getenv("YOUTUBE_REDIRECT_URI") or "").strip()
    if env:
        return env
    base = _public_base_url()
    if base:
        return f"{base}/dashboard/youtube/callback"
    return ""


def _org_youtube_access_token(org: Organization) -> str | None:
    enc = (org.youtube_refresh_token_enc or "").strip()
    if not enc:
        return None
    refresh = yt.decrypt_token(enc)
    if not refresh:
        return None
    try:
        data = yt.refresh_access_token(refresh)
        return data.get("access_token")
    except Exception:
        return None


def _twitch_redirect_uri() -> str:
    env = (os.getenv("TWITCH_REDIRECT_URI") or "").strip()
    if env:
        return env
    base = _public_base_url()
    if base:
        return f"{base}/dashboard/twitch/callback"
    return ""


def _org_twitch_access_token(org: Organization) -> str | None:
    enc = (org.twitch_refresh_token_enc or "").strip()
    if not enc:
        return None
    refresh = tw.decrypt_token(enc)
    if not refresh:
        return None
    try:
        data = tw.refresh_access_token(refresh)
        return data.get("access_token")
    except Exception:
        return None


def _safe_hex_color(value: str, default: str) -> str:
    raw = (value or "").strip()
    if re.fullmatch(r"#[0-9a-fA-F]{6}", raw):
        return raw.lower()
    return default


def _org_play_cricket_root(org: Organization) -> str:
    raw = (org.play_cricket_base_url or "").strip()
    return normalize_play_cricket_club_root(raw) or raw.rstrip("/")


def _org_fixture_roots(org: Organization, matches: list[RelayMatch] | None = None) -> list[str]:
    """Candidate Play-Cricket roots for fixture scraping.

    Legacy orgs may have no saved base URL, but existing relay rows often contain
    full scrape URLs with enough info to recover the club host.
    """
    roots: list[str] = []
    seen: set[str] = set()

    def add(raw: str) -> None:
        base = normalize_play_cricket_club_root(raw) or (raw or "").strip().rstrip("/")
        if not base or "play-cricket.com" not in base.lower():
            return
        key = base.lower()
        if key in seen:
            return
        seen.add(key)
        roots.append(base)

    add(_org_play_cricket_root(org))
    for row in matches or []:
        src = (getattr(row, "full_scrape_url", None) or "").strip()
        if not src:
            continue
        parsed = urlparse(src)
        if parsed.scheme and parsed.netloc:
            add(f"{parsed.scheme}://{parsed.netloc}")
    return roots


def _fixture_candidate_urls(base_url: str) -> list[str]:
    """Play-Cricket fixture pages to try, in priority order.

    Prefer the club Weekly matches tab (``/Matches?tab=Weekly``) so the Home
    fixture list tracks upcoming weekly fixtures instead of arbitrary homepage links
    (same idea as bmacc.play-cricket.com/Matches?tab=Weekly).
    """
    raw = (base_url or "").strip().rstrip("/")
    if not raw:
        return []
    parsed = urlparse(raw)
    host_root = f"{parsed.scheme}://{parsed.netloc}" if parsed.scheme and parsed.netloc else ""
    if not host_root:
        return [raw]
    low = raw.lower()
    seen: set[str] = set()
    out: list[str] = []

    def add(u: str) -> None:
        k = u.lower()
        if k in seen:
            return
        seen.add(k)
        out.append(u)

    weekly = f"{host_root}/Matches?tab=Weekly"
    add(weekly)
    # If the org saved a specific matches/results URL (not just the host root), try it next.
    if low != host_root.lower() and raw.lower().startswith(host_root.lower()):
        if "/matches" in low or "/website/results" in low:
            add(raw)
    add(f"{host_root}/Matches")
    add(f"{host_root}/website/results")
    return out


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


def save_state_with_manual_touch():
    """Record manual scorer activity timestamp when in manual relay mode."""
    mode = (state.get("relay_mode") or "manual").strip().lower()
    if mode == "manual":
        state["last_manual_at"] = datetime.now(timezone.utc).isoformat()
    save_state()


def _relay_set_paused(org: Organization, slug: str, paused: bool) -> tuple[RelayMatch | None, str | None]:
    row = relay_match_for_org(org, slug)
    if not row:
        return None, "unknown stream"
    row.paused = bool(paused)
    db.session.commit()
    return row, None


def _relay_delete(org: Organization, slug: str) -> str | None:
    row = relay_match_for_org(org, slug)
    if not row:
        return "unknown stream"
    db.session.delete(row)
    db.session.commit()
    path = state_path_for(slug)
    if path.is_file():
        try:
            path.unlink()
        except OSError:
            pass
    return None


def _update_broadcast_status_in_state(slug: str, status: str, platform: str | None, watch_url: str | None):
    with match_context(slug):
        merge_missing_state_keys(state)
        state["broadcast_status"] = status
        state["broadcast_platform"] = platform
        state["broadcast_watch_url"] = watch_url
        state["broadcast_updated_at"] = datetime.now(timezone.utc).isoformat()
        save_state()


def apply_relay_to_score_match(match_slug: str, full_url: str):
    url = canonicalize_play_cricket_scrape_url((full_url or "").strip())
    with match_context(match_slug):
        merge_missing_state_keys(state)
        state["relay_mode"] = "play_cricket"
        state["relay_play_cricket_url"] = url
        state["relay_wrapper"] = None
        state["relay_last_error"] = None
        save_state()


def apply_pcs_ble_to_score_match(match_slug: str, ingest_token: str, label: str = ""):
    with match_context(match_slug):
        merge_missing_state_keys(state)
        state["relay_mode"] = "pcs_ble"
        state["relay_play_cricket_url"] = ""
        state["relay_wrapper"] = None
        state["relay_last_error"] = None
        state["pcs_ingest_token"] = (ingest_token or "").strip() or secrets.token_urlsafe(24)
        state["pcs_ble_state"] = None
        if label:
            snap = pcs_state_to_snapshot(None, label=label)
            state["relay_wrapper"] = {
                "source_url": "pcs-ble",
                "stale": True,
                "snapshot": snap,
                "last_fetch_at": None,
                "last_ok_at": None,
                "last_changed_at": None,
                "last_error": "Awaiting PCS BLE packets from relay app",
            }
        save_state()


def _public_base_url() -> str:
    env_base = (os.getenv("PUBLIC_BASE_URL") or "").strip().rstrip("/")
    if env_base:
        return env_base
    if has_request_context():
        return request.url_root.rstrip("/")
    return ""


def _pcs_relay_apk_path() -> Path:
    custom = (os.getenv("PCS_RELAY_APK_PATH") or "").strip()
    if custom:
        return Path(custom).expanduser()
    static = Path(app.static_folder or "../static")
    for candidate in (static / "pcs-relay.apk", static / "pcs-relay" / "pcs-relay.apk"):
        if candidate.is_file():
            return candidate
    return static / "pcs-relay.apk"


def _stream_apk_path() -> Path:
    custom = (os.getenv("STREAM_APP_APK_PATH") or "").strip()
    if custom:
        return Path(custom).expanduser()
    static = Path(app.static_folder or "../static")
    for candidate in (static / "cricrelay-stream.apk", static / "cricrelay-stream" / "cricrelay-stream.apk"):
        if candidate.is_file():
            return candidate
    return static / "cricrelay-stream.apk"


def _stream_ipa_path() -> Path:
    custom = (os.getenv("STREAM_APP_IPA_PATH") or "").strip()
    if custom:
        return Path(custom).expanduser()
    static = Path(app.static_folder or "../static")
    for candidate in (static / "cricrelay-stream.ipa", static / "cricrelay-stream" / "cricrelay-stream.ipa"):
        if candidate.is_file():
            return candidate
    return static / "cricrelay-stream.ipa"


def _stream_app_version_label() -> str:
    env = (os.getenv("STREAM_APP_VERSION") or "").strip()
    if env:
        return env
    gradle = Path(__file__).resolve().parents[1] / "cricrelay-mobile" / "android" / "app" / "build.gradle.kts"
    if gradle.is_file():
        m = re.search(r'versionName\s*=\s*"([^"]+)"', gradle.read_text(encoding="utf-8"))
        if m:
            return m.group(1)
    return "2.0.0"


def _stream_app_builds_payload() -> dict:
    base = _public_base_url().rstrip("/")
    apk = _stream_apk_path()
    ipa = _stream_ipa_path()
    version = _stream_app_version_label()
    ota_manifest = f"{base}/download/cricrelay-stream-ota.plist" if base else "/download/cricrelay-stream-ota.plist"
    return {
        "ok": True,
        "version": version,
        "android": {
            "available": apk.is_file(),
            "url": f"{base}/download/cricrelay-stream.apk" if apk.is_file() and base else None,
            "label": "Android (APK)",
        },
        "ios": {
            "available": ipa.is_file(),
            "url": f"{base}/download/cricrelay-stream.ipa" if ipa.is_file() and base else None,
            "ota_install_url": (
                f"itms-services://?action=download-manifest&url={ota_manifest}"
                if ipa.is_file()
                else None
            ),
            "ota_manifest_url": ota_manifest if ipa.is_file() else None,
            "label": "iPhone (install)",
            "install_note": (
                "Open the install link in Safari on your iPhone. "
                "After install: Settings → General → VPN & Device Management → trust the developer."
            ),
            "streaming_note": (
                "Live camera + scoreboard burn-in is optimized for Android. "
                "On iPhone you can sign in, manage streams, and use stream-key RTMP where supported."
            ),
        },
    }


def _apk_download_response(path: Path, filename: str):
    """Stream APK from disk with Range support (resume) — do not read_bytes() large APKs."""
    return send_file(
        path,
        mimetype="application/vnd.android.package-archive",
        as_attachment=True,
        download_name=filename,
        conditional=True,
        etag=True,
        max_age=0,
    )


def manual_scoring_blocked_response():
    mode = (state.get("relay_mode") or "manual").strip().lower()
    if mode == "play_cricket":
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
    if mode == "pcs_ble":
        return (
            jsonify(
                {
                    "error": (
                        "Manual scoring is disabled while PCS BLE relay (R&D) is active. "
                        "Scores come from the relay app, or switch relay mode to manual."
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
    wrapper = data.get("relay_wrapper")
    if not isinstance(wrapper, dict):
        wrapper = {}
    snap = wrapper.get("snapshot") if isinstance(wrapper.get("snapshot"), dict) else None
    url = (data.get("relay_play_cricket_url") or "").strip()
    mode = (data.get("relay_mode") or "manual").strip().lower()
    enabled = (mode == "play_cricket" and bool(url)) or mode == "pcs_ble"
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
        if not _org_from_session():
            session.pop("org_id", None)
            return redirect(url_for("login_page"))
        return view(*args, **kwargs)

    return wrapped


def _org_from_session():
    oid = session.get("org_id")
    if not oid:
        return None
    return db.session.get(Organization, oid)


def _password_reset_serializer() -> URLSafeTimedSerializer:
    return URLSafeTimedSerializer(app.config["SECRET_KEY"], salt="cricrelay-password-reset")


def _password_reset_ttl_sec() -> int:
    try:
        return max(300, int(os.getenv("PASSWORD_RESET_TTL_SEC", "3600")))
    except ValueError:
        return 3600


def _make_password_reset_token(org: Organization) -> str:
    payload = {"oid": org.id, "ph": org.password_hash}
    return _password_reset_serializer().dumps(payload)


def _read_password_reset_token(token: str) -> Optional[Organization]:
    try:
        payload = _password_reset_serializer().loads(token, max_age=_password_reset_ttl_sec())
    except SignatureExpired:
        return None
    except BadSignature:
        return None
    oid = str(payload.get("oid") or "").strip()
    expected_hash = str(payload.get("ph") or "")
    if not oid or not expected_hash:
        return None
    org = db.session.get(Organization, oid)
    if not org:
        return None
    if org.password_hash != expected_hash:
        # Token invalidated after password was changed.
        return None
    return org


def _smtp_enabled() -> bool:
    return bool((os.getenv("SMTP_HOST") or "").strip())


def _send_password_reset_email(to_email: str, reset_url: str) -> bool:
    host = (os.getenv("SMTP_HOST") or "").strip()
    if not host:
        return False
    port = int(os.getenv("SMTP_PORT", "587"))
    username = (os.getenv("SMTP_USERNAME") or "").strip()
    password = os.getenv("SMTP_PASSWORD", "")
    from_addr = (os.getenv("SMTP_FROM") or "no-reply@cricrelay.co.uk").strip()
    use_tls = (os.getenv("SMTP_USE_TLS", "1") or "1").strip().lower() not in {"0", "false", "no", "off"}

    msg = EmailMessage()
    msg["Subject"] = "Reset your CricRelay password"
    msg["From"] = from_addr
    msg["To"] = to_email
    msg.set_content(
        "We received a password reset request for your CricRelay account.\n\n"
        f"Open this link to reset your password:\n{reset_url}\n\n"
        f"This link expires in about {max(5, _password_reset_ttl_sec() // 60)} minutes.\n"
        "If you did not request this, you can ignore this email."
    )

    try:
        with smtplib.SMTP(host, port, timeout=20) as server:
            if use_tls:
                server.starttls()
            if username:
                server.login(username, password)
            server.send_message(msg)
        return True
    except Exception as exc:
        print(f"[password_reset] email send failed for {to_email}: {exc}", flush=True)
        return False


def normalize_overlay_size(value, fallback_scale=None) -> int:
    """Widget size preset 1 (smallest) … 5 (largest) for Play-Cricket relay overlays."""
    try:
        n = int(value)
        if 1 <= n <= 5:
            return n
    except (TypeError, ValueError):
        pass
    if fallback_scale is not None:
        try:
            sc = float(fallback_scale)
            return max(1, min(5, round(1 + (sc - 0.8) / 0.25)))
        except (TypeError, ValueError):
            pass
    return 3


VALID_OVERLAY_THEMES = {"classic", "neon", "minimal", "compact", "ai", "stadium"}

def _sanitize_overlay_theme(raw) -> str:
    t = str(raw or "classic").strip().lower()
    return t if t in VALID_OVERLAY_THEMES else "classic"

def read_relay_overlay_prefs(slug):
    safe = sanitize_match_id(slug)
    path = state_path_for(safe)
    if not path.exists():
        return {"overlay_size": 3, "overlay_scale": 1.0, "theme": "classic"}
    try:
        with path.open("r", encoding="utf-8") as fh:
            s = json.load(fh)
        size = normalize_overlay_size(s.get("overlay_size"), s.get("overlay_scale"))
        return {
            "overlay_size": size,
            "overlay_scale": float(s.get("overlay_scale") or 1.0),
            "theme": _sanitize_overlay_theme(s.get("theme")),
        }
    except Exception:
        return {"overlay_size": 3, "overlay_scale": 1.0, "theme": "classic"}


@app.get("/")
def cricrelay_home():
    if _org_from_session():
        return redirect(url_for("dashboard"))
    structured_data = {
        "@context": "https://schema.org",
        "@type": "SoftwareApplication",
        "name": "CricRelay",
        "applicationCategory": "SportsApplication",
        "operatingSystem": "Web",
        "offers": {"@type": "Offer", "price": "0", "priceCurrency": "GBP"},
        "description": "Relay ECB Play-Cricket scores into live stream overlays for UK cricket clubs.",
    }
    return render_template(
        "cricrelay_home.html",
        logged_in=False,
        structured_data=structured_data,
        app_builds=_stream_app_builds_payload(),
    )


@app.get("/pricing")
def pricing_page():
    return render_template("pricing.html")


@app.get("/compare")
def compare_page():
    return render_template("compare.html")


@app.get("/privacy")
def privacy_page():
    return render_template("privacy.html")


@app.get("/terms")
def terms_page():
    return render_template("terms.html")


@app.get("/club/<slug>")
def public_club_page(slug):
    """Public, read-only club page for fans and sponsors — only that org’s data."""
    safe = normalize_public_club_slug(slug)
    if not safe:
        abort(404)
    if safe != slug:
        return redirect(url_for("public_club_page", slug=safe), code=301)
    org = Organization.query.filter_by(slug=safe).first()
    if not org:
        abort(404)
    relays = RelayMatch.query.filter_by(organization_id=org.id).order_by(RelayMatch.created_at.desc()).all()
    public_page_url = url_for("public_club_page", slug=org.slug, _external=True)
    structured_data = {
        "@context": "https://schema.org",
        "@type": "SportsOrganization",
        "name": org.name,
        "url": public_page_url,
        "sport": "Cricket",
    }
    return render_template(
        "public_club.html",
        org=org,
        relays=relays,
        structured_data=structured_data,
        public_page_url=public_page_url,
        public_logo_url=(org.public_logo_url or "").strip(),
        public_primary_color=_safe_hex_color(getattr(org, "public_primary_color", ""), "#22d3a8"),
        public_accent_color=_safe_hex_color(getattr(org, "public_accent_color", ""), "#38bdf8"),
    )


@app.get("/robots.txt")
def robots_txt():
    root = request.url_root.rstrip("/")
    body = f"User-agent: *\nAllow: /\nSitemap: {root}/sitemap.xml\n"
    return Response(body, mimetype="text/plain")


@app.get("/sitemap.xml")
def sitemap_xml():
    root = request.url_root.rstrip("/")
    paths = ["/", "/pricing", "/compare", "/privacy", "/terms", "/register", "/login"]
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">',
    ]
    for p in paths:
        lines.append(f"<url><loc>{root}{p}</loc><changefreq>weekly</changefreq></url>")
    for org in Organization.query.all():
        if org.slug:
            lines.append(f"<url><loc>{root}/club/{org.slug}</loc><changefreq>daily</changefreq></url>")
    lines.append("</urlset>")
    return Response("\n".join(lines), mimetype="application/xml")


@app.route("/register", methods=["GET", "POST"])
@auth_limiter.limit_auth(scope="register", html_template="cricrelay_register.html")
def register_page():
    if request.method == "GET":
        return render_template("cricrelay_register.html")
    name = (request.form.get("name") or "").strip()
    email = (request.form.get("email") or "").strip().lower()
    password = request.form.get("password") or ""
    password2 = request.form.get("password2") or ""
    base_url = normalize_play_cricket_club_root(request.form.get("play_cricket_base_url") or "")
    if not name or not email or not password:
        flash("Please fill in club name, email, and password.", "error")
        return render_template("cricrelay_register.html"), 400
    if password != password2:
        flash("Passwords do not match.", "error")
        return render_template("cricrelay_register.html"), 400
    if len(password) < 8:
        flash("Use a password of at least 8 characters.", "error")
        return render_template("cricrelay_register.html"), 400
    if not request.form.get("consent"):
        flash("You must agree to the Privacy Policy to create an account.", "error")
        return render_template("cricrelay_register.html"), 400
    if not base_url:
        flash(
            "Enter your Play-Cricket club code — the short name before .play-cricket.com in your club site "
            "(for example bmacc for https://bmacc.play-cricket.com). You can type just bmacc.",
            "error",
        )
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
    org.consent_given_at = datetime.now(timezone.utc)
    db.session.add(org)
    try:
        db.session.commit()
    except IntegrityError:
        db.session.rollback()
        flash("That email or club URL slug is already in use.", "error")
        return render_template("cricrelay_register.html"), 400
    session["org_id"] = org.id
    flash("Welcome to CricRelay — pick a fixture and start your first stream.", "success")
    return redirect(url_for("dashboard"))


@app.route("/login", methods=["GET", "POST"])
@auth_limiter.limit_auth(scope="login", html_template="cricrelay_login.html")
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


@app.route("/forgot-password", methods=["GET", "POST"])
@auth_limiter.limit_auth(scope="forgot-password", html_template="forgot_password.html")
def forgot_password_page():
    if request.method == "GET":
        return render_template("forgot_password.html")
    email = (request.form.get("email") or "").strip().lower()
    if not email:
        flash("Enter the account email first.", "error")
        return render_template("forgot_password.html"), 400
    org = Organization.query.filter_by(email=email).first()
    if org:
        token = _make_password_reset_token(org)
        reset_url = url_for("reset_password_page", token=token, _external=True)
        sent = _send_password_reset_email(org.email, reset_url)
        if not sent:
            print(f"[password_reset] fallback reset link for {org.email}: {reset_url}", flush=True)
    # Always return generic message to avoid account enumeration.
    flash("If this email exists, we sent a password reset link.", "success")
    return redirect(url_for("login_page"))


@app.route("/reset-password/<token>", methods=["GET", "POST"])
def reset_password_page(token):
    org = _read_password_reset_token(token)
    if not org:
        flash("This reset link is invalid or has expired. Please request a new one.", "error")
        return redirect(url_for("forgot_password_page"))
    if request.method == "GET":
        return render_template("reset_password.html", token=token, email_hint=org.email)
    password = request.form.get("password") or ""
    password2 = request.form.get("password2") or ""
    if len(password) < 8:
        flash("Use a password with at least 8 characters.", "error")
        return render_template("reset_password.html", token=token, email_hint=org.email), 400
    if password != password2:
        flash("Passwords do not match.", "error")
        return render_template("reset_password.html", token=token, email_hint=org.email), 400
    org.set_password(password)
    db.session.commit()
    flash("Password reset complete. You can now log in.", "success")
    return redirect(url_for("login_page"))


@app.post("/logout")
def logout():
    session.pop("org_id", None)
    return redirect(url_for("cricrelay_home"))



def _dashboard_fixture_data(org):
    matches = RelayMatch.query.filter_by(organization_id=org.id).order_by(
        RelayMatch.created_at.desc()
    ).all()
    active_ids = {m.play_cricket_match_id for m in matches}
    fixtures = []
    fixtures_error = None
    fixture_source_url = ""
    probe_errors = []
    for root in _org_fixture_roots(org, matches):
        for candidate in _fixture_candidate_urls(root):
            try:
                rows = scrape_fixtures(candidate, limit=36)
                if rows:
                    fixtures = rows
                    fixture_source_url = candidate
                    break
            except Exception as exc:
                probe_errors.append(f"{candidate}: {exc}")
        if fixtures:
            break
    if not fixtures and probe_errors:
        fixtures_error = probe_errors[0]
    if not fixture_source_url:
        fixture_source_url = _org_play_cricket_root(org)
    relay_poll_sec = max(5, int(os.getenv("RELAY_POLL_INTERVAL_SEC", "10")))
    relay_auto_poll = (os.getenv("RELAY_AUTO_POLL", "1") or "1").strip().lower() not in {
        "0", "false", "no", "off",
    }
    base = _public_base_url()
    apk_path = _pcs_relay_apk_path()
    relay_rows = []
    for m in matches:
        slug = m.score_match_slug
        ingest_url = f"{base}/relay/pcs-ingest?match={slug}" if base else f"/relay/pcs-ingest?match={slug}"
        pcs_token = ""
        pcs_live = {}
        overlay_url = f"{base}/m/{slug}/stream" if base else f"/m/{slug}/stream"
        with match_context(slug):
            merge_missing_state_keys(state)
            pcs_token = (state.get("pcs_ingest_token") or "").strip()
            if (getattr(m, "relay_source", None) or "scraper") == "pcs_ble":
                pcs_live = pcs_live_summary(
                    state.get("pcs_ble_state"),
                    state.get("relay_wrapper"),
                )
                pcs_live["last_ok_at"] = state.get("relay_last_ok_at")
                pcs_live["relay_mode"] = state.get("relay_mode")
        embed_url = f"{overlay_url}?embed=1" if "?" not in overlay_url else f"{overlay_url}&embed=1"
        relay_rows.append(
            {
                "match": m,
                "overlay_prefs": read_relay_overlay_prefs(slug),
                "relay_source": (getattr(m, "relay_source", None) or "scraper"),
                "pcs_ingest_url": ingest_url,
                "pcs_ingest_token": pcs_token,
                "pcs_live": pcs_live,
                "overlay_url": overlay_url,
                "overlay_embed_url": embed_url,
            }
        )
    youtube_connected = bool((org.youtube_refresh_token_enc or "").strip())
    return {
        "matches": matches,
        "active_ids": active_ids,
        "fixtures": fixtures,
        "fixtures_error": fixtures_error,
        "fixture_source_url": fixture_source_url,
        "relay_rows": relay_rows,
        "relay_poll_sec": relay_poll_sec,
        "relay_auto_poll": relay_auto_poll,
        "stream_slots_used": len(matches),
        "stream_slots_total": MAX_LIVE_STREAMS_PER_CLUB,
        "public_base_url": base,
        "pcs_apk_available": apk_path.is_file(),
        "pcs_apk_download_url": url_for("download_pcs_relay_apk"),
        "stream_apk_available": _stream_apk_path().is_file(),
        "stream_apk_download_url": url_for("download_stream_apk"),
        "stream_ipa_available": _stream_ipa_path().is_file(),
        "stream_ipa_download_url": url_for("download_stream_ipa"),
        "stream_ios_ota_install_url": (_stream_app_builds_payload().get("ios") or {}).get("ota_install_url")
        or "",
        "youtube_connected": youtube_connected,
        "youtube_channel_title": org.youtube_channel_title or "",
        "youtube_oauth_configured": yt.oauth_configured(),
        "youtube_live_active": bool(org.youtube_active_broadcast_id),
        "youtube_active_match_slug": org.youtube_active_match_slug or "",
        "twitch_connected": bool((org.twitch_refresh_token_enc or "").strip()),
        "twitch_display_name": org.twitch_display_name or org.twitch_login or "",
        "twitch_oauth_configured": tw.oauth_configured(),
        "twitch_live_active": bool(org.twitch_active_match_slug),
        "twitch_active_match_slug": org.twitch_active_match_slug or "",
    }


@app.get("/dashboard")
@login_required
def dashboard():
    org = _org_from_session()
    ctx = _dashboard_fixture_data(org)
    return render_template(
        "cricrelay_dashboard_streams.html",
        org=org,
        play_cricket_root=_org_play_cricket_root(org),
        **ctx,
    )


@app.get("/dashboard/home")
@app.get("/dashboard/relays")
@login_required
def dashboard_legacy_redirect():
    return redirect(url_for("dashboard"))


@app.get("/download/cricrelay-stream.apk")
def download_stream_apk():
    path = _stream_apk_path()
    if not path.is_file():
        flash(
            "Stream app APK is not on the server yet. Push cricrelay-mobile/ to main to trigger CI "
            "(see cricrelay-mobile/README.md).",
            "error",
        )
        return redirect(url_for("dashboard"))
    return _apk_download_response(path, "cricrelay-stream.apk")


@app.get("/download/cricrelay-stream.ipa")
def download_stream_ipa():
    path = _stream_ipa_path()
    if not path.is_file():
        flash(
            "Stream app for iPhone is not on the server yet. Add Apple signing secrets to GitHub Actions "
            "(see cricrelay-stream/docs/IOS_CI_SETUP.md).",
            "error",
        )
        return redirect(url_for("dashboard"))
    return send_file(
        path,
        mimetype="application/octet-stream",
        as_attachment=True,
        download_name="cricrelay-stream.ipa",
        conditional=True,
        etag=True,
        max_age=0,
    )


@app.get("/download/cricrelay-stream-ota.plist")
def download_stream_ios_ota_manifest():
    """Over-the-air install manifest (open via itms-services:// from Safari on iPhone)."""
    if not _stream_ipa_path().is_file():
        return Response("IPA not available", status=404, mimetype="text/plain")
    base = _public_base_url().rstrip("/")
    ipa_url = f"{base}/download/cricrelay-stream.ipa"
    version = _stream_app_version_label()
    bundle_id = (os.getenv("STREAM_APP_IOS_BUNDLE_ID") or "uk.co.cricrelay.stream").strip()
    plist = f"""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>items</key>
  <array>
    <dict>
      <key>assets</key>
      <array>
        <dict>
          <key>kind</key>
          <string>software-package</string>
          <key>url</key>
          <string>{ipa_url}</string>
        </dict>
      </array>
      <key>metadata</key>
      <dict>
        <key>bundle-identifier</key>
        <string>{bundle_id}</string>
        <key>bundle-version</key>
        <string>{version}</string>
        <key>kind</key>
        <string>software</string>
        <key>title</key>
        <string>CricRelay Live</string>
      </dict>
    </dict>
  </array>
</dict>
</plist>
"""
    return Response(plist, mimetype="application/xml")


@app.get("/download/pcs-relay.apk")
def download_pcs_relay_apk():
    path = _pcs_relay_apk_path()
    if not path.is_file():
        flash(
            "PCS relay APK is not on the server yet. Build the app and copy pcs-relay.apk to static/ "
            "(see static/pcs-relay/README.md), or set PCS_RELAY_APK_PATH.",
            "error",
        )
        return redirect(url_for("dashboard"))
    return _apk_download_response(path, "cricrelay-pcs-relay.apk")


def _stream_slot_error(org: Organization) -> str | None:
    n_relays = RelayMatch.query.filter_by(organization_id=org.id).count()
    if n_relays >= MAX_LIVE_STREAMS_PER_CLUB:
        return (
            f"You can run up to {MAX_LIVE_STREAMS_PER_CLUB} live streams per club. "
            "Pause or remove one to add another."
        )
    return None


def _create_play_cricket_stream_org(
    org: Organization,
    play_cricket_match_id: str,
    label: str = "",
    play_cricket_base_url: str = "",
) -> tuple[RelayMatch | None, str | None]:
    slot_err = _stream_slot_error(org)
    if slot_err:
        return None, slot_err
    mid = (play_cricket_match_id or "").strip()
    if not mid or not re.fullmatch(r"\d+", mid):
        return None, (
            "Match ID must be the numeric fixture id "
            "(from …/website/results/7560599 or …match_details?id=7560599)."
        )
    base_override_raw = (play_cricket_base_url or "").strip()
    if base_override_raw:
        base = normalize_play_cricket_club_root(base_override_raw)
        if not base:
            return None, (
                "That Play-Cricket club code was not recognised. "
                "Use letters and numbers only, like bmacc for https://bmacc.play-cricket.com."
            )
    else:
        base = _org_play_cricket_root(org)
    if "play-cricket.com" not in base.lower():
        return None, "Play-Cricket club site must be on play-cricket.com."
    full_url = canonicalize_play_cricket_scrape_url(build_play_cricket_scrape_url(base, mid))
    existing = RelayMatch.query.filter_by(organization_id=org.id, play_cricket_match_id=mid).first()
    if existing:
        return None, "This Play-Cricket match is already linked for your club."
    base_slug = sanitize_match_id(f"{org.slug}-{mid}")
    score_slug = base_slug
    for _ in range(16):
        taken = RelayMatch.query.filter_by(score_match_slug=score_slug).first()
        if not taken:
            break
        score_slug = sanitize_match_id(f"{org.slug}-{mid}-{secrets.token_hex(3)}")
    stream_label = (label or "").strip()[:120]
    row = RelayMatch(
        organization_id=org.id,
        play_cricket_match_id=mid,
        full_scrape_url=full_url,
        score_match_slug=score_slug,
        label=(stream_label or None),
        paused=False,
        relay_source="scraper",
    )
    db.session.add(row)
    try:
        db.session.commit()
    except IntegrityError:
        db.session.rollback()
        return None, "Could not create that stream (duplicate or conflict)."
    apply_relay_to_score_match(score_slug, full_url)
    return row, None


def _create_pcs_ble_stream_org(org: Organization, label: str) -> tuple[RelayMatch | None, str | None]:
    slot_err = _stream_slot_error(org)
    if slot_err:
        return None, slot_err
    stream_label = (label or "").strip()[:120]
    if not stream_label:
        return None, "Label is required for a PCS BLE stream (e.g. 1st XI vs Rivals)."
    mid = f"ble-{secrets.token_hex(4)}"
    base_slug = sanitize_match_id(f"{org.slug}-pcs-{mid}")
    score_slug = base_slug
    for _ in range(16):
        if not RelayMatch.query.filter_by(score_match_slug=score_slug).first():
            break
        score_slug = sanitize_match_id(f"{org.slug}-pcs-{mid}-{secrets.token_hex(2)}")
    ingest_token = secrets.token_urlsafe(24)
    row = RelayMatch(
        organization_id=org.id,
        play_cricket_match_id=mid,
        full_scrape_url="",
        score_match_slug=score_slug,
        label=stream_label,
        paused=False,
        relay_source="pcs_ble",
    )
    db.session.add(row)
    try:
        db.session.commit()
    except IntegrityError:
        db.session.rollback()
        return None, "Could not create PCS BLE stream."
    apply_pcs_ble_to_score_match(score_slug, ingest_token, label=stream_label)
    return row, None


@app.post("/dashboard/matches")
@login_required
def dashboard_add_match():
    org = _org_from_session()
    relay_source = (request.form.get("relay_source") or "scraper").strip().lower()
    if relay_source not in {"scraper", "pcs_ble"}:
        relay_source = "scraper"
    if relay_source == "pcs_ble":
        return dashboard_add_pcs_ble_match(org)
    row, err = _create_play_cricket_stream_org(
        org,
        request.form.get("play_cricket_match_id") or "",
        request.form.get("stream_label") or "",
        request.form.get("play_cricket_base_url") or "",
    )
    if err:
        flash(err, "error")
        return redirect(url_for("dashboard"))
    flash("Stream ready — copy your overlay URL into Prism or OBS.", "success")
    return redirect(url_for("dashboard"))


def dashboard_add_pcs_ble_match(org: Organization):
    row, err = _create_pcs_ble_stream_org(org, request.form.get("stream_label") or "")
    if err:
        flash(err, "error")
        return redirect(url_for("dashboard"))
    flash(
        "PCS BLE stream ready (R&D) — install the relay APK, paste ingest URL + token, then copy the overlay URL.",
        "success",
    )
    return redirect(url_for("dashboard"))


@app.post("/dashboard/relay-appearance")
@login_required
def dashboard_relay_appearance():
    org = _org_from_session()
    slug = sanitize_match_id(request.form.get("score_match_slug", ""))
    if not slug:
        flash("Missing match.", "error")
        return redirect(url_for("dashboard"))
    row = RelayMatch.query.filter_by(organization_id=org.id, score_match_slug=slug).first()
    if not row:
        flash("Unknown relay for your club.", "error")
        return redirect(url_for("dashboard"))
    size = normalize_overlay_size(request.form.get("overlay_size"), request.form.get("overlay_scale"))
    theme = _sanitize_overlay_theme(request.form.get("theme", "classic"))
    with match_context(slug):
        merge_missing_state_keys(state)
        state["overlay_size"] = size
        state["overlay_scale"] = round(0.8 + (size - 1) * 0.25, 2)
        state["theme"] = theme
        save_state()
    flash("Overlay style updated.", "success")
    return redirect(url_for("dashboard"))


@app.post("/dashboard/relay/toggle-pause")
@login_required
def dashboard_relay_toggle_pause():
    org = _org_from_session()
    slug = sanitize_match_id(request.form.get("score_match_slug", ""))
    if not slug:
        flash("Missing stream.", "error")
        return redirect(url_for("dashboard"))
    row = RelayMatch.query.filter_by(organization_id=org.id, score_match_slug=slug).first()
    if not row:
        flash("Unknown stream.", "error")
        return redirect(url_for("dashboard"))
    row, err = _relay_set_paused(org, slug, not bool(row.paused))
    if err:
        flash("Unknown stream.", "error")
        return redirect(url_for("dashboard"))
    src = (getattr(row, "relay_source", None) or "scraper")
    if row.paused:
        flash("Stream paused — automatic scoring updates are off.", "success")
    elif src == "pcs_ble":
        flash("Stream live — waiting for PCS BLE relay app.", "success")
    else:
        flash("Stream live — Play-Cricket sync is on.", "success")
    return redirect(url_for("dashboard"))


@app.post("/dashboard/relay/delete")
@login_required
def dashboard_relay_delete():
    org = _org_from_session()
    slug = sanitize_match_id(request.form.get("score_match_slug", ""))
    if not slug:
        flash("Missing stream.", "error")
        return redirect(url_for("dashboard"))
    err = _relay_delete(org, slug)
    if err:
        flash("Unknown stream.", "error")
        return redirect(url_for("dashboard"))
    flash("Stream removed. Add a new match any time.", "success")
    return redirect(url_for("dashboard"))


@app.get("/stream")
def stream_overlay_default():
    embed = request.args.get("embed", "").strip().lower() in {"1", "true", "yes"}
    poll_ms = 1000 if embed else 2000
    return render_template(
        "overlay.html",
        match_id=DEFAULT_MATCH_ID,
        embed_mode=embed,
        poll_interval_ms=poll_ms,
    )


@app.get("/m/<match_id>/stream")
def stream_overlay_scoped(match_id):
    embed = request.args.get("embed", "").strip().lower() in {"1", "true", "yes"}
    poll_ms = 1000 if embed else 2000
    return render_template(
        "overlay.html",
        match_id=sanitize_match_id(match_id),
        embed_mode=embed,
        poll_interval_ms=poll_ms,
    )


@app.get("/m/<match_id>")
def legacy_overlay_redirect(match_id):
    slug = sanitize_match_id(match_id)
    return redirect(f"/m/{slug}/stream", code=301)


@app.get("/input")
def input_page():
    return render_template("input.html", match_id=DEFAULT_MATCH_ID)


@app.get("/m/<match_id>/input")
def input_page_scoped(match_id):
    slug = sanitize_match_id(match_id)
    if str(request.args.get("layout") or "").strip().lower() == "scorer":
        return _render_scorer_page(slug)
    return render_template("input.html", match_id=slug)


@app.get("/m/<match_id>/score")
def scorer_page_scoped(match_id):
    return _render_scorer_page(sanitize_match_id(match_id))


def _render_scorer_page(slug: str):
    label = slug
    row = RelayMatch.query.filter_by(score_match_slug=slug).first()
    if row and row.label:
        label = row.label
    return render_template("input_scorer.html", match_id=slug, match_label=label)


def _relay_ingest_authorized() -> bool:
    expected = os.getenv("RELAY_INGEST_TOKEN", "").strip()
    if not expected:
        return True
    auth = (request.headers.get("Authorization") or "").strip()
    return auth == f"Bearer {expected}"


def _relay_allow_open_ingest() -> bool:
    return os.getenv("RELAY_ALLOW_OPEN_INGEST", "").strip().lower() in {"1", "true", "yes"}


def _pcs_ingest_authorized(match_slug: str) -> bool:
    auth = (request.headers.get("Authorization") or "").strip()
    global_tok = os.getenv("RELAY_INGEST_TOKEN", "").strip()
    if global_tok and auth == f"Bearer {global_tok}":
        return True
    with match_context(match_slug):
        merge_missing_state_keys(state)
        per = (state.get("pcs_ingest_token") or "").strip()
    if per and auth == f"Bearer {per}":
        return True
    if not global_tok and not per:
        return _relay_allow_open_ingest()
    return False


@app.post("/relay/config")
def relay_config():
    data = request.get_json(silent=True) or {}
    mode = str(data.get("relay_mode", "manual")).strip().lower()
    if mode not in {"manual", "play_cricket", "pcs_ble"}:
        return jsonify({"error": "relay_mode must be manual, play_cricket, or pcs_ble"}), 400
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
        if mode == "pcs_ble" and not (state.get("pcs_ingest_token") or "").strip():
            state["pcs_ingest_token"] = secrets.token_urlsafe(24)
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
        src = (wrapper.get("source_url") or state.get("relay_play_cricket_url") or "").strip()
        if src:
            state["relay_play_cricket_url"] = canonicalize_play_cricket_scrape_url(src)
        state["relay_last_ok_at"] = datetime.now(timezone.utc).isoformat()
        state["relay_last_error"] = None
        save_state()
        return (
            {"ok": True, "relay_bundle": with_calculated_values(state)["relay_bundle"]},
            200,
        )


def apply_pcs_ble_ingest_payload(match_id: str, payload: dict) -> tuple[dict, int]:
    mid = sanitize_match_id(match_id)
    with match_context(mid):
        if (state.get("relay_mode") or "manual") != "pcs_ble":
            return ({"error": "relay_mode is not pcs_ble for this match"}, 400)
        events: list[str] = []
        if isinstance(payload.get("events"), list):
            events = [str(x) for x in payload["events"] if str(x).strip()]
        line = (payload.get("line") or payload.get("packet") or "").strip()
        if line:
            events.append(line)
        if not events:
            return ({"error": "payload must include events[] or line/packet"}, 400)
        pcs_state = state.get("pcs_ble_state")
        for ev in events:
            pcs_state = apply_pcs_packet(pcs_state, ev)
        raw_extra = (payload.get("raw") or payload.get("raw_line") or "").strip()
        if raw_extra and raw_extra not in events:
            pcs_state = apply_pcs_packet(pcs_state, raw_extra)
        state["pcs_ble_state"] = pcs_state
        label = ""
        rm = RelayMatch.query.filter_by(score_match_slug=mid).first()
        if rm and rm.label:
            label = rm.label
        snap = pcs_state_to_snapshot(pcs_state, label=label)
        now = datetime.now(timezone.utc).isoformat()
        state["relay_wrapper"] = {
            "source_url": "pcs-ble",
            "stale": False,
            "snapshot": snap,
            "last_fetch_at": now,
            "last_ok_at": now,
            "last_changed_at": now,
            "last_error": None,
        }
        state["relay_last_ok_at"] = now
        state["relay_last_error"] = None
        save_state()
        bundle = with_calculated_values(state)["relay_bundle"]
        capture = pcs_capture_report(state.get("pcs_ble_state"), state.get("relay_wrapper"))
        return (
            {
                "ok": True,
                "packets": len(events),
                "snapshot": bundle.get("snapshot"),
                "relay_bundle": bundle,
                "pcs_live": pcs_live_summary(state.get("pcs_ble_state"), state.get("relay_wrapper")),
                "pcs_capture": capture,
            },
            200,
        )


@app.get("/relay/pcs-status")
def relay_pcs_status():
    """JSON status for PCS BLE stream (overlay poll / debug). Same match slug as ingest."""
    match_id = get_request_match_id()
    if not _pcs_ingest_authorized(match_id):
        return jsonify({"error": "unauthorized"}), 401
    with match_context(match_id):
        if (state.get("relay_mode") or "manual") != "pcs_ble":
            return jsonify({"error": "relay_mode is not pcs_ble for this match"}), 400
        bundle = with_calculated_values(state).get("relay_bundle") or {}
        pcs_st = state.get("pcs_ble_state")
        wrap = state.get("relay_wrapper")
        return jsonify(
            {
                "ok": True,
                "relay_bundle": bundle,
                "pcs_live": pcs_live_summary(pcs_st, wrap),
                "pcs_capture": pcs_capture_report(pcs_st, wrap),
            }
        )


@app.get("/dashboard/pcs-capture-export")
@login_required
def dashboard_pcs_capture_export():
    """Download BLE capture log JSON for a PCS stream (R&D)."""
    org = _org_from_session()
    slug = sanitize_match_id(request.args.get("match", ""))
    if not slug:
        flash("Missing match slug.", "error")
        return redirect(url_for("dashboard"))
    row = RelayMatch.query.filter_by(organization_id=org.id, score_match_slug=slug).first()
    if not row or (getattr(row, "relay_source", None) or "scraper") != "pcs_ble":
        flash("Unknown PCS BLE stream.", "error")
        return redirect(url_for("dashboard"))
    with match_context(slug):
        report = pcs_capture_report(state.get("pcs_ble_state"), state.get("relay_wrapper"))
        report["match_slug"] = slug
        report["stream_label"] = row.label
    body = json.dumps(report, indent=2)
    return Response(
        body,
        mimetype="application/json",
        headers={
            "Content-Disposition": f'attachment; filename="pcs-capture-{slug}.json"',
        },
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


@app.post("/relay/pcs-ingest")
def relay_pcs_ingest():
    match_id = get_request_match_id()
    if not _pcs_ingest_authorized(match_id):
        return jsonify({"error": "unauthorized"}), 401
    payload = request.get_json(silent=True)
    if not isinstance(payload, dict):
        return jsonify({"error": "JSON body required"}), 400
    body, code = apply_pcs_ble_ingest_payload(match_id, payload)
    return jsonify(body), code


@app.get("/score")
def score():
    with match_context():
        _shadow_compare(current_match_id, state)
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
        state["theme"] = _sanitize_overlay_theme(theme)
        state["toss_winner"] = toss_winner
        state["toss_decision"] = toss_decision
        state["scoring_mode"] = "ball_by_ball"
        state["batting_team"] = batting_team
        state["bowling_team"] = bowling_team
        state["total_overs"] = safe_num(data.get("total_overs", 20), 20)
        state["batting_squad"] = build_batting_squad(batting_names)
        state["bowling_squad"] = build_bowling_squad(bowling_names)
        state["match_started"] = True
        last_action = None
        action_history = []
        redo_history = []
        _dual_write_setup(current_match_id, state)
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
        if innings_done():
            return jsonify({"error": "innings already complete"}), 400
        push_history()
        last_action = {"state_snapshot": copy.deepcopy(state)}
        _dual_write_ball(current_match_id, ball_type, run_bonus, out_batter, dismissal_kind)
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
            save_state_with_manual_touch()
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
            save_state_with_manual_touch()
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
        save_state_with_manual_touch()
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
        blocked = manual_scoring_blocked_response()
        if blocked is not None:
            return blocked
        if innings_done():
            return jsonify({"error": "innings already complete"}), 400
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


@app.get("/dashboard/youtube/connect")
@login_required
def dashboard_youtube_connect():
    org = _org_from_session()
    if not yt.oauth_configured():
        flash("YouTube OAuth is not configured on this server (YOUTUBE_CLIENT_ID / SECRET).", "error")
        return redirect(url_for("dashboard"))
    redirect_uri = _youtube_redirect_uri()
    if not redirect_uri:
        flash("Set PUBLIC_BASE_URL or YOUTUBE_REDIRECT_URI for YouTube connect.", "error")
        return redirect(url_for("dashboard"))
    state = yt.new_oauth_state()
    session["youtube_oauth_state"] = state
    session["youtube_oauth_org_id"] = org.id
    return redirect(yt.build_authorize_url(redirect_uri, state))


@app.get("/dashboard/youtube/callback")
def dashboard_youtube_callback():
    org = _org_from_session()
    state = request.args.get("state") or ""
    if org is None:
        mobile_oid = org_id_from_youtube_oauth_state(state)
        if mobile_oid:
            org = db.session.get(Organization, mobile_oid)
    if org is None:
        flash("Sign in to the dashboard, then connect YouTube.", "error")
        return redirect(url_for("cricrelay_login"))
    err = request.args.get("error")
    if err:
        flash(f"YouTube authorization failed: {err}", "error")
        return redirect(url_for("dashboard"))
    web_state = session.pop("youtube_oauth_state", None)
    web_oid = session.pop("youtube_oauth_org_id", None)
    if web_state is not None:
        if state != web_state or web_oid != org.id:
            flash("Invalid OAuth state. Try connecting YouTube again.", "error")
            return redirect(url_for("dashboard"))
    elif org_id_from_youtube_oauth_state(state) != org.id:
        flash("Invalid OAuth state. Try connecting YouTube again.", "error")
        return redirect(url_for("dashboard"))
    code = request.args.get("code")
    if not code:
        flash("Missing authorization code from Google.", "error")
        return redirect(url_for("dashboard"))
    redirect_uri = _youtube_redirect_uri()
    try:
        tok = yt.exchange_code(code, redirect_uri)
        access = tok.get("access_token")
        refresh = tok.get("refresh_token")
        if not refresh:
            flash("Google did not return a refresh token. Revoke app access and reconnect.", "error")
            return redirect(url_for("dashboard"))
        ch_id, ch_title = yt.fetch_channel_for_token(access)
        live_check = yt.verify_live_streaming_access(access)
        encrypted_refresh = yt.encrypt_token(refresh)
        if not encrypted_refresh:
            flash(
                "YouTube connected but token storage is misconfigured on the server "
                "(YOUTUBE_TOKEN_ENCRYPTION_KEY). Contact support@cricrelay.co.uk.",
                "error",
            )
            return redirect(url_for("dashboard"))
        org.youtube_refresh_token_enc = encrypted_refresh
        org.youtube_channel_id = ch_id
        org.youtube_channel_title = ch_title
        org.youtube_connected_at = datetime.now(timezone.utc)
        db.session.commit()
        if live_check.get("ok"):
            flash(f"YouTube connected: {ch_title} (live streaming access OK)", "success")
        else:
            flash(
                f"YouTube channel linked ({ch_title}) but live API access failed: "
                f"{live_check.get('message')}",
                "error",
            )
    except Exception as exc:
        flash(f"Could not connect YouTube: {exc}", "error")
        return redirect(url_for("dashboard"))
    if web_state is None:
        live_ok = live_check.get("ok")
        live_msg = (live_check.get("message") or "").strip()
        extra = (
            "<p style='color:#22c55e'>Live streaming API: OK — you can use Go Live from the app.</p>"
            if live_ok
            else (
                f"<p style='color:#f97316'>Live streaming API: not ready yet.</p>"
                f"<p>{live_msg}</p>"
                "<p><b>Enable live on your channel:</b> "
                "<a href='https://studio.youtube.com/channel/UC/livestreaming'>YouTube Studio → Go live</a> "
                "(phone verification; up to 24h wait for new channels).</p>"
                "<p>Then revoke CricRelay at "
                "<a href='https://myaccount.google.com/permissions'>Google permissions</a> "
                "and connect again, allowing <b>Manage your YouTube account</b>.</p>"
            )
        )
        return (
            "<!DOCTYPE html><html><body style='font-family:sans-serif;padding:2rem;max-width:36rem'>"
            f"<h2>YouTube connected</h2><p><b>{ch_title}</b></p>"
            f"{extra}"
            "<p><b>Next:</b> switch back to the <b>CricRelay Live</b> app "
            "(it will refresh). Or use <b>Custom RTMP</b> in the broadcast screen "
            "with a stream key from YouTube Studio.</p></body></html>"
        )
    return redirect(url_for("dashboard"))


@app.post("/dashboard/youtube/disconnect")
@login_required
def dashboard_youtube_disconnect():
    org = _org_from_session()
    org.youtube_refresh_token_enc = None
    org.youtube_channel_id = None
    org.youtube_channel_title = None
    org.youtube_connected_at = None
    org.youtube_active_broadcast_id = None
    org.youtube_active_stream_id = None
    org.youtube_active_match_slug = None
    db.session.commit()
    flash("YouTube disconnected.", "success")
    return redirect(url_for("dashboard"))


@app.get("/dashboard/twitch/connect")
@login_required
def dashboard_twitch_connect():
    org = _org_from_session()
    if not tw.oauth_configured():
        flash("Twitch OAuth is not configured on this server (TWITCH_CLIENT_ID / SECRET).", "error")
        return redirect(url_for("dashboard"))
    redirect_uri = _twitch_redirect_uri()
    if not redirect_uri:
        flash("Set PUBLIC_BASE_URL or TWITCH_REDIRECT_URI for Twitch connect.", "error")
        return redirect(url_for("dashboard"))
    state = tw.new_oauth_state()
    session["twitch_oauth_state"] = state
    session["twitch_oauth_org_id"] = org.id
    return redirect(tw.build_authorize_url(redirect_uri, state))


@app.get("/dashboard/twitch/callback")
def dashboard_twitch_callback():
    org = _org_from_session()
    state = request.args.get("state") or ""
    if org is None:
        mobile_oid = org_id_from_twitch_oauth_state(state)
        if mobile_oid:
            org = db.session.get(Organization, mobile_oid)
    if org is None:
        flash("Sign in to the dashboard, then connect Twitch.", "error")
        return redirect(url_for("cricrelay_login"))
    err = request.args.get("error")
    if err:
        flash(f"Twitch authorization failed: {err}", "error")
        return redirect(url_for("dashboard"))
    web_state = session.pop("twitch_oauth_state", None)
    web_oid = session.pop("twitch_oauth_org_id", None)
    if web_state is not None:
        if state != web_state or web_oid != org.id:
            flash("Invalid OAuth state. Try connecting Twitch again.", "error")
            return redirect(url_for("dashboard"))
    elif org_id_from_twitch_oauth_state(state) != org.id:
        flash("Invalid OAuth state. Try connecting Twitch again.", "error")
        return redirect(url_for("dashboard"))
    code = request.args.get("code")
    if not code:
        flash("Missing authorization code from Twitch.", "error")
        return redirect(url_for("dashboard"))
    redirect_uri = _twitch_redirect_uri()
    try:
        tok = tw.exchange_code(code, redirect_uri)
        access = tok.get("access_token")
        refresh = tok.get("refresh_token")
        if not refresh:
            flash("Twitch did not return a refresh token. Disconnect and connect again.", "error")
            return redirect(url_for("dashboard"))
        user_id, login, display = tw.fetch_user_for_token(access)
        live_check = tw.verify_streaming_access(access, user_id)
        encrypted_refresh = tw.encrypt_token(refresh)
        if not encrypted_refresh:
            flash(
                "Twitch connected but token storage is misconfigured on the server "
                "(YOUTUBE_TOKEN_ENCRYPTION_KEY). Contact support@cricrelay.co.uk.",
                "error",
            )
            return redirect(url_for("dashboard"))
        org.twitch_refresh_token_enc = encrypted_refresh
        org.twitch_user_id = user_id
        org.twitch_login = login
        org.twitch_display_name = display
        org.twitch_connected_at = datetime.now(timezone.utc)
        db.session.commit()
        if live_check.get("ok"):
            flash(f"Twitch connected: {display} (@{login})", "success")
        else:
            flash(
                f"Twitch linked ({display}) but stream key access failed: {live_check.get('message')}",
                "error",
            )
    except Exception as exc:
        flash(f"Could not connect Twitch: {exc}", "error")
        return redirect(url_for("dashboard"))
    if web_state is None:
        live_ok = live_check.get("ok")
        live_msg = (live_check.get("message") or "").strip()
        extra = (
            "<p style='color:#22c55e'>Stream key API: OK — you can use Go Live to Twitch from the app.</p>"
            if live_ok
            else f"<p style='color:#f97316'>Stream key not ready: {live_msg}</p>"
        )
        return (
            "<!DOCTYPE html><html><body style='font-family:sans-serif;padding:2rem;max-width:36rem'>"
            f"<h2>Twitch connected</h2><p><b>{display}</b> (@{login})</p>"
            f"{extra}"
            "<p><b>Next:</b> switch back to <b>CricRelay Live</b> and choose "
            "<b>Twitch (OAuth)</b> on the broadcast screen, or paste a stream key under Custom RTMP.</p>"
            "</body></html>"
        )
    return redirect(url_for("dashboard"))


@app.post("/dashboard/twitch/disconnect")
@login_required
def dashboard_twitch_disconnect():
    org = _org_from_session()
    org.twitch_refresh_token_enc = None
    org.twitch_user_id = None
    org.twitch_login = None
    org.twitch_display_name = None
    org.twitch_connected_at = None
    org.twitch_active_match_slug = None
    db.session.commit()
    flash("Twitch disconnected.", "success")
    return redirect(url_for("dashboard"))


@app.post("/api/auth/login")
@auth_limiter.limit_auth(scope="api-login", json_endpoint=True)
def api_stream_login():
    data = request.get_json(silent=True) or {}
    email = (data.get("email") or "").strip().lower()
    password = data.get("password") or ""
    if not email or not password:
        return jsonify({"error": "email and password required"}), 400
    org = Organization.query.filter_by(email=email).first()
    if not org or not org.check_password(password):
        return jsonify({"error": "invalid credentials"}), 401
    return jsonify(
        {
            "ok": True,
            "token": issue_stream_token(org),
            "org_id": org.id,
            "org_name": org.name,
            "youtube_connected": bool((org.youtube_refresh_token_enc or "").strip()),
            "twitch_connected": bool((org.twitch_refresh_token_enc or "").strip()),
        }
    )


@app.post("/api/auth/register")
@auth_limiter.limit_auth(scope="api-register", json_endpoint=True)
def api_stream_register():
    data = request.get_json(silent=True) or {}
    name = (data.get("name") or "").strip()
    email = (data.get("email") or "").strip().lower()
    password = data.get("password") or ""
    play_cricket_base_url = normalize_play_cricket_club_root(data.get("play_cricket_base_url") or "")
    if not name or not email or not password:
        return jsonify({"error": "name, email, and password are required"}), 400
    if not data.get("consent"):
        return jsonify({"error": "You must agree to the Privacy Policy"}), 400
    if len(password) < 8:
        return jsonify({"error": "Password must be at least 8 characters"}), 400
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
        play_cricket_base_url=play_cricket_base_url,
    )
    org.set_password(password)
    org.consent_given_at = datetime.now(timezone.utc)
    db.session.add(org)
    try:
        db.session.commit()
    except IntegrityError:
        db.session.rollback()
        return jsonify({"error": "An account with that email already exists"}), 409
    return jsonify(
        {
            "ok": True,
            "token": issue_stream_token(org),
            "org_id": org.id,
            "org_name": org.name,
        }
    ), 201


def _erase_org_personal_data(org_id: str) -> None:
    """Delete all org-scoped rows in FK-safe order (GDPR Art. 17)."""
    StreamSession.query.filter_by(organization_id=org_id).delete(
        synchronize_session=False
    )
    Sponsor.query.filter_by(organization_id=org_id).delete(synchronize_session=False)
    ClubUser.query.filter_by(organization_id=org_id).delete(synchronize_session=False)
    RelayMatch.query.filter_by(organization_id=org_id).delete(synchronize_session=False)


@app.delete("/api/auth/account")
@stream_api_auth_required
def api_delete_account(org: Organization):
    """GDPR Article 17 — right to erasure. Deletes account and all associated data."""
    yt_refresh = yt.decrypt_token((org.youtube_refresh_token_enc or "").strip())
    if yt_refresh:
        try:
            import requests as _req
            _req.post(
                "https://oauth2.googleapis.com/revoke",
                params={"token": yt_refresh},
                timeout=5,
            )
        except Exception:
            pass

    tw_refresh = tw.decrypt_token((org.twitch_refresh_token_enc or "").strip())
    if tw_refresh:
        try:
            import requests as _req
            _req.post(
                "https://id.twitch.tv/oauth2/revoke",
                data={
                    "client_id": os.getenv("TWITCH_CLIENT_ID", ""),
                    "token": tw_refresh,
                },
                timeout=5,
            )
        except Exception:
            pass

    _erase_org_personal_data(org.id)

    db.session.delete(org)
    try:
        db.session.commit()
    except Exception:
        db.session.rollback()
        return jsonify({"error": "Deletion failed — contact support@cricrelay.co.uk"}), 500

    return "", 204


@app.get("/api/auth/account/export")
@stream_api_auth_required
def api_export_account(org: Organization):
    """GDPR Article 20 — data portability. Returns all personal data as JSON."""
    matches = RelayMatch.query.filter_by(organization_id=org.id).all()
    users = ClubUser.query.filter_by(organization_id=org.id).all()
    sessions = (
        StreamSession.query.filter_by(organization_id=org.id)
        .order_by(StreamSession.started_at.desc())
        .all()
    )
    return jsonify({
        "account": {
            "name": org.name,
            "email": org.email,
            "slug": org.slug,
            "play_cricket_url": org.play_cricket_base_url or "",
            "created_at": org.created_at.isoformat() if org.created_at else None,
            "consent_given_at": org.consent_given_at.isoformat() if org.consent_given_at else None,
            "youtube_connected": bool((org.youtube_refresh_token_enc or "").strip()),
            "twitch_connected": bool((org.twitch_refresh_token_enc or "").strip()),
        },
        "users": [
            {
                "name": u.name,
                "email": u.email,
                "role": u.role,
                "created_at": u.created_at.isoformat() if u.created_at else None,
                "last_login_at": u.last_login_at.isoformat() if u.last_login_at else None,
            }
            for u in users
        ],
        "streams": [
            {
                "slug": m.score_match_slug,
                "label": m.label or "",
                "created_at": m.created_at.isoformat() if m.created_at else None,
            }
            for m in matches
        ],
        "stream_sessions": [
            {
                "match_slug": s.match_slug,
                "match_label": s.match_label or "",
                "platform": s.platform,
                "started_at": s.started_at.isoformat() if s.started_at else None,
                "ended_at": s.ended_at.isoformat() if s.ended_at else None,
                "duration_sec": s.duration_sec,
                "peak_viewers": s.peak_viewers,
                "avg_viewers": s.avg_viewers,
                "vod_url": s.vod_url or s.watch_url or "",
                "status": s.status,
            }
            for s in sessions
        ],
    })


@app.get("/api/streams")
@stream_api_auth_required
def api_list_streams(org: Organization):
    return jsonify({"ok": True, "streams": relay_matches_for_org(org)})


@app.get("/api/stream/app-builds")
@stream_api_auth_required
def api_stream_app_builds(org: Organization):
    """Authenticated app download links (Android APK, iOS OTA) for volunteers after login."""
    return jsonify(_stream_app_builds_payload())


@app.get("/api/fixtures")
@stream_api_auth_required
def api_fixtures(org: Organization):
    ctx = _dashboard_fixture_data(org)
    fixtures = []
    for f in ctx.get("fixtures") or []:
        if not isinstance(f, dict):
            continue
        mid = str(f.get("match_id") or f.get("id") or "").strip()
        if not mid:
            continue
        fixtures.append(
            {
                "match_id": mid,
                "title": (f.get("title") or f.get("label") or "").strip(),
                "url": (f.get("url") or "").strip(),
            }
        )
    return jsonify(
        {
            "ok": True,
            "fixtures": fixtures,
            "active_match_ids": sorted(ctx.get("active_ids") or []),
            "fixture_source_url": ctx.get("fixture_source_url") or "",
            "error": ctx.get("fixtures_error"),
            "slots_used": ctx.get("stream_slots_used"),
            "slots_total": ctx.get("stream_slots_total"),
        }
    )


@app.post("/api/streams")
@stream_api_auth_required
def api_create_stream(org: Organization):
    data = request.get_json(silent=True) or {}
    kind = str(data.get("type") or "play_cricket").strip().lower()
    if kind in {"pcs_ble", "ble"}:
        row, err = _create_pcs_ble_stream_org(org, str(data.get("label") or ""))
    else:
        row, err = _create_play_cricket_stream_org(
            org,
            str(data.get("play_cricket_match_id") or ""),
            str(data.get("label") or ""),
            str(data.get("play_cricket_base_url") or ""),
        )
    if err:
        return jsonify({"error": err}), 400
    slug = row.score_match_slug
    base = _public_base_url().rstrip("/")
    stream = {
        "slug": slug,
        "label": row.label or row.play_cricket_match_id,
        "play_cricket_match_id": row.play_cricket_match_id,
        "relay_source": getattr(row, "relay_source", None) or "scraper",
        "overlay_embed_url": f"{base}/m/{slug}/stream?embed=1" if base else f"/m/{slug}/stream?embed=1",
    }
    return jsonify({"ok": True, "stream": stream})


@app.get("/api/stream/youtube/authorize")
@stream_api_auth_required
def api_youtube_authorize(org: Organization):
    if not yt.oauth_configured():
        return (
            jsonify(
                {
                    "error": (
                        "YouTube OAuth is not configured on this server. "
                        "Set YOUTUBE_CLIENT_ID/YOUTUBE_CLIENT_SECRET "
                        "(or GOOGLE_CLIENT_ID/GOOGLE_CLIENT_SECRET)."
                    )
                }
            ),
            503,
        )
    redirect_uri = _youtube_redirect_uri()
    if not redirect_uri:
        return jsonify({"error": "Set PUBLIC_BASE_URL or YOUTUBE_REDIRECT_URI"}), 503
    state = issue_youtube_oauth_state(org.id)
    return jsonify(
        {
            "ok": True,
            "authorize_url": yt.build_authorize_url(redirect_uri, state),
            "scopes": yt.oauth_scopes(),
            "scope_hint": "Allow manage your YouTube account (includes live streaming).",
        }
    )


@app.get("/api/match/<match_slug>/scoring")
@stream_api_auth_required
def api_get_scoring(org: Organization, match_slug: str):
    slug = sanitize_match_id(match_slug)
    row = relay_match_for_org(org, slug)
    if not row:
        return jsonify({"error": "unknown stream"}), 404
    with match_context(slug):
        merge_missing_state_keys(state)
        relay_mode = (state.get("relay_mode") or "manual").strip().lower()
        pcs_token = (state.get("pcs_ingest_token") or "").strip()
    mode_map = {"play_cricket": "auto", "manual": "manual", "pcs_ble": "ble"}
    app_mode = mode_map.get(relay_mode, "manual")
    base = _public_base_url().rstrip("/")
    pcs_ingest_url = f"{base}/relay/pcs-ingest?match={slug}" if base else f"/relay/pcs-ingest?match={slug}"
    return jsonify(
        {
            "ok": True,
            "mode": app_mode,
            "relay_mode": relay_mode,
            "relay_source": getattr(row, "relay_source", None) or "scraper",
            "manual_input_url": f"{base}/m/{slug}/input" if base else f"/m/{slug}/input",
            "manual_scorer_url": f"{base}/m/{slug}/score" if base else f"/m/{slug}/score",
            "overlay_embed_url": f"{base}/m/{slug}/stream?embed=1" if base else f"/m/{slug}/stream?embed=1",
            "pcs_ingest_url": pcs_ingest_url,
            "pcs_ingest_token": pcs_token,
            "pcs_relay_apk_url": f"{base}/download/pcs-relay.apk" if base else "/download/pcs-relay.apk",
        }
    )


def _overlay_prefs_json(slug: str) -> dict:
    with match_context(slug):
        merge_missing_state_keys(state)
        size = normalize_overlay_size(state.get("overlay_size"), state.get("overlay_scale"))
        theme = _sanitize_overlay_theme(state.get("theme"))
        density = str(state.get("overlay_density") or "expanded").strip().lower()
        if density not in {"compact", "expanded"}:
            density = "expanded"
        return {
            "ok": True,
            "overlay_size": size,
            "overlay_scale": float(state.get("overlay_scale") or 1.0),
            "theme": theme,
            "overlay_density": density,
        }


@app.get("/api/match/<match_slug>/overlay")
@stream_api_auth_required
def api_get_overlay(org: Organization, match_slug: str):
    slug = sanitize_match_id(match_slug)
    if not relay_match_for_org(org, slug):
        return jsonify({"error": "unknown stream"}), 404
    return jsonify(_overlay_prefs_json(slug))


@app.post("/api/match/<match_slug>/overlay")
@stream_api_auth_required
def api_set_overlay(org: Organization, match_slug: str):
    slug = sanitize_match_id(match_slug)
    if not relay_match_for_org(org, slug):
        return jsonify({"error": "unknown stream"}), 404
    data = request.get_json(silent=True) or {}
    with match_context(slug):
        merge_missing_state_keys(state)
        if "overlay_size" in data:
            state["overlay_size"] = normalize_overlay_size(
                data.get("overlay_size"), data.get("overlay_scale")
            )
            state["overlay_scale"] = round(0.8 + (state["overlay_size"] - 1) * 0.25, 2)
        if "theme" in data:
            state["theme"] = _sanitize_overlay_theme(data.get("theme"))
        elif "overlay_theme" in data:
            state["theme"] = _sanitize_overlay_theme(data.get("overlay_theme"))
        if "overlay_density" in data:
            density = str(data.get("overlay_density") or "expanded").strip().lower()
            state["overlay_density"] = density if density in {"compact", "expanded"} else "expanded"
        save_state()
    return jsonify(_overlay_prefs_json(slug))


@app.post("/api/match/<match_slug>/scoring")
@stream_api_auth_required
def api_set_scoring(org: Organization, match_slug: str):
    try:
        slug = sanitize_match_id(match_slug)
        row = relay_match_for_org(org, slug)
        if not row:
            return jsonify({"error": "unknown stream"}), 404
        data = request.get_json(silent=True) or {}
        mode = str(data.get("mode") or "").strip().lower()
        if mode not in {"auto", "manual", "ble"}:
            return jsonify({"error": "mode must be auto, manual, or ble"}), 400
        if mode == "auto":
            apply_relay_to_score_match(slug, row.full_scrape_url)
            row.relay_source = "scraper"
        elif mode == "manual":
            with match_context(slug):
                merge_missing_state_keys(state)
                state["relay_mode"] = "manual"
                state["relay_wrapper"] = None
                state["relay_last_error"] = None
                save_state()
            row.relay_source = "scraper"
        else:
            with match_context(slug):
                merge_missing_state_keys(state)
                token = (state.get("pcs_ingest_token") or "").strip() or secrets.token_urlsafe(24)
            apply_pcs_ble_to_score_match(slug, token, row.label or "")
            row.relay_source = "pcs_ble"
        db.session.commit()
        return api_get_scoring(org, slug)
    except Exception as exc:
        current_app.logger.exception("api_set_scoring failed for %s", match_slug)
        db.session.rollback()
        return jsonify({"error": str(exc) or "scoring update failed"}), 500


@app.get("/api/match/<match_slug>/match-day")
@stream_api_auth_required
def api_match_day(org: Organization, match_slug: str):
    slug = sanitize_match_id(match_slug)
    row = relay_match_for_org(org, slug)
    if not row:
        return jsonify({"error": "unknown stream"}), 404
    return jsonify({"ok": True, **match_day_status(org, row)})


@app.post("/api/match/<match_slug>/broadcast-status")
@stream_api_auth_required
def api_broadcast_status(org: Organization, match_slug: str):
    slug = sanitize_match_id(match_slug)
    if not relay_match_for_org(org, slug):
        return jsonify({"error": "unknown stream"}), 404
    data = request.get_json(silent=True) or {}
    status = str(data.get("status") or "idle").strip().lower()
    if status not in {"idle", "streaming", "paused"}:
        return jsonify({"error": "status must be idle, streaming, or paused"}), 400
    platform = str(data.get("platform") or "").strip().lower() or None
    watch_url = str(data.get("watch_url") or "").strip() or None
    _update_broadcast_status_in_state(slug, status, platform, watch_url)
    return jsonify({"ok": True, "status": status, "platform": platform, "watch_url": watch_url})


@app.post("/api/match/<match_slug>/relay-pause")
@stream_api_auth_required
def api_relay_pause(org: Organization, match_slug: str):
    slug = sanitize_match_id(match_slug)
    data = request.get_json(silent=True) or {}
    if "paused" not in data:
        return jsonify({"error": "paused (boolean) required"}), 400
    row, err = _relay_set_paused(org, slug, bool(data.get("paused")))
    if err:
        return jsonify({"error": err}), 404
    return jsonify({"ok": True, "slug": slug, "paused": bool(row.paused)})


@app.patch("/api/streams/<match_slug>")
@stream_api_auth_required
def api_patch_stream(org: Organization, match_slug: str):
    slug = sanitize_match_id(match_slug)
    row = relay_match_for_org(org, slug)
    if not row:
        return jsonify({"error": "unknown stream"}), 404
    data = request.get_json(silent=True) or {}
    if "label" in data:
        label = str(data.get("label") or "").strip()
        row.label = label or row.play_cricket_match_id
        db.session.commit()
    return jsonify(
        {
            "ok": True,
            "stream": {
                "slug": slug,
                "label": row.label or row.play_cricket_match_id,
            },
        }
    )


@app.delete("/api/streams/<match_slug>")
@stream_api_auth_required
def api_delete_stream(org: Organization, match_slug: str):
    slug = sanitize_match_id(match_slug)
    err = _relay_delete(org, slug)
    if err:
        return jsonify({"error": err}), 404
    return jsonify({"ok": True, "deleted": slug})


@app.get("/api/stream/youtube-status")
@stream_api_auth_required
def api_youtube_status(org: Organization):
    connected = bool((org.youtube_refresh_token_enc or "").strip())
    live_streaming_ok = False
    live_streaming_message = ""
    if connected:
        access = _org_youtube_access_token(org)
        if access:
            check = yt.verify_live_streaming_access(access)
            live_streaming_ok = bool(check.get("ok"))
            live_streaming_message = str(check.get("message") or "")
    return jsonify(
        {
            "ok": True,
            "oauth_configured": yt.oauth_configured(),
            "oauth_scopes": yt.oauth_scopes(),
            "connected": connected,
            "channel_title": org.youtube_channel_title or "",
            "live_streaming_ok": live_streaming_ok,
            "live_streaming_message": live_streaming_message,
            "live_active": bool(org.youtube_active_broadcast_id),
            "active_match_slug": org.youtube_active_match_slug or "",
        }
    )


@app.post("/api/stream/youtube-disconnect")
@stream_api_auth_required
def api_youtube_disconnect(org: Organization):
    org.youtube_refresh_token_enc = None
    org.youtube_channel_id = None
    org.youtube_channel_title = None
    org.youtube_connected_at = None
    org.youtube_active_broadcast_id = None
    org.youtube_active_stream_id = None
    org.youtube_active_match_slug = None
    db.session.commit()
    return jsonify({"ok": True})


@app.get("/api/stream/twitch/authorize")
@stream_api_auth_required
def api_twitch_authorize(org: Organization):
    if not tw.oauth_configured():
        return (
            jsonify(
                {
                    "error": (
                        "Twitch OAuth is not configured on this server. "
                        "Set TWITCH_CLIENT_ID and TWITCH_CLIENT_SECRET."
                    )
                }
            ),
            503,
        )
    redirect_uri = _twitch_redirect_uri()
    if not redirect_uri:
        return jsonify({"error": "Set PUBLIC_BASE_URL or TWITCH_REDIRECT_URI"}), 503
    state = issue_twitch_oauth_state(org.id)
    return jsonify(
        {
            "ok": True,
            "authorize_url": tw.build_authorize_url(redirect_uri, state),
            "scopes": tw.oauth_scopes(),
        }
    )


@app.get("/api/stream/twitch-status")
@stream_api_auth_required
def api_twitch_status(org: Organization):
    connected = bool((org.twitch_refresh_token_enc or "").strip())
    stream_key_ok = False
    stream_key_message = ""
    if connected:
        access = _org_twitch_access_token(org)
        bid = (org.twitch_user_id or "").strip()
        if access and bid:
            check = tw.verify_streaming_access(access, bid)
            stream_key_ok = bool(check.get("ok"))
            stream_key_message = (check.get("message") or "").strip()
    return jsonify(
        {
            "ok": True,
            "oauth_configured": tw.oauth_configured(),
            "oauth_scopes": tw.oauth_scopes(),
            "connected": connected,
            "display_name": org.twitch_display_name or org.twitch_login or "",
            "login": org.twitch_login or "",
            "stream_key_ok": stream_key_ok,
            "stream_key_message": stream_key_message,
            "live_active": bool(org.twitch_active_match_slug),
            "active_match_slug": org.twitch_active_match_slug or "",
        }
    )


@app.post("/api/stream/twitch-disconnect")
@stream_api_auth_required
def api_twitch_disconnect(org: Organization):
    org.twitch_refresh_token_enc = None
    org.twitch_user_id = None
    org.twitch_login = None
    org.twitch_display_name = None
    org.twitch_connected_at = None
    org.twitch_active_match_slug = None
    db.session.commit()
    return jsonify({"ok": True})


@app.post("/api/stream/go-live")
@stream_api_auth_required
def api_stream_go_live(org: Organization):
    data = request.get_json(silent=True) or {}
    slug = sanitize_match_id(data.get("match_slug") or "")
    platform = str(data.get("platform") or "youtube").strip().lower()
    if not slug:
        return jsonify({"error": "match_slug required"}), 400
    if platform not in {"youtube", "twitch"}:
        return jsonify({"error": "platform must be youtube or twitch"}), 400
    row = RelayMatch.query.filter_by(organization_id=org.id, score_match_slug=slug).first()
    if not row:
        return jsonify({"error": "unknown stream"}), 404
    title = (row.label or f"CricRelay {slug}")[:100]
    base = _public_base_url().rstrip("/")
    embed_url = f"{base}/m/{slug}/stream?embed=1" if base else f"/m/{slug}/stream?embed=1"

    if platform == "twitch":
        access = _org_twitch_access_token(org)
        if not access:
            return jsonify({"error": "Twitch not connected for this club"}), 400
        bid = (org.twitch_user_id or "").strip()
        login = (org.twitch_login or "").strip()
        if not bid or not login:
            return jsonify({"error": "Twitch channel info missing — reconnect Twitch"}), 400
        try:
            bundle = tw.go_live_bundle(access, bid, login, title)
        except Exception as exc:
            return jsonify({"error": f"Twitch go-live failed: {exc}"}), 502
        org.twitch_active_match_slug = slug
        org.youtube_active_broadcast_id = None
        org.youtube_active_stream_id = None
        org.youtube_active_match_slug = None
        db.session.commit()
        rtmp_url = bundle["ingestion_address"]
        stream_key = bundle["stream_name"]
        return jsonify(
            {
                "ok": True,
                "platform": "twitch",
                "match_slug": slug,
                "watch_url": bundle["watch_url"],
                "rtmp_url": rtmp_url,
                "stream_key": stream_key,
                "rtmp_full_url": f"{rtmp_url.rstrip('/')}/{stream_key}" if stream_key else rtmp_url,
                "overlay_embed_url": embed_url,
            }
        )

    access = _org_youtube_access_token(org)
    if not access:
        return jsonify({"error": "YouTube not connected for this club"}), 400
    try:
        bundle = yt.go_live_bundle(access, title)
    except Exception as exc:
        return jsonify({"error": f"YouTube go-live failed: {exc}"}), 502
    org.youtube_active_broadcast_id = bundle["broadcast_id"]
    org.youtube_active_stream_id = bundle["stream_id"]
    org.youtube_active_match_slug = slug
    org.twitch_active_match_slug = None
    db.session.commit()
    rtmp_url = bundle["ingestion_address"]
    stream_key = bundle["stream_name"]
    return jsonify(
        {
            "ok": True,
            "platform": "youtube",
            "match_slug": slug,
            "broadcast_id": bundle["broadcast_id"],
            "stream_id": bundle["stream_id"],
            "watch_url": bundle["watch_url"],
            "rtmp_url": rtmp_url,
            "stream_key": stream_key,
            "rtmp_full_url": f"{rtmp_url.rstrip('/')}/{stream_key}" if stream_key else rtmp_url,
            "overlay_embed_url": embed_url,
        }
    )


@app.post("/api/stream/stop")
@stream_api_auth_required
def api_stream_stop(org: Organization):
    data = request.get_json(silent=True) or {}
    platform = str(data.get("platform") or "").strip().lower()

    if platform in {"", "youtube"} and org.youtube_active_broadcast_id:
        access = _org_youtube_access_token(org)
        if access:
            b_id = org.youtube_active_broadcast_id
            s_id = org.youtube_active_stream_id
            if b_id:
                try:
                    yt.stop_live_bundle(access, b_id, s_id)
                except Exception:
                    pass
        org.youtube_active_broadcast_id = None
        org.youtube_active_stream_id = None
        org.youtube_active_match_slug = None

    if platform in {"", "twitch"} or org.twitch_active_match_slug:
        org.twitch_active_match_slug = None

    db.session.commit()
    return jsonify({"ok": True})


@app.get("/api/stream/status")
@stream_api_auth_required
def api_stream_status(org: Organization):
    slug = org.youtube_active_match_slug or ""
    overlay = ""
    if slug:
        base = _public_base_url()
        overlay = f"{base}/m/{slug}/stream?embed=1" if base else f"/m/{slug}/stream?embed=1"
    return jsonify(
        {
            "ok": True,
            "youtube_connected": bool((org.youtube_refresh_token_enc or "").strip()),
            "live_active": bool(org.youtube_active_broadcast_id),
            "active_match_slug": slug,
            "overlay_embed_url": overlay,
            "broadcast_id": org.youtube_active_broadcast_id,
        }
    )


@app.get("/health")
def health():
    with match_context():
        return jsonify(
            {"status": "ok", "innings": state["innings"], "match_started": state["match_started"]}
        )


@app.get("/api/stream/setup")
def api_stream_setup():
    """Public setup check for mobile app (no secrets exposed)."""
    redirect_uri = _youtube_redirect_uri()
    twitch_redirect = _twitch_redirect_uri()
    return jsonify(
        {
            "ok": True,
            "youtube_oauth_configured": yt.oauth_configured(),
            "youtube_redirect_uri": redirect_uri,
            "youtube_oauth_scopes": yt.oauth_scopes(),
            "twitch_oauth_configured": tw.oauth_configured(),
            "twitch_redirect_uri": twitch_redirect,
            "twitch_oauth_scopes": tw.oauth_scopes(),
            "public_base_url": _public_base_url(),
        }
    )


from .relay_poller import start_relay_poller

register_relay_worker(app, apply_relay_ingest_payload)
start_relay_poller(app, apply_relay_ingest_payload, get_live_snapshot)


with app.app_context():
    db.create_all()
    migrate_relay_match_columns()
    migrate_relay_source_column()
    migrate_organization_brand_columns()
    migrate_youtube_columns()
    migrate_twitch_columns()
    migrate_play_cricket_base_url_nullable()
    migrate_consent_given_at_column()

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
