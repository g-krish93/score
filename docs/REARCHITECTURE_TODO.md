# CricRelay Re-architecture — Remaining Work (agent / Cursor handoff)

This is a self-contained backlog for continuing the modular-monolith re-architecture.
Each task has **goal · steps · files · acceptance · safety**. Read the GUARDRAILS first.
Cross-reference `score_documentation.md` for the original tech-debt (TD-x) and security
(S-x) catalogue.

---

## What already exists (don't rebuild it)

- **`cricrelay_core/`** — framework-free, I/O-free event-sourced scoring engine.
  `StartInnings`/`Delivery` events → `reduce()` → `MatchState`; `derived()` for
  CRR/RRR/overs/result; `codec.py` for event⇄dict; `ports.EventStore` (the persistence
  seam). Pure, fully unit-tested (`cricrelay_core/tests/`). Import **only** from
  `cricrelay_core` (its `__init__`), never submodules.
- **`cricrelay_store/`** — `PostgresEventStore` (append-only JSONB log) + `RedisLiveState`
  (scoreboard cache + pub/sub) implementing the port. psycopg2 + redis.
- **`server/app.py`** — the Flask monolith. Additive integration already in place,
  **all default-OFF**:
  - `SCORING_DUAL_WRITE` → records each ball as a core event (`server/scoring_bridge.py`).
  - `SCORING_SHADOW_COMPARE` → on `/score`, folds the event log and logs legacy-vs-core
    diffs (`server/scoring_shadow.py`).
  - `server/migrate_sqlite_to_postgres.py` → SQLite→Postgres data migration (`--dry-run`).
  - Public live page `GET /live/<match_id>` (`templates/public_live_score.html`) + SSE
    `GET /live/<match_id>/events` gated by `PUBLIC_LIVE_SSE` (off → page polls).
  - `after_request` security headers.
- **Infra**: self-hosted Postgres 16 + Redis 7 on a dedicated EC2 box
  `i-072819b1b4163a51e`, **private IP `172.31.38.51`** (5432/6379, reachable only from the
  app SG). DB password in **SSM SecureString `/cricrelay/datastore/pg_password`**.
  Terraform state in S3 (`cricrelay-tfstate-973646734579`). Prod app = EC2 gunicorn
  `cricket` systemd service behind nginx; **deploy = push to `main`** →
  `.github/workflows/deploy.yml`.

---

## GUARDRAILS (must follow on every task)

1. **Additive / strangler.** Build new modules alongside the old; never change existing
   behaviour until a flag-gated cut-over that has been verified.
2. **Default-OFF flags** for anything that touches a live request path.
3. **Test every change** (pytest-style; the existing tests also run standalone via
   `python -m <module>`). Keep the core's no-outward-deps rule.
4. **Feature branch + PR.** Do **not** push to `main` (which auto-deploys) for anything
   that changes prod behaviour. Docs-only commits may go to `main` with `[skip deploy]`
   in the message.
5. **Secrets never in code or git.** Use SSM / env. Never read the committed
   `score-key.pem` into anything new.
6. **GATED — require explicit human approval, do NOT do unattended:**
   - flipping the scoring engine cut-over (serving `/score` from the core);
   - running the SQLite→Postgres migration against prod;
   - rotating the EC2 key / rewriting git history;
   - enabling `PUBLIC_LIVE_SSE` in prod;
   - deleting the Flutter / standalone-relay apps.

---

## Task A — Competition module (#3)  ·  SAFE, additive

**Goal:** non-ECB "native mode" — organisers create tournaments, teams, players, fixtures,
and a points table; the public can follow each game via `/live/<slug>`.

**Steps**
1. Add models to `server/models_cricrelay.py` (same `db`, mirror existing style with
   `to_dict`): `Tournament(organization_id FK, name, slug UNIQUE, format, overs, starts_on,
   created_at)`, `Team(tournament_id FK, name, short_name)`, `Player(team_id FK, name)`,
   `Fixture(tournament_id FK, home_team_id, away_team_id, scheduled_at, score_match_slug
   NULLABLE, status, result_json)`.
2. Ensure tables get created (the app's `db.create_all()` path, or add an Alembic
   migration). Verify against local SQLite.
3. Authed CRUD routes — copy the existing pattern (`@login_required`, `_org_from_session()`,
   see `dashboard_relay_toggle_pause`). **Scope every query by org.**
   `POST /dashboard/tournaments`, `POST /dashboard/tournaments/<id>/teams`,
   `POST /dashboard/tournaments/<id>/players`, fixture create or auto round-robin generator.
4. Public read-only page `GET /t/<slug>` → `templates/public_tournament.html` (reuse the
   Floodlight tokens from `static/cricrelay.css` and the style of `public_live_score.html`):
   teams + squads, schedule, **points table + NRR** computed from completed fixtures, each
   fixture deep-links to `/live/<score_match_slug>`.
5. A native match created here sets `RelayMatch.relay_source = "native"` (the model already
   anticipates non-scraper sources) and uses the existing `/setup` + `/ball` scoring.

**Files:** `server/models_cricrelay.py`, `server/app.py`, `templates/public_tournament.html`,
`templates/dashboard_tournaments.html`, `server/tests/test_competition.py`.
**Acceptance:** create tournament + 2 teams + players via authed routes; `/t/<slug>` renders
with a points table; fixtures link to working live pages; everything org-scoped (no
cross-org leakage).

---

## Task B — Finish scoring cut-over reconciliation (#2)  ·  build SAFE, FLIP is GATED

**Goal:** make `cricrelay_core` a faithful replacement for the legacy `/ball` engine, then
flip behind a flag.

**Steps**
1. In a **local/staging** env (never prod), set `SCORING_DUAL_WRITE=1`,
   `SCORING_SHADOW_COMPARE=1`, `EVENT_STORE_DSN=<a Postgres>` (local docker, or the datastore
   via an SSM port-forward; password from SSM).
2. Score a representative match through the legacy API; collect the
   `"scoring shadow diff"` log lines.
3. For each divergence, update `cricrelay_core` to match legacy and add a test. Likely
   remaining (no-ball striker-credit already done):
   - `Wd`/`Nb` that also carry a `run_out`/`stumped` dismissal → extend
     `cricrelay_core/events.Delivery` to carry a wicket-on-extra, handle in `scoring.apply`,
     map in `scoring_bridge`.
   - penalties (note TD-5: legacy fielding-side penalty is buggy — decide correct behaviour),
     `record-dismissal` kinds (run_out/stumped/hit_wicket/obstructing/timed_out/handled),
     retire (hurt/unhurt), `end-over`, `start-second-innings`, `edit`. Add event types +
     reducer cases + bridge mappings + tests for each.
4. Iterate until shadow-diff is empty across a full 2-innings match.
5. **[GATED]** With human go-ahead only: add a read-path flag to serve `/score` from the
   core, canary on one match, then retire the legacy global-state engine and `/tmp` JSON
   (TD-1/TD-2). Run the data migration (Task below) as part of going live on Postgres.

**Acceptance:** shadow-compare logs **zero** diffs over a full match including extras,
wickets, dismissals, retirements, penalties, and the innings break.

---

## Task C — Security hardening remainder (#6)  ·  MIXED

- **C1 (SAFE) — CORS off wildcard (S-3):** `server/app.py` already parses `CORS_ORIGINS` via
  `_parse_cors_origins()`. Set the prod env to the real web hostnames + app origins and stop
  using `*`. Verify the KMP app + web still work.
- **C2 (SAFE) — authz on mutating scoring routes (S-2):** require a **match-scoped scorer
  token** on `POST /ball`, `/setup`, etc., so an organiser can grant scoring on one match
  without full admin. Ties into Task A.
- **C3 (GATED — human must drive) — rotate leaked key (S-1):** create a new EC2 key pair,
  add the new public key to the prod box `authorized_keys`, **verify access**, then remove
  the old key and delete the old AWS key pair; finally `git filter-repo` to purge
  `score-key.pem` (and historical `infra/terraform.tfstate`, TD-8) and force-push. **Never
  unattended — wrong order = SSH lockout; force-push rewrites shared history.**
- **C4 — network (S-4/S-5):** confirm nginx TLS + Let's Encrypt renewal; restrict SSH SG
  ingress from `0.0.0.0/0` to known IPs in `infra/main.tf`, and reconcile the prod SG drift
  deliberately (a full `terraform plan` currently wants to rewrite `cricket_sg` ingress).

---

## Task D — Versioned API contract (#7)  ·  SAFE, additive

**Goal:** a stable `/api/v1` for the KMP app + web, decoupled from internals.
**Steps:** author `docs/openapi.yaml`; add an `/api/v1` blueprint mirroring the existing
`/api/*` with versioning + Pydantic validation; generate KMP/Swift client models from the
spec; add contract tests. Keep `/api/*` until clients migrate.

---

## Task E — Mobile consolidation (#8)  ·  GATED on on-device parity

**Goal:** make `cricrelay-mobile` (KMP) the single mobile app; retire `cricrelay-stream`
(Flutter) and archived `pcs-ble-relay(-android)`.
**Gate (needs a real iOS device):** (1) `ios-parity-tightening` merged; (2) on-device
camera+overlay RTMP, in-app BLE relay, go-live preflight, manual scoring verified; (3)
IPA/OTA distribution confirmed for the KMP build; (4) one full prod stream on KMP iOS.
**Only after all four:** delete the Flutter + standalone-relay projects and remove the dead
`/download/*` + build/CI paths.

---

## Task F — Realtime at scale + live-page polish

- **Enable `PUBLIC_LIVE_SSE` [GATED]:** first make the SSE-safe worker model — switch
  gunicorn to threaded/gevent workers (`deploy/cricket.service`) **or** stand up a dedicated
  SSE process backed by Redis pub/sub (`RedisLiveState.publish_scoreboard` /
  channel already exist). Only then set `PUBLIC_LIVE_SSE=1`.
- **Full scorecard** on `/live/<id>`: batting + bowling tables (fall of wickets,
  partnerships) — new projections over the same event log; no write-path change.

---

## Run / test / deploy

- **Run locally:** `python -m server.app` (port 5000).
- **Tests:** `python -m cricrelay_core.tests.test_scoring` (and `test_codec`,
  `server/tests/*`), or `pytest`. Store integration: `cricrelay_store/tests/test_integration.py`
  (needs Postgres via `CR_PG_DSN`; Redis test uses in-process fakeredis).
- **Prod deploy:** push to `main` → `.github/workflows/deploy.yml` (SSH to EC2, git-sync,
  pip if `requirements.txt` changed, restart `cricket`, health-check). Use `[skip deploy]`
  in the commit message for docs-only changes.
- **Verify prod:** `curl https://cricrelay.co.uk/health`.
