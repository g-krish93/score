"""Tests for the native-mode competition module (Task A / #3).

Covers: org-scoped CRUD for tournaments/teams/players/fixtures, round-robin
generation, native-match wiring (relay_source="native" + live page), the public
/t/<slug> page, and the points-table + NRR computation.

Env is configured before importing the app so it runs against an isolated,
throwaway SQLite database and STATE_DIR.
"""
from __future__ import annotations

import json
import os
import tempfile

import pytest

_TMP = tempfile.mkdtemp(prefix="cr_comp_test_")
os.environ["STATE_DIR"] = _TMP
os.environ["DATABASE_URL"] = f"sqlite:///{os.path.join(_TMP, 'test.db')}"
os.environ.setdefault("SECRET_KEY", "test-secret")

from server.app import app, compute_points_table  # noqa: E402
from server.models_cricrelay import (  # noqa: E402
    Fixture,
    Organization,
    Player,
    RelayMatch,
    Team,
    Tournament,
    db,
)


@pytest.fixture()
def client():
    app.config["TESTING"] = True
    with app.test_client() as c:
        yield c


_org_seq = 0


def _make_org() -> str:
    global _org_seq
    _org_seq += 1
    slug = f"club{_org_seq}"
    with app.app_context():
        org = Organization(slug=slug, name=slug, email=f"{slug}@example.com")
        org.set_password("pw")
        db.session.add(org)
        db.session.commit()
        return org.id


def _login(client, org_id: str) -> None:
    with client.session_transaction() as sess:
        sess["org_id"] = org_id


def _create_tournament(client) -> Tournament:
    client.post("/dashboard/tournaments", data={"name": "Summer Cup", "overs": "20"}, follow_redirects=True)
    with app.app_context():
        return Tournament.query.filter_by(name="Summer Cup").order_by(Tournament.created_at.desc()).first()


# --- pure unit: points table + NRR ---------------------------------------

def test_points_table_win_loss_and_nrr():
    a = Team(id="a", tournament_id="t", name="Alpha")
    b = Team(id="b", tournament_id="t", name="Bravo")
    fx = Fixture(
        id="f1", tournament_id="t", home_team_id="a", away_team_id="b",
        status="completed",
        result_json=json.dumps({
            "home": {"runs": 160, "balls": 120},
            "away": {"runs": 150, "balls": 120},
            "outcome": "home",
        }),
    )
    table = compute_points_table([a, b], [fx])
    top, bottom = table[0], table[1]
    assert top["team"].id == "a"
    assert top["won"] == 1 and top["points"] == 2
    assert bottom["lost"] == 1 and bottom["points"] == 0
    # Alpha scored 160 in 20 overs, conceded 150 in 20 -> +0.5 NRR.
    assert top["nrr"] == pytest.approx(0.5, abs=1e-3)
    assert bottom["nrr"] == pytest.approx(-0.5, abs=1e-3)


def test_points_table_tie_awards_one_each():
    a = Team(id="a", tournament_id="t", name="Alpha")
    b = Team(id="b", tournament_id="t", name="Bravo")
    fx = Fixture(
        id="f1", tournament_id="t", home_team_id="a", away_team_id="b",
        status="completed",
        result_json=json.dumps({
            "home": {"runs": 150, "balls": 120},
            "away": {"runs": 150, "balls": 120},
            "outcome": "tie",
        }),
    )
    table = compute_points_table([a, b], [fx])
    assert all(r["points"] == 1 and r["drawn"] == 1 for r in table)


# --- route integration ----------------------------------------------------

def test_create_tournament_teams_players(client):
    _login(client, _make_org())
    t = _create_tournament(client)
    assert t is not None and t.slug

    client.post(f"/dashboard/tournaments/{t.id}/teams", data={"name": "Eagles", "short_name": "EAG"}, follow_redirects=True)
    client.post(f"/dashboard/tournaments/{t.id}/teams", data={"name": "Hawks"}, follow_redirects=True)
    with app.app_context():
        teams = Team.query.filter_by(tournament_id=t.id).order_by(Team.name).all()
        assert [x.name for x in teams] == ["Eagles", "Hawks"]
        eagles = next(x for x in teams if x.name == "Eagles")

    client.post(f"/dashboard/tournaments/{t.id}/players", data={"team_id": eagles.id, "name": "A. Player"}, follow_redirects=True)
    with app.app_context():
        assert Player.query.filter_by(team_id=eagles.id).count() == 1


def test_generate_round_robin(client):
    _login(client, _make_org())
    t = _create_tournament(client)
    for name in ("A", "B", "C", "D"):
        client.post(f"/dashboard/tournaments/{t.id}/teams", data={"name": name}, follow_redirects=True)
    client.post(f"/dashboard/tournaments/{t.id}/generate-fixtures", follow_redirects=True)
    with app.app_context():
        # 4 teams -> C(4,2) = 6 fixtures
        assert Fixture.query.filter_by(tournament_id=t.id).count() == 6


def test_start_native_match_and_live_page(client):
    _login(client, _make_org())
    t = _create_tournament(client)
    client.post(f"/dashboard/tournaments/{t.id}/teams", data={"name": "A"}, follow_redirects=True)
    client.post(f"/dashboard/tournaments/{t.id}/teams", data={"name": "B"}, follow_redirects=True)
    client.post(f"/dashboard/tournaments/{t.id}/generate-fixtures", follow_redirects=True)
    with app.app_context():
        fx = Fixture.query.filter_by(tournament_id=t.id).first()

    client.post(f"/dashboard/tournaments/{t.id}/fixtures/{fx.id}/start-match", follow_redirects=True)
    with app.app_context():
        fx2 = db.session.get(Fixture, fx.id)
        assert fx2.score_match_slug and fx2.status == "live"
        rm = RelayMatch.query.filter_by(score_match_slug=fx2.score_match_slug).first()
        assert rm is not None and rm.relay_source == "native"
        slug = fx2.score_match_slug

    assert client.get(f"/live/{slug}").status_code == 200


def test_result_updates_public_points_table(client):
    _login(client, _make_org())
    t = _create_tournament(client)
    client.post(f"/dashboard/tournaments/{t.id}/teams", data={"name": "Alpha"}, follow_redirects=True)
    client.post(f"/dashboard/tournaments/{t.id}/teams", data={"name": "Bravo"}, follow_redirects=True)
    client.post(f"/dashboard/tournaments/{t.id}/generate-fixtures", follow_redirects=True)
    with app.app_context():
        fx = Fixture.query.filter_by(tournament_id=t.id).first()

    client.post(
        f"/dashboard/tournaments/{t.id}/fixtures/{fx.id}/result",
        data={"home_runs": "180", "home_balls": "120", "away_runs": "120", "away_balls": "120"},
        follow_redirects=True,
    )
    with app.app_context():
        fx2 = db.session.get(Fixture, fx.id)
        assert fx2.status == "completed"
        slug = t.slug

    resp = client.get(f"/t/{slug}")
    assert resp.status_code == 200
    body = resp.get_data(as_text=True)
    assert "Points table" in body
    assert "Alpha" in body and "Bravo" in body


def test_org_scoping_blocks_cross_org_access(client):
    org_a = _make_org()
    _login(client, org_a)
    t = _create_tournament(client)
    with app.app_context():
        before = Team.query.filter_by(tournament_id=t.id).count()

    # Switch to a different org and try to mutate org A's tournament.
    org_b = _make_org()
    _login(client, org_b)
    manage = client.get(f"/dashboard/tournaments/{t.id}")
    assert manage.status_code == 302  # redirected away, not rendered

    client.post(f"/dashboard/tournaments/{t.id}/teams", data={"name": "Intruder"}, follow_redirects=True)
    with app.app_context():
        after = Team.query.filter_by(tournament_id=t.id).count()
        assert after == before
        assert Team.query.filter_by(name="Intruder").count() == 0


def test_login_required_redirects_anonymous(client):
    assert client.get("/dashboard/tournaments").status_code == 302
