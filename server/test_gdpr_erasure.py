"""Minimal SQLite check: account delete cascades all org-scoped tables."""
from __future__ import annotations

import os
import sys
import uuid

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from flask import Flask

from server.models_cricrelay import (
    ClubUser,
    Organization,
    RelayMatch,
    Sponsor,
    StreamSession,
    db,
)


def erase_org_personal_data(org_id: str) -> None:
    """Mirror of app._erase_org_personal_data — keep in sync for this smoke test."""
    StreamSession.query.filter_by(organization_id=org_id).delete(
        synchronize_session=False
    )
    Sponsor.query.filter_by(organization_id=org_id).delete(synchronize_session=False)
    ClubUser.query.filter_by(organization_id=org_id).delete(synchronize_session=False)
    RelayMatch.query.filter_by(organization_id=org_id).delete(synchronize_session=False)


def main() -> None:
    app = Flask(__name__)
    app.config["SQLALCHEMY_DATABASE_URI"] = "sqlite:///:memory:"
    app.config["SQLALCHEMY_TRACK_MODIFICATIONS"] = False
    app.config["SECRET_KEY"] = "test"
    db.init_app(app)

    with app.app_context():
        db.create_all()

        org = Organization(
            id=str(uuid.uuid4()),
            slug="test-club",
            name="Test Club",
            email="admin@test.example",
        )
        org.set_password("secret")
        db.session.add(org)
        db.session.flush()

        user = ClubUser(
            id=str(uuid.uuid4()),
            organization_id=org.id,
            name="Admin",
            email="member@test.example",
            role="admin",
        )
        user.set_password("secret")
        match = RelayMatch(
            id=str(uuid.uuid4()),
            organization_id=org.id,
            play_cricket_match_id="12345",
            full_scrape_url="https://example.play-cricket.com/website/results/12345",
            score_match_slug="test-match",
        )
        sponsor = Sponsor(
            id=str(uuid.uuid4()),
            organization_id=org.id,
            name="Acme Ltd",
        )
        session_row = StreamSession(
            id=str(uuid.uuid4()),
            organization_id=org.id,
            relay_match_id=match.id,
            match_slug="test-match",
            platform="youtube",
            started_by_user_id=user.id,
        )
        db.session.add_all([user, match, sponsor, session_row])
        db.session.commit()

        erase_org_personal_data(org.id)
        org = db.session.get(Organization, org.id)
        db.session.delete(org)
        db.session.commit()

        assert Organization.query.count() == 0
        assert ClubUser.query.count() == 0
        assert RelayMatch.query.count() == 0
        assert Sponsor.query.count() == 0
        assert StreamSession.query.count() == 0
        print("ok: gdpr erasure cascade leaves no orphan rows")


if __name__ == "__main__":
    main()
