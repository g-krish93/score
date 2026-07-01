"""Unit tests for CricHeroes URL canonicalization and host allowlist."""
from __future__ import annotations

from server.models_cricrelay import (
    canonicalize_cricheroes_scrape_url,
    normalize_cricheroes_team_root,
)


def test_scorecard_url_accepted_and_canonicalized():
    url = (
        "https://cricheroes.com/scorecard/25869377/"
        "ahemdabad-bharwad-premier-league-abpl-season-1/"
        "rajal-11-telav-vs-ramadhani-xi-thori/live"
    )
    assert canonicalize_cricheroes_scrape_url(url) == (
        "https://cricheroes.com/scorecard/25869377/live"
    )


def test_individual_url_canonicalized_to_scorecard():
    url = "https://cricheroes.com/individual/25869377/some-slug/live"
    assert canonicalize_cricheroes_scrape_url(url) == (
        "https://cricheroes.com/scorecard/25869377/live"
    )


def test_cricheroes_in_domain_accepted():
    url = "https://cricheroes.in/scorecard/12345/some-league/match/live"
    assert canonicalize_cricheroes_scrape_url(url) == (
        "https://cricheroes.in/scorecard/12345/live"
    )


def test_substring_cricheroes_in_query_rejected():
    assert canonicalize_cricheroes_scrape_url("https://attacker.example/?note=cricheroes") == ""


def test_subdomain_trick_rejected():
    assert (
        canonicalize_cricheroes_scrape_url(
            "https://cricheroes.com.attacker.example/scorecard/123/live"
        )
        == ""
    )


def test_bare_match_id_rejected():
    assert canonicalize_cricheroes_scrape_url("25869377") == ""


def test_empty_input_rejected():
    assert canonicalize_cricheroes_scrape_url("") == ""
    assert canonicalize_cricheroes_scrape_url("   ") == ""


def test_www_subdomain_accepted():
    url = "https://www.cricheroes.com/scorecard/99/foo/live"
    assert canonicalize_cricheroes_scrape_url(url) == (
        "https://www.cricheroes.com/scorecard/99/live"
    )


def test_normalize_team_root_com_host():
    assert normalize_cricheroes_team_root("https://cricheroes.com/team/42/my-team") == (
        "https://cricheroes.com/team/42/my-team"
    )


def test_normalize_team_root_bare_id():
    assert normalize_cricheroes_team_root("42") == (
        "https://cricheroes.com/team/42/team"
    )


def test_normalize_team_root_rejects_foreign_host():
    assert normalize_cricheroes_team_root("https://evil.example/team/1/x") == ""
