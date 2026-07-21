import argparse
import json
import sys

from scraper import scrape_match


def main() -> int:
    parser = argparse.ArgumentParser(description="Scrape Play-Cricket match summary.")
    parser.add_argument("url", help="Play-Cricket match URL")
    args = parser.parse_args()

    try:
        data = scrape_match(args.url)
    except Exception as exc:
        print(json.dumps({"error": str(exc)}), file=sys.stderr)
        return 1

    print(json.dumps(data, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
