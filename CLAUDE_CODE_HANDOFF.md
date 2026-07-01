# BMACC Cricket Streaming — Scoreboard Overlay
## Complete Claude Code Handoff Document

---

## 1. PROJECT CONTEXT

Brighton Malayalee Association CC (BMACC) streams live cricket matches on YouTube.
The goal is a **scoreboard overlay** that sits on top of the camera feed inside their
custom Android/iOS streaming app, auto-populated from Play Cricket's live match data.

### Architecture
```
Play Cricket match page (scrape every 20s)
        ↓
  Python scraper on AWS EC2
        ↓
  Flask /score JSON endpoint
        ↓
  /overlay HTML page (transparent, no controls)
        ↓
  Android/iOS streaming app loads /overlay as a WebView layer
        ↓
  Composited over camera feed → RTMP → YouTube/stream
```

### Key constraints
- Play Cricket updates roughly **once per completed over** (~4–5 mins), NOT ball by ball
- The overlay must handle **stale data gracefully** (amber pulse, last known score shown)
- Wickets appear within 1–2 mins of falling — overlay must **auto-detect and flash alert**
- Match URL format: `https://bmacc.play-cricket.com/website/results/{MATCH_ID}`
- Example live match: `https://bmacc.play-cricket.com/website/results/7344201`
- The app pulls the overlay as a **transparent WebView** composited over camera

---

## 2. DATA SOURCE — WHAT TO SCRAPE

### Primary: Play Cricket HTML page
URL: `https://bmacc.play-cricket.com/website/results/{MATCH_ID}`

The page has 4 tabs: Match Stream, Scorecard, Ball by Ball, Statistics.
**Scrape the Scorecard tab** — it has all data needed.

#### Fields confirmed scrapeable (verified from live match 7344201, 27 Jun 2026):

**Match level:**
| Field | Example value |
|---|---|
| home_team | "Brighton Malayalee Association CC" |
| away_team | "East Grinstead CC" |
| home_xi | "1st XI" |
| away_xi | "3rd XI" |
| status | "IN PROGRESS" |
| competition | "Sussex Cricket League - Division 8 Central" |
| date | "27 June 2026 @ 13:00" |
| toss | "East Grinstead CC won toss and elected to field" |

**Innings (1st innings confirmed live):**
| Field | Example value |
|---|---|
| batting_team | "Brighton Malayalee Association CC" |
| runs | 92 |
| wickets | 2 |
| overs | "11.4" |
| extras.total | 11 |
| extras.byes | 4 |
| extras.leg_byes | 1 |
| extras.no_balls | 1 |
| extras.wides | 5 |

**Batters table (from Scorecard tab):**
Each row: name, runs, balls, 4s, 6s, SR, dismissal text, status (batting/out/yet to bat)

Confirmed batters from match 7344201:
- A Clement — 38(32) c A Thilo b L Hunt — OUT
- I Ramu — 33(31) not out — BATTING
- A P R — 5(6) b L Hunt — OUT
- B Ramamoorthy — 5(2) not out — BATTING
- B Joseph, S Lakkakula, S Nair (WK), A Padmakumar Manju, S Sulfiker,
  R Thoppil Ramanan, G Annamalalachamy — yet to bat

**Extras line:** "11 ( b 4, lb 1, nb 1, w 5) TOTAL: 92 (11.4 Overs)"

**Fall of wickets:** "81-1 A Clement; 87-2 A P R;"

**Bowlers table:**
| Name | Overs | Maidens | Runs | Wickets | Wides | NoBalls | Econ |
|---|---|---|---|---|---|---|---|
| A Whyman | 5 | 0 | 32 | 0 | 3 | 0 | 6.40 |
| D Clarke | 4 | 0 | 44 | 0 | 2 | 1 | 11.00 |
| L Hunt | 1.4 | 1 | 5 | 2 | 0 | 0 | 3.00 |
| O Dawson | 1 | 0 | 6 | 0 | 0 | 0 | 6.00 |

**Scraper also derives:**
- striker: batting batter with most recent not-out status (first "batting" row)
- non_striker: second "batting" row
- current_bowler: last bowler in bowling table (most recent partial over)

### Fallback: ResultsVault API
Discovered via Chrome DevTools — this is what Play Cricket uses internally.
```
https://api.resultsvault.co.uk/rv/130000/matches/{MATCH_ID}/?apiid=1003&strmflg=3
```
Returns clean JSON with same fields. Use as fallback if HTML scrape fails.

---

## 3. SCRAPER — Python

### File: `scraper/play_cricket.py`

```python
# Poll interval: every 20 seconds
# URL: https://bmacc.play-cricket.com/website/results/{MATCH_ID}
# Headers: standard browser UA to avoid 403

# Parse flow:
# 1. GET match page HTML
# 2. Find the Scorecard tab content (may need JS rendering — check if data is in HTML or XHR)
# 3. Parse batting table → list of batter dicts
# 4. Parse bowling table → list of bowler dicts
# 5. Parse extras line → dict
# 6. Parse fall of wickets → list
# 7. Derive striker/non-striker from batting status
# 8. Derive current_bowler from last bowler with partial over
# 9. Return full match dict (schema below)

# Error handling:
# - 403/429 → log, return None, trigger ResultsVault fallback
# - Missing field → use None, never crash
# - Wrap all in try/except, log to scraper.log
```

### File: `scraper/resultsvault.py`
```python
# Fallback scraper
# URL: https://api.resultsvault.co.uk/rv/130000/matches/{MATCH_ID}/?apiid=1003&strmflg=3
# Returns JSON — map fields to same schema as play_cricket.py
```

### File: `scraper/score_engine.py`
```python
# Score engine logic:
# 1. Try play_cricket.py scraper
# 2. If None → try resultsvault.py
# 3. If both fail → return last cached result with stale=True
# 4. Cache in memory (global dict) — no database
# 5. APScheduler or threading.Timer calls this every 20s
# 6. Expose get_score() function for Flask to call
```

### JSON Schema — /score endpoint response
```json
{
  "match": {
    "date": "27 June 2026 @ 13:00",
    "competition": "Sussex Cricket League - Division 8 Central",
    "status": "IN PROGRESS",
    "toss": "East Grinstead CC won toss and elected to field"
  },
  "home_team": "Brighton Malayalee Association CC",
  "away_team": "East Grinstead CC",
  "home_xi": "1st XI",
  "away_xi": "3rd XI",
  "batting_team": "Brighton Malayalee Association CC",
  "target": null,
  "total_overs": 40,
  "innings": [
    {
      "number": 1,
      "batting_team": "Brighton Malayalee Association CC",
      "runs": 92,
      "wickets": 2,
      "overs": "11.4",
      "extras": { "total": 11, "byes": 4, "leg_byes": 1, "no_balls": 1, "wides": 5 },
      "batters": [
        {
          "name": "A Clement",
          "runs": 38, "balls": 32, "fours": 7, "sixes": 1,
          "sr": 118.75,
          "dismissal": "c A Thilo b L Hunt",
          "status": "out"
        },
        {
          "name": "I Ramu",
          "runs": 33, "balls": 31, "fours": 7, "sixes": 0,
          "sr": 106.45,
          "dismissal": "not out",
          "status": "batting"
        }
      ],
      "fall_of_wickets": [
        { "wicket": 1, "score": 81, "batter": "A Clement" },
        { "wicket": 2, "score": 87, "batter": "A P R" }
      ],
      "bowlers": [
        { "name": "L Hunt", "overs": "1.4", "maidens": 1, "runs": 5, "wickets": 2, "wides": 0, "no_balls": 0, "econ": 3.00 }
      ]
    }
  ],
  "striker":     { "name": "I Ramu",        "runs": 33, "balls": 31 },
  "non_striker": { "name": "B Ramamoorthy", "runs": 5,  "balls": 2  },
  "current_bowler": { "name": "L Hunt", "overs": "1.4", "wickets": 2, "runs": 5, "econ": 3.0 },
  "stale": false,
  "last_updated": "2026-06-27T13:04:49"
}
```

---

## 4. FLASK SERVER

### File: `server/app.py`

**Endpoints:**
```
GET /           → serves overlay/cricket_overlay.html (transparent overlay page)
GET /score      → returns current score JSON (from score_engine.get_score())
GET /health     → returns {"status": "ok", "uptime": <seconds>}
GET /sim        → returns scorecard_simulation.html (for testing)
```

**Config:**
- CORS: open for all origins (`*`) — the streaming app WebView needs this
- Port: 5000 (or 80 if running as root)
- Single gunicorn worker (free tier EC2, t2.micro)
- Match ID configurable via env var: `MATCH_ID=7344201`

---

## 5. OVERLAY — THE MAIN DELIVERABLE

### File: `overlay/cricket_overlay.html`

This is the transparent HTML page the streaming app loads as a WebView.
**Background must be fully transparent** — `body { background: transparent; }`

### Layout: Bottom bar, full width, 72px height
```
┌────────────────────────────────────────────────────────────────────────┐
│ 2px accent line (green gradient)                                        │
├──────────────────┬──────────────────────────────────────┬──────────────┤
│  SCORE BLOCK     │  ROTATING PANEL (centre)             │  RIGHT BLOCK │
│  (permanent)     │                                      │  (codes)     │
│                  │                                      │              │
│  BMACC           │  ★ I Ramu  33(31)  | ⚡ L Hunt 2-5  │  LIVE ●      │
│  92/2  11.4 ov   │  B Ramamoorthy 5(2)   (1.4)  ECN3.0 │  BMACC       │
│  CRR 7.9         │                                      │  vs EGCC     │
│                  │                                      │  1st Inn     │
└──────────────────┴──────────────────────────────────────┴──────────────┘
```

### Panels — centre area rotates every 8 seconds:
1. **Batters panel** — striker (★) with runs(balls), non-striker
2. **Bowler panel** — current bowler name, wickets-runs, overs, economy

### Smart situation engine — 2nd innings only:
The panel rotation becomes context-aware based on match situation:

| Situation | Urgency | Panel frequency | Pill colour |
|---|---|---|---|
| 1st innings | — | Batters → Bowler | Green CRR |
| 2nd inn, RRR < CRR | ok | Batters → Chase → Bowler | Green |
| 2nd inn, RRR > CRR + 0.5 | warn | Batters → Chase → Chase → Bowler | Orange |
| 2nd inn, RRR > CRR + 2 | crit | Chase → Batters → Chase → Bowler | Red flash |
| Last 3 overs, any state | crit/warn | Balls countdown dominates | Red/Orange |
| Need ≤ 10 runs | ok | "X to WIN" message | Green |

**Chase panel pills:**
- NEEDED (runs), BALLS, RRR, CRR shown as small labelled blocks
- Message: "On track · RRR 6.2" / "Behind pace · RRR 8.1" / "35 from 30 balls"

### Wicket alert system:
- Fires automatically when `wickets` count increases after a poll
- Shows: `WICKET  I Ramu 33(31) b L Hunt`
- Dismissal formats supported:
  - `b L Hunt` — bowled
  - `c A Thilo b L Hunt` — caught
  - `lbw b L Hunt` — LBW
  - `run out (A Clement)` — run out
  - `st S Nair b I Ramu` — stumped
- Duration: 4 minutes (1 over worth of time) then auto-expires
- Multiple wickets same over: cycles every 30s between them
- Countdown bar drains across bottom of panel

### Extras milestone:
- Every 5 completed overs, shows extras panel for 6 seconds
- Format: `Extras  Total: 11  b4 lb1 nb1 w5`

### 2nd innings target bar:
- Thin orange strip above main panel (18px)
- Shows: `Target  183  ·  35 needed`

### Stale data indicator:
- Amber pulsing line at very top if no update for 60 seconds

### Themes (via URL param `?theme=navy|green|gold|red`):
| Theme | Background | Accent | Wicket |
|---|---|---|---|
| navy (default) | rgba(8,15,30,0.90) | #4ade80 | #f87171 |
| green | rgba(5,20,10,0.90) | #4ade80 | #fca5a5 |
| gold | rgba(15,10,2,0.92) | #fbbf24 | #f87171 |
| red | rgba(20,5,5,0.90) | #f87171 | #fbbf24 |

### Polling:
- Polls `GET /score` every 20 seconds
- `cache: 'no-store'` on fetch
- On failure: marks stale, keeps showing last known data

### Right block short codes:
- `toCode("Brighton Malayalee Association CC")` → "BMACC"
- `toCode("East Grinstead CC")` → "EGCC"
- Generated by taking first letter of each word

### Dev/test helpers (keep in prod, harmless):
```javascript
window.loadMock(2, 'crit')  // load 2nd innings critical chase
window.loadMock(2, 'warn')  // behind pace
window.loadMock(2, 'ok')    // comfortable
window.loadMock(1)           // 1st innings
window.testWicket()          // trigger wicket alert
```

---

## 6. FILE STRUCTURE

```
cricket-overlay/
├── scraper/
│   ├── play_cricket.py       # Primary HTML scraper
│   ├── resultsvault.py       # Fallback JSON API scraper
│   └── score_engine.py       # Orchestrator, caching, polling
├── overlay/
│   └── cricket_overlay.html  # THE OVERLAY — transparent bottom bar
├── static/
│   └── scorecard_simulation.html  # Dev tool — 4-over match simulator
├── server/
│   └── app.py                # Flask server
├── infra/
│   ├── main.tf               # Terraform EC2 t2.micro, eu-west-2
│   ├── variables.tf
│   └── user_data.sh          # EC2 bootstrap: install Python, clone repo, start service
├── .github/
│   └── workflows/
│       └── deploy.yml        # SSH deploy on git push to main
├── requirements.txt          # flask, gunicorn, requests, beautifulsoup4, lxml, apscheduler, flask-cors
├── .env.example              # MATCH_ID=7344201, PORT=5000
└── README.md
```

---

## 7. INFRASTRUCTURE

- **EC2**: t2.micro, Amazon Linux 2, eu-west-2 (London)
- **Security group**: port 22 (SSH), 5000 (overlay + API), 80 (optional redirect)
- **Elastic IP**: so URL never changes between reboots
- **Systemd service**: auto-starts gunicorn on EC2 boot
- **Deploy**: GitHub Actions → SSH → git pull → systemctl restart

### user_data.sh (EC2 bootstrap):
```bash
yum update -y
yum install -y python3 git
pip3 install flask gunicorn requests beautifulsoup4 lxml apscheduler flask-cors
git clone https://github.com/YOUR_REPO/cricket-overlay.git /opt/cricket-overlay
# create systemd service
# start on boot
```

---

## 8. MATCH DAY WORKFLOW

```
1. SSH into EC2 or update MATCH_ID env var with new match ID
   e.g. MATCH_ID=7344201

2. Restart service:
   systemctl restart cricket-overlay

3. Streaming app loads:
   http://{EC2_IP}:5000/overlay?theme=navy

4. Scraper auto-polls every 20s, overlay auto-updates
5. Wicket alerts fire automatically when score syncs
```

---

## 9. SIMULATION / TEST PAGE

File: `static/scorecard_simulation.html`

Standalone HTML page showing what the overlay displays at 4 snapshots:
- Over 1: 8/0 — basic score, no extras, no wickets
- Over 10: 62/1 — Clement out, Ramu & A P R batting
- Over 20: 118/3 — halfway, Ramamoorthy hitting
- Over 35 (2nd innings): 148/5 chasing 183 — CRITICAL mode, RRR 7.0

Each tab shows: full batting scorecard, bowling figures, fall of wickets,
and a preview of what the overlay bottom bar looks like at that moment.

---

## 10. COMPLETED DELIVERABLES (already built, attach these files)

1. `overlay/cricket_overlay.html` — full production overlay (28,883 bytes)
   - Bottom bar layout, dark navy theme
   - Batters/Bowler rotating panels
   - Smart chase engine (ok/warn/crit)
   - Wicket alert with countdown bar
   - Multi-wicket cycling
   - Extras milestone panel
   - Target bar (2nd innings)
   - Stale data indicator
   - 4 themes via URL param
   - Polls /score every 20s
   - Dev test helpers: loadMock(), testWicket()

2. `static/scorecard_simulation.html` — match simulator (23,674 bytes)
   - Dark themed standalone page
   - 4 tabs: Over 1, 10, 20, 35 (2nd innings)
   - Full batting + bowling tables per snapshot
   - Overlay preview bar per snapshot
   - Download and open in any browser

---

## 11. WHAT CLAUDE CODE NEEDS TO BUILD

The overlay HTML is done. What remains:

### Priority 1 — Scraper
- `scraper/play_cricket.py` — scrape `bmacc.play-cricket.com/website/results/{MATCH_ID}`
  - Parse Scorecard tab HTML (BeautifulSoup)
  - Extract all fields from Section 2 above
  - Handle 403, missing fields, partial data gracefully

- `scraper/resultsvault.py` — fallback
  - GET `https://api.resultsvault.co.uk/rv/130000/matches/{MATCH_ID}/?apiid=1003&strmflg=3`
  - Map response to same schema

- `scraper/score_engine.py` — orchestrator
  - Poll every 20s via background thread
  - Cache last good result
  - Set stale=True if both sources fail

### Priority 2 — Flask server
- `server/app.py`
  - Serve /overlay, /score, /health
  - CORS open
  - Env var: MATCH_ID

### Priority 3 — Infra
- `infra/main.tf` — EC2 t2.micro, eu-west-2, elastic IP
- `infra/user_data.sh` — bootstrap
- `.github/workflows/deploy.yml` — SSH deploy on push

---

## 12. NOTES FOR CLAUDE CODE

- The scraper CANNOT be tested from Claude's sandbox (play-cricket.com blocked)
  but WILL work from EC2 — robots.txt only blocks crawlers, not app servers
- The ResultsVault API is the more reliable source — try it first if Play Cricket HTML
  proves unreliable
- The overlay HTML files are complete — do NOT regenerate them, only integrate them
- MATCH_ID changes every fixture — make it easy to update (env var, not hardcoded)
- The streaming app is Android/iOS — the overlay WebView loads the /overlay URL
  over the local network or internet depending on setup
- Keep the Flask server simple — no database, no auth, single worker gunicorn
- Test the overlay in a browser first: open /overlay?theme=navy and run loadMock(2,'crit')
  in console to verify all panels work before connecting to live scraper

