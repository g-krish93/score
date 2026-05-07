# Cricket Live Score Overlay

## Architecture

Phone 1 (Larix) streams with overlay URL `http://EC2-IP:5000/stream` (or a scoped relay URL), Phone 3 updates score on `http://EC2-IP:5000/input`, and the Flask app serves `/score` for overlay polling while persisting backup under `STATE_DIR` (default `/tmp`).

## First-time setup

1. Create EC2 key pair in AWS console (eu-west-2).
2. Set `key_name` and `github_repo` variables.
3. Run `cd infra && terraform init && terraform apply`.
4. Copy `.env.example` to `.env` and keep `PORT=5000`.
5. Upload `.env` to EC2 `/app/.env`.
6. Restart service and test `curl http://PUBLIC_IP:5000/health`.
7. Add `EC2_HOST` and `EC2_KEY` secrets in GitHub repository.

## Match day

1. Open input UI on Phone 3 at `/input` (or scoped match page like `/m/bmacc-team1/input`).
2. Pick **Ball by ball** (full squads and player controls) or **Over by over** (teams, toss, overs only), then complete that screen and start.
3. Load overlay `/stream` (or scoped overlay `/m/<relay-slug>/stream`) in your stream Browser Source. Requests to `/m/<slug>` redirect to `/m/<slug>/stream`.
4. Score ball-by-ball and switch overlay panels as needed.

## Save/restore

- Auto-save runs after every `/ball`.
- Manual save: `POST /save`
- Manual restore: `POST /restore`

## CricRelay (Play-Cricket → same overlay URL)

Clubs can keep **manual scoring** in the input UI, or switch the overlay to follow a **Play-Cricket** match page fed by your `play-cricket-score-scrapper` worker.

- **Product / registration:** `/` — marketing, register, login. **Club setup** at `/dashboard` (squads + default Play-Cricket base). **Live relays** at `/dashboard/relays` (match id → scrape URL, Prism overlay, ingest, overlay layout). If your saved base contains `…/website/results`, scrape URLs are `…/website/results/<id>`; otherwise `…/match_details?id=<id>`. Set `SECRET_KEY` in production; optional `DATABASE_URL` for Postgres (otherwise SQLite under `STATE_DIR`).
- **Per-match operator UI:** `/cricrelay` or `/m/<match_id>/cricrelay` (paste full `match_details` URL, test relay).
- **Scorer controls:** Input page → **CricRelay (Play-Cricket)** card — choose *Manual* vs *Play-Cricket (URL + ingest)*, save the match URL.
- **Ingest endpoint (for the scraper):** `POST /relay/ingest?match=<match_id>` with JSON body from the scraper (`snapshot` + optional `stale`, etc.).
- **Optional auth:** set `RELAY_INGEST_TOKEN` on the server and send `Authorization: Bearer <token>` on ingest.
- **Prism / browser overlay:** use `/stream` or `/m/<match_id>/stream`. Legacy `/m/<match_id>` redirects with HTTP 301. The overlay reads `/score?match=…` and switches layout when relay mode is active.

## Multiple parallel matches

- Use dedicated URLs per match so states do not mix.
- Example Team 1:
  - Input: `/m/bmacc-team1/input`
  - Overlay: `/m/bmacc-team1/stream`
- Example Team 2:
  - Input: `/m/bmacc-team2/input`
  - Overlay: `/m/bmacc-team2/stream`
- All API calls from those pages automatically include the match scope.

## Deploying updates on EC2

The `cricket` systemd unit runs Gunicorn as **root** and uses packages under `/usr/local/lib/python3.9/site-packages`. If you run `pip3 install` as **ec2-user** without `sudo`, new wheels install under `~/.local` and the app fails to import (Gunicorn exits with status **3**). Use **`sudo pip3 install -r requirements.txt`** after `git pull` (the included GitHub Action does this).

## SSH cheat sheet

- `sudo systemctl status cricket`
- `sudo journalctl -u cricket -f`
- `sudo systemctl restart cricket`
