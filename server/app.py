from __future__ import annotations

import copy
import json
import os
import re
import secrets
import smtplib
import threading
import uuid
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
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
    render_template_string,
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
from werkzeug.utils import secure_filename

from .models_cricrelay import (
    ClubUser,
    Fixture,
    Organization,
    Player,
    RelayMatch,
    Sponsor,
    StreamDestination,
    StreamSession,
    Team,
    Tournament,
    build_play_cricket_scrape_url,
    canonicalize_cricheroes_scrape_url,
    canonicalize_play_cricket_scrape_url,
    db,
    normalize_play_cricket_club_root,
    slugify_competition_name,
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
    companion_token_required,
    issue_stream_token,
    manual_scorer_token_required,
    issue_twitch_oauth_state,
    issue_youtube_oauth_state,
    match_day_status,
    org_id_from_twitch_oauth_state,
    org_id_from_youtube_oauth_state,
    relay_match_for_org,
    relay_matches_for_org,
    stream_api_auth_required,
    stream_dict_for_relay_match,
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


@app.after_request
def _security_headers(resp):
    """Safe, additive security headers (no functional impact on existing routes)."""
    resp.headers.setdefault("X-Content-Type-Options", "nosniff")
    resp.headers.setdefault("Referrer-Policy", "strict-origin-when-cross-origin")
    resp.headers.setdefault(
        "Permissions-Policy", "geolocation=(), microphone=(), camera=()"
    )
    if _is_production_https():
        resp.headers.setdefault(
            "Strict-Transport-Security", "max-age=31536000; includeSubDomains"
        )
    return resp


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

PCS_BLE_RETIRED_MSG = (
    "PCS BLE relay was removed from CricRelay. Use Play-Cricket or CricHeroes auto-scoring, "
    "or manual scoring."
)


def pcs_ble_retired_response():
    return jsonify({"error": PCS_BLE_RETIRED_MSG, "code": "pcs_ble_retired"}), 410
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
# The public live page uses SSE only when this is on — otherwise it polls. An SSE
# stream holds a gunicorn worker for its lifetime, so keep this OFF until the
# prod workers are threaded (or the Redis pub/sub pusher is in front).
PUBLIC_LIVE_SSE = (os.getenv("PUBLIC_LIVE_SSE", "") or "").strip().lower() in {
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
        # QR scorer-page totals (manual streams): full payload from the scorer phone,
        # guarded by a monotonic seq. None until the scorer completes setup.
        "manual_totals": None,
        "pcs_ingest_token": "",
        "pcs_ble_state": None,
        "sponsor_enabled": False,
        "active_sponsor_id": None,
        "active_sponsor_ids": [],
        "sponsor_layout_mode": "single",
        "sponsor_carousel_interval_sec": 6.0,
        "sponsor_display_mode": "static",
        "sponsor_position_x": 0.92,
        "sponsor_position_y": 0.88,
        "sponsor_size_scale": 1.0,
        "sponsor_opacity": 1.0,
        "sponsor_scroll_speed": 1.0,
        "overlay_height_fraction": 0.16,
        "overlay_width_fraction": 1.0,
        "overlay_anchor_x": 0.5,
        "overlay_anchor_y": 0.85,
        "overlay_bottom_margin": 8.0,
        "overlay_horizontal_inset": 0.0,
        "overlay_font_scale": 1.0,
        "overlay_bg_color": "",
        "overlay_text_color": "",
        "overlay_opacity": 1.0,
        # Floodlight bowling island — default ON; merge_missing_state_keys back-fills
        # True onto pre-existing states (unlike stabilization_level, that's the intent:
        # the island is new, so nobody has ever turned it off).
        "bowling_island_enabled": True,
        "video_stabilization": True,
        "keep_screen_on": True,
        "watermark_enabled": True,
        "watermark_text": "Visit cricrelay.co.uk",
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


def purge_legacy_pcs_ble_relay_rows() -> int:
    """Remove retired PCS BLE RelayMatch rows and their on-disk state files."""
    rows = RelayMatch.query.filter_by(relay_source="pcs_ble").all()
    if not rows:
        return 0
    removed = 0
    for row in rows:
        slug = (row.score_match_slug or "").strip()
        db.session.delete(row)
        removed += 1
        if slug:
            path = state_path_for(slug)
            if path.is_file():
                try:
                    path.unlink()
                except OSError:
                    pass
    db.session.commit()
    if removed:
        print(f"[startup] purged {removed} legacy PCS BLE relay row(s)", flush=True)
    return removed


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


MAX_STREAM_DESTINATIONS_PER_ORG = 20


def migrate_stream_destination_columns():
    """Add stream_destination_id on RelayMatch for databases created before destinations vault."""
    insp = inspect(db.engine)
    if not insp.has_table("cricrelay_match"):
        return
    cols = {c["name"] for c in insp.get_columns("cricrelay_match")}
    if "stream_destination_id" in cols:
        return
    db.session.execute(
        text("ALTER TABLE cricrelay_match ADD COLUMN stream_destination_id VARCHAR(36)")
    )
    try:
        db.session.execute(
            text(
                "CREATE INDEX IF NOT EXISTS ix_cricrelay_match_stream_destination_id "
                "ON cricrelay_match (stream_destination_id)"
            )
        )
    except Exception:
        pass
    db.session.commit()


def _mask_stream_key(plain: str) -> str:
    key = (plain or "").strip()
    if len(key) <= 4:
        return "••••"
    return f"••••{key[-4:]}"


def _destination_public_dict(dest: StreamDestination, *, include_key: bool = False) -> dict:
    plain = yt.decrypt_token((dest.stream_key_enc or "").strip()) if include_key else ""
    out = {
        "id": dest.id,
        "label": dest.label or "",
        "provider": dest.provider or "custom_rtmp",
        "rtmp_url": dest.rtmp_url or "",
        "watch_url": dest.watch_url or "",
        "stream_key_masked": _mask_stream_key(
            yt.decrypt_token((dest.stream_key_enc or "").strip())
        ),
    }
    if include_key:
        out["stream_key"] = plain
    return out


def _destination_summary(dest: StreamDestination | None) -> dict | None:
    if not dest:
        return None
    return {"id": dest.id, "label": dest.label or ""}


def _org_destinations(org: Organization) -> list[StreamDestination]:
    return (
        StreamDestination.query.filter_by(organization_id=org.id)
        .order_by(StreamDestination.created_at.asc())
        .all()
    )


def _destination_for_org(org: Organization, dest_id: str) -> StreamDestination | None:
    did = (dest_id or "").strip()
    if not did:
        return None
    return StreamDestination.query.filter_by(id=did, organization_id=org.id).first()


def _validate_destination_payload(
    data: dict, *, require_key: bool
) -> tuple[str, str, str, str, str | None]:
    label = str(data.get("label") or "").strip()[:120]
    rtmp_url = str(data.get("rtmp_url") or "").strip()[:500]
    stream_key = str(data.get("stream_key") or "").strip()
    watch_url = str(data.get("watch_url") or "").strip()[:500]
    if not label:
        return "", "", "", "", "Label is required"
    if not rtmp_url:
        return "", "", "", "", "RTMP server URL is required"
    if not rtmp_url.lower().startswith(("rtmp://", "rtmps://")):
        return "", "", "", "", "RTMP URL must start with rtmp:// or rtmps://"
    if require_key and not stream_key:
        return "", "", "", "", "Stream key is required"
    return label, rtmp_url, stream_key, watch_url, None


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
    except Exception:
        return None
    # Twitch rotates refresh tokens: each refresh response carries a new refresh_token and
    # the old one can stop working once the new one exists. Not persisting the rotation is
    # how a club that connected Twitch fine gets "Twitch not connected" at go-live minutes
    # later (the destination sheet's status poll burns the stored token first).
    new_refresh = (data.get("refresh_token") or "").strip()
    if new_refresh and new_refresh != refresh:
        new_enc = tw.encrypt_token(new_refresh)
        if new_enc:
            org.twitch_refresh_token_enc = new_enc
            db.session.commit()
    return data.get("access_token")


def _safe_hex_color(value: str, default: str) -> str:
    raw = (value or "").strip()
    if re.fullmatch(r"#[0-9a-fA-F]{6}", raw):
        return raw.lower()
    return default


def _org_play_cricket_root(org: Organization) -> str:
    raw = (org.play_cricket_base_url or "").strip()
    return normalize_play_cricket_club_root(raw) or raw.rstrip("/")


def _sibling_play_cricket_root(club_name: str, exclude_org_id: str | None = None) -> str:
    """Play-Cricket site used by other accounts registered under the same club name.

    Second and third volunteers sign up with their own email (the mobile app never
    asks for the club code), leaving their org without a base URL and therefore an
    empty fixture list. Match them to the club by normalized name, and only trust
    the result when every same-name account points at a single Play-Cricket site.
    """
    name_slug = slugify_org_name(club_name)
    if not name_slug or name_slug == "club":
        return ""
    candidates = Organization.query.filter(
        Organization.play_cricket_base_url.isnot(None),
        Organization.play_cricket_base_url != "",
    ).all()
    roots: set[str] = set()
    for other in candidates:
        if exclude_org_id and other.id == exclude_org_id:
            continue
        if slugify_org_name(other.name) != name_slug:
            continue
        root = normalize_play_cricket_club_root(other.play_cricket_base_url or "")
        if root:
            roots.add(root)
    return roots.pop() if len(roots) == 1 else ""


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
    if not roots:
        sibling = _sibling_play_cricket_root(org.name, exclude_org_id=org.id)
        if sibling:
            # Self-heal accounts created without a club code (e.g. via the mobile
            # app): persist so match-id stream creation and later loads work too.
            org.play_cricket_base_url = sibling
            db.session.commit()
            add(sibling)
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


def apply_relay_to_score_match(match_slug: str, full_url: str, provider: str = "play_cricket"):
    from .models_cricrelay import RELAY_PROVIDERS, resolve_provider_callable

    prov = (provider or "play_cricket").strip().lower()
    if prov not in RELAY_PROVIDERS:
        prov = "play_cricket"
    cfg = RELAY_PROVIDERS[prov]
    canonicalize = resolve_provider_callable(cfg["canonicalize_fn"])
    url = canonicalize((full_url or "").strip())
    with match_context(match_slug):
        merge_missing_state_keys(state)
        state["relay_mode"] = prov
        state["relay_provider"] = prov
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


def apply_manual_to_score_match(match_slug: str):
    with match_context(match_slug):
        merge_missing_state_keys(state)
        state["relay_mode"] = "manual"
        state["relay_play_cricket_url"] = ""
        state["relay_wrapper"] = None
        state["relay_last_error"] = None
        state["manual_totals"] = None
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
    if mode == "manual":
        # QR-scored streams (relay_source="manual") own their state via the scorer
        # page; the legacy unauthenticated endpoints must not clobber it.
        try:
            row = RelayMatch.query.filter_by(score_match_slug=current_match_id).first()
        except Exception:
            row = None
        if row is not None and (row.relay_source or "") == "manual":
            return (
                jsonify({"error": "This stream is scored from the QR scorer page."}),
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


# "barlow" = legacy board; the rest are Floodlight-board presets (SPEC P1–P5).
# Unknown ids sanitize to "barlow" so old clients degrade gracefully.
VALID_OVERLAY_THEMES = {"barlow", "floodlight", "chalk", "club-green", "broadcast-blue", "mono"}

def _sanitize_overlay_theme(raw) -> str:
    t = str(raw or "barlow").strip().lower()
    return t if t in VALID_OVERLAY_THEMES else "barlow"

def read_relay_overlay_prefs(slug):
    safe = sanitize_match_id(slug)
    path = state_path_for(safe)
    if not path.exists():
        return {"overlay_size": 3, "overlay_scale": 1.0, "theme": "barlow"}
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
        return {"overlay_size": 3, "overlay_scale": 1.0, "theme": "barlow"}


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


@app.get("/live/<match_id>")
def public_live_score(match_id):
    """Public, no-login, shareable live-score page — works for relay and native matches."""
    slug = sanitize_match_id(match_id)
    if slug != match_id:
        return redirect(url_for("public_live_score", match_id=slug), code=301)
    with match_context(slug):
        snapshot = with_calculated_values(state)
    label = slug
    row = RelayMatch.query.filter_by(score_match_slug=slug).first()
    if row and row.label:
        label = row.label
    if snapshot.get("match_started") and snapshot.get("batting_team"):
        team1 = snapshot.get("team1") or snapshot.get("batting_team")
        team2 = snapshot.get("team2") or snapshot.get("bowling_team")
        share_title = f"{team1} vs {team2}".strip(" vs")
        share_desc = (
            f"{snapshot.get('batting_team')} {snapshot.get('runs', 0)}/"
            f"{snapshot.get('wickets', 0)} ({snapshot.get('overs_display', '0.0')}) — live"
        )
    else:
        share_title = f"{label} — live score"
        share_desc = "Follow the live cricket score on CricRelay."
    share_url = url_for("public_live_score", match_id=slug, _external=True)
    return render_template(
        "public_live_score.html",
        match_id=slug,
        match_label=label,
        snapshot=snapshot,
        share_title=share_title,
        share_desc=share_desc,
        share_url=share_url,
        enable_sse=PUBLIC_LIVE_SSE,
    )


def _live_snapshot(slug):
    """Read-only current scoreboard for a match — no persist, no torn reads."""
    with state_lock:
        if current_match_id == slug:
            data = copy.deepcopy(state)
        else:
            path = state_path_for(slug)
            if path.exists():
                with path.open(encoding="utf-8") as fh:
                    data = merge_missing_state_keys(json.load(fh))
            else:
                data = blank_state()
    return with_calculated_values(data)


@app.get("/live/<match_id>/events")
def public_live_events(match_id):
    """Server-Sent Events stream of the live score — pushes within ~1s of a
    change over a single connection; the page falls back to polling if SSE is
    unavailable. NOTE: holds a worker for the connection's lifetime, so prod
    needs threaded workers or the dedicated Redis-pub/sub pusher. The connection
    self-recycles after 5 minutes (the client reconnects automatically)."""
    if not PUBLIC_LIVE_SSE:
        abort(404)
    slug = sanitize_match_id(match_id)

    def stream():
        import time as _t

        last = None
        deadline = _t.time() + 300
        while _t.time() < deadline:
            payload = json.dumps(_live_snapshot(slug), separators=(",", ":"))
            if payload != last:
                last = payload
                yield f"data: {payload}\n\n"
            else:
                yield ": keep-alive\n\n"
            _t.sleep(1)

    return Response(
        stream(),
        mimetype="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
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
    fixture_roots = _org_fixture_roots(org, matches)
    for root in fixture_roots:
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
    if not fixture_roots:
        fixtures_error = (
            "No Play-Cricket club site is linked to this account yet. Add your club code "
            "(the short name before .play-cricket.com) to load fixtures."
        )
    if not fixture_source_url:
        fixture_source_url = _org_play_cricket_root(org)
    relay_poll_sec = max(5, int(os.getenv("RELAY_POLL_INTERVAL_SEC", "10")))
    relay_auto_poll = (os.getenv("RELAY_AUTO_POLL", "1") or "1").strip().lower() not in {
        "0", "false", "no", "off",
    }
    base = _public_base_url()
    relay_rows = []
    for m in matches:
        slug = m.score_match_slug
        overlay_url = f"{base}/m/{slug}/stream" if base else f"/m/{slug}/stream"
        embed_url = f"{overlay_url}?embed=1" if "?" not in overlay_url else f"{overlay_url}&embed=1"
        relay_rows.append(
            {
                "match": m,
                "overlay_prefs": read_relay_overlay_prefs(slug),
                "relay_source": (getattr(m, "relay_source", None) or "scraper"),
                "overlay_url": overlay_url,
                "overlay_embed_url": embed_url,
            }
        )
    youtube_connected = bool((org.youtube_refresh_token_enc or "").strip())
    destinations = _org_destinations(org)
    dest_by_id = {d.id: d for d in destinations}
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
        "sponsors": Sponsor.query.filter_by(organization_id=org.id)
        .order_by(Sponsor.created_at.desc())
        .all(),
        "stream_destinations": destinations,
        "stream_destination_dicts": [_destination_public_dict(d) for d in destinations],
        "destination_by_id": dest_by_id,
        "destination_slots_total": MAX_STREAM_DESTINATIONS_PER_ORG,
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
    """Dormant PCS BLE APK route — retained for backward compatibility only."""
    flash("PCS BLE relay is no longer distributed. Use Play-Cricket or CricHeroes auto-scoring.", "error")
    return redirect(url_for("dashboard"))


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


def _create_cricheroes_stream_org(
    org: Organization,
    match_url: str,
    label: str = "",
) -> tuple[RelayMatch | None, str | None]:
    slot_err = _stream_slot_error(org)
    if slot_err:
        return None, slot_err
    from .models_cricrelay import canonicalize_cricheroes_scrape_url

    full_url = canonicalize_cricheroes_scrape_url((match_url or "").strip())
    if not full_url:
        return None, (
            "Paste a CricHeroes scorecard URL "
            "(e.g. https://cricheroes.com/scorecard/<match-id>/.../live)."
        )
    mid_match = re.search(r"/scorecard/(\d+)", full_url)
    mid = mid_match.group(1) if mid_match else f"ch-{secrets.token_hex(4)}"
    existing = RelayMatch.query.filter_by(
        organization_id=org.id, play_cricket_match_id=mid, relay_source="cricheroes"
    ).first()
    if existing:
        return None, "This CricHeroes match is already linked for your club."
    base_slug = sanitize_match_id(f"{org.slug}-ch-{mid}")
    score_slug = base_slug
    for _ in range(16):
        taken = RelayMatch.query.filter_by(score_match_slug=score_slug).first()
        if not taken:
            break
        score_slug = sanitize_match_id(f"{org.slug}-ch-{mid}-{secrets.token_hex(3)}")
    stream_label = (label or "").strip()[:120]
    row = RelayMatch(
        organization_id=org.id,
        play_cricket_match_id=mid,
        full_scrape_url=full_url,
        score_match_slug=score_slug,
        label=(stream_label or None),
        paused=False,
        relay_source="cricheroes",
    )
    db.session.add(row)
    try:
        db.session.commit()
    except IntegrityError:
        db.session.rollback()
        return None, "Could not create that stream (duplicate or conflict)."
    apply_relay_to_score_match(score_slug, full_url, provider="cricheroes")
    return row, None


def _create_manual_stream_org(org: Organization, label: str = "") -> tuple[RelayMatch | None, str | None]:
    slot_err = _stream_slot_error(org)
    if slot_err:
        return None, slot_err
    stream_label = (label or "").strip()[:120] or "Manual scoring"
    mid = f"man-{secrets.token_hex(4)}"
    base_slug = sanitize_match_id(f"{org.slug}-{mid}")
    score_slug = base_slug
    for _ in range(16):
        if not RelayMatch.query.filter_by(score_match_slug=score_slug).first():
            break
        score_slug = sanitize_match_id(f"{org.slug}-{mid}-{secrets.token_hex(2)}")
    row = RelayMatch(
        organization_id=org.id,
        play_cricket_match_id=mid,
        full_scrape_url="",
        score_match_slug=score_slug,
        label=stream_label,
        paused=False,
        relay_source="manual",
    )
    db.session.add(row)
    try:
        db.session.commit()
    except IntegrityError:
        db.session.rollback()
        return None, "Could not create that stream (duplicate or conflict)."
    apply_manual_to_score_match(score_slug)
    return row, None


@app.post("/dashboard/matches")
@login_required
def dashboard_add_match():
    org = _org_from_session()
    relay_source = (request.form.get("relay_source") or "scraper").strip().lower()
    if relay_source not in {"scraper", "cricheroes"}:
        relay_source = "scraper"
    if relay_source == "cricheroes":
        row, err = _create_cricheroes_stream_org(
            org,
            request.form.get("cricheroes_url") or "",
            request.form.get("stream_label") or "",
        )
        if err:
            flash(err, "error")
            return redirect(url_for("dashboard"))
        flash("CricHeroes stream ready (R&D) — copy your overlay URL into Prism or OBS.", "success")
        return redirect(url_for("dashboard"))
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


@app.post("/dashboard/play-cricket")
@login_required
def dashboard_set_play_cricket():
    """Link the club's Play-Cricket site to an account that registered without one."""
    org = _org_from_session()
    base = normalize_play_cricket_club_root(request.form.get("play_cricket_base_url") or "")
    if not base:
        flash(
            "That Play-Cricket club code was not recognised — enter the short name before "
            ".play-cricket.com, like bmacc for https://bmacc.play-cricket.com.",
            "error",
        )
        return redirect(url_for("dashboard"))
    org.play_cricket_base_url = base
    db.session.commit()
    flash("Play-Cricket club site linked — your fixtures now load from it.", "success")
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
    elif src == "cricheroes":
        flash("Stream live — CricHeroes sync is on (when enabled on server).", "success")
    else:
        flash("Stream live — Play-Cricket sync is on.", "success")
    return redirect(url_for("dashboard"))


@app.post("/dashboard/destinations/add")
@login_required
def dashboard_destination_add():
    org = _org_from_session()
    if StreamDestination.query.filter_by(organization_id=org.id).count() >= MAX_STREAM_DESTINATIONS_PER_ORG:
        flash(f"Maximum {MAX_STREAM_DESTINATIONS_PER_ORG} destinations per club.", "error")
        return redirect(url_for("dashboard"))
    data = {
        "label": request.form.get("label", ""),
        "rtmp_url": request.form.get("rtmp_url", ""),
        "stream_key": request.form.get("stream_key", ""),
        "watch_url": request.form.get("watch_url", ""),
    }
    label, rtmp_url, stream_key, watch_url, err = _validate_destination_payload(data, require_key=True)
    if err:
        flash(err, "error")
        return redirect(url_for("dashboard"))
    enc = yt.encrypt_token(stream_key)
    if not enc:
        flash("Could not encrypt stream key — check server encryption config.", "error")
        return redirect(url_for("dashboard"))
    dest = StreamDestination(
        organization_id=org.id,
        label=label,
        provider="custom_rtmp",
        rtmp_url=rtmp_url,
        stream_key_enc=enc,
        watch_url=watch_url or None,
    )
    db.session.add(dest)
    db.session.commit()
    flash(f"Saved destination “{label}”.", "success")
    return redirect(url_for("dashboard"))


@app.post("/dashboard/destinations/delete")
@login_required
def dashboard_destination_delete():
    org = _org_from_session()
    dest = _destination_for_org(org, request.form.get("destination_id", ""))
    if not dest:
        flash("Unknown destination.", "error")
        return redirect(url_for("dashboard"))
    label = dest.label or "destination"
    RelayMatch.query.filter_by(organization_id=org.id, stream_destination_id=dest.id).update(
        {"stream_destination_id": None}
    )
    db.session.delete(dest)
    db.session.commit()
    flash(f"Removed “{label}”.", "success")
    return redirect(url_for("dashboard"))


@app.post("/dashboard/relay/assign-destination")
@login_required
def dashboard_relay_assign_destination():
    org = _org_from_session()
    slug = sanitize_match_id(request.form.get("score_match_slug", ""))
    if not slug:
        flash("Missing stream.", "error")
        return redirect(url_for("dashboard"))
    row = RelayMatch.query.filter_by(organization_id=org.id, score_match_slug=slug).first()
    if not row:
        flash("Unknown stream.", "error")
        return redirect(url_for("dashboard"))
    raw = (request.form.get("stream_destination_id") or "").strip()
    if not raw:
        row.stream_destination_id = None
        db.session.commit()
        flash("Cleared assigned destination — pick in the app.", "success")
        return redirect(url_for("dashboard"))
    dest = _destination_for_org(org, raw)
    if not dest:
        flash("Unknown destination.", "error")
        return redirect(url_for("dashboard"))
    row.stream_destination_id = dest.id
    db.session.commit()
    flash(f"Assigned “{dest.label}” to this stream.", "success")
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


SPONSOR_LOGO_MAX_BYTES = 2 * 1024 * 1024
SPONSOR_LOGO_EXTS = {".png", ".jpg", ".jpeg", ".webp", ".gif"}


def _sponsor_static_root() -> Path:
    return Path(app.static_folder or "../static") / "sponsors"


def _sponsor_logo_public_url(org_id: str, filename: str) -> str:
    base = _public_base_url().rstrip("/")
    return f"{base}/static/sponsors/{org_id}/{filename}"


def _save_sponsor_logo_upload(org: Organization, file_storage) -> str:
    """Persist an uploaded logo under static/sponsors/<org_id>/ and return its public URL."""
    if not file_storage or not file_storage.filename:
        raise ValueError("Choose a logo image to upload.")
    raw_name = secure_filename(file_storage.filename)
    ext = Path(raw_name).suffix.lower()
    if ext not in SPONSOR_LOGO_EXTS:
        raise ValueError("Logo must be PNG, JPG, WEBP, or GIF.")
    file_storage.stream.seek(0, os.SEEK_END)
    size = file_storage.stream.tell()
    file_storage.stream.seek(0)
    if size <= 0:
        raise ValueError("Logo file is empty.")
    if size > SPONSOR_LOGO_MAX_BYTES:
        raise ValueError("Logo must be 2 MB or smaller.")
    dest_dir = _sponsor_static_root() / org.id
    dest_dir.mkdir(parents=True, exist_ok=True)
    filename = f"{uuid.uuid4().hex}{ext}"
    dest = dest_dir / filename
    file_storage.save(dest)
    return _sponsor_logo_public_url(org.id, filename)


def _try_delete_sponsor_logo_file(logo_url: str | None, org_id: str) -> None:
    if not logo_url:
        return
    marker = f"/static/sponsors/{org_id}/"
    if marker not in logo_url:
        return
    filename = logo_url.split(marker, 1)[-1].split("?")[0]
    path = _sponsor_static_root() / org_id / filename
    if path.is_file():
        try:
            path.unlink()
        except OSError:
            pass


@app.post("/dashboard/sponsors/add")
@login_required
def dashboard_sponsors_add():
    org = _org_from_session()
    name = str(request.form.get("sponsor_name") or "").strip()
    link_url = str(request.form.get("link_url") or "").strip() or None
    logo = request.files.get("logo")
    if not name:
        flash("Sponsor name is required.", "error")
        return redirect(url_for("dashboard"))
    try:
        logo_url = _save_sponsor_logo_upload(org, logo)
    except ValueError as exc:
        flash(str(exc), "error")
        return redirect(url_for("dashboard"))
    except Exception:
        flash("Could not save logo — try again.", "error")
        return redirect(url_for("dashboard"))
    s = Sponsor(
        organization_id=org.id,
        name=name,
        logo_url=logo_url,
        link_url=link_url,
        is_active=True,
    )
    db.session.add(s)
    db.session.commit()
    flash(f"Sponsor “{name}” added — pick it in the Stream app under Style → Sponsor logo.", "success")
    return redirect(url_for("dashboard"))


@app.post("/dashboard/sponsors/delete")
@login_required
def dashboard_sponsors_delete():
    org = _org_from_session()
    sponsor_id = str(request.form.get("sponsor_id") or "").strip()
    s = Sponsor.query.filter_by(id=sponsor_id, organization_id=org.id).first()
    if not s:
        flash("Unknown sponsor.", "error")
        return redirect(url_for("dashboard"))
    logo_url = s.logo_url
    db.session.delete(s)
    db.session.commit()
    _try_delete_sponsor_logo_file(logo_url, org.id)
    flash("Sponsor removed.", "success")
    return redirect(url_for("dashboard"))


# --- Competition module (native mode): tournaments, teams, players, fixtures ---
# Additive feature. All mutating routes are @login_required and scoped to the
# caller's org; the only public surface is the read-only GET /t/<slug>.


def _tournament_for_org(org: Organization, tournament_id: str) -> Tournament | None:
    if not org or not tournament_id:
        return None
    return Tournament.query.filter_by(id=tournament_id, organization_id=org.id).first()


def _team_in_tournament(tournament: Tournament, team_id: str) -> Team | None:
    if not tournament or not team_id:
        return None
    return Team.query.filter_by(id=team_id, tournament_id=tournament.id).first()


def _unique_tournament_slug(name: str) -> str:
    base = slugify_competition_name(name)
    slug = base
    for _ in range(24):
        if not Tournament.query.filter_by(slug=slug).first():
            return slug
        slug = f"{base}-{secrets.token_hex(2)}"
    return f"{base}-{secrets.token_hex(4)}"


def _parse_date_opt(raw: str):
    raw = (raw or "").strip()
    if not raw:
        return None
    try:
        return datetime.strptime(raw[:10], "%Y-%m-%d").date()
    except ValueError:
        return None


def _parse_datetime_opt(raw: str):
    raw = (raw or "").strip()
    if not raw:
        return None
    for fmt in ("%Y-%m-%dT%H:%M", "%Y-%m-%d %H:%M", "%Y-%m-%d"):
        try:
            return datetime.strptime(raw, fmt)
        except ValueError:
            continue
    return None


def compute_points_table(teams: list[Team], fixtures: list[Fixture]) -> list[dict]:
    """Standings + Net Run Rate, computed only from completed fixtures.

    Points: win = 2, tie/no-result = 1, loss = 0. NRR = runs scored per over
    faced minus runs conceded per over bowled (overs = balls / 6).
    """
    rows: dict[str, dict] = {}
    for t in teams:
        rows[t.id] = {
            "team": t,
            "played": 0,
            "won": 0,
            "lost": 0,
            "drawn": 0,
            "points": 0,
            "_rf": 0, "_bf": 0, "_ra": 0, "_ba": 0,
        }

    for fx in fixtures:
        if fx.status != "completed" or not fx.result_json:
            continue
        try:
            data = json.loads(fx.result_json)
        except (ValueError, TypeError):
            continue
        home, away = rows.get(fx.home_team_id), rows.get(fx.away_team_id)
        if not home or not away:
            continue
        h = data.get("home") or {}
        a = data.get("away") or {}
        hr, hb = int(h.get("runs", 0)), int(h.get("balls", 0))
        ar, ab = int(a.get("runs", 0)), int(a.get("balls", 0))

        home["played"] += 1
        away["played"] += 1
        home["_rf"] += hr; home["_bf"] += hb; home["_ra"] += ar; home["_ba"] += ab
        away["_rf"] += ar; away["_bf"] += ab; away["_ra"] += hr; away["_ba"] += hb

        outcome = (data.get("outcome") or "").strip().lower()
        if outcome == "home":
            home["won"] += 1; away["lost"] += 1
        elif outcome == "away":
            away["won"] += 1; home["lost"] += 1
        else:  # tie or no-result
            home["drawn"] += 1; away["drawn"] += 1

    out = []
    for r in rows.values():
        r["points"] = r["won"] * 2 + r["drawn"]
        rf_rate = (r["_rf"] / (r["_bf"] / 6)) if r["_bf"] else 0.0
        ra_rate = (r["_ra"] / (r["_ba"] / 6)) if r["_ba"] else 0.0
        r["nrr"] = round(rf_rate - ra_rate, 3)
        out.append(r)
    out.sort(key=lambda r: (-r["points"], -r["nrr"], r["team"].name.lower()))
    return out


def _tournament_view(org: Organization, tournament: Tournament) -> dict:
    teams = Team.query.filter_by(tournament_id=tournament.id).order_by(Team.name).all()
    fixtures = Fixture.query.filter_by(tournament_id=tournament.id).all()
    players_by_team: dict[str, list] = {t.id: [] for t in teams}
    if teams:
        for p in Player.query.filter(
            Player.team_id.in_([t.id for t in teams])
        ).order_by(Player.name).all():
            players_by_team.setdefault(p.team_id, []).append(p)
    team_names = {t.id: t for t in teams}
    fixtures_sorted = sorted(
        fixtures, key=lambda f: (f.scheduled_at or datetime.max, f.created_at or datetime.max)
    )
    return {
        "tournament": tournament,
        "teams": teams,
        "players_by_team": players_by_team,
        "team_names": team_names,
        "fixtures": fixtures_sorted,
        "points_table": compute_points_table(teams, fixtures),
    }


@app.get("/dashboard/tournaments")
@login_required
def dashboard_tournaments():
    org = _org_from_session()
    tournaments = (
        Tournament.query.filter_by(organization_id=org.id)
        .order_by(Tournament.created_at.desc())
        .all()
    )
    return render_template(
        "dashboard_tournaments.html",
        org=org,
        tournaments=tournaments,
        view=None,
    )


@app.get("/dashboard/tournaments/<tournament_id>")
@login_required
def dashboard_tournament_manage(tournament_id):
    org = _org_from_session()
    tournament = _tournament_for_org(org, tournament_id)
    if not tournament:
        flash("Unknown tournament for your club.", "error")
        return redirect(url_for("dashboard_tournaments"))
    tournaments = (
        Tournament.query.filter_by(organization_id=org.id)
        .order_by(Tournament.created_at.desc())
        .all()
    )
    return render_template(
        "dashboard_tournaments.html",
        org=org,
        tournaments=tournaments,
        view=_tournament_view(org, tournament),
    )


@app.post("/dashboard/tournaments")
@login_required
def dashboard_create_tournament():
    org = _org_from_session()
    name = (request.form.get("name") or "").strip()[:200]
    if not name:
        flash("Tournament name is required.", "error")
        return redirect(url_for("dashboard_tournaments"))
    fmt = (request.form.get("format") or "T20").strip()[:24] or "T20"
    try:
        overs = max(1, min(int(request.form.get("overs") or 20), 200))
    except (ValueError, TypeError):
        overs = 20
    tournament = Tournament(
        organization_id=org.id,
        name=name,
        slug=_unique_tournament_slug(name),
        format=fmt,
        overs=overs,
        starts_on=_parse_date_opt(request.form.get("starts_on")),
    )
    db.session.add(tournament)
    try:
        db.session.commit()
    except IntegrityError:
        db.session.rollback()
        flash("Could not create that tournament (conflict).", "error")
        return redirect(url_for("dashboard_tournaments"))
    flash("Tournament created — add teams next.", "success")
    return redirect(url_for("dashboard_tournament_manage", tournament_id=tournament.id))


@app.post("/dashboard/tournaments/<tournament_id>/teams")
@login_required
def dashboard_add_team(tournament_id):
    org = _org_from_session()
    tournament = _tournament_for_org(org, tournament_id)
    if not tournament:
        flash("Unknown tournament for your club.", "error")
        return redirect(url_for("dashboard_tournaments"))
    name = (request.form.get("name") or "").strip()[:120]
    if not name:
        flash("Team name is required.", "error")
        return redirect(url_for("dashboard_tournament_manage", tournament_id=tournament.id))
    short = (request.form.get("short_name") or "").strip()[:8] or None
    db.session.add(Team(tournament_id=tournament.id, name=name, short_name=short))
    db.session.commit()
    flash(f"Team “{name}” added.", "success")
    return redirect(url_for("dashboard_tournament_manage", tournament_id=tournament.id))


@app.post("/dashboard/tournaments/<tournament_id>/players")
@login_required
def dashboard_add_player(tournament_id):
    org = _org_from_session()
    tournament = _tournament_for_org(org, tournament_id)
    if not tournament:
        flash("Unknown tournament for your club.", "error")
        return redirect(url_for("dashboard_tournaments"))
    team = _team_in_tournament(tournament, (request.form.get("team_id") or "").strip())
    if not team:
        flash("Pick a team in this tournament first.", "error")
        return redirect(url_for("dashboard_tournament_manage", tournament_id=tournament.id))
    name = (request.form.get("name") or "").strip()[:120]
    if not name:
        flash("Player name is required.", "error")
        return redirect(url_for("dashboard_tournament_manage", tournament_id=tournament.id))
    db.session.add(Player(team_id=team.id, name=name))
    db.session.commit()
    flash(f"Player “{name}” added to {team.name}.", "success")
    return redirect(url_for("dashboard_tournament_manage", tournament_id=tournament.id))


@app.post("/dashboard/tournaments/<tournament_id>/fixtures")
@login_required
def dashboard_add_fixture(tournament_id):
    org = _org_from_session()
    tournament = _tournament_for_org(org, tournament_id)
    if not tournament:
        flash("Unknown tournament for your club.", "error")
        return redirect(url_for("dashboard_tournaments"))
    home = _team_in_tournament(tournament, (request.form.get("home_team_id") or "").strip())
    away = _team_in_tournament(tournament, (request.form.get("away_team_id") or "").strip())
    if not home or not away or home.id == away.id:
        flash("Pick two different teams for the fixture.", "error")
        return redirect(url_for("dashboard_tournament_manage", tournament_id=tournament.id))
    db.session.add(
        Fixture(
            tournament_id=tournament.id,
            home_team_id=home.id,
            away_team_id=away.id,
            scheduled_at=_parse_datetime_opt(request.form.get("scheduled_at")),
        )
    )
    db.session.commit()
    flash("Fixture added.", "success")
    return redirect(url_for("dashboard_tournament_manage", tournament_id=tournament.id))


@app.post("/dashboard/tournaments/<tournament_id>/generate-fixtures")
@login_required
def dashboard_generate_fixtures(tournament_id):
    org = _org_from_session()
    tournament = _tournament_for_org(org, tournament_id)
    if not tournament:
        flash("Unknown tournament for your club.", "error")
        return redirect(url_for("dashboard_tournaments"))
    teams = Team.query.filter_by(tournament_id=tournament.id).order_by(Team.created_at).all()
    if len(teams) < 2:
        flash("Add at least two teams before generating fixtures.", "error")
        return redirect(url_for("dashboard_tournament_manage", tournament_id=tournament.id))
    created = 0
    for i in range(len(teams)):
        for j in range(i + 1, len(teams)):
            db.session.add(
                Fixture(
                    tournament_id=tournament.id,
                    home_team_id=teams[i].id,
                    away_team_id=teams[j].id,
                )
            )
            created += 1
    db.session.commit()
    flash(f"Generated {created} round-robin fixture(s).", "success")
    return redirect(url_for("dashboard_tournament_manage", tournament_id=tournament.id))


def _fixture_for_org(org: Organization, tournament: Tournament, fixture_id: str) -> Fixture | None:
    if not tournament or not fixture_id:
        return None
    return Fixture.query.filter_by(id=fixture_id, tournament_id=tournament.id).first()


@app.post("/dashboard/tournaments/<tournament_id>/fixtures/<fixture_id>/start-match")
@login_required
def dashboard_start_fixture_match(tournament_id, fixture_id):
    org = _org_from_session()
    tournament = _tournament_for_org(org, tournament_id)
    fixture = _fixture_for_org(org, tournament, fixture_id) if tournament else None
    if not fixture:
        flash("Unknown fixture for your club.", "error")
        return redirect(url_for("dashboard_tournaments"))
    if fixture.score_match_slug:
        flash("This fixture is already linked to a live match.", "error")
        return redirect(url_for("dashboard_tournament_manage", tournament_id=tournament.id))
    mid = f"native-{secrets.token_hex(4)}"
    base_slug = sanitize_match_id(f"{org.slug}-{tournament.slug}-{secrets.token_hex(2)}")
    score_slug = base_slug
    for _ in range(16):
        if not RelayMatch.query.filter_by(score_match_slug=score_slug).first():
            break
        score_slug = sanitize_match_id(f"{base_slug}-{secrets.token_hex(2)}")
    home = _team_in_tournament(tournament, fixture.home_team_id)
    away = _team_in_tournament(tournament, fixture.away_team_id)
    label = f"{home.name if home else 'Home'} vs {away.name if away else 'Away'}"[:120]
    row = RelayMatch(
        organization_id=org.id,
        play_cricket_match_id=mid,
        full_scrape_url="",
        score_match_slug=score_slug,
        label=label,
        paused=False,
        relay_source="native",
    )
    db.session.add(row)
    fixture.score_match_slug = score_slug
    fixture.status = "live"
    try:
        db.session.commit()
    except IntegrityError:
        db.session.rollback()
        flash("Could not start the match (conflict).", "error")
        return redirect(url_for("dashboard_tournament_manage", tournament_id=tournament.id))
    flash("Native match started — score it from the scorer page.", "success")
    return redirect(url_for("dashboard_tournament_manage", tournament_id=tournament.id))


@app.post("/dashboard/tournaments/<tournament_id>/fixtures/<fixture_id>/result")
@login_required
def dashboard_record_fixture_result(tournament_id, fixture_id):
    org = _org_from_session()
    tournament = _tournament_for_org(org, tournament_id)
    fixture = _fixture_for_org(org, tournament, fixture_id) if tournament else None
    if not fixture:
        flash("Unknown fixture for your club.", "error")
        return redirect(url_for("dashboard_tournaments"))

    def _num(field, default=0):
        try:
            return max(0, int(request.form.get(field) or default))
        except (ValueError, TypeError):
            return default

    home = {"runs": _num("home_runs"), "wkts": _num("home_wkts"), "balls": _num("home_balls")}
    away = {"runs": _num("away_runs"), "wkts": _num("away_wkts"), "balls": _num("away_balls")}
    if home["runs"] > away["runs"]:
        outcome, winner = "home", fixture.home_team_id
    elif away["runs"] > home["runs"]:
        outcome, winner = "away", fixture.away_team_id
    else:
        outcome, winner = "tie", None
    home_t = _team_in_tournament(tournament, fixture.home_team_id)
    away_t = _team_in_tournament(tournament, fixture.away_team_id)
    if outcome == "tie":
        result_text = "Match tied"
    else:
        win_t = home_t if outcome == "home" else away_t
        margin = abs(home["runs"] - away["runs"])
        result_text = f"{(win_t.name if win_t else 'Winner')} won by {margin} run(s)"
    fixture.result_json = json.dumps(
        {
            "home": home,
            "away": away,
            "outcome": outcome,
            "winner_team_id": winner,
            "result_text": result_text,
        }
    )
    fixture.status = "completed"
    db.session.commit()
    flash("Result recorded — standings updated.", "success")
    return redirect(url_for("dashboard_tournament_manage", tournament_id=tournament.id))


@app.get("/t/<slug>")
def public_tournament(slug):
    """Public, no-login tournament page: squads, schedule, points table + NRR."""
    norm = normalize_public_club_slug(slug)
    tournament = Tournament.query.filter_by(slug=norm).first()
    if not tournament:
        abort(404)
    org = db.session.get(Organization, tournament.organization_id)
    view = _tournament_view(org, tournament)
    return render_template(
        "public_tournament.html",
        tournament=tournament,
        org=org,
        teams=view["teams"],
        players_by_team=view["players_by_team"],
        team_names=view["team_names"],
        fixtures=view["fixtures"],
        points_table=view["points_table"],
    )


def _serve_rich_overlay(match_id: str):
    """Serve cricket_overlay.html with cache-busting headers so WebViews always get fresh HTML."""
    overlay_path = Path(__file__).parent.parent / "cricket_overlay.html"
    if overlay_path.exists():
        resp = send_file(overlay_path, mimetype="text/html")
        resp.headers["Cache-Control"] = "no-store, no-cache, must-revalidate, max-age=0"
        resp.headers["Pragma"] = "no-cache"
        return resp
    # Fallback to legacy template if rich overlay file not deployed
    embed = request.args.get("embed", "").strip().lower() in {"1", "true", "yes"}
    poll_ms = 1000 if embed else 2000
    return render_template(
        "overlay.html",
        match_id=match_id,
        embed_mode=embed,
        poll_interval_ms=poll_ms,
    )


@app.get("/stream")
def stream_overlay_default():
    return _serve_rich_overlay(DEFAULT_MATCH_ID)


@app.get("/m/<match_id>/stream")
def stream_overlay_scoped(match_id):
    return _serve_rich_overlay(sanitize_match_id(match_id))


@app.get("/m/<match_id>/overlay-data")
def relay_overlay_data(match_id):
    """
    Serve the overlay-ready JSON schema consumed by cricket_overlay.html.
    Transforms scraper snapshots (Play-Cricket, CricHeroes) or PCS BLE into
    the innings[]/striker/current_bowler schema the rich overlay expects.
    """
    from .models_cricrelay import RELAY_PROVIDERS, resolve_provider_callable

    slug = sanitize_match_id(match_id)
    data = _live_snapshot(slug)
    row = RelayMatch.query.filter_by(score_match_slug=slug).first()
    is_manual_stream = (getattr(row, "relay_source", None) or "") == "manual"
    mode = (data.get("relay_mode") or "manual").strip().lower()
    bundle = data.get("relay_bundle") or {}
    snapshot = bundle.get("snapshot") if isinstance(bundle.get("snapshot"), dict) else None
    stale = bool(bundle.get("stale", True))
    last_ok = bundle.get("last_ok_at")

    if snapshot and mode in RELAY_PROVIDERS:
        mapper = resolve_provider_callable(RELAY_PROVIDERS[mode]["mapper_fn"])
        payload = mapper(snapshot, stale=stale, last_ok_at=last_ok)
    elif snapshot and mode == "pcs_ble":
        from .play_cricket_mapper import snapshot_to_overlay
        payload = snapshot_to_overlay(snapshot, stale=stale, last_ok_at=last_ok)
    else:
        totals = data.get("manual_totals") if isinstance(data.get("manual_totals"), dict) else None
        if is_manual_stream and totals:
            from .overlay_mapping_common import manual_totals_to_overlay

            last_manual = data.get("last_manual_at")
            payload = manual_totals_to_overlay(
                totals,
                label=(row.label or ""),
                stale=_manual_totals_stale(last_manual),
                last_ok_at=last_manual,
            )
        else:
            # No relay configured, or manual stream awaiting scorer setup —
            # return a minimal pre-match shell (labelled for manual streams).
            payload = {
                "home_team": data.get("team1", ""),
                "away_team": data.get("team2", ""),
                "total_overs": data.get("total_overs", 0),
                "batting_team": "",
                "match": {
                    "date": "",
                    "competition": (row.label or "") if is_manual_stream and row else "",
                    "status": "NOT STARTED",
                    "toss": "",
                },
                "innings": [],
                "striker": {"name": "", "runs": 0, "balls": 0, "sr": None},
                "non_striker": {"name": "", "runs": 0, "balls": 0},
                "current_bowler": {"name": "", "overs": "0", "runs": 0, "wickets": 0, "econ": 0.0},
                "current_partnership": {"runs": 0, "balls": 0},
                "recent_over": [],
                "target": None,
                "stale": stale,
                "last_updated": last_ok,
            }

    sponsor_enabled = bool(data.get("sponsor_enabled", False))
    sponsor_ids = _resolved_active_sponsor_ids(data)
    sponsor_payload = None
    if sponsor_enabled and sponsor_ids:
        now = datetime.now(timezone.utc)
        logos = []
        for sid in sponsor_ids[:6]:
            s = Sponsor.query.filter_by(id=sid, is_active=True).first()
            if s and (s.active_from is None or s.active_from <= now) and (
                s.active_to is None or s.active_to >= now
            ):
                logos.append(
                    {
                        "logo_url": s.logo_url,
                        "link_url": s.link_url,
                        "name": s.name,
                    }
                )
        if logos:
            layout_mode = _sanitize_sponsor_layout_mode(data.get("sponsor_layout_mode"))
            display_mode = _sanitize_sponsor_display_mode(data.get("sponsor_display_mode"))
            first = logos[0]
            sponsor_payload = {
                "layout_mode": layout_mode,
                "logos": logos,
                "carousel_interval_sec": float(data.get("sponsor_carousel_interval_sec") or 6.0),
                "logo_url": first.get("logo_url"),
                "link_url": first.get("link_url"),
                "name": first.get("name"),
                "display_mode": display_mode,
                "position_x": float(data.get("sponsor_position_x") or 0.92),
                "position_y": float(data.get("sponsor_position_y") or 0.88),
                "size_scale": float(data.get("sponsor_size_scale") or 1.0),
                "opacity": float(data.get("sponsor_opacity") or 1.0),
                "scroll_speed": float(data.get("sponsor_scroll_speed") or 1.0),
            }
    payload["sponsor"] = sponsor_payload

    relay_src = (getattr(row, "relay_source", None) or "scraper").strip().lower()
    payload["relay_source"] = relay_src
    payload["relay_mode"] = mode
    if mode == "cricheroes" or relay_src == "cricheroes":
        payload["data_pattern"] = "cricheroes"
    elif mode in ("play_cricket", "pcs_ble") or relay_src in ("scraper", "pcs_ble"):
        payload["data_pattern"] = "play_cricket"
    else:
        payload["data_pattern"] = mode or "manual"

    resp = jsonify(payload)
    resp.headers["Cache-Control"] = "no-store"
    return resp


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


def _manual_totals_stale(last_manual_at) -> bool:
    from .stream_api import MANUAL_STALE_AFTER_SEC, _parse_iso_ts

    ts = _parse_iso_ts(last_manual_at)
    if ts is None:
        return True
    return (datetime.now(timezone.utc) - ts).total_seconds() > MANUAL_STALE_AFTER_SEC


def _validated_manual_scorer_payload(payload) -> tuple[dict | None, str | None]:
    if not isinstance(payload, dict):
        return None, "invalid payload"
    try:
        seq = int(payload.get("seq"))
    except (TypeError, ValueError):
        return None, "seq must be a positive integer"
    if seq < 1:
        return None, "seq must be a positive integer"
    team_a = str(payload.get("team_a") or "").strip()[:60]
    team_b = str(payload.get("team_b") or "").strip()[:60]
    if not team_a or not team_b:
        return None, "team_a and team_b are required"
    try:
        total_overs = int(payload.get("total_overs"))
    except (TypeError, ValueError):
        return None, "total_overs must be a number"
    if not 1 <= total_overs <= 90:
        return None, "total_overs must be between 1 and 90"
    batting_first = str(payload.get("batting_first") or "").strip()
    if batting_first not in {"team_a", "team_b"}:
        return None, "batting_first must be team_a or team_b"
    current_innings = payload.get("current_innings")
    if current_innings not in (1, 2):
        return None, "current_innings must be 1 or 2"
    raw_innings = payload.get("innings")
    if not isinstance(raw_innings, list) or len(raw_innings) != current_innings:
        return None, "innings must list one entry per innings played"
    innings = []
    for idx, entry in enumerate(raw_innings, start=1):
        if not isinstance(entry, dict):
            return None, "innings entries must be objects"
        try:
            runs = int(entry.get("runs"))
            wickets = int(entry.get("wickets"))
            overs = int(entry.get("overs"))
            balls = int(entry.get("balls"))
        except (TypeError, ValueError):
            return None, "innings totals must be numbers"
        if not 0 <= runs <= 999:
            return None, "runs must be between 0 and 999"
        if not 0 <= wickets <= 10:
            return None, "wickets must be between 0 and 10"
        if not 0 <= overs <= total_overs:
            return None, "overs must be between 0 and total overs"
        if not 0 <= balls <= 5:
            return None, "balls must be between 0 and 5"
        # Batting side is derived, not trusted: innings 1 = batting_first.
        batting = "team_a" if (batting_first == "team_a") == (idx == 1) else "team_b"
        innings.append({
            "innings": idx,
            "batting": batting,
            "runs": runs,
            "wickets": wickets,
            "overs": overs,
            "balls": balls,
        })
    return {
        "seq": seq,
        "team_a": team_a,
        "team_b": team_b,
        "total_overs": total_overs,
        "batting_first": batting_first,
        "current_innings": current_innings,
        "innings": innings,
        "match_over": bool(payload.get("match_over")),
        "result_text": str(payload.get("result_text") or "").strip()[:120],
    }, None


def apply_manual_scorer_state(match_slug: str, payload):
    """Store validated QR-scorer totals; returns (result, error_body, error_status).

    State-based protocol: the scorer page owns undo and posts the full totals
    with a monotonic seq; anything <= the stored seq is rejected with the
    current state so a reloaded/second phone can resync. NOTE: relies on the
    single-worker gunicorn deploy — the in-memory state for the active slug is
    authoritative (see _live_snapshot).
    """
    clean, verr = _validated_manual_scorer_payload(payload)
    if verr:
        return None, {"error": verr}, 400
    with match_context(match_slug):
        merge_missing_state_keys(state)
        current = state.get("manual_totals") if isinstance(state.get("manual_totals"), dict) else None
        cur_seq = int((current or {}).get("seq") or 0)
        if clean["seq"] <= cur_seq:
            return None, {"error": "stale_seq", "seq": cur_seq, "state": current}, 409
        state["manual_totals"] = {
            **clean,
            "updated_at": datetime.now(timezone.utc).isoformat(),
        }
        # Mirror into the legacy scoreboard keys so /live/<slug>,
        # with_calculated_values (CRR/RRR) and match-day status work unchanged.
        names = {"team_a": clean["team_a"], "team_b": clean["team_b"]}
        cur = clean["innings"][clean["current_innings"] - 1]
        bowling_key = "team_b" if cur["batting"] == "team_a" else "team_a"
        target = clean["innings"][0]["runs"] + 1 if clean["current_innings"] == 2 else None
        state["team1"] = clean["team_a"]
        state["team2"] = clean["team_b"]
        state["total_overs"] = clean["total_overs"]
        state["innings"] = clean["current_innings"]
        state["batting_team"] = names[cur["batting"]]
        state["bowling_team"] = names[bowling_key]
        state["runs"] = cur["runs"]
        state["wickets"] = cur["wickets"]
        state["overs"] = cur["overs"]
        state["balls"] = cur["balls"]
        state["target"] = target
        state["match_started"] = True
        state["match_ended"] = clean["match_over"]
        save_state_with_manual_touch()
    return {"ok": True, "seq": clean["seq"], "target": target, "stale": False}, None, None


@app.get("/m/<match_slug>/scorer")
def manual_scorer_page(match_slug):
    from .stream_api import manual_scorer_match_for_token

    slug = sanitize_match_id(match_slug)
    token = (request.args.get("token") or "").strip()
    row = manual_scorer_match_for_token(token, slug) if token else None
    if row is None:
        return (
            render_template_string(
                "<!doctype html><meta name='viewport' content='width=device-width, initial-scale=1'>"
                "<title>Scoring link expired</title>"
                "<body style='font-family:system-ui,sans-serif;background:#0c1222;color:#e2e8f0;margin:0;"
                "min-height:100vh;display:flex;align-items:center;justify-content:center;text-align:center;padding:24px'>"
                "<div><h2 style='margin:0 0 8px'>Scoring link expired</h2>"
                "<p style='color:#94a3b8;margin:0'>Ask the streamer to open the Scorer QR screen "
                "in CricRelay and scan the fresh code.</p></div></body>"
            ),
            403,
        )
    return render_template(
        "manual_scorer.html",
        match_id=slug,
        match_label=row.label or "Manual scoring",
        scorer_token=token,
    )


@app.get("/m/<match_slug>/scorer/state")
@manual_scorer_token_required
def manual_scorer_get_state(row: RelayMatch, match_slug: str):
    with match_context(match_slug):
        merge_missing_state_keys(state)
        totals = state.get("manual_totals") if isinstance(state.get("manual_totals"), dict) else None
    return jsonify(
        {
            "ok": True,
            "setup_complete": bool(totals),
            "label": row.label or "Manual scoring",
            "seq": int((totals or {}).get("seq") or 0),
            "state": totals,
            "server_time": datetime.now(timezone.utc).isoformat(),
        }
    )


@app.post("/m/<match_slug>/scorer/state")
@manual_scorer_token_required
def manual_scorer_post_state(row: RelayMatch, match_slug: str):
    result, err_body, err_status = apply_manual_scorer_state(match_slug, request.get_json(silent=True))
    if err_body:
        return jsonify(err_body), err_status
    return jsonify(result)


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
    if mode == "pcs_ble":
        return pcs_ble_retired_response()
    if mode not in {"manual", "play_cricket", "cricheroes"}:
        return jsonify({"error": "relay_mode must be manual, play_cricket, or cricheroes"}), 400
    url = str(data.get("relay_play_cricket_url", "")).strip()
    if mode == "play_cricket":
        if not url:
            return jsonify({"error": "relay_play_cricket_url required when relay_mode is play_cricket"}), 400
        if "play-cricket.com" not in url.lower():
            return jsonify({"error": "URL must be a play-cricket.com page"}), 400
    if mode == "cricheroes":
        if not url:
            return jsonify({"error": "relay_play_cricket_url required when relay_mode is cricheroes (CricHeroes scorecard URL)"}), 400
        url = canonicalize_cricheroes_scrape_url(url)
        if not url:
            return jsonify({"error": "URL must be a CricHeroes scorecard page"}), 400
    with match_context():
        state["relay_mode"] = mode
        state["relay_provider"] = mode if mode in {"play_cricket", "cricheroes"} else state.get("relay_provider")
        state["relay_play_cricket_url"] = url if mode in {"play_cricket", "cricheroes"} else ""
        if mode == "manual":
            state["relay_wrapper"] = None
            state["relay_last_error"] = None
        save_state()
        return jsonify(with_calculated_values(state))


def apply_relay_ingest_payload(match_id: str, payload: dict) -> tuple[dict, int]:
    """Apply JSON ingest for a match slug. Used by ``/relay/ingest`` and the in-app relay worker."""
    from .models_cricrelay import RELAY_PROVIDERS

    mid = sanitize_match_id(match_id)
    with match_context(mid):
        mode = (state.get("relay_mode") or "manual").strip().lower()
        if mode not in RELAY_PROVIDERS:
            return ({"error": f"relay_mode is not a scraper provider for this match ({mode})"}, 400)
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
    raw_base_url = str(data.get("play_cricket_base_url") or "").strip()
    play_cricket_base_url = normalize_play_cricket_club_root(raw_base_url)
    if not name or not email or not password:
        return jsonify({"error": "name, email, and password are required"}), 400
    if not data.get("consent"):
        return jsonify({"error": "You must agree to the Privacy Policy"}), 400
    if len(password) < 8:
        return jsonify({"error": "Password must be at least 8 characters"}), 400
    if raw_base_url and not play_cricket_base_url:
        return jsonify(
            {
                "error": (
                    "That Play-Cricket club code was not recognised. Use the short name "
                    "before .play-cricket.com, like bmacc for https://bmacc.play-cricket.com."
                )
            }
        ), 400
    if not play_cricket_base_url:
        # App registrations don't ask for the club code; a teammate's account
        # under the same club name tells us which Play-Cricket site to use.
        play_cricket_base_url = _sibling_play_cricket_root(name)
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
            "play_cricket_base_url": org.play_cricket_base_url or "",
        }
    ), 201


@app.patch("/api/auth/account")
@stream_api_auth_required
def api_update_account(org: Organization):
    """Link or update the club's Play-Cricket site after registration.

    The mobile registration flow has no club-code field, so accounts created
    there start with no Play-Cricket site and an empty fixture list — this is
    the recovery path the apps (and support) can call.
    """
    data = request.get_json(silent=True) or {}
    if "play_cricket_base_url" not in data:
        return jsonify({"error": "play_cricket_base_url is required"}), 400
    raw = str(data.get("play_cricket_base_url") or "").strip()
    base = normalize_play_cricket_club_root(raw)
    if not base:
        return jsonify(
            {
                "error": (
                    "That Play-Cricket club code was not recognised. Use the short name "
                    "before .play-cricket.com, like bmacc for https://bmacc.play-cricket.com."
                )
            }
        ), 400
    org.play_cricket_base_url = base
    db.session.commit()
    return jsonify({"ok": True, "play_cricket_base_url": base})


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
        return pcs_ble_retired_response()
    if kind in {"cricheroes", "cric_heroes"}:
        row, err = _create_cricheroes_stream_org(
            org,
            str(data.get("match_url") or data.get("cricheroes_url") or ""),
            str(data.get("label") or ""),
        )
    elif kind == "manual":
        row, err = _create_manual_stream_org(org, str(data.get("label") or ""))
    else:
        row, err = _create_play_cricket_stream_org(
            org,
            str(data.get("play_cricket_match_id") or ""),
            str(data.get("label") or ""),
            str(data.get("play_cricket_base_url") or ""),
        )
    if err:
        return jsonify({"error": err}), 400
    stream = stream_dict_for_relay_match(org, row)
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
    mode_map = {"play_cricket": "auto", "cricheroes": "auto", "manual": "manual", "pcs_ble": "ble"}
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


def _sanitize_sponsor_display_mode(raw) -> str:
    modes = {
        "static",
        "scroll_top",
        "scroll_bottom",
        "scroll_above_board",
        "scroll_below_board",
    }
    m = str(raw or "static").strip().lower()
    return m if m in modes else "static"


def _sanitize_sponsor_layout_mode(raw) -> str:
    modes = {"single", "multi", "carousel"}
    m = str(raw or "single").strip().lower()
    return m if m in modes else "single"


def _resolved_active_sponsor_ids(data: dict) -> list[str]:
    ids = data.get("active_sponsor_ids")
    if isinstance(ids, list):
        out = [str(x).strip() for x in ids if str(x).strip()]
        if out:
            return out[:6]
    single = data.get("active_sponsor_id")
    if single:
        return [str(single).strip()]
    return []


OVERLAY_LAYOUT_STATE_KEYS = (
    "overlay_height_fraction",
    "overlay_width_fraction",
    "overlay_anchor_x",
    "overlay_anchor_y",
    "overlay_bottom_margin",
    "overlay_horizontal_inset",
    "overlay_font_scale",
    "overlay_bg_color",
    "overlay_text_color",
    "overlay_opacity",
    "bowling_island_enabled",
    "video_stabilization",
    "stabilization_level",
    "keep_screen_on",
    "watermark_enabled",
    "watermark_text",
    "sponsor_enabled",
    "active_sponsor_id",
    "active_sponsor_ids",
    "sponsor_layout_mode",
    "sponsor_carousel_interval_sec",
    "sponsor_display_mode",
    "sponsor_position_x",
    "sponsor_position_y",
    "sponsor_size_scale",
    "sponsor_opacity",
    "sponsor_scroll_speed",
)


def _overlay_layout_from_state() -> dict:
    merge_missing_state_keys(state)
    defaults = blank_state()
    out: dict = {}
    for key in OVERLAY_LAYOUT_STATE_KEYS:
        out[key] = state.get(key, defaults.get(key))
    out["sponsor_display_mode"] = _sanitize_sponsor_display_mode(out.get("sponsor_display_mode"))
    out["sponsor_layout_mode"] = _sanitize_sponsor_layout_mode(out.get("sponsor_layout_mode"))
    out["sponsor_carousel_interval_sec"] = max(2.0, min(30.0, float(out.get("sponsor_carousel_interval_sec") or 6.0)))
    ids = out.get("active_sponsor_ids")
    if not isinstance(ids, list):
        out["active_sponsor_ids"] = _resolved_active_sponsor_ids(out)
    out["sponsor_position_x"] = max(0.0, min(1.0, float(out.get("sponsor_position_x") or 0.92)))
    out["sponsor_position_y"] = max(0.0, min(1.0, float(out.get("sponsor_position_y") or 0.88)))
    out["sponsor_size_scale"] = max(0.3, min(3.0, float(out.get("sponsor_size_scale") or 1.0)))
    out["sponsor_opacity"] = max(0.2, min(1.0, float(out.get("sponsor_opacity") or 1.0)))
    out["sponsor_scroll_speed"] = max(0.3, min(3.0, float(out.get("sponsor_scroll_speed") or 1.0)))
    # Stabilization: the 3-level field is the source of truth; legacy states that only have the
    # boolean map on->STANDARD(1)/off->OFF(0). Keep the wire-compat boolean consistent with it.
    # (Deliberately NOT in blank_state: merge_missing_state_keys would back-fill level=1 onto old
    # states where the user had turned stabilization off.)
    lvl = state.get("stabilization_level")
    if lvl is None:
        lvl = 1 if out.get("video_stabilization", True) else 0
    try:
        lvl = max(0, min(2, int(lvl)))
    except (TypeError, ValueError):
        lvl = 1
    out["stabilization_level"] = lvl
    out["video_stabilization"] = lvl > 0
    return out


def _apply_overlay_layout_to_state(data: dict) -> None:
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
    for key in OVERLAY_LAYOUT_STATE_KEYS:
        if key not in data:
            continue
        val = data[key]
        if key == "sponsor_display_mode":
            state[key] = _sanitize_sponsor_display_mode(val)
        elif key in {"sponsor_enabled", "video_stabilization", "keep_screen_on", "watermark_enabled", "bowling_island_enabled"}:
            state[key] = bool(val)
        elif key == "stabilization_level":
            try:
                state[key] = max(0, min(2, int(val)))
            except (TypeError, ValueError):
                pass
        elif key == "active_sponsor_id":
            state[key] = str(val).strip() if val else None
        elif key == "active_sponsor_ids":
            if isinstance(val, list):
                state[key] = [str(x).strip() for x in val if str(x).strip()][:6]
        elif key == "sponsor_layout_mode":
            state[key] = _sanitize_sponsor_layout_mode(val)
        elif key == "sponsor_carousel_interval_sec":
            try:
                state[key] = max(2.0, min(30.0, float(val)))
            except (TypeError, ValueError):
                pass
        elif key in {
            "sponsor_position_x",
            "sponsor_position_y",
            "sponsor_size_scale",
            "sponsor_opacity",
            "sponsor_scroll_speed",
            "overlay_height_fraction",
            "overlay_width_fraction",
            "overlay_anchor_x",
            "overlay_anchor_y",
            "overlay_bottom_margin",
            "overlay_horizontal_inset",
            "overlay_font_scale",
            "overlay_opacity",
        }:
            try:
                state[key] = float(val)
            except (TypeError, ValueError):
                pass
        elif key in {"overlay_bg_color", "overlay_text_color", "watermark_text"}:
            state[key] = str(val or "")
        else:
            state[key] = val
    # Keep the stabilization pair consistent: the level wins when the writer sent it; a
    # legacy bool-only writer maps on->STANDARD(1)/off->OFF(0).
    if "stabilization_level" in data:
        lvl = state.get("stabilization_level")
        if isinstance(lvl, int):
            state["video_stabilization"] = lvl > 0
    elif "video_stabilization" in data:
        state["stabilization_level"] = 1 if state.get("video_stabilization") else 0


def _overlay_prefs_json(slug: str) -> dict:
    with match_context(slug):
        merge_missing_state_keys(state)
        size = normalize_overlay_size(state.get("overlay_size"), state.get("overlay_scale"))
        theme = _sanitize_overlay_theme(state.get("theme"))
        density = str(state.get("overlay_density") or "expanded").strip().lower()
        if density not in {"compact", "expanded"}:
            density = "expanded"
        layout = _overlay_layout_from_state()
        return {
            "ok": True,
            "overlay_size": size,
            "overlay_scale": float(state.get("overlay_scale") or 1.0),
            "theme": theme,
            "overlay_density": density,
            **layout,
        }


@app.get("/api/match/<match_slug>/overlay")
@stream_api_auth_required
def api_get_overlay(org: Organization, match_slug: str):
    slug = sanitize_match_id(match_slug)
    if not relay_match_for_org(org, slug):
        return jsonify({"error": "unknown stream"}), 404
    return jsonify(_overlay_prefs_json(slug))


@app.post("/api/net-probe")
@stream_api_auth_required
def api_net_probe(org: Organization):
    """Upload bandwidth probe: the app times this discarded POST (~2 MB) right before Go Live
    to choose the stream resolution (1080p needs ~6 Mbps of uplink headroom)."""
    size = len(request.get_data(cache=False) or b"")
    return jsonify({"ok": True, "bytes": size})


@app.post("/api/match/<match_slug>/overlay")
@stream_api_auth_required
def api_set_overlay(org: Organization, match_slug: str):
    slug = sanitize_match_id(match_slug)
    if not relay_match_for_org(org, slug):
        return jsonify({"error": "unknown stream"}), 404
    data = request.get_json(silent=True) or {}
    with match_context(slug):
        _apply_overlay_layout_to_state(data)
        save_state()
    return jsonify(_overlay_prefs_json(slug))


def _sponsor_json(s: Sponsor) -> dict:
    return {
        "id": s.id,
        "name": s.name,
        "logo_url": s.logo_url,
        "link_url": s.link_url,
        "is_active": s.is_active,
        "active_from": s.active_from.isoformat() if s.active_from else None,
        "active_to": s.active_to.isoformat() if s.active_to else None,
    }


@app.get("/api/sponsors")
@stream_api_auth_required
def api_list_sponsors(org: Organization):
    rows = Sponsor.query.filter_by(organization_id=org.id).order_by(Sponsor.created_at.desc()).all()
    return jsonify({"ok": True, "sponsors": [_sponsor_json(s) for s in rows]})


@app.post("/api/sponsors")
@stream_api_auth_required
def api_create_sponsor(org: Organization):
    data = request.get_json(silent=True) or {}
    name = str(data.get("name") or "").strip()
    if not name:
        return jsonify({"error": "name required"}), 400
    s = Sponsor(
        organization_id=org.id,
        name=name,
        logo_url=str(data.get("logo_url") or "").strip() or None,
        link_url=str(data.get("link_url") or "").strip() or None,
        is_active=bool(data.get("is_active", True)),
    )
    db.session.add(s)
    db.session.commit()
    return jsonify({"ok": True, "sponsor": _sponsor_json(s)})


@app.patch("/api/sponsors/<sponsor_id>")
@stream_api_auth_required
def api_patch_sponsor(org: Organization, sponsor_id: str):
    s = Sponsor.query.filter_by(id=sponsor_id, organization_id=org.id).first()
    if not s:
        return jsonify({"error": "unknown sponsor"}), 404
    data = request.get_json(silent=True) or {}
    if "name" in data:
        s.name = str(data.get("name") or "").strip() or s.name
    if "logo_url" in data:
        s.logo_url = str(data.get("logo_url") or "").strip() or None
    if "link_url" in data:
        s.link_url = str(data.get("link_url") or "").strip() or None
    if "is_active" in data:
        s.is_active = bool(data.get("is_active"))
    db.session.commit()
    return jsonify({"ok": True, "sponsor": _sponsor_json(s)})


@app.delete("/api/sponsors/<sponsor_id>")
@stream_api_auth_required
def api_delete_sponsor(org: Organization, sponsor_id: str):
    s = Sponsor.query.filter_by(id=sponsor_id, organization_id=org.id).first()
    if not s:
        return jsonify({"error": "unknown sponsor"}), 404
    db.session.delete(s)
    db.session.commit()
    return jsonify({"ok": True, "deleted": sponsor_id})


@app.post("/api/match/<match_slug>/pair")
@stream_api_auth_required
def api_pair_remote(org: Organization, match_slug: str):
    from .stream_api import REMOTE_PAIR_TOKEN_MAX_AGE, issue_remote_pair_token

    slug = sanitize_match_id(match_slug)
    if not relay_match_for_org(org, slug):
        return jsonify({"error": "unknown stream"}), 404
    token = issue_remote_pair_token(org, slug)
    expires_at = (datetime.now(timezone.utc) + timedelta(seconds=REMOTE_PAIR_TOKEN_MAX_AGE)).isoformat()
    return jsonify({"ok": True, "pair_token": token, "expires_at": expires_at})


@app.post("/api/match/<match_slug>/scorer-link")
@stream_api_auth_required
def api_manual_scorer_link(org: Organization, match_slug: str):
    from .stream_api import MANUAL_SCORER_TOKEN_MAX_AGE, issue_manual_scorer_token

    slug = sanitize_match_id(match_slug)
    row = relay_match_for_org(org, slug)
    if not row:
        return jsonify({"error": "unknown stream"}), 404
    if (row.relay_source or "") != "manual":
        return jsonify({"error": "Scorer links are only available for manual streams."}), 400
    token = issue_manual_scorer_token(org, slug)
    base = _public_base_url()
    scorer_url = f"{base}/m/{slug}/scorer?token={token}"
    expires_at = (
        datetime.now(timezone.utc) + timedelta(seconds=MANUAL_SCORER_TOKEN_MAX_AGE)
    ).isoformat()
    return jsonify({"ok": True, "scorer_url": scorer_url, "expires_at": expires_at})


@app.post("/stream/<match_slug>/pair/redeem")
def public_pair_redeem(match_slug: str):
    from .stream_api import redeem_remote_pair_token

    data = request.get_json(silent=True) or {}
    pair_token = str(data.get("pair_token") or "").strip()
    if not pair_token:
        return jsonify({"error": "pair_token required"}), 400
    result = redeem_remote_pair_token(pair_token)
    if not result or result["slug"] != sanitize_match_id(match_slug):
        return jsonify({"error": "invalid or expired pairing code"}), 400
    return jsonify({"ok": True, "companion_token": result["companion_token"], "match_slug": result["slug"]})


def _remote_sponsor_overlay_patch(raw: dict) -> dict:
    """Extract and sanitize sponsor overlay fields from a companion remote payload."""
    if not isinstance(raw, dict):
        return {}
    from .stream_api import REMOTE_SPONSOR_OVERLAY_KEYS

    out: dict = {}
    for key in REMOTE_SPONSOR_OVERLAY_KEYS:
        if key not in raw:
            continue
        val = raw[key]
        if key == "sponsor_enabled":
            out[key] = bool(val)
        elif key == "active_sponsor_id":
            sid = str(val or "").strip()
            out[key] = sid if sid else None
        elif key == "active_sponsor_ids":
            if isinstance(val, list):
                out[key] = [str(x).strip() for x in val if str(x).strip()][:6]
        elif key == "sponsor_layout_mode":
            out[key] = _sanitize_sponsor_layout_mode(val)
        elif key == "sponsor_carousel_interval_sec":
            try:
                out[key] = max(2.0, min(30.0, float(val)))
            except (TypeError, ValueError):
                pass
        elif key == "sponsor_display_mode":
            out[key] = _sanitize_sponsor_display_mode(val)
        elif key in {
            "sponsor_position_x",
            "sponsor_position_y",
            "sponsor_size_scale",
            "sponsor_opacity",
            "sponsor_scroll_speed",
        }:
            try:
                fval = float(val)
            except (TypeError, ValueError):
                continue
            if key in {"sponsor_position_x", "sponsor_position_y"}:
                out[key] = max(0.0, min(1.0, fval))
            elif key == "sponsor_size_scale":
                out[key] = max(0.3, min(3.0, fval))
            elif key == "sponsor_opacity":
                out[key] = max(0.2, min(1.0, fval))
            else:
                out[key] = max(0.3, min(3.0, fval))
    return out


@app.get("/api/match/<match_slug>/remote/context")
@companion_token_required
def api_remote_context(companion_slug: str, companion_org_id: str, match_slug: str):
    slug = sanitize_match_id(match_slug)
    if slug != companion_slug:
        return jsonify({"error": "slug mismatch"}), 400
    if not relay_match_for_org_id(companion_org_id, slug):
        return jsonify({"error": "unknown stream"}), 404
    overlay = _overlay_prefs_json(slug)
    from .stream_api import REMOTE_SPONSOR_OVERLAY_KEYS

    sponsor_prefs = {k: overlay.get(k) for k in REMOTE_SPONSOR_OVERLAY_KEYS if k in overlay}
    rows = (
        Sponsor.query.filter_by(organization_id=companion_org_id)
        .order_by(Sponsor.created_at.desc())
        .all()
    )
    watch_url = ""
    sess = (
        StreamSession.query.filter_by(match_slug=slug, organization_id=companion_org_id)
        .order_by(StreamSession.started_at.desc())
        .first()
    )
    if sess and sess.watch_url:
        watch_url = str(sess.watch_url).strip()
    return jsonify(
        {
            "ok": True,
            "sponsor_prefs": sponsor_prefs,
            "sponsors": [_sponsor_json(s) for s in rows],
            "watch_url": watch_url,
        }
    )


def relay_match_for_org_id(org_id: str, match_slug: str) -> RelayMatch | None:
    slug = (match_slug or "").strip()
    if not slug or not org_id:
        return None
    return RelayMatch.query.filter_by(organization_id=org_id, score_match_slug=slug).first()


@app.post("/api/match/<match_slug>/remote/command")
@companion_token_required
def api_remote_command(companion_slug: str, companion_org_id: str, match_slug: str):
    from .stream_api import REMOTE_CONTROL_COMMANDS, redis_client

    slug = sanitize_match_id(match_slug)
    if slug != companion_slug:
        return jsonify({"error": "slug mismatch"}), 400
    data = request.get_json(silent=True) or {}
    msg_type = str(data.get("type") or "").strip()
    command = str(data.get("command") or "").strip()
    import time as _t

    if msg_type == "control":
        if command not in REMOTE_CONTROL_COMMANDS:
            return jsonify({"error": "invalid command"}), 400
        envelope = json.dumps({"type": msg_type, "command": command, "ts": _t.time()})
    elif msg_type == "overlay":
        patch = _remote_sponsor_overlay_patch(data.get("prefs") or {})
        if not patch:
            return jsonify({"error": "overlay prefs required"}), 400
        envelope = json.dumps({"type": msg_type, "prefs": patch, "ts": _t.time()})
    else:
        return jsonify({"error": "invalid command type"}), 400
    key = f"cricrelay:remote:cmds:{slug}"
    r = redis_client()
    r.rpush(key, envelope)
    r.expire(key, 600)
    return jsonify({"ok": True})


@app.get("/api/match/<match_slug>/remote/commands")
@stream_api_auth_required
def api_remote_commands_poll(org: Organization, match_slug: str):
    from .stream_api import redis_client

    slug = sanitize_match_id(match_slug)
    if not relay_match_for_org(org, slug):
        return jsonify({"error": "unknown stream"}), 404
    key = f"cricrelay:remote:cmds:{slug}"
    r = redis_client()
    pipe = r.pipeline()
    pipe.lrange(key, 0, -1)
    pipe.delete(key)
    raw_list, _ = pipe.execute()
    commands = [json.loads(item) for item in raw_list]
    return jsonify({"ok": True, "commands": commands})


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
        if mode == "ble":
            return pcs_ble_retired_response()
        if mode not in {"auto", "manual"}:
            return jsonify({"error": "mode must be auto or manual"}), 400
        if (row.relay_source or "") == "manual":
            # QR-scored streams are permanently manual: never rewrite their
            # relay_source (the poller/UI would treat them as scrapers).
            if mode == "manual":
                return api_get_scoring.__wrapped__(org, slug)
            return jsonify({"error": "Manual streams have no auto-scoring source."}), 400
        provider = str(data.get("provider") or "play_cricket").strip().lower()
        if provider not in {"play_cricket", "cricheroes"}:
            provider = "play_cricket"
        if mode == "auto":
            apply_relay_to_score_match(slug, row.full_scrape_url, provider=provider)
            row.relay_source = "cricheroes" if provider == "cricheroes" else "scraper"
        elif mode == "manual":
            with match_context(slug):
                merge_missing_state_keys(state)
                state["relay_mode"] = "manual"
                state["relay_wrapper"] = None
                state["relay_last_error"] = None
                save_state()
            row.relay_source = "scraper"
        db.session.commit()
        # __wrapped__ skips the auth decorator, which would otherwise re-read
        # the bearer header and shift the positional args.
        return api_get_scoring.__wrapped__(org, slug)
    except Exception as exc:
        app.logger.exception("api_set_scoring failed for %s", match_slug)
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
    changed = False
    if "label" in data:
        label = str(data.get("label") or "").strip()
        row.label = label or row.play_cricket_match_id
        changed = True
    if "stream_destination_id" in data:
        raw = data.get("stream_destination_id")
        if raw is None or str(raw).strip() == "":
            row.stream_destination_id = None
            changed = True
        else:
            dest = _destination_for_org(org, str(raw))
            if not dest:
                return jsonify({"error": "unknown destination"}), 404
            row.stream_destination_id = dest.id
            changed = True
    if changed:
        db.session.commit()
    return jsonify({"ok": True, "stream": stream_dict_for_relay_match(org, row)})


@app.get("/api/stream/destinations")
@stream_api_auth_required
def api_list_destinations(org: Organization):
    rows = _org_destinations(org)
    return jsonify(
        {
            "ok": True,
            "destinations": [_destination_public_dict(d) for d in rows],
            "slots_used": len(rows),
            "slots_total": MAX_STREAM_DESTINATIONS_PER_ORG,
        }
    )


@app.post("/api/stream/destinations")
@stream_api_auth_required
def api_create_destination(org: Organization):
    if StreamDestination.query.filter_by(organization_id=org.id).count() >= MAX_STREAM_DESTINATIONS_PER_ORG:
        return jsonify({"error": f"Maximum {MAX_STREAM_DESTINATIONS_PER_ORG} destinations per club"}), 400
    data = request.get_json(silent=True) or {}
    label, rtmp_url, stream_key, watch_url, err = _validate_destination_payload(data, require_key=True)
    if err:
        return jsonify({"error": err}), 400
    enc = yt.encrypt_token(stream_key)
    if not enc:
        return jsonify({"error": "Could not encrypt stream key — check server encryption config"}), 503
    dest = StreamDestination(
        organization_id=org.id,
        label=label,
        provider="custom_rtmp",
        rtmp_url=rtmp_url,
        stream_key_enc=enc,
        watch_url=watch_url or None,
    )
    db.session.add(dest)
    db.session.commit()
    return jsonify({"ok": True, "destination": _destination_public_dict(dest)}), 201


@app.get("/api/stream/destinations/<dest_id>")
@stream_api_auth_required
def api_get_destination(org: Organization, dest_id: str):
    dest = _destination_for_org(org, dest_id)
    if not dest:
        return jsonify({"error": "unknown destination"}), 404
    return jsonify({"ok": True, "destination": _destination_public_dict(dest, include_key=True)})


@app.patch("/api/stream/destinations/<dest_id>")
@stream_api_auth_required
def api_patch_destination(org: Organization, dest_id: str):
    dest = _destination_for_org(org, dest_id)
    if not dest:
        return jsonify({"error": "unknown destination"}), 404
    data = request.get_json(silent=True) or {}
    require_key = "stream_key" in data and bool(str(data.get("stream_key") or "").strip())
    # Allow partial updates: reuse existing fields when omitted
    merged = {
        "label": data["label"] if "label" in data else dest.label,
        "rtmp_url": data["rtmp_url"] if "rtmp_url" in data else dest.rtmp_url,
        "stream_key": data.get("stream_key") if require_key else "x",
        "watch_url": data["watch_url"] if "watch_url" in data else (dest.watch_url or ""),
    }
    label, rtmp_url, stream_key, watch_url, err = _validate_destination_payload(
        merged, require_key=require_key
    )
    if err:
        return jsonify({"error": err}), 400
    dest.label = label
    dest.rtmp_url = rtmp_url
    dest.watch_url = watch_url or None
    if require_key:
        enc = yt.encrypt_token(stream_key)
        if not enc:
            return jsonify({"error": "Could not encrypt stream key — check server encryption config"}), 503
        dest.stream_key_enc = enc
    dest.updated_at = datetime.now(timezone.utc)
    db.session.commit()
    return jsonify({"ok": True, "destination": _destination_public_dict(dest)})


@app.delete("/api/stream/destinations/<dest_id>")
@stream_api_auth_required
def api_delete_destination(org: Organization, dest_id: str):
    dest = _destination_for_org(org, dest_id)
    if not dest:
        return jsonify({"error": "unknown destination"}), 404
    RelayMatch.query.filter_by(organization_id=org.id, stream_destination_id=dest.id).update(
        {"stream_destination_id": None}
    )
    db.session.delete(dest)
    db.session.commit()
    return jsonify({"ok": True, "deleted": dest_id})


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
        if not access:
            # Same honesty rule as twitch-status: a token that no longer refreshes must not
            # gate Go Live open, or the operator only learns at go-live time.
            connected = False
            live_streaming_message = "YouTube connection expired — reconnect YouTube."
        else:
            check = yt.verify_live_streaming_access(access)
            live_streaming_ok = bool(check.get("ok"))
            live_streaming_message = str(check.get("message") or "")
    return jsonify(
        {
            "ok": True,
            "oauth_configured": yt.oauth_configured(),
            "oauth_scopes": yt.oauth_scopes(),
            "connected": connected,
            "ready": bool(connected and live_streaming_ok),
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
        if not access:
            # A stored token that no longer refreshes (revoked, or rotation lost) is not
            # "connected": reporting it as such is how go-live fails with "Twitch not
            # connected" right after the destination sheet showed Twitch as ready.
            connected = False
            stream_key_message = "Twitch connection expired — reconnect Twitch."
        elif bid:
            check = tw.verify_streaming_access(access, bid)
            stream_key_ok = bool(check.get("ok"))
            stream_key_message = (check.get("message") or "").strip()
    return jsonify(
        {
            "ok": True,
            "oauth_configured": tw.oauth_configured(),
            "oauth_scopes": tw.oauth_scopes(),
            "connected": connected,
            "ready": bool(connected and stream_key_ok),
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

with app.app_context():
    db.create_all()
    migrate_relay_match_columns()
    migrate_relay_source_column()
    migrate_stream_destination_columns()
    migrate_organization_brand_columns()
    migrate_youtube_columns()
    migrate_twitch_columns()
    migrate_play_cricket_base_url_nullable()
    migrate_consent_given_at_column()
    purge_legacy_pcs_ble_relay_rows()

register_relay_worker(app, apply_relay_ingest_payload)
start_relay_poller(app, apply_relay_ingest_payload, get_live_snapshot)


with state_lock:
    try:
        activate_context(DEFAULT_MATCH_ID)
        restore_state(DEFAULT_MATCH_ID)
        persist_active_context()
    except Exception:
        state = blank_state()


if __name__ == "__main__":
    port = safe_num(os.getenv("PORT", "5000"), 5000)
    app.run(host="0.0.0.0", port=port, threaded=True)
