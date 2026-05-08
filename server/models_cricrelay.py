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


def canonicalize_play_cricket_scrape_url(url: str) -> str:
    """Rewrite malformed ECB URLs that 404.

    Clubs sometimes paste or merge paths into
    ``…/website/results/match_details?id=<id>``. The live scorecard is
    ``…/website/results/<id>`` (see registration docs).
    """
    raw = (url or "").strip()
    if not raw or "play-cricket.com" not in raw.lower():
        return raw
    parsed = urlparse(raw)
    path = (parsed.path or "").replace("//", "/")
    low_path = path.lower()
    qs = parse_qs(parsed.query)
    mid = (qs.get("id") or [None])[0]
    if not mid or not str(mid).strip().isdigit():
        return raw
    mid = str(mid).strip()
    marker = "/website/results"
    if marker not in low_path or "match_details" not in low_path:
        return raw
    idx = low_path.find(marker)
    prefix = path[: idx + len(marker)].rstrip("/")
    new_path = f"{prefix}/{mid}"
    return urlunparse((parsed.scheme, parsed.netloc, new_path, "", "", ""))


def build_match_details_url(base_url: str, match_id: str) -> str:
    b = (base_url or "").strip().rstrip("/")
    mid = str(match_id or "").strip()
    return f"{b}/match_details?id={mid}"


def build_play_cricket_scrape_url(base_url: str, match_id: str) -> str:
    """Build the page URL your scraper should fetch.

    If the club base points at the Play-Cricket *results* area (…/website/results),
    the live fixture is typically ``…/website/results/<numeric_id>``.

    Otherwise we use the classic ``…/match_details?id=<id>`` form.
    """
    b = (base_url or "").strip().rstrip("/")
    mid = str(match_id or "").strip()
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
    created_at = db.Column(db.DateTime, default=lambda: datetime.now(timezone.utc))

    def set_password(self, password: str) -> None:
        self.password_hash = generate_password_hash(password)

    def check_password(self, password: str) -> bool:
        return check_password_hash(self.password_hash, password)


class ClubTeam(db.Model):
    __tablename__ = "cricrelay_club_team"

    id = db.Column(db.String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    organization_id = db.Column(db.String(36), db.ForeignKey("cricrelay_org.id"), nullable=False)
    name = db.Column(db.String(200), nullable=False)

    __table_args__ = (UniqueConstraint("organization_id", "name", name="uq_cricrelay_club_team_name"),)


class RelayMatch(db.Model):
    __tablename__ = "cricrelay_match"

    id = db.Column(db.String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    organization_id = db.Column(db.String(36), db.ForeignKey("cricrelay_org.id"), nullable=False)
    club_team_id = db.Column(db.String(36), db.ForeignKey("cricrelay_club_team.id"), nullable=True)
    play_cricket_match_id = db.Column(db.String(32), nullable=False)
    full_scrape_url = db.Column(db.String(800), nullable=False)
    score_match_slug = db.Column(db.String(120), unique=True, nullable=False, index=True)
    label = db.Column(db.String(120), nullable=True)
    paused = db.Column(db.Boolean, nullable=False, default=False)
    created_at = db.Column(db.DateTime, default=lambda: datetime.now(timezone.utc))

    __table_args__ = (
        UniqueConstraint("organization_id", "play_cricket_match_id", name="uq_cricrelay_org_pc_match"),
    )
