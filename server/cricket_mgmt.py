"""Cricket Tournament Management Blueprint.

Handles teams, players, tournaments, and match scheduling.
All dashboard routes require org login. Public routes are open.
"""
from __future__ import annotations

import json
import re
import uuid
from datetime import datetime, timezone
from functools import wraps

from flask import (
    Blueprint,
    abort,
    flash,
    jsonify,
    redirect,
    render_template,
    request,
    session,
    url_for,
)

from .models_cricrelay import (
    CricInnings,
    CricMatch,
    CricPlayer,
    CricPlayerMatchStat,
    CricTeam,
    CricTournament,
    Organization,
    db,
)

cricket_bp = Blueprint("cricket", __name__)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _org_from_session():
    oid = session.get("org_id")
    if not oid:
        return None
    return db.session.get(Organization, oid)


def login_required(view):
    @wraps(view)
    def wrapped(*args, **kwargs):
        if not _org_from_session():
            session.pop("org_id", None)
            return redirect(url_for("login_page"))
        return view(*args, **kwargs)
    return wrapped


def _slugify(name: str) -> str:
    s = re.sub(r"[^a-zA-Z0-9]+", "-", (name or "").strip().lower()).strip("-")
    return s[:48] or "item"


def _unique_slug(base: str, model, org_id: str, exclude_id: str | None = None) -> str:
    slug = _slugify(base)
    candidate = slug
    n = 1
    while True:
        q = model.query.filter_by(organization_id=org_id, slug=candidate)
        if exclude_id:
            q = q.filter(model.id != exclude_id)
        if not q.first():
            return candidate
        candidate = f"{slug}-{n}"
        n += 1


def _overs_for_tournament(tournament: CricTournament) -> int:
    fmt_overs = {"T20": 20, "ODI": 50, "Test": 0}
    if tournament.format == "Custom":
        return tournament.custom_overs or 20
    return fmt_overs.get(tournament.format, 20)


# ---------------------------------------------------------------------------
# Team Management (dashboard)
# ---------------------------------------------------------------------------

@cricket_bp.get("/dashboard/teams")
@login_required
def teams_list():
    org = _org_from_session()
    teams = CricTeam.query.filter_by(organization_id=org.id).order_by(CricTeam.name).all()
    return render_template("teams_list.html", org=org, teams=teams)


@cricket_bp.route("/dashboard/teams/new", methods=["GET", "POST"])
@login_required
def team_new():
    org = _org_from_session()
    if request.method == "POST":
        name = request.form.get("name", "").strip()
        if not name:
            flash("Team name is required.", "error")
            return render_template("team_form.html", org=org, team=None)
        slug = _unique_slug(name, CricTeam, org.id)
        team = CricTeam(
            organization_id=org.id,
            name=name,
            slug=slug,
            color=request.form.get("color", "#22d3a8"),
            logo_url=request.form.get("logo_url", "").strip() or None,
            description=request.form.get("description", "").strip() or None,
        )
        db.session.add(team)
        db.session.commit()
        flash(f"Team '{name}' created.", "success")
        return redirect(url_for("cricket.team_players", team_slug=slug))
    return render_template("team_form.html", org=org, team=None)


@cricket_bp.route("/dashboard/teams/<team_slug>/edit", methods=["GET", "POST"])
@login_required
def team_edit(team_slug):
    org = _org_from_session()
    team = CricTeam.query.filter_by(organization_id=org.id, slug=team_slug).first_or_404()
    if request.method == "POST":
        name = request.form.get("name", "").strip()
        if not name:
            flash("Team name is required.", "error")
            return render_template("team_form.html", org=org, team=team)
        if name != team.name:
            team.slug = _unique_slug(name, CricTeam, org.id, exclude_id=team.id)
        team.name = name
        team.color = request.form.get("color", team.color)
        team.logo_url = request.form.get("logo_url", "").strip() or None
        team.description = request.form.get("description", "").strip() or None
        db.session.commit()
        flash("Team updated.", "success")
        return redirect(url_for("cricket.teams_list"))
    return render_template("team_form.html", org=org, team=team)


@cricket_bp.post("/dashboard/teams/<team_slug>/delete")
@login_required
def team_delete(team_slug):
    org = _org_from_session()
    team = CricTeam.query.filter_by(organization_id=org.id, slug=team_slug).first_or_404()
    db.session.delete(team)
    db.session.commit()
    flash(f"Team '{team.name}' deleted.", "success")
    return redirect(url_for("cricket.teams_list"))


@cricket_bp.get("/dashboard/teams/<team_slug>/players")
@login_required
def team_players(team_slug):
    org = _org_from_session()
    team = CricTeam.query.filter_by(organization_id=org.id, slug=team_slug).first_or_404()
    players = team.players.order_by(CricPlayer.name).all()
    return render_template("team_players.html", org=org, team=team, players=players)


@cricket_bp.post("/dashboard/teams/<team_slug>/players/add")
@login_required
def player_add(team_slug):
    org = _org_from_session()
    team = CricTeam.query.filter_by(organization_id=org.id, slug=team_slug).first_or_404()
    name = request.form.get("name", "").strip()
    if not name:
        flash("Player name is required.", "error")
        return redirect(url_for("cricket.team_players", team_slug=team_slug))
    player = CricPlayer(
        organization_id=org.id,
        team_id=team.id,
        name=name,
        role=request.form.get("role", "batsman"),
        batting_style=request.form.get("batting_style", "").strip() or None,
        bowling_style=request.form.get("bowling_style", "").strip() or None,
        jersey_number=int(request.form["jersey_number"]) if request.form.get("jersey_number", "").strip().isdigit() else None,
    )
    db.session.add(player)
    db.session.commit()
    flash(f"Player '{name}' added.", "success")
    return redirect(url_for("cricket.team_players", team_slug=team_slug))


@cricket_bp.post("/dashboard/teams/<team_slug>/players/<player_id>/edit")
@login_required
def player_edit(team_slug, player_id):
    org = _org_from_session()
    team = CricTeam.query.filter_by(organization_id=org.id, slug=team_slug).first_or_404()
    player = CricPlayer.query.filter_by(id=player_id, team_id=team.id).first_or_404()
    name = request.form.get("name", "").strip()
    if name:
        player.name = name
    player.role = request.form.get("role", player.role)
    player.batting_style = request.form.get("batting_style", "").strip() or None
    player.bowling_style = request.form.get("bowling_style", "").strip() or None
    jn = request.form.get("jersey_number", "").strip()
    player.jersey_number = int(jn) if jn.isdigit() else None
    db.session.commit()
    flash("Player updated.", "success")
    return redirect(url_for("cricket.team_players", team_slug=team_slug))


@cricket_bp.post("/dashboard/teams/<team_slug>/players/<player_id>/remove")
@login_required
def player_remove(team_slug, player_id):
    org = _org_from_session()
    team = CricTeam.query.filter_by(organization_id=org.id, slug=team_slug).first_or_404()
    player = CricPlayer.query.filter_by(id=player_id, team_id=team.id).first_or_404()
    db.session.delete(player)
    db.session.commit()
    flash(f"Player '{player.name}' removed.", "success")
    return redirect(url_for("cricket.team_players", team_slug=team_slug))


# ---------------------------------------------------------------------------
# Tournament Management (dashboard)
# ---------------------------------------------------------------------------

@cricket_bp.get("/dashboard/tournaments")
@login_required
def tournaments_list():
    org = _org_from_session()
    tournaments = CricTournament.query.filter_by(organization_id=org.id).order_by(CricTournament.created_at.desc()).all()
    return render_template("tournaments_list.html", org=org, tournaments=tournaments)


@cricket_bp.route("/dashboard/tournaments/new", methods=["GET", "POST"])
@login_required
def tournament_new():
    org = _org_from_session()
    if request.method == "POST":
        name = request.form.get("name", "").strip()
        if not name:
            flash("Tournament name is required.", "error")
            return render_template("tournament_form.html", org=org, tournament=None)
        slug = _unique_slug(name, CricTournament, org.id)
        start_date = _parse_date(request.form.get("start_date", ""))
        end_date = _parse_date(request.form.get("end_date", ""))
        custom_overs = int(request.form["custom_overs"]) if request.form.get("custom_overs", "").strip().isdigit() else None
        t = CricTournament(
            organization_id=org.id,
            name=name,
            slug=slug,
            format=request.form.get("format", "T20"),
            custom_overs=custom_overs,
            tournament_type=request.form.get("tournament_type", "League"),
            location=request.form.get("location", "").strip() or None,
            start_date=start_date,
            end_date=end_date,
            is_public=request.form.get("is_public") == "1",
        )
        db.session.add(t)
        db.session.commit()
        flash(f"Tournament '{name}' created.", "success")
        return redirect(url_for("cricket.tournament_teams_manage", t_slug=slug))
    return render_template("tournament_form.html", org=org, tournament=None)


@cricket_bp.route("/dashboard/tournaments/<t_slug>/edit", methods=["GET", "POST"])
@login_required
def tournament_edit(t_slug):
    org = _org_from_session()
    t = CricTournament.query.filter_by(organization_id=org.id, slug=t_slug).first_or_404()
    if request.method == "POST":
        name = request.form.get("name", "").strip()
        if not name:
            flash("Tournament name is required.", "error")
            return render_template("tournament_form.html", org=org, tournament=t)
        if name != t.name:
            t.slug = _unique_slug(name, CricTournament, org.id, exclude_id=t.id)
        t.name = name
        t.format = request.form.get("format", t.format)
        t.tournament_type = request.form.get("tournament_type", t.tournament_type)
        t.location = request.form.get("location", "").strip() or None
        t.start_date = _parse_date(request.form.get("start_date", ""))
        t.end_date = _parse_date(request.form.get("end_date", ""))
        co = request.form.get("custom_overs", "").strip()
        t.custom_overs = int(co) if co.isdigit() else None
        t.is_public = request.form.get("is_public") == "1"
        db.session.commit()
        flash("Tournament updated.", "success")
        return redirect(url_for("cricket.tournaments_list"))
    return render_template("tournament_form.html", org=org, tournament=t)


@cricket_bp.post("/dashboard/tournaments/<t_slug>/delete")
@login_required
def tournament_delete(t_slug):
    org = _org_from_session()
    t = CricTournament.query.filter_by(organization_id=org.id, slug=t_slug).first_or_404()
    db.session.delete(t)
    db.session.commit()
    flash(f"Tournament '{t.name}' deleted.", "success")
    return redirect(url_for("cricket.tournaments_list"))


@cricket_bp.get("/dashboard/tournaments/<t_slug>/teams")
@login_required
def tournament_teams_manage(t_slug):
    org = _org_from_session()
    t = CricTournament.query.filter_by(organization_id=org.id, slug=t_slug).first_or_404()
    all_teams = CricTeam.query.filter_by(organization_id=org.id).order_by(CricTeam.name).all()
    enrolled_ids = {team.id for team in t.teams}
    return render_template("tournament_teams.html", org=org, tournament=t, all_teams=all_teams, enrolled_ids=enrolled_ids)


@cricket_bp.post("/dashboard/tournaments/<t_slug>/teams/add")
@login_required
def tournament_team_add(t_slug):
    org = _org_from_session()
    t = CricTournament.query.filter_by(organization_id=org.id, slug=t_slug).first_or_404()
    team_id = request.form.get("team_id", "").strip()
    team = CricTeam.query.filter_by(id=team_id, organization_id=org.id).first_or_404()
    if team not in t.teams:
        t.teams.append(team)
        db.session.commit()
        flash(f"'{team.name}' added to tournament.", "success")
    return redirect(url_for("cricket.tournament_teams_manage", t_slug=t_slug))


@cricket_bp.post("/dashboard/tournaments/<t_slug>/teams/<team_id>/remove")
@login_required
def tournament_team_remove(t_slug, team_id):
    org = _org_from_session()
    t = CricTournament.query.filter_by(organization_id=org.id, slug=t_slug).first_or_404()
    team = CricTeam.query.filter_by(id=team_id, organization_id=org.id).first_or_404()
    if team in t.teams:
        t.teams.remove(team)
        db.session.commit()
        flash(f"'{team.name}' removed from tournament.", "success")
    return redirect(url_for("cricket.tournament_teams_manage", t_slug=t_slug))


# ---------------------------------------------------------------------------
# Match Management (dashboard)
# ---------------------------------------------------------------------------

@cricket_bp.get("/dashboard/tournaments/<t_slug>/matches")
@login_required
def tournament_matches(t_slug):
    org = _org_from_session()
    t = CricTournament.query.filter_by(organization_id=org.id, slug=t_slug).first_or_404()
    matches = t.matches.order_by(CricMatch.match_date.asc()).all()
    return render_template("tournament_matches.html", org=org, tournament=t, matches=matches)


@cricket_bp.route("/dashboard/tournaments/<t_slug>/matches/new", methods=["GET", "POST"])
@login_required
def match_new(t_slug):
    org = _org_from_session()
    t = CricTournament.query.filter_by(organization_id=org.id, slug=t_slug).first_or_404()
    enrolled_teams = sorted(t.teams, key=lambda x: x.name)
    if request.method == "POST":
        team1_id = request.form.get("team1_id", "").strip()
        team2_id = request.form.get("team2_id", "").strip()
        if not team1_id or not team2_id or team1_id == team2_id:
            flash("Select two different teams.", "error")
            return render_template("match_form.html", org=org, tournament=t, teams=enrolled_teams, match=None)
        match_date = _parse_datetime(request.form.get("match_date", ""))
        overs_str = request.form.get("overs", "").strip()
        overs = int(overs_str) if overs_str.isdigit() else None
        m = CricMatch(
            organization_id=org.id,
            tournament_id=t.id,
            team1_id=team1_id,
            team2_id=team2_id,
            venue=request.form.get("venue", "").strip() or None,
            match_date=match_date,
            overs=overs,
        )
        db.session.add(m)
        db.session.commit()
        flash("Match created.", "success")
        return redirect(url_for("cricket.tournament_matches", t_slug=t_slug))
    return render_template("match_form.html", org=org, tournament=t, teams=enrolled_teams, match=None)


@cricket_bp.route("/dashboard/tournaments/<t_slug>/matches/<m_id>/edit", methods=["GET", "POST"])
@login_required
def match_edit(t_slug, m_id):
    org = _org_from_session()
    t = CricTournament.query.filter_by(organization_id=org.id, slug=t_slug).first_or_404()
    m = CricMatch.query.filter_by(id=m_id, tournament_id=t.id).first_or_404()
    enrolled_teams = sorted(t.teams, key=lambda x: x.name)
    if request.method == "POST":
        team1_id = request.form.get("team1_id", "").strip()
        team2_id = request.form.get("team2_id", "").strip()
        if team1_id and team2_id and team1_id != team2_id:
            m.team1_id = team1_id
            m.team2_id = team2_id
        m.venue = request.form.get("venue", "").strip() or None
        m.match_date = _parse_datetime(request.form.get("match_date", ""))
        overs_str = request.form.get("overs", "").strip()
        m.overs = int(overs_str) if overs_str.isdigit() else None
        db.session.commit()
        flash("Match updated.", "success")
        return redirect(url_for("cricket.tournament_matches", t_slug=t_slug))
    return render_template("match_form.html", org=org, tournament=t, teams=enrolled_teams, match=m)


@cricket_bp.post("/dashboard/tournaments/<t_slug>/matches/<m_id>/delete")
@login_required
def match_delete(t_slug, m_id):
    org = _org_from_session()
    t = CricTournament.query.filter_by(organization_id=org.id, slug=t_slug).first_or_404()
    m = CricMatch.query.filter_by(id=m_id, tournament_id=t.id).first_or_404()
    db.session.delete(m)
    db.session.commit()
    flash("Match deleted.", "success")
    return redirect(url_for("cricket.tournament_matches", t_slug=t_slug))


@cricket_bp.post("/dashboard/tournaments/<t_slug>/matches/<m_id>/link-scoring")
@login_required
def match_link_scoring(t_slug, m_id):
    """Generate a score_match_slug and redirect to the scorer for this match."""
    org = _org_from_session()
    t = CricTournament.query.filter_by(organization_id=org.id, slug=t_slug).first_or_404()
    m = CricMatch.query.filter_by(id=m_id, tournament_id=t.id).first_or_404()
    if not m.score_match_slug:
        short = str(uuid.uuid4())[:8]
        m.score_match_slug = f"t-{_slugify(t.name)[:16]}-{_slugify(m.team1.name)[:10]}-vs-{_slugify(m.team2.name)[:10]}-{short}"
        m.status = "live"
        db.session.commit()
    return redirect(f"/m/{m.score_match_slug}/input")


@cricket_bp.post("/dashboard/tournaments/<t_slug>/matches/<m_id>/status")
@login_required
def match_status_update(t_slug, m_id):
    org = _org_from_session()
    t = CricTournament.query.filter_by(organization_id=org.id, slug=t_slug).first_or_404()
    m = CricMatch.query.filter_by(id=m_id, tournament_id=t.id).first_or_404()
    new_status = request.form.get("status", "").strip()
    if new_status in ("scheduled", "live", "completed", "abandoned", "no_result"):
        m.status = new_status
        db.session.commit()
        flash(f"Match status updated to '{new_status}'.", "success")
    return redirect(url_for("cricket.tournament_matches", t_slug=t_slug))


@cricket_bp.post("/dashboard/tournaments/<t_slug>/matches/<m_id>/extract-stats")
@login_required
def match_extract_stats(t_slug, m_id):
    org = _org_from_session()
    t = CricTournament.query.filter_by(organization_id=org.id, slug=t_slug).first_or_404()
    m = CricMatch.query.filter_by(id=m_id, tournament_id=t.id).first_or_404()
    if not m.score_match_slug:
        flash("No scoring session linked to this match yet.", "error")
        return redirect(url_for("cricket.tournament_matches", t_slug=t_slug))
    result = _extract_match_stats(m)
    if "error" in result:
        flash(f"Stat extraction failed: {result['error']}", "error")
    else:
        flash(f"Stats extracted: {result.get('player_rows_saved', 0)} player rows, {result.get('innings_saved', 0)} innings.", "success")
        if result.get("unmatched_names"):
            flash(f"Unmatched player names (not in roster): {', '.join(result['unmatched_names'])}", "warning")
    return redirect(url_for("cricket.tournament_matches", t_slug=t_slug))


# ---------------------------------------------------------------------------
# JSON API for scoring integration
# ---------------------------------------------------------------------------

@cricket_bp.get("/api/cric-match/<m_id>/roster")
def api_match_roster(m_id):
    m = CricMatch.query.filter_by(id=m_id).first_or_404()
    return jsonify({
        "team1": {"id": m.team1.id, "name": m.team1.name, "color": m.team1.color,
                  "players": [p.name for p in m.team1.players.order_by(CricPlayer.name)]},
        "team2": {"id": m.team2.id, "name": m.team2.name, "color": m.team2.color,
                  "players": [p.name for p in m.team2.players.order_by(CricPlayer.name)]},
    })


@cricket_bp.get("/api/org/<org_slug>/live-matches")
def api_live_matches(org_slug):
    """Return live/scheduled tournament matches for use in streaming relay picker."""
    org = Organization.query.filter_by(slug=org_slug).first_or_404()
    matches = (
        CricMatch.query
        .join(CricTournament)
        .filter(CricTournament.organization_id == org.id)
        .filter(CricMatch.status.in_(["scheduled", "live"]))
        .order_by(CricMatch.match_date.asc())
        .all()
    )
    result = []
    for m in matches:
        result.append({
            "id": m.id,
            "tournament": m.tournament.name,
            "team1": m.team1.name,
            "team2": m.team2.name,
            "status": m.status,
            "score_match_slug": m.score_match_slug,
            "match_date": m.match_date.isoformat() if m.match_date else None,
        })
    return jsonify(result)


# ---------------------------------------------------------------------------
# Public Pages
# ---------------------------------------------------------------------------

@cricket_bp.get("/t/<org_slug>/<t_slug>")
def public_tournament(org_slug, t_slug):
    org = Organization.query.filter_by(slug=org_slug).first_or_404()
    t = CricTournament.query.filter_by(organization_id=org.id, slug=t_slug, is_public=True).first_or_404()
    matches = t.matches.order_by(CricMatch.match_date.asc()).all()
    points_table = _calculate_points_table(t)
    return render_template("public_tournament.html", org=org, tournament=t, matches=matches, points_table=points_table)


@cricket_bp.get("/t/<org_slug>/<t_slug>/leaderboard")
def public_leaderboard(org_slug, t_slug):
    org = Organization.query.filter_by(slug=org_slug).first_or_404()
    t = CricTournament.query.filter_by(organization_id=org.id, slug=t_slug, is_public=True).first_or_404()
    top_batters, top_bowlers = _leaderboard(t)
    return render_template("public_leaderboard.html", org=org, tournament=t,
                           top_batters=top_batters, top_bowlers=top_bowlers)


@cricket_bp.get("/team/<org_slug>/<team_slug>")
def public_team(org_slug, team_slug):
    org = Organization.query.filter_by(slug=org_slug).first_or_404()
    team = CricTeam.query.filter_by(organization_id=org.id, slug=team_slug).first_or_404()
    players = team.players.order_by(CricPlayer.name).all()
    return render_template("public_team.html", org=org, team=team, players=players)


@cricket_bp.get("/player/<org_slug>/<player_id>")
def public_player(org_slug, player_id):
    org = Organization.query.filter_by(slug=org_slug).first_or_404()
    player = CricPlayer.query.filter_by(id=player_id, organization_id=org.id).first_or_404()
    stats = player.match_stats.all()
    batting = _aggregate_batting(stats)
    bowling = _aggregate_bowling(stats)
    return render_template("public_player.html", org=org, player=player, batting=batting, bowling=bowling, stats=stats)


@cricket_bp.get("/scorecard/<org_slug>/<match_id>")
def public_scorecard(org_slug, match_id):
    org = Organization.query.filter_by(slug=org_slug).first_or_404()
    m = CricMatch.query.filter_by(id=match_id, organization_id=org.id).first_or_404()
    player_stats_by_innings = {}
    for innings in m.innings_list:
        batters = [s for s in m.player_match_stats if s.innings_number == innings.innings_number and s.runs_scored is not None]
        bowlers = [s for s in m.player_match_stats if s.innings_number == innings.innings_number and s.overs_bowled is not None]
        batters.sort(key=lambda s: s.batting_position or 99)
        player_stats_by_innings[innings.innings_number] = {"batting": batters, "bowling": bowlers}
    return render_template("public_scorecard.html", org=org, match=m,
                           innings_list=m.innings_list, player_stats=player_stats_by_innings)


# ---------------------------------------------------------------------------
# Stat Extraction (JSON state → DB)
# ---------------------------------------------------------------------------

def _state_path(slug: str):
    """Return the Path for a match's JSON state file. Mirrors app.py logic."""
    import os
    from pathlib import Path
    state_dir = Path(os.getenv("STATE_DIR", "/tmp")).expanduser()
    return state_dir / f"cricket_state_{slug}.json"


def _extract_match_stats(match: CricMatch) -> dict:
    """Read JSON state file and persist innings + player stats to DB."""
    path = _state_path(match.score_match_slug)
    if not path.exists():
        return {"error": "state file not found"}
    try:
        with path.open() as f:
            state = json.load(f)
    except Exception as e:
        return {"error": str(e)}

    innings_saved = 0
    player_rows_saved = 0
    unmatched: list[str] = []

    innings_num = state.get("innings", 1)
    for inn_n in range(1, innings_num + 1):
        # Determine which squads correspond to this innings
        # In innings 1: batting_squad = team1 batters, bowling_squad = team2 bowlers
        # In innings 2: it's swapped (team2 bats, team1 bowls)
        if inn_n == 1:
            batting_team = match.team1
            bowling_team = match.team2
        else:
            batting_team = match.team2
            bowling_team = match.team1

        # Only process current innings data that's available in the state file
        if inn_n < innings_num:
            continue  # First innings data is only available when extract is called mid-game

        batting_squad = state.get("batting_squad", [])
        bowling_squad = state.get("bowling_squad", [])

        # Save or update Innings record
        existing_innings = CricInnings.query.filter_by(match_id=match.id, innings_number=inn_n).first()
        if not existing_innings:
            existing_innings = CricInnings(
                match_id=match.id,
                innings_number=inn_n,
                batting_team_id=batting_team.id,
                bowling_team_id=bowling_team.id,
            )
            db.session.add(existing_innings)

        existing_innings.total_runs = state.get("runs", 0)
        existing_innings.total_wickets = state.get("wickets", 0)
        existing_innings.total_overs = state.get("overs", 0.0)
        existing_innings.extras = _sum_extras(state)
        innings_saved += 1

        # Process batting stats
        for pos, batter in enumerate(batting_squad, 1):
            name = (batter.get("name") or "").strip()
            if not name:
                continue
            player = _match_player(name, batting_team.id, match.organization_id)
            if not player:
                unmatched.append(name)
            _upsert_batting_stat(match.id, player, name, inn_n, batter, pos)
            player_rows_saved += 1

        # Process bowling stats
        for bowler in bowling_squad:
            name = (bowler.get("name") or "").strip()
            if not name:
                continue
            player = _match_player(name, bowling_team.id, match.organization_id)
            if not player:
                unmatched.append(name)
            _upsert_bowling_stat(match.id, player, name, inn_n, bowler)
            player_rows_saved += 1

    db.session.commit()
    return {"innings_saved": innings_saved, "player_rows_saved": player_rows_saved, "unmatched_names": list(set(unmatched))}


def _match_player(name: str, team_id: str, org_id: str):
    normalized = name.lower().strip()
    players = CricPlayer.query.filter_by(team_id=team_id, organization_id=org_id).all()
    for p in players:
        if p.name.lower().strip() == normalized:
            return p
    return None


def _upsert_batting_stat(match_id, player, raw_name, innings_num, batter, position):
    player_id = player.id if player else None
    stat = None
    if player_id:
        stat = CricPlayerMatchStat.query.filter_by(match_id=match_id, player_id=player_id, innings_number=innings_num).first()
    if not stat:
        stat = CricPlayerMatchStat(match_id=match_id, player_id=player_id, raw_name=raw_name, innings_number=innings_num)
        db.session.add(stat)
    stat.runs_scored = batter.get("runs", 0)
    stat.balls_faced = batter.get("balls", 0)
    stat.fours = batter.get("fours", 0)
    stat.sixes = batter.get("sixes", 0)
    stat.dismissal_kind = batter.get("dismissal_kind") or (None if batter.get("status") == "not_out" else batter.get("status"))
    stat.batting_position = position


def _upsert_bowling_stat(match_id, player, raw_name, innings_num, bowler):
    player_id = player.id if player else None
    stat = None
    if player_id:
        stat = CricPlayerMatchStat.query.filter_by(match_id=match_id, player_id=player_id, innings_number=innings_num).first()
    if not stat:
        stat = CricPlayerMatchStat(match_id=match_id, player_id=player_id, raw_name=raw_name, innings_number=innings_num)
        db.session.add(stat)
    stat.overs_bowled = bowler.get("overs", 0) + bowler.get("balls", 0) / 10.0
    stat.runs_conceded = bowler.get("runs", 0)
    stat.wickets_taken = bowler.get("wickets", 0)
    stat.maidens = bowler.get("maidens", 0)
    stat.wides = bowler.get("wides", 0)
    stat.no_balls = bowler.get("no_balls", 0)


def _sum_extras(state: dict) -> int:
    history = state.get("history", [])
    extras = 0
    for ball in history[:state.get("history_idx", len(history))]:
        t = ball.get("type", "")
        if t in ("wide", "no_ball", "bye", "leg_bye"):
            extras += ball.get("runs", 1)
    return extras


# ---------------------------------------------------------------------------
# Points Table & Leaderboard Helpers
# ---------------------------------------------------------------------------

def _calculate_points_table(tournament: CricTournament) -> list[dict]:
    rows: dict[str, dict] = {}
    for team in tournament.teams:
        rows[team.id] = {
            "team": team,
            "played": 0, "won": 0, "lost": 0, "nr": 0,
            "points": 0,
            "runs_for": 0, "overs_for": 0.0,
            "runs_against": 0, "overs_against": 0.0,
            "nrr": 0.0,
        }

    for m in tournament.matches:
        if m.status not in ("completed", "no_result", "abandoned"):
            continue
        t1_id = m.team1_id
        t2_id = m.team2_id
        for tid in (t1_id, t2_id):
            if tid not in rows:
                continue
            rows[tid]["played"] += 1

        if m.status in ("no_result", "abandoned"):
            for tid in (t1_id, t2_id):
                if tid in rows:
                    rows[tid]["nr"] += 1
                    rows[tid]["points"] += 1
            continue

        # Use innings data for NRR
        for inn in m.innings_list:
            bid = inn.batting_team_id
            fid = inn.bowling_team_id
            if bid in rows:
                rows[bid]["runs_for"] += inn.total_runs
                rows[bid]["overs_for"] += _decimal_overs(inn.total_overs)
            if fid in rows:
                rows[fid]["runs_against"] += inn.total_runs
                rows[fid]["overs_against"] += _decimal_overs(inn.total_overs)

        if m.winner_id and m.winner_id in rows:
            loser_id = t2_id if m.winner_id == t1_id else t1_id
            rows[m.winner_id]["won"] += 1
            rows[m.winner_id]["points"] += 2
            if loser_id in rows:
                rows[loser_id]["lost"] += 1

    for row in rows.values():
        rf = row["runs_for"]
        of = row["overs_for"]
        ra = row["runs_against"]
        oa = row["overs_against"]
        row["nrr"] = round((rf / of if of else 0) - (ra / oa if oa else 0), 3)

    return sorted(rows.values(), key=lambda r: (-r["points"], -r["nrr"]))


def _decimal_overs(overs_float: float) -> float:
    whole = int(overs_float)
    balls = round((overs_float - whole) * 10)
    return whole + balls / 6.0


def _leaderboard(tournament: CricTournament):
    match_ids = [m.id for m in tournament.matches if m.status == "completed"]
    if not match_ids:
        return [], []

    stats = CricPlayerMatchStat.query.filter(CricPlayerMatchStat.match_id.in_(match_ids)).all()

    batter_totals: dict[str, dict] = {}
    bowler_totals: dict[str, dict] = {}

    for s in stats:
        name = s.player.name if s.player else (s.raw_name or "Unknown")
        pid = s.player_id or s.raw_name or "?"

        if s.runs_scored is not None:
            if pid not in batter_totals:
                batter_totals[pid] = {"name": name, "matches": 0, "runs": 0, "balls": 0, "highest": 0, "fifties": 0, "hundreds": 0}
            bt = batter_totals[pid]
            bt["matches"] += 1
            bt["runs"] += s.runs_scored
            bt["balls"] += s.balls_faced or 0
            bt["highest"] = max(bt["highest"], s.runs_scored)
            if s.runs_scored >= 100:
                bt["hundreds"] += 1
            elif s.runs_scored >= 50:
                bt["fifties"] += 1

        if s.overs_bowled is not None:
            if pid not in bowler_totals:
                bowler_totals[pid] = {"name": name, "matches": 0, "overs": 0.0, "runs": 0, "wickets": 0}
            bo = bowler_totals[pid]
            bo["matches"] += 1
            bo["overs"] += s.overs_bowled or 0
            bo["runs"] += s.runs_conceded or 0
            bo["wickets"] += s.wickets_taken or 0

    for bt in batter_totals.values():
        bt["avg"] = round(bt["runs"] / bt["matches"], 2) if bt["matches"] else 0
        bt["sr"] = round(bt["runs"] / bt["balls"] * 100, 2) if bt["balls"] else 0

    for bo in bowler_totals.values():
        bo["economy"] = round(bo["runs"] / bo["overs"], 2) if bo["overs"] else 0
        bo["avg"] = round(bo["runs"] / bo["wickets"], 2) if bo["wickets"] else 0

    top_batters = sorted(batter_totals.values(), key=lambda x: -x["runs"])[:20]
    top_bowlers = sorted(bowler_totals.values(), key=lambda x: -x["wickets"])[:20]
    return top_batters, top_bowlers


def _aggregate_batting(stats):
    runs = sum(s.runs_scored or 0 for s in stats if s.runs_scored is not None)
    balls = sum(s.balls_faced or 0 for s in stats if s.runs_scored is not None)
    innings = sum(1 for s in stats if s.runs_scored is not None)
    outs = sum(1 for s in stats if s.dismissal_kind and s.dismissal_kind not in ("not_out", "retired_hurt"))
    highest = max((s.runs_scored or 0 for s in stats if s.runs_scored is not None), default=0)
    return {
        "matches": innings,
        "runs": runs,
        "avg": round(runs / outs, 2) if outs else runs,
        "sr": round(runs / balls * 100, 2) if balls else 0,
        "highest": highest,
        "fifties": sum(1 for s in stats if (s.runs_scored or 0) >= 50 and (s.runs_scored or 0) < 100),
        "hundreds": sum(1 for s in stats if (s.runs_scored or 0) >= 100),
    }


def _aggregate_bowling(stats):
    bowling = [s for s in stats if s.overs_bowled is not None]
    overs = sum(s.overs_bowled or 0 for s in bowling)
    runs = sum(s.runs_conceded or 0 for s in bowling)
    wickets = sum(s.wickets_taken or 0 for s in bowling)
    return {
        "matches": len(bowling),
        "overs": round(overs, 1),
        "wickets": wickets,
        "runs": runs,
        "economy": round(runs / overs, 2) if overs else 0,
        "avg": round(runs / wickets, 2) if wickets else 0,
    }


# ---------------------------------------------------------------------------
# Date parsing helpers
# ---------------------------------------------------------------------------

def _parse_date(s: str):
    s = (s or "").strip()
    if not s:
        return None
    try:
        from datetime import date
        return date.fromisoformat(s)
    except ValueError:
        return None


def _parse_datetime(s: str):
    s = (s or "").strip()
    if not s:
        return None
    for fmt in ("%Y-%m-%dT%H:%M", "%Y-%m-%d %H:%M", "%Y-%m-%d"):
        try:
            return datetime.strptime(s, fmt)
        except ValueError:
            continue
    return None
