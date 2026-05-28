import re
import uuid
from datetime import datetime, timezone
from urllib.parse import parse_qs, urlunparse, urlparse

from flask_sqlalchemy import SQLAlchemy
from sqlalchemy import UniqueConstraint
from werkzeug.security import check_password_hash, generate_password_hash

db = SQLAlchemy()


def slugify_org_name(name: str) -> str:
    s = re.sub(r"[^a-zA-Z0-9]+", "-", (name or "").strip().lower()).strip("-")
    return s[:48] or "club"


def _play_cricket_match_id_from_url(url: str) -> str:
    """Extract numeric fixture id from results or match_details URLs."""
    raw = (url or "").strip()
    if not raw:
        return ""
    parsed = urlparse(raw)
    qs = parse_qs(parsed.query)
    mid = (qs.get("id") or [None])[0]
    if mid and str(mid).strip().isdigit():
        return str(mid).strip()
    m = re.search(r"/website/results/(\d+)\b", (parsed.path or "").lower())
    if m:
        return m.group(1)
    return ""


def build_play_cricket_results_url(host_root: str, match_id: str) -> str:
    """Live scorecards are published at ``…/website/results/<id>``."""
    root = (host_root or "").strip().rstrip("/")
    mid = str(match_id or "").strip()
    if not root or not mid.isdigit():
        return ""
    parsed = urlparse(root if "://" in root else f"https://{root}")
    if not parsed.netloc or "play-cricket.com" not in parsed.netloc.lower():
        return ""
    scheme = parsed.scheme or "https"
    return f"{scheme}://{parsed.netloc}/website/results/{mid}"


def canonicalize_play_cricket_scrape_url(url: str) -> str:
    """Normalize ECB Play-Cricket URLs to the page that carries live scores.

    - ``match_details?id=<id>`` → ``/website/results/<id>`` (scores are not on match_details)
    - ``…/website/results/match_details?id=<id>`` → ``…/website/results/<id>``
    """
    raw = (url or "").strip()
    if not raw or "play-cricket.com" not in raw.lower():
        return raw
    parsed = urlparse(raw)
    path = (parsed.path or "").replace("//", "/")
    low_path = path.lower()
    mid = _play_cricket_match_id_from_url(raw)
    if not mid:
        return raw
    scheme = parsed.scheme or "https"
    netloc = parsed.netloc
    if not netloc:
        return raw

    if "match_details" in low_path:
        results = build_play_cricket_results_url(f"{scheme}://{netloc}", mid)
        if results:
            return results

    marker = "/website/results"
    if marker in low_path and "match_details" in low_path:
        idx = low_path.find(marker)
        prefix = path[: idx + len(marker)].rstrip("/")
        new_path = f"{prefix}/{mid}"
        return urlunparse((scheme, netloc, new_path, "", "", ""))
    return raw


def normalize_play_cricket_club_root(raw: str) -> str:
    """Turn user input into ``https://<subdomain>.play-cricket.com``.

    Accepts the short club code (``bmacc``), a host (``bmacc.play-cricket.com``),
    or a full URL on that host. Anything after the host is ignored.

    Returns empty string if the value cannot be interpreted as a club site.
    """
    s = (raw or "").strip().strip("<>")
    if not s:
        return ""

    def slug_ok(slug: str) -> bool:
        slug = (slug or "").lower()
        return bool(re.fullmatch(r"[a-z0-9-]{2,48}", slug))

    def from_host(host: str) -> str:
        host = (host or "").lower().strip(".")
        if not host.endswith(".play-cricket.com"):
            return ""
        without = host[: -len(".play-cricket.com")].strip(".")
        if not without:
            return ""
        parts = without.split(".")
        if parts[0] == "www" and len(parts) > 1:
            slug = parts[-1]
        else:
            slug = parts[0]
        slug = re.sub(r"[^a-z0-9-]+", "", slug)
        if slug_ok(slug):
            return f"https://{slug}.play-cricket.com"
        return ""

    s_low = s.lower()
    if "://" in s_low or ".play-cricket.com" in s_low:
        to_parse = s_low if "://" in s_low else f"https://{s_low}"
        parsed = urlparse(to_parse)
        return from_host(parsed.netloc)

    slug = re.sub(r"[^a-zA-Z0-9-]+", "", s.split("/")[0]).lower()
    if slug_ok(slug):
        return f"https://{slug}.play-cricket.com"
    return ""


def build_match_details_url(base_url: str, match_id: str) -> str:
    b = (base_url or "").strip().rstrip("/")
    mid = str(match_id or "").strip()
    return f"{b}/match_details?id={mid}"


def build_play_cricket_scrape_url(base_url: str, match_id: str) -> str:
    """Build the scrape URL for a fixture (live scores on ``/website/results/<id>``)."""
    b = (base_url or "").strip().rstrip("/")
    mid = str(match_id or "").strip()
    if not mid:
        return b
    results = build_play_cricket_results_url(b, mid)
    if results:
        return results
    low = b.lower()
    marker = "/website/results"
    if marker in low:
        idx = low.find(marker)
        prefix = b[: idx + len(marker)].rstrip("/")
        return f"{prefix}/{mid}"
    return f"{b}/match_details?id={mid}"


class Organization(db.Model):
    __tablename__ = "cricrelay_org"

    id = db.Column(db.String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    slug = db.Column(db.String(64), unique=True, nullable=False, index=True)
    name = db.Column(db.String(200), nullable=False)
    email = db.Column(db.String(200), unique=True, nullable=False, index=True)
    password_hash = db.Column(db.String(256), nullable=False)
    play_cricket_base_url = db.Column(db.String(500), nullable=False)
    public_logo_url = db.Column(db.String(1000), nullable=True)
    public_primary_color = db.Column(db.String(7), nullable=False, default="#22d3a8")
    public_accent_color = db.Column(db.String(7), nullable=False, default="#38bdf8")
    ui_theme = db.Column(db.String(16), nullable=False, default="original")
    youtube_refresh_token_enc = db.Column(db.Text, nullable=True)
    youtube_channel_id = db.Column(db.String(64), nullable=True)
    youtube_channel_title = db.Column(db.String(200), nullable=True)
    youtube_connected_at = db.Column(db.DateTime, nullable=True)
    youtube_active_broadcast_id = db.Column(db.String(64), nullable=True)
    youtube_active_stream_id = db.Column(db.String(64), nullable=True)
    youtube_active_match_slug = db.Column(db.String(120), nullable=True)
    twitch_refresh_token_enc = db.Column(db.Text, nullable=True)
    twitch_user_id = db.Column(db.String(32), nullable=True)
    twitch_login = db.Column(db.String(64), nullable=True)
    twitch_display_name = db.Column(db.String(200), nullable=True)
    twitch_connected_at = db.Column(db.DateTime, nullable=True)
    twitch_active_match_slug = db.Column(db.String(120), nullable=True)
    created_at = db.Column(db.DateTime, default=lambda: datetime.now(timezone.utc))

    def set_password(self, password: str) -> None:
        self.password_hash = generate_password_hash(password)

    def check_password(self, password: str) -> bool:
        return check_password_hash(self.password_hash, password)


class RelayMatch(db.Model):
    __tablename__ = "cricrelay_match"

    id = db.Column(db.String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    organization_id = db.Column(db.String(36), db.ForeignKey("cricrelay_org.id"), nullable=False)
    play_cricket_match_id = db.Column(db.String(32), nullable=False)
    full_scrape_url = db.Column(db.String(800), nullable=False)
    score_match_slug = db.Column(db.String(120), unique=True, nullable=False, index=True)
    label = db.Column(db.String(120), nullable=True)
    paused = db.Column(db.Boolean, nullable=False, default=False)
    # scraper = Play-Cricket HTML poll; pcs_ble = Android PCS relay (R&D)
    relay_source = db.Column(db.String(24), nullable=False, default="scraper")
    created_at = db.Column(db.DateTime, default=lambda: datetime.now(timezone.utc))

    __table_args__ = (
        UniqueConstraint("organization_id", "play_cricket_match_id", name="uq_cricrelay_org_pc_match"),
    )
