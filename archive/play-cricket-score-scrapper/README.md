# play-cricket-score-scrapper

Self-hosted scraper utility to extract scoreboard summary from a Play-Cricket match page and expose it as JSON.

## Scope and plan

This project is intentionally separate from your `score` app. It is structured in phases:

1. **Phase 1 (done):** fetch page + parse summary scoreboard + expose REST endpoint.
2. **Phase 2 (next):** improve parser robustness with selector-first strategy and fallback regexes.
3. **Phase 3 (next):** polling mode + cache + retry strategy for near-live dashboards.
4. **Phase 4 (next):** adapter output that matches your in-house overlay schema exactly.

## Why this is useful

- Quickly test whether Play-Cricket pages can be consumed programmatically.
- Generate JSON snapshots for your internal stream overlay pipeline.
- Keep this service isolated so it can be replaced if Play-Cricket markup changes.

## Limitations

- Play-Cricket content can be dynamic and markup can change.
- This parser is **best-effort** and focused on score summary lines.
- For reliable live scoring, your own scorer backend should stay primary.

## Setup

```bash
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
```

## CLI usage

```bash
python cli.py "https://bmacc.play-cricket.com/website/results/7681278"
```

## API usage

Run server:

```bash
python app.py
```

Health:

```bash
GET http://localhost:8080/health
```

Scrape:

```bash
GET "http://localhost:8080/scrape?url=https://bmacc.play-cricket.com/website/results/7681278"
```

Near-live polled JSON (with stale detection):

```bash
GET "http://localhost:8080/live?url=https://bmacc.play-cricket.com/website/results/7681278&interval=8&stale_after=45"
```

- `interval`: minimum seconds between source fetches
- `stale_after`: mark stale if no successful fetch for this duration
- optional `store=1`: persist the fetched payload to local JSON file
- optional `push=1&push_url=https://your-aws-flask/ingest`: push payload to your Flask app

Prism browser-source overlay URL:

```bash
http://YOUR_SERVER:8080/overlay?url=https://bmacc.play-cricket.com/website/results/7681278
```

Use this `/overlay` URL directly in Prism as a browser source.

## Store JSON and push to AWS Flask

One-shot sync endpoint:

```bash
POST http://localhost:8080/sync
Content-Type: application/json

{
  "url": "https://bmacc.play-cricket.com/website/results/7681278",
  "interval": 8,
  "stale_after": 45,
  "push_url": "https://YOUR_EC2_PUBLIC:5000/relay/ingest?match=default",
  "push_token": "same-as-RELAY_INGEST_TOKEN-on-score-server-if-set"
}
```

What `/sync` does:

1. Scrapes latest score snapshot
2. Stores it as JSON in `./data/snapshots`
3. Pushes the same payload to your existing Flask endpoint

Environment-variable defaults (optional):

- `PUSH_TARGET_URL` default push endpoint
- `PUSH_AUTH_TOKEN` default bearer token
- `SNAPSHOT_DIR` local snapshot folder

Example response:

```json
{
  "source_url": "https://bmacc.play-cricket.com/website/results/7681278",
  "status": "BEXHILL STRIKERS WON BY 16 RUNS",
  "toss_note": "Won the toss and elected to bat",
  "innings_1": {
    "team": "Bexhill Strikers Twenty20 1st XI",
    "runs": 144,
    "wickets": 5,
    "overs": "20.0",
    "score": "144/5",
    "overs_display": "20.0"
  },
  "innings_2": {
    "team": "Brighton Malayalee Association CC Twenty20",
    "runs": 128,
    "wickets": 8,
    "overs": "20.0",
    "score": "128/8",
    "overs_display": "20.0"
  }
}
```
