"""CLI: print JSON snapshot for a Play-Cricket match URL.

Run from repo root: python -m server.scrape_cli \"https://...\"
"""
import json
import sys

from .play_cricket_scraper import scrape_match


def main() -> int:
    if len(sys.argv) < 2:
        print('Usage: python -m server.scrape_cli "<play-cricket-url>"', file=sys.stderr)
        return 1
    try:
        print(json.dumps(scrape_match(sys.argv[1]), indent=2))
    except Exception as exc:
        print(json.dumps({"error": str(exc)}), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
