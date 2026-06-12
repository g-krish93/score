# Cricket Live Score Overlay — Technical Documentation

> **App:** `score`  
> **Stack:** Python 3 · Flask 3 · Gunicorn · AWS EC2 or OCI Compute · Terraform  
> **Last reviewed:** May 2026

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Project Structure](#project-structure)
4. [Local Development](#local-development)
5. [Deployment](#deployment)
6. [API Reference](#api-reference)
7. [State Model](#state-model)
8. [Multi-Match Support](#multi-match-support)
9. [Scoring Modes](#scoring-modes)
10. [Undo / Redo](#undo--redo)
11. [Tech Debts](#tech-debts)
12. [Performance Issues](#performance-issues)
13. [Security Concerns](#security-concerns)
14. [Runbook — Match Day Operations](#runbook--match-day-operations)

---

## Overview

The Cricket Live Score Overlay is a lightweight Flask web application that provides real-time cricket scorekeeping for live video streaming. A scorer on one phone updates the score through a web-based input UI; the stream host's device shows the `/` overlay, which OBS/Larix picks up as a Browser Source that sits on top of the video feed.

**Key capabilities:**

- Ball-by-ball or over-only scoring
- Batting and bowling scorecards with player stats
- Second-innings chase display (RRR, runs needed, balls remaining)
- Undo/redo (up to 12 actions deep)
- Multiple simultaneous match scopes
- Auto-save after every ball; manual save/restore
- Three overlay themes (Classic, Neon, Minimal) and adjustable scale

---

## Architecture

```
Phone 1 (Larix / OBS)
  └─ Browser Source → http://EC2_IP:5000/          (overlay)

Phone 2 / Laptop
  └─ Browser → http://EC2_IP:5000/input            (scorer input UI)

EC2 (eu-west-2, t3.micro)
  └─ gunicorn -w 1  →  server/app.py  (Flask)
       ├─ GET  /score          poll every ~2 s (overlay JS)
       ├─ POST /ball           record a delivery
       ├─ GET  /               render overlay.html
       └─ GET  /input          render input.html
  └─ /tmp/cricket_state.json   (flat JSON persistence)
```

The server runs as a single Gunicorn worker process, protected by a Python `threading.Lock` that serialises all state mutations. The overlay HTML page polls `/score` on a short interval and re-renders a fixed-position `<div>` at the bottom of the screen.

---

## Project Structure

```
score/
├── server/
│   ├── __init__.py
│   └── app.py              # All Flask routes and state logic
├── templates/
│   ├── overlay.html         # OBS Browser Source page
│   └── input.html           # Scorer control panel
├── static/
│   └── style.css
├── infra/
│   ├── main.tf              # Terraform: EC2 + EIP + security group
│   ├── variables.tf
│   ├── terraform.tfvars     # ⚠ Not committed — fill locally
│   └── user_data.sh         # Bootstrap script (git clone + systemd)
├── requirements.txt         # flask, flask-cors, python-dotenv, gunicorn
├── .env.example             # PORT=5000
├── validate_smoke.py        # In-process smoke test suite
└── score-key.pem            # ⚠ EC2 private key — see Security section
```

---

## Local Development

```bash
# 1. Clone and create a virtual environment
git clone <repo-url> score
cd score
python -m venv .venv
source .venv/bin/activate          # Windows: .venv\Scripts\activate

# 2. Install dependencies
pip install -r requirements.txt

# 3. Copy env file
cp .env.example .env               # Edit PORT if needed

# 4. Run the development server
flask --app server.app run --debug --port 5000

# 5. Open http://localhost:5000/input  (scorer UI)
#    Open http://localhost:5000/       (overlay preview)

# 6. Run smoke tests
python validate_smoke.py
```

---

## Deployment

### Prerequisites

- AWS account with EC2 key pair created in `eu-west-2`
- Terraform ≥ 1.5 installed
- GitHub repository with the app code

### First-time deploy

```bash
cd infra

# 1. Fill in terraform.tfvars (never commit this file)
cat > terraform.tfvars <<EOF
key_name    = "your-key-pair-name"
github_repo = "https://github.com/YOUR_ORG/score.git"
EOF

# 2. Initialise and apply
terraform init
terraform apply

# 3. Note the output public IP
# Overlay URL:  http://<PUBLIC_IP>:5000
# Input UI:     http://<PUBLIC_IP>:5000/input

# 4. Upload the .env file
scp -i score-key.pem .env ec2-user@<PUBLIC_IP>:/app/.env

# 5. Restart the service
ssh -i score-key.pem ec2-user@<PUBLIC_IP> \
  "sudo systemctl restart cricket"

# 6. Health check
curl http://<PUBLIC_IP>:5000/health
```

### EC2 service management

```bash
sudo systemctl status cricket      # Check service status
sudo systemctl restart cricket     # Restart after config changes
sudo journalctl -u cricket -f      # Tail live logs
sudo systemctl stop cricket        # Stop the service
```

The app is managed by `systemd` and configured to restart automatically on failure (5-second back-off).

### Oracle Cloud Infrastructure (pilot migration)

An OCI pilot stack (VCN, Oracle Linux 9, reserved public IP, cloud-init bootstrap) lives under `infra/oci/environments/pilot/`. Migration inventories and runbooks are under `infra/migration-aws-oci/` (for example `INVENTORY.md`, `OCI_LANDING_ZONE.md`, `RUNBOOK_DNS_TLS.md`). GitHub Actions: `.github/workflows/deploy-oci.yml` targets user `opc` and secrets `OCI_COMPUTE_HOST` / `OCI_SSH_KEY`.

---

## API Reference

All endpoints return JSON. POST endpoints accept `Content-Type: application/json`. For scoped matches, append `?match=<match-id>` to any request.

### Match Setup

#### `POST /setup`

Initialise a new match. Resets all state.

**Request:**
```json
{
  "team1": "India",
  "team2": "Australia",
  "toss_winner": "India",
  "toss_decision": "bat",
  "total_overs": 20,
  "scoring_mode": "ball_by_ball",
  "theme": "classic",
  "team1_color": "#2dd4bf",
  "team2_color": "#f59e0b",
  "batting_squad": ["Rohit", "Virat", "SKY", ...],
  "bowling_squad": ["Starc", "Hazlewood", "Cummins", ...]
}
```

| Field | Type | Default | Notes |
|---|---|---|---|
| `team1` / `team2` | string | — | Team names |
| `toss_winner` | string | `team1` | Must match one of the team names |
| `toss_decision` | `"bat"` \| `"bowl"` | `"bat"` | — |
| `total_overs` | integer | 20 | — |
| `scoring_mode` | `"ball_by_ball"` \| `"over_only"` | `"ball_by_ball"` | See [Scoring Modes](#scoring-modes) |
| `theme` | `"classic"` \| `"neon"` \| `"minimal"` | `"classic"` | Overlay visual theme |
| `team1_color` / `team2_color` | hex string | `#2dd4bf` / `#f59e0b` | — |
| `batting_squad` | string[] | `[]` | Player names |
| `bowling_squad` | string[] | `[]` | Player names |

**Response:** full state object (see [State Model](#state-model)).

---

#### `POST /reset-match`

Wipes all state back to blank. No request body needed.

---

### Scoring

#### `POST /ball`

Record a single delivery. Only valid in `ball_by_ball` mode.

**Request:**
```json
{
  "type": "4",
  "runs": 0,
  "dismissal_kind": "",
  "out_batter": "striker"
}
```

| Field | Type | Values | Notes |
|---|---|---|---|
| `type` | string | `.` `1` `2` `3` `4` `6` `W` `Wd` `Nb` `Bye` `Lb` | Ball outcome |
| `runs` | integer | ≥ 0 | Extra runs for wides/no-balls/byes/leg-byes |
| `dismissal_kind` | string | `run_out` `stumped` | Used with `Wd` or `Nb` deliveries that also result in a wicket |
| `out_batter` | string | `striker` `non_striker` | Which batter is out (default `striker`) |

**Response:** updated state object.

**Error responses:**

| Code | Reason |
|---|---|
| 400 | Invalid ball type |
| 400 | `out_batter` not `striker` or `non_striker` |
| 400 | Innings already complete |
| 400 | `ball-by-ball` disabled (over-only mode active) |

---

#### `POST /over-update`

Record an entire over's result. Only valid in `over_only` mode.

```json
{ "runs": 12, "wickets": 1 }
```

---

#### `POST /end-over`

Manually close the current over (swaps striker/non-striker, increments over count). Use when the last ball was a wide or no-ball that didn't trigger auto end-of-over.

---

#### `POST /start-second-innings`

Transition to the second innings. Swaps batting/bowling squads. The target is automatically set to `first_innings_runs + 1`.

```json
{
  "batting_team": "Australia",
  "batting_squad": ["Warner", "Finch", ...],
  "bowling_squad": ["Rohit", "Virat", ...]
}
```

If squads are omitted, the previous innings' squads are swapped automatically.

---

### Player Management

#### `POST /set-players`

Set the current striker, non-striker, and/or bowler by name.

```json
{
  "striker": "Rohit",
  "non_striker": "Virat",
  "current_bowler": "Starc"
}
```

#### `POST /retire-batter`

```json
{ "batter": "striker", "type": "hurt" }
```

`type` must be `"hurt"` (retired hurt) or `"unhurt"` (retired out).

#### `POST /record-dismissal`

Record a dismissal not handled by `/ball` (e.g. run-out, obstructing the field).

```json
{
  "kind": "run_out",
  "batter": "non_striker",
  "legal_delivery": true,
  "add_ball": true,
  "credited_to_bowler": false
}
```

Valid `kind` values: `run_out`, `stumped`, `hit_wicket`, `obstructing_field`, `timed_out`, `handled_ball`.

---

### Penalties and Events

#### `POST /penalty-runs`

```json
{ "runs": 5, "side": "batting", "reason": "ball hitting helmet" }
```

`side` must be `"batting"` or `"fielding"`. Penalty runs to the fielding side are logged but do not add to the batting team's score in the current implementation.

#### `POST /dead-ball`

Log a dead ball event (no score change).

```json
{ "note": "crowd interference" }
```

---

### Editing and Corrections

#### `POST /edit`

Directly overwrite score fields. Use sparingly for corrections.

```json
{ "runs": 50, "wickets": 3, "overs": 9, "balls": 5, "extras": 6 }
```

#### `POST /undo` / `POST /redo`

Step back or forward through the last 12 recorded ball actions.

---

### Overlay Controls

#### `POST /set-panel`

Switch the active display panel on the overlay.

```json
{ "panel": "score" }
```

Valid panels: `score`, `batting`, `bowling`, `chase`, `fullscore`.

#### `POST /set-overlay-density`

```json
{ "density": "compact" }
```

`"compact"` or `"expanded"` (default).

#### `POST /set-overlay-scale`

```json
{ "scale": 1.35 }
```

Clamped between `0.8` and `1.8`.

---

### Persistence

#### `POST /save`

Force a manual save of the current state to disk.

#### `POST /restore`

Reload the last saved state from disk.

---

### Utility

#### `GET /score`

Returns the full current state with derived values (CRR, RRR, match result, etc.).

#### `GET /health`

```json
{ "status": "ok", "innings": 1, "match_started": true }
```

---

## State Model

The full state object returned by most endpoints:

```json
{
  "team1": "India",
  "team2": "Australia",
  "team1_color": "#2dd4bf",
  "team2_color": "#f59e0b",
  "theme": "classic",
  "overlay_density": "expanded",
  "overlay_scale": 1.0,
  "toss_winner": "India",
  "toss_decision": "bat",
  "innings": 1,
  "batting_team": "India",
  "bowling_team": "Australia",
  "total_overs": 20,
  "target": null,
  "scoring_mode": "ball_by_ball",
  "runs": 45,
  "wickets": 2,
  "overs": 7,
  "balls": 3,
  "extras": 4,
  "penalty_runs": 0,
  "current_over": ["1", "4", "Wd", "."],
  "batting_squad": [
    { "name": "Rohit", "runs": 30, "balls": 22, "status": "batting" },
    { "name": "Virat", "runs": 12, "balls": 10, "status": "batting" },
    ...
  ],
  "bowling_squad": [
    { "name": "Starc", "overs": 3, "balls": 0, "runs": 18, "wickets": 1, "maidens": 0, "over_runs": 0 },
    ...
  ],
  "striker": "Rohit",
  "non_striker": "Virat",
  "current_bowler": "Starc",
  "active_panel": "score",
  "match_started": true,
  "match_ended": false,
  "event_log": ["Rohit retired hurt", "Penalty runs 5 to batting: ball hitting helmet"],

  // Derived fields (calculated on every response, not persisted):
  "crr": 6.0,
  "overs_display": "7.3",
  "rrr": null,
  "runs_needed": null,
  "balls_remaining": null,
  "match_complete": false,
  "match_result": null
}
```

### Batter status values

`"yet to bat"` → `"batting"` → `"out"` | `"retired hurt"` | `"retired out"`

---

## Multi-Match Support

Multiple matches can run simultaneously by routing through scoped URLs:

| Purpose | URL |
|---|---|
| Default overlay | `http://HOST:5000/` |
| Default input UI | `http://HOST:5000/input` |
| Scoped overlay | `http://HOST:5000/m/<match-id>` |
| Scoped input UI | `http://HOST:5000/m/<match-id>/input` |

Match IDs are slugified (lowercase, alphanumeric with hyphens). All API calls from a scoped input page automatically append `?match=<match-id>` to requests.

Match state is isolated in memory (`match_contexts` dict) and on disk (`/tmp/cricket_state_<match-id>.json`). The default match uses `/tmp/cricket_state.json`.

---

## Scoring Modes

### Ball by ball (default)

Every delivery is recorded individually via `POST /ball`. The server tracks ball count, auto-ends overs at 6 legal deliveries, and handles striker rotation.

### Over only

Only aggregate over results are submitted via `POST /over-update`. Individual ball details are unavailable. Useful when a detailed scorer is not available. Ball-by-ball endpoints return `400` when this mode is active.

---

## Undo / Redo

Before every ball, over-update, dismissal, penalty, or edit, a full deep copy of the state is pushed onto `action_history`. Undo pops from `action_history` and pushes to `redo_history`; redo does the reverse. The history is capped at 12 snapshots per match context to limit memory use.

---

## Tech Debts

The following items are known limitations that should be addressed before the app is used in higher-stakes production scenarios.

### TD-1 — State persisted to `/tmp` (ephemeral)

**Location:** `server/app.py` → `save_state()` / `state_path_for()`  
**Impact:** All match state is lost on EC2 instance reboot, instance replacement, or OS cleanup of `/tmp`. Recovery requires the `/restore` endpoint, but only if the file still exists.  
**Recommendation:** Persist state to a path under `/app/data/` or an external store (S3, DynamoDB). Add a startup check that restores automatically.

---

### TD-2 — Global mutable state with module-level variables

**Location:** `server/app.py` (top-level `state`, `last_action`, `action_history`, `redo_history`, `current_match_id`)  
**Impact:** The activate/persist context pattern mutates globals, which is fragile and non-idiomatic. Adding a second Gunicorn worker would create separate process states that diverge immediately.  
**Recommendation:** Encapsulate match state in a class or use an external store that all workers share.

---

### TD-3 — `match_contexts` dict grows unbounded

**Location:** `server/app.py` → `match_contexts = {}`  
**Impact:** Every unique match ID ever requested creates a new entry in memory. On a long-running server with many ad-hoc match IDs (e.g. typos), memory grows without bound.  
**Recommendation:** Apply an LRU eviction policy (e.g. `functools.lru_cache` on context loading, or a max-size OrderedDict).

---

### TD-4 — `event_log` silently truncates at 50 entries

**Location:** `server/app.py` → `log_event()`  
**Impact:** Events older than the last 50 are permanently discarded. For long matches, early significant events (penalties, retirements) are lost.  
**Recommendation:** Write events to append-only structured logs (file or external service) rather than keeping them in-state.

---

### TD-5 — `penalty_runs` to the fielding side are not applied

**Location:** `server/app.py` → `penalty_runs()` route  
**Impact:** The code logs the event but only adds runs when `side == "batting"`. Fielding-side penalties (5 runs awarded to the batting team by law) are silently ignored.  
**Recommendation:** When `side == "fielding"`, add 5 runs to `state["runs"]` and `state["extras"]`.

---

### TD-6 — Undo history capped at 12 with deep copies

**Location:** `server/app.py` → `push_history()`  
**Impact:** Only the last 12 actions are undoable, which can be insufficient in over-only mode. Each snapshot is a full `copy.deepcopy` of the entire state including both full squads — memory spikes with large squads.  
**Recommendation:** Consider structural sharing (only copy the delta) or store diffs rather than full snapshots.

---

### TD-7 — No test coverage beyond smoke script

**Location:** `validate_smoke.py`  
**Impact:** The smoke script covers happy paths. Edge cases (10-wicket innings completion, DLS target, concurrent requests, invalid JSON bodies) are not tested. Regressions in edge-case ball logic will likely go undetected.  
**Recommendation:** Add a pytest suite with parametrised ball-type tests, innings-transition tests, and concurrent-request tests using Flask's `test_client`.

---

### TD-8 — `terraform.tfstate` committed to version control

**Location:** `infra/terraform.tfstate`  
**Impact:** Terraform state files contain resource metadata (instance IDs, IP addresses) and can contain sensitive values. Committing them to a shared repository is a best-practice violation.  
**Recommendation:** Move to a remote backend (e.g. S3 + DynamoDB state lock). Add `*.tfstate` and `*.tfstate.backup` to `.gitignore`.

---

## Performance Issues

### P-1 — Single Gunicorn worker (`-w 1`)

**Location:** `infra/user_data.sh`  
```bash
ExecStart=/usr/local/bin/gunicorn -w 1 -b 0.0.0.0:5000 server.app:app
```
**Impact:** All HTTP requests are serialised through one OS process. If the overlay is polling every 2 seconds and a scorer submits a ball simultaneously, one request blocks. Any slow request (e.g. a large state serialise) delays all others.  
**Note:** Increasing workers is currently unsafe because state is process-local. Fix TD-2 first, then scale workers.

---

### P-2 — Overlay polls `/score` on a fixed short interval (no WebSocket / SSE)

**Location:** `templates/overlay.html` (JavaScript polling loop)  
**Impact:** Every connected overlay device issues repeated GET requests even when the score hasn't changed. With multiple overlays open (multi-camera streams), request volume multiplies. CPU and network load scale linearly with viewer count.  
**Recommendation:** Replace polling with Server-Sent Events (SSE) or WebSockets so the server pushes updates only when state changes.

---

### P-3 — `with_calculated_values` performs a deep copy on every `/score` GET

**Location:** `server/app.py` → `with_calculated_values()`  
**Impact:** Every polling request copies the entire state dict including both full squads. For 22-player squads, this is done dozens of times per minute.  
**Recommendation:** Cache the computed snapshot and invalidate only when state changes, or compute derived values client-side.

---

### P-4 — `state_lock` is a single global lock across all matches

**Location:** `server/app.py` — `threading.Lock()`  
**Impact:** Requests for different match IDs (`/m/match-a/` and `/m/match-b/`) still contend on the same lock. As the number of simultaneous matches grows, all requests serialise.  
**Recommendation:** Use a per-match lock stored alongside each match's context in `match_contexts`.

---

### P-5 — State is serialised to disk on every ball

**Location:** `server/app.py` → `ball()` route calls `save_state()` synchronously  
**Impact:** Every `/ball` POST does a synchronous file write inside the request/response cycle. On slow storage (e.g. network-mounted EFS), this adds latency directly to the scorer's experience.  
**Recommendation:** Write to disk asynchronously in a background thread, or batch writes every N balls, accepting a small window of potential data loss.

---

## Security Concerns

### S-1 — EC2 private key committed to the repository

**Location:** `score-key.pem`  
**Impact:** Anyone with read access to the repository can SSH into the EC2 instance as `ec2-user`. This is a critical security issue.  
**Recommendation:** Delete the key from git history (`git filter-repo` or BFG), rotate the EC2 key pair in the AWS console, and add `*.pem` to `.gitignore`.

---

### S-2 — No authentication on any endpoint

**Location:** All routes in `server/app.py`  
**Impact:** Anyone who can reach port 5000 can reset a match, edit scores, or corrupt state. There is no session, token, or IP allowlist.  
**Recommendation:** Add a simple shared secret (passed as a header or query parameter) for all mutating endpoints (`POST`), or restrict the security group to known IP ranges.

---

### S-3 — CORS wildcard

**Location:** `server/app.py`
```python
CORS(app, resources={r"/*": {"origins": "*"}})
```
**Impact:** Any webpage on the internet can make cross-origin requests to the server, including state-mutating POSTs if the user is on the same network.  
**Recommendation:** Restrict `origins` to the specific hostnames that need access, or remove CORS entirely if all clients are same-origin.

---

### S-4 — HTTP only (no TLS)

**Location:** `infra/main.tf` — port 5000 with no HTTPS  
**Impact:** Score updates and overlay payloads are sent in plaintext over the network. On public Wi-Fi (common at sports venues), traffic is visible.  
**Recommendation:** Terminate TLS at a load balancer or with Nginx + Let's Encrypt, and redirect HTTP to HTTPS.

---

### S-5 — SSH port 22 open to `0.0.0.0/0`

**Location:** `infra/main.tf` — `aws_security_group`  
**Impact:** The EC2 instance is exposed to brute-force SSH attacks from the entire internet.  
**Recommendation:** Restrict the SSH ingress rule to specific IP CIDRs, or use AWS Systems Manager Session Manager instead of direct SSH.

---

## Runbook — Match Day Operations

### Before the match

1. Verify the service is running: `curl http://PUBLIC_IP:5000/health`
2. Open the input UI at `/input` or `/m/<match-id>/input`
3. Fill in team names, squad lists, toss result, and overs
4. Click **START MATCH**
5. Add the overlay URL (`/` or `/m/<match-id>`) as a Browser Source in OBS/Larix

### During the match

- Set striker, non-striker, and bowler using the player dropdowns before the first ball
- Record each delivery with the ball buttons (`.`, `1`, `2`, `3`, `4`, `6`, `W`, `Wd`, `Nb`, `Bye`, `Lb`)
- Use **UNDO** immediately for mis-clicks; redo is available if you undo too many steps
- Switch overlay panel via the panel buttons (Score / Batting / Bowling / Chase)
- At innings break: click **START 2ND INNINGS** and confirm squads

### After the match

- Use **RESET MATCH** to clear state for the next fixture
- The state file at `/tmp/cricket_state.json` (or scoped variant) persists until a reset or server restart

### Recovering from a crash

If the server restarts mid-match, the state file in `/tmp` may still exist. Hit `POST /restore` or use the **Restore** button in the input UI to reload the last saved state.

> **Warning:** `/tmp` is cleared on full instance replacement or OS reboot. There is no cross-reboot durability guarantee (see TD-1).
