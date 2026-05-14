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

### Play-Cricket relay (CricRelay)

1. Club logs in → **Live relays** → **create relay** with match id (overlay URL is ready).
2. Server **polls Play-Cricket automatically** (default every 10s) — **Prism** uses **`/m/<slug>/stream`** only.
3. Optional: manual scorer at **`/m/<slug>/input`** is blocked while relay mode is on.

### Manual scoring only

1. Open **`/input`** (or **`/m/<slug>/input`**).
2. Ball by ball or over-by-over setup → score as usual.
3. Overlay **`/stream`** or **`/m/<slug>/stream`** in Prism.

## Save/restore

- Auto-save runs after every `/ball`.
- Manual save: `POST /save`
- Manual restore: `POST /restore`

## CricRelay (Play-Cricket → same overlay URL)

Clubs can use **manual scoring** in the input UI, or follow a **Play-Cricket** page. The **Play-Cricket scraper is built into this app** (no separate GitHub project required).

- **Product / registration:** `/` — marketing, register, login. **Club setup** at `/dashboard` (squads + default Play-Cricket base). **Live relays** at `/dashboard/relays` (match id → scrape URL, Prism overlay, ingest, overlay layout). If your saved base contains `…/website/results`, scrape URLs are `…/website/results/<id>`; otherwise `…/match_details?id=<id>`. Set `SECRET_KEY` in production; optional `DATABASE_URL` for Postgres (otherwise SQLite under `STATE_DIR`).
- **Automatic relay polling (product default):** With **`RELAY_AUTO_POLL=1`** (default), the server starts a background thread that **every `RELAY_POLL_INTERVAL_SEC` seconds** (default **10**) loads every **`RelayMatch`** row from the database, scrapes **`full_scrape_url`**, and applies ingest to **`score_match_slug`**. Clubs only **create the relay on Live relays** — no cron, no manual `/relay-worker/live` on match day. Use **Gunicorn `-w 1`** so only one poller runs (multiple workers would duplicate polls unless you add an external queue later).

- **Built-in relay worker** (`/relay-worker/…`) remains available for debugging and one-off pushes.
- **Per-match operator UI:** `/cricrelay` or `/m/<match_id>/cricrelay`.
- **Scorer controls:** Input page → **CricRelay** card — Manual vs Play-Cricket; manual scoring is blocked while relay mode is active for that match slug.
- **Ingest endpoint:** `POST /relay/ingest?match=<slug>` with JSON (`snapshot` + optional `stale`, etc.). Same payload shape as `/relay-worker/live` returns.
- **External push only if needed:** `PUSH_TARGET_URL` / `PUSH_AUTH_TOKEN` for pushing to another host; otherwise use **`push_match`** for same-server ingest.
- **CLI (optional):** `python -m server.scrape_cli "<url>"` from repo root.
- **Prism overlay:** `/stream` or `/m/<slug>/stream`; `/score?match=…` polling unchanged.

**If the overlay shows “Awaiting data” for several minutes:** check `journalctl -u cricket` for `[relay_poller]` errors, confirm **`RELAY_AUTO_POLL=1`**, and verify the scrape URL opens in a browser. Legacy manual **`/relay-worker/live?push_match=`** is optional for debugging.

## Multiple parallel matches

- Use dedicated URLs per match so states do not mix.
- Example Team 1:
  - Input: `/m/bmacc-team1/input`
  - Overlay: `/m/bmacc-team1/stream`
- Example Team 2:
  - Input: `/m/bmacc-team2/input`
  - Overlay: `/m/bmacc-team2/stream`
- All API calls from those pages automatically include the match scope.

## EC2 rebuild: site not loading (cricrelay.co.uk)

After a **new instance** from Terraform, run **`deploy/bootstrap-ec2.sh`** on the server once (as root). It creates a minimal `/app/.env` if missing, installs **nginx**, copies **`deploy/nginx-cricrelay.conf`**, fixes **`EnvironmentFile=-/app/.env`**, and restarts **cricket** and **nginx**. Then merge your real secrets (e.g. `DATABASE_URL`, SMTP) into `/app/.env` and `sudo systemctl restart cricket`.

```bash
cd /app && sudo git pull
sudo bash deploy/bootstrap-ec2.sh
```

If you use **HTTPS on the origin** (not only Cloudflare-to-port-80), install certificates on the box (e.g. **certbot**) — the repo nginx sample only listens on **port 80**.

## Deploying updates on EC2

The `cricket` systemd unit runs Gunicorn as **root** and uses packages under `/usr/local/lib/python3.9/site-packages`. If you run `pip3 install` as **ec2-user** without `sudo`, new wheels install under `~/.local` and the app fails to import (Gunicorn exits with status **3**). Use **`sudo pip3 install -r requirements.txt`** after `git pull` (the included GitHub Action does this).

On **Amazon Linux**, `python3-requests` may be installed by **RPM**. Plain `pip install requests==…` can fail with *Cannot uninstall requests … RECORD file not found*. Use **`sudo pip3 install --ignore-installed -r requirements.txt`** so pip installs our pinned wheels without removing the RPM package (Python picks up the newer copy under `/usr/local/…`).

## SSH cheat sheet

- `sudo systemctl status cricket`
- `sudo journalctl -u cricket -f`
- `sudo systemctl restart cricket`
