# CricRelay Architecture

*Auto-maintained by Stop hook (`/.claude/hooks/update-architecture.sh`). Last updated: 2026-07-01 (public live-score page + SSE; public club page; SEO infrastructure; password reset flow; iOS OTA install; viewer-stat columns; ui_theme; scoring dual-write flags; stream slot cap; Sponsor time bounds; CRICHEROES_HOSTS + _is_cricheroes_host promoted to module-level constants in models_cricrelay.py — dual-host validation (`cricheroes.com` + `cricheroes.in` including subdomains) now the single source of truth; canonicalize_cricheroes_scrape_url returns `""` for any URL failing `_is_cricheroes_host` (previously returned raw input on failure — callers now treat falsy return as invalid); canonicalize_cricheroes_scrape_url promoted to top-level import in app.py; relay_config() canonicalizes CricHeroes URL before validation via canonicalize_cricheroes_scrape_url; relay_config error message updated to generic "CricHeroes scorecard page"; _create_cricheroes_stream_org validation simplified to `if not full_url`; normalize_cricheroes_team_root uses _is_cricheroes_host and defaults to cricheroes.com branding; _cricheroes_match_id_from_url handles /individual/<id>/live paths (normalised to /scorecard/<id>/live); fixture Weekly-tab priority; relay_source_to_provider; relay_source="native" for club-owned tournament fixtures; tournament management dashboard routes + round-robin generation; public /t/<slug> tournament page; compute_points_table NRR standings; GDPR Art. 17 erasure + Art. 20 data-portability API endpoints; authenticated app-builds endpoint; home page (GET /) now passes app_builds payload (Android APK + iOS OTA links with streaming_note) to template — download links surfaced on public acquisition page).*

---

## Overview

CricRelay is a live cricket broadcast platform. An operator uses the mobile app to stream a match via RTMP to YouTube or Twitch, while a scoreboard overlay (scraped from Play Cricket or entered via BLE scoring device) composites onto the video in real time. A Flask backend coordinates stream sessions, platform OAuth, overlays, and viewer stats.

---

## Components

### 1. Python Flask Server (`server/`)

Central API and orchestration layer.

| File | Responsibility |
|---|---|
| `app.py` | Flask app factory, routes, CORS; security headers (`X-Content-Type-Options`, HSTS, etc.); SEO (canonical URL, `sitemap.xml`, `robots.txt`, `inject_seo_context`); public live-score page (`/live/<slug>`) + SSE endpoint (`/live/<slug>/events`, `PUBLIC_LIVE_SSE` flag); public club page (`/club/<slug>`); public tournament page (`/t/<slug>` — squads, schedule, points table + NRR via `compute_points_table()`); tournament management dashboard routes (`/dashboard/tournaments`, `/dashboard/tournaments/<id>`, team/player/fixture CRUD, round-robin `generate-fixtures`, `start-match`, `record-result`); password reset flow (SMTP, `URLSafeTimedSerializer`); iOS OTA install manifest (`/download/cricrelay-stream-ota.plist`); scoring dual-write + shadow-compare feature flags (`SCORING_DUAL_WRITE`, `SCORING_SHADOW_COMPARE`); stream slot cap (`MAX_LIVE_STREAMS_PER_CLUB = 6`); PCS BLE retirement (`pcs_ble_retired_response()`, `purge_legacy_pcs_ble_relay_rows()`); fixture discovery (`_fixture_candidate_urls()` — probes `/Matches?tab=Weekly` first, then `/Matches`, then `/website/results`); GDPR `DELETE /api/auth/account` (Art. 17 erasure — revokes YouTube/Twitch OAuth, calls `_erase_org_personal_data()` to delete org rows FK-safe: StreamSession → Sponsor → ClubUser → RelayMatch → Organization) + `GET /api/auth/account/export` (Art. 20 data portability — returns account, users, streams, stream_sessions as JSON); `GET /api/stream/app-builds` (auth-gated Android APK + iOS OTA install links with version label); `GET /` home page now passes `app_builds=_stream_app_builds_payload()` to template — Android APK + iOS OTA download links (including `streaming_note`: camera+overlay burn-in is Android-primary; iOS supports sign-in, stream management, and custom RTMP) surfaced publicly on the acquisition page |
| `stream_api.py` | Bearer-token auth (`bearer_org_from_request`), stream session CRUD, go-live / stop-live, broadcast status, match-day status |
| `models_cricrelay.py` | SQLAlchemy models: `Organization` (+ `ui_theme` original/light/dark, branding fields), `ClubUser`, `RelayMatch`, `StreamSession` (+ `started_by_user_id`, `match_label`, `peak_viewers`, `viewer_sample_sum/count`, `final_score_json`, `vod_url`), `Sponsor` (+ `active_from`/`active_to` time bounds), `Tournament`, `Team`, `Player`, `Fixture`; `RELAY_PROVIDERS` dispatch table; `CRICHEROES_HOSTS` (`{"cricheroes.com", "cricheroes.in"}`); `_is_cricheroes_host()` (validates netloc against CRICHEROES_HOSTS, including subdomains); `_cricheroes_match_id_from_url()` (extracts numeric match id from `/scorecard/<id>` and `/individual/<id>` CricHeroes URL paths); URL canonicalization helpers (`canonicalize_play_cricket_scrape_url`, `canonicalize_cricheroes_scrape_url` — returns `""` for any non-CricHeroes URL; normalises `/individual/<id>/live` paths to `/scorecard/<id>/live`, `normalize_cricheroes_team_root`); `relay_source_to_provider()` (maps `relay_source` string → provider key); `resolve_provider_callable()` lazy-import; `provider_poll_interval_sec()` |
| `play_cricket_scraper.py` | Scrapes Play Cricket scorecard HTML |
| `play_cricket_mapper.py` | Maps scraped rows → overlay JSON schema |
| `scoring_bridge.py` | Routes scoring events to overlay + cricrelay_core |
| `scoring_shadow.py` | Shadow-writes live score to a secondary store |
| `relay_poller.py` | Polls BLE relay for PCS device score packets (dormant) |
| `pcs_protocol.py` | Decodes PCS BLE binary scoring protocol |
| `youtube_stream.py` | YouTube Data API v3 broadcast lifecycle |
| `twitch_stream.py` | Twitch Helix stream key / status |
| `rate_limit.py` | Per-IP rate limiting middleware |
| `scraper_worker.py` | Background worker: polls Play Cricket or CricHeroes on interval via `RELAY_PROVIDERS` dispatch |
| `cricheroes_scraper.py` | CricHeroes live scorecard scrape (Playwright) |
| `cricheroes_mapper.py` | Maps CricHeroes snapshot → overlay JSON schema |

**Data stores:** PostgreSQL (primary), SQLite (migration source via `migrate_sqlite_to_postgres.py`).

**Auth:** Bearer org-token (`issue_stream_token(org)`, itsdangerous signed). `ClubUser` supports individual member logins (admin/member roles) belonging to an `Organization`. Multi-user per-club dashboard. Password reset via time-limited signed token (`URLSafeTimedSerializer`, SMTP delivery; env: `SMTP_HOST/PORT/USERNAME/PASSWORD`). **GDPR compliance:** `DELETE /api/auth/account` (Art. 17 right to erasure — revokes stored YouTube/Twitch refresh tokens via their revocation endpoints, then `_erase_org_personal_data()` deletes all org-scoped rows in FK-safe order before removing the `Organization` row); `GET /api/auth/account/export` (Art. 20 data portability — returns account details, member users, relay streams, and stream session history as structured JSON).

**Relay sources:** `RelayMatch.relay_source` is `"scraper"` (Play Cricket), `"cricheroes"`, `"pcs_ble"` (dormant), or `"native"` (club-owned tournament fixture — manual scoring only, created by `dashboard_start_fixture_match`, no external scraper). `RELAY_PROVIDERS` dict maps provider keys to their scrape/map/fixtures/poll-interval callables; `scraper_worker` dispatches via this table. CricHeroes URLs on both `cricheroes.com` and `cricheroes.in` are accepted via `CRICHEROES_HOSTS = {"cricheroes.com", "cricheroes.in"}`; `_is_cricheroes_host(netloc)` validates against this set (including subdomains). `canonicalize_cricheroes_scrape_url` returns `""` (falsy) for any URL that doesn't pass `_is_cricheroes_host` — both `_create_cricheroes_stream_org` and `relay_config` rely on this contract: they call `canonicalize_cricheroes_scrape_url` and reject on empty return rather than doing their own string checks.

**Viewer stats:** `StreamSession` accumulates `peak_viewers`, `viewer_sample_sum`, `viewer_sample_count` (→ `avg_viewers` property) for each broadcast session, linked to the `ClubUser` who started it via `started_by_user_id`. `final_score_json` and `vod_url` are stored at session end.

**Public-facing routes:** `/live/<slug>` — shareable no-login live-score page (polling or SSE when `PUBLIC_LIVE_SSE=1`). `/live/<slug>/events` — SSE push of score JSON (~1 s cadence; holds a gunicorn worker; keep `PUBLIC_LIVE_SSE` off until threaded workers or Redis pub/sub is in place). `/club/<slug>` — read-only org page for fans and sponsors. `/t/<slug>` — public tournament page (squads, schedule, fixture results, points table + NRR computed by `compute_points_table()`).

**Scoring migration flags:** `SCORING_DUAL_WRITE=1` shadow-writes each ball to `cricrelay_store` (Postgres event store) alongside the legacy engine. `SCORING_SHADOW_COMPARE=1` folds the event log through `cricrelay_core` on each `/score` read and logs divergences. Both failures are swallowed; the legacy engine stays authoritative until cut-over.

**Native mode models:** `Tournament` → `Team`/`Player` → `Fixture` supports non-ECB club-owned competitions. A `Fixture.score_match_slug` links to a `RelayMatch` for live scoring.

---

### 2. Core Scoring Engine (`cricrelay_core/`)

Pure-Python, dependency-free domain model. No Flask imports.

| File | Responsibility |
|---|---|
| `scoring.py` | Innings, over, ball event state machine |
| `events.py` | Typed event model (BallEvent, WicketEvent, …) |
| `ports.py` | Abstract ports (ScoringRepository, EventBus) |
| `codec.py` | Serialise/deserialise events to/from wire format |
| `stats.py` | Run-rate, projected score, partnership calculations |

The server wires concrete adapters into these ports; the core never touches HTTP or DB directly.

---

### 3. Storage Layer (`cricrelay_store/`)

Adapters implementing `cricrelay_core.ports` against Postgres. Decouples the domain from SQLAlchemy.

---

### 4. Live Overlay (`cricket_overlay.html`, `static/`, `templates/`)

Browser-rendered HTML/CSS/JS scoreboard burned into the stream via the camera engine's WebView compositor.

- Served by Flask at `/stream` and `/m/<slug>/stream` (rich overlay endpoint).
- **Design canvas:** 1280px reference layout (`DESIGN_W`); scales down when encode width is narrower (portrait 720p, low-tier 640p). Chrome at `/stream` is the visual reference at full 1280p.
- **Streamer phone:** Capture width tracks the **encoded RTMP frame** (not screen size). Orientation and encoder tier adjust automatically; strip stays bottom-anchored edge-to-edge on the video.
- **Viewer phone:** Overlay is burned into the video — Twitch/YouTube scale the whole frame. No per-viewer overlay layout; use 720p+ stream for readable text on phones.
- Receives score JSON via polling (`/score` or `/m/<slug>/overlay-data`).
- Team names are dash-formatted; T20 format is inferred pre-match.
- **Mobile capture/compositing (must match Chrome):**
  - Android `OverlayWebViewCapture`: rasterizes at **encoded canvas width** (dynamic, max 1280); injects viewport + top-pinned `#overlay` CSS; GL sprite composites at 100% width at default prefs.
  - iOS `OverlayWebViewCapture`: same dynamic capture width + measure loop; `StreamCameraEngine` left-aligns and scales to `encodedCanvasWidth × widthFraction`.
  - Studio preview (`BroadcastCameraUi`) uses the same width fraction and horizontal inset as the burned-in GL overlay (WYSIWYG).
- The `cricket_overlay.html` / `overlay_lovable_export.html` files are standalone variants for development.

---

### 5. iOS App (`cricrelay-mobile/ios/`)

Swift/SwiftUI native app. Feature modules under `Features/`:

| Feature | Contents |
|---|---|
| `Auth/` | Login, token storage, club selection |
| `Home/` | Match list (stream tiles + GlanceRow stats), YouTube/Twitch OAuth cards, stream management (rename/delete), CricHeroes stream creation |
| `CreateStream/` | New stream wizard — Play-Cricket fixture picker or CricHeroes URL entry |
| `Studio/` | Camera preview, broadcast controls (detail below) |
| `Scoring/` | In-app scoring entry UI |
| `PcsBle/` | *(removed)* — PCS BLE feature removed from main app; archived standalone relay apps remain in `archive/` |

**Shared infrastructure:**
- `Models.swift` — shared domain structs (StreamMatch, OverlayLayoutPrefs, ScoringConfig, GoLiveResult, StreamRecap, PlatformStatus, RtmpCredentials)
- `CricMotion.swift` — animation constants and custom SwiftUI transitions
- `CricRelayAPI.shared` — URLSession-backed API client for all server calls
- `StreamCameraEngine.shared` — HaishinKit RTMP engine singleton (MTHKView preview, overlay WebView compositor, focus/AE control, zoom, stabilisation, keep-screen-on)

#### Studio Module (`Features/Studio/`)

The broadcast control screen — the operator's primary live interface.

**`StudioView.swift`**
- `CameraPreviewView` (`UIViewRepresentable`): wraps `MTHKView` (HaishinKit), forwards single-finger taps for focus and two-finger pinch for zoom.
- `StudioView`: full-screen SwiftUI broadcast UI:
  - *Permission gate* — requests both camera and microphone on load; shows `permissionDeniedView` (with "Open Settings" deep-link) if camera access is denied.
  - *Top bar* — back button + ON AIR / PAUSED badge with mm:ss elapsed timer.
  - *Quick toggle row* — Focus Lock, Stabilize, Keep Screen On (one-tap; surfaced on the camera screen for parity with Android's QuickToggles row).
  - *Tool button row* — Destination, Overlay, Scoring, Menu (open modal sheets).
  - *Shutter row* — Pause/Resume + main Go-Live/Stop shutter + zoom indicator.
  - *Focus reticle* — animated square with padlock icon when focus is locked.
  - *Countdown overlay* — 5→1 countdown before stream starts.
  - *Recap banner* — post-stream summary (title, destination, duration, watch URL).
  - *Error banner* — dismissible stream error notification.
  - *Status handler* — registers a `StreamCameraEngine.setStatusHandler` callback on load to receive "connected" / "error" events and update ViewModel state; cleared on disappear.
- Five modal sheets: `DestinationSheet`, `OverlaySheet`, `ScoringSheet`, `PreflightSheet`, `StudioMenuSheet`.

**`StudioViewModel.swift`** (`@MainActor ObservableObject`)

Owns all broadcast state and orchestrates the go-live flow:

- *Destination management*: YouTube, Twitch (OAuth, gated on `PlatformStatus.ready/connected`), or Custom RTMP. Prefers a connected OAuth platform on load; falls back to Custom RTMP when `RtmpCredentialsStore` has saved credentials.
- *Go-live flow*: camera preview warms up at 1280×720×30fps on Studio load (`preparePreview` in view `.task`); then `requestGoLive()` → `openPreflight()` → `confirmGoLive()` → 5-second countdown → `startStream()`. Stream output: 1280×720, 2.5 Mbps, 30fps.
- *Live elapsed timer*: wall-clock `Task` ticking once/second while on-air (consistent with recap duration; keeps counting while paused).
- *Broadcast status sync*: on go-live, pause/resume, and stop, pushes the new state back to the server via `api.updateBroadcastStatus()` (bidirectional — not just read-only polling).
- *Polling*: 5-second `matchDay` poll to sync broadcast status from the server.
- *Overlay sync*: on load, pushes the match's `overlayEmbedUrl` into `StreamCameraEngine` so the scoreboard composites into the preview before going live.
- *Focus lock*: `tapToFocus` → continuous AF/AE at tap point; `toggleFocusLock` → `StreamCameraEngine.lockFocus()` / `unlockFocus()`; focus reticle stays on screen while locked.
- *Quick toggles*: `toggleStabilization()` / `toggleKeepScreenOn()` mutate `OverlayLayoutPrefs` and persist via `saveOverlay()`.
- *Recap*: on stop, builds a `StreamRecap` (title, destination label, duration, watch URL) and surfaces it as the recap banner.
- *Custom RTMP persistence*: `RtmpCredentialsStore` (UserDefaults-backed, keyed by match slug) — parity with Android's `RtmpCredentialsStore`.
- *Additional public methods*: `cancelCountdown()` aborts the 5-second countdown; `restartCameraPreview()` re-initialises the camera pipeline (error recovery path); `setScoringMode()` calls the API to change scoring mode for the match.

**`RtmpCredentialsStore.swift`** — UserDefaults persistence for custom RTMP URL / stream key / watch URL, keyed by match slug.

**`StudioSheets.swift`** — All five sheet components (`DestinationSheet`, `OverlaySheet`, `ScoringSheet`, `PreflightSheet`, `StudioMenuSheet`).

---

### 6. Android App (`cricrelay-mobile/android/`)

Kotlin multi-module Gradle project (Hilt DI, Compose UI, KMP shared module).

| Module | Role |
|---|---|
| `app/` | Application shell; Compose `CricRelayNavHost` with type-safe serializable routes |
| `core/` | Shared domain models, network, DI |
| `feature/home` | `HomeScreen` + `CreateStreamScreen`; `HomeViewModel`; `CreateStreamViewModel`; stream management (rename/delete), GlanceRow stats, YouTube/Twitch OAuth cards, CricHeroes stream entry; `StudioBackdrop` with `BackdropMood.OnAir`/`Idle` context-aware theming; `StudioHero` banner; `LiveNowCard` (promotes the active stream above the list when live) |
| `feature/studio` | `StudioScreen`, `StudioViewModel`, modal sheets (detail below) |
| `feature/scoring` | In-app scoring entry UI |
| `streaming/` | `StreamController` — RootEncoder 2.4.8 RTMP engine; PiP overlay; offscreen-GL WebView swap; BLE overlay push |
| `shared/` | Kotlin Multiplatform module (`CricRelayApiClient`, `StreamRepository`, `AuthRepository`, domain models) |

**Navigation (`CricRelayNavHost.kt`):** Type-safe `@Serializable` route objects (Login, Register, Onboarding, Home, `CreateStreamRoute(mode)`, `StudioRoute(matchSlug)`, `ScoringRoute(matchSlug)`). Default slide-in/out with `AppMotion` timing; Studio entry uses zoom+fade ("step into the broadcast").

**Studio module (`feature/studio`):**

`StudioViewModel` owns broadcast state and orchestrates the full go-live flow:
- *Camera gate*: `StudioCameraGate.Readiness` (match loaded + permissions + surface bound) guards `prepareCamera()` — no partial starts.
- *Overlay preview*: `streamController.setPreviewOverlayListener` delivers JPEG frames; decoded to `ImageBitmap` for WYSIWYG in-view preview.
- *PiP mode*: `streamController.pipMode` Flow collapses the UI to camera-only and closes sheets when entering PiP.
- *Go-live flow*: `requestGoLive()` → preflight sheet → `confirmGoLive()` → 3-2-1 countdown → `goLive()`. OAuth platforms call `streamRepository.goLive(slug, platform)` to get RTMP creds from the server; custom RTMP uses `RtmpCredentialsStore` (SharedPrefs, keyed by slug).
- *Broadcast sync*: `updateBroadcastStatus()` pushes streaming/paused/idle state back to server bidirectionally.
- *Focus/zoom*: `tapToFocusAt()` + `lockFocus()`/`unlockFocus()` mirrored from `StreamController`; focus reticle dismissed after 1.5 s unless locked.
- *Match-day polling*: 8-second coroutine loop via `streamRepository.getMatchDayStatus()`.
- *Scoring mode*: `setScoringMode()` supports `"auto"`, `"manual"`, `"auto:cricheroes"` (provider prefix stripped before API call).
- *Stream cache*: `StreamDao` provides instant local match data while remote fetch completes (12 s timeout fallback).

**Studio sheets (`StudioSheets.kt`):**
- `DestinationSheet` — YouTube / Twitch / Custom RTMP picker with animated custom-RTMP field expansion.
- `OverlaySheet` — horizontal style carousel (6 named themes: Broadcast, Compact, AI Neural, Stadium, Neon, Minimal), sliders for width/height/font/opacity/position, stream watermark toggle.
- `ScoringSheet` — Auto (Play-Cricket), Auto (CricHeroes), Manual; "Open scorer in browser" deep-link.
- `PreflightSheet` — staggered animated checklist (camera ready, destination set, overlay URL present); gates Go Live button.
- `StudioMenuSheet` — restart camera preview.

Note: Android has focus-only lock (RootEncoder 2.4.8 has no AE-lock API); iOS locks both focus and exposure.

---

### 7. Shared KMP Module (`cricrelay-mobile/shared/`)

Kotlin Multiplatform library consumed by both Android and (via Kotlin/JS or future native) iOS.

| Component | Responsibility |
|---|---|
| `CricRelayApiClient` | Ktor-backed HTTP client: login/register, listStreams, listFixtures, createPlayCricketStream, createCricHeroesStream, goLive/stopLive, updateBroadcastStatus, getScoring/setScoring, getOverlayPrefs/setOverlayPrefs, setRelayPause, youtube/twitch OAuth + status, deleteStream, renameStream, getAppBuilds |
| `AuthRepository` | Wraps `SessionStore` + `CricRelayApiClient`; login/register/logout/onboarding state |
| `StreamRepository` | Thin coroutine wrappers over `ApiClientProvider.get()` calls; used by Android ViewModels |
| Domain models | `StreamMatch`, `OverlayLayoutPrefs`, `ScoringConfig`, `MatchDayStatus`, `PlatformStatus`, `GoLiveResult`, `FixturesResponse`, `FixtureItem`, `StreamRecap` |

`CricRelayApiClient` validates `isAllowedApiBaseUrl` (HTTPS or local), normalizes base URLs, and handles HTML-vs-JSON response disambiguation with typed `ApiException` messages.

---

### 8. BLE PCS Scoring Relay

The PCS BLE feature has been removed from the main mobile app on both platforms. Only archived standalone relay apps remain:

- `archive/pcs-ble-relay/` — archived Flutter/Dart PCS relay (unmaintained)
- `archive/pcs-ble-relay-android/` — archived Android-native PCS relay APK (unmaintained)

Protocol decoder lives in `server/pcs_protocol.py` (server-side) and `cricrelay_core/codec.py` (domain-level). Server ingest endpoint (`/relay/pcs-ingest`) is kept dormant.

---

## Data Flow

```
PCS Device (BLE)
      │  BLE packets
      ▼
pcs-ble-relay (archived) ──POST──► Flask server (/relay/pcs-ingest) [dormant]
                               │
Play Cricket website           │
      │  HTTP scrape           │
      ▼                        │
scraper_worker ────────────────┤  RELAY_PROVIDERS dispatch
                               │  (play_cricket | cricheroes)
CricHeroes website             │
      │  Playwright scrape     │
      ▼                        │
cricheroes_scraper ────────────┤
                               │  scoring_bridge / mapper
                               │  → cricrelay_core state machine
                               ▼
                         Postgres (StreamSession, BallEvent,
                                   Tournament/Team/Player/Fixture)
                               │
                    ┌──────────┴──────────┐
                    │                     │
               API poll               /stream overlay
               (CricRelayApiClient)   (Flask → HTML/CSS)
                    │                     │
              Mobile app            Browser HTML/CSS
              (KMP shared module)   (composited into video via
                    │                StreamCameraEngine WebView)
                    │
             RTMP push (HaishinKit / RootEncoder)
                    │
              YouTube / Twitch CDN
                    │
                 Viewers
```

---

## Two Product Modes

| Mode | Description |
|---|---|
| **ECB relay** | Stream + relay an official ECB match; Play Cricket scraper (`relay_source="scraper"`) drives the overlay |
| **CricHeroes relay** | Best-effort scrape from a CricHeroes live scorecard URL (`relay_source="cricheroes"`); same RTMP pipeline, different data source |
| **Non-ECB native** | Club creates its own competition via `Tournament`/`Fixture` models (managed at `/dashboard/tournaments`); fixtures use `relay_source="native"`; scoring entered via in-app manual scorer; standings + NRR visible on `/t/<slug>` |

Scoring mode is set per-match on the server (`RelayMatch.relay_source`) and can be switched live from the app's Scoring sheet (`ScoringSheet` on both iOS and Android). Supported modes: `"auto"` (Play Cricket), `"auto:cricheroes"` (CricHeroes), `"manual"`.

---

## Supporting Apps

Three standalone apps complement the main mobile app:

| App | Location | Tech | Purpose |
|-----|----------|------|---------|
| CricRelay Stream | `cricrelay-stream/` | Flutter | Lightweight RTMP streaming companion — used when the full KMP app is too heavy |
| PCS BLE Relay (Android) | `archive/pcs-ble-relay-android/` | Kotlin Android | **Archived** — unmaintained; server `/relay/pcs-ingest` dormant |
| PCS BLE Relay (Flutter) | `archive/pcs-ble-relay/` | Dart/Flutter | **Archived** — unmaintained |
| CricHeroes scraper | `server/cricheroes_scraper.py` | Python/Playwright | Live scorecard scrape — now a first-class relay source (`relay_source="cricheroes"`) |

Prebuilt APKs (`cricrelay-stream.apk`) are committed to `static/` and served directly from the Flask app for club download.

---

## Infrastructure — `infra/`

Terraform-managed deployment targeting AWS (primary) with OCI migration in progress.

| Resource | Purpose |
|----------|---------|
| EC2 / OCI compute | Gunicorn + Nginx serving Flask |
| RDS PostgreSQL | Primary relational data store |
| ElastiCache / Redis | Live session state, score pub/sub |
| S3 | Automated backups, static APK hosting |
| Nginx | Reverse proxy, TLS termination, static files |
| systemd | `cricket.service`, daily backup timers |

**Active migration:** `infra/migration-aws-oci/` contains scripts moving from AWS EC2+RDS to OCI compute+managed DB. Both targets have Terraform definitions; `infra/oci/` is the landing zone.

**Deployment:** `.github/workflows/deploy.yml` (AWS) and `deploy-oci.yml` (OCI). Server push triggers Gunicorn reload; DB migrations run in the workflow via `migrate_sqlite_to_postgres.py`.

**Operations:** `deploy/` holds systemd unit files, Nginx config, GDPR erasure runbook, security hardening notes, and EC2 disk-cleanup scripts.

---

## CI/CD — `.github/workflows/`

| Workflow | Trigger | What it does |
|----------|---------|--------------|
| `build-cricrelay-mobile.yml` | Push/PR | Android Kotlin: lint, unit tests, APK + AAB |
| `build-cricrelay-stream-apk.yml` | Push/PR | Flutter stream app APK |
| `build-pcs-relay-apk.yml` | *(removed)* | PCS relay APK workflow archived |
| `validate-cricrelay-mobile.yml` | PR gate | Mobile pre-merge checks |
| `validate-cricrelay-stream.yml` | PR gate | Flutter pre-merge checks |
| `deploy.yml` | Workflow dispatch | Flask → EC2, health check |
| `deploy-oci.yml` | Workflow dispatch | Flask → OCI |
| `migrate-to-rds.yml` | Workflow dispatch | DB migration to RDS |
| `sync-stream-apk-ec2.yml` | Workflow dispatch | Sync APK builds to EC2 static dir |
| `ci.yml` | Push | General CI checks |

---

## Active Design Decisions

| Decision | Detail |
|----------|--------|
| **Freemium tiers** (June 2026) | AdMob on free tier; custom sponsor overlay slot as Pro upsell; `Sponsor.active_from`/`active_to` bound sponsorships by date; website is the acquisition layer |
| **Per-org UI theme** | `Organization.ui_theme` (original/light/dark) injected into every dashboard response via `inject_seo_context`; branding colours (`public_primary_color`, `public_accent_color`) drive the `/club/<slug>` public page |
| **SSE live-score** | `PUBLIC_LIVE_SSE=1` enables push on `/live/<slug>/events`; off by default — each SSE connection holds a gunicorn worker for its 5-minute lifetime. Migrate to threaded workers or a Redis pub/sub pusher before enabling in production |
| **Scoring dual-write / shadow** | `SCORING_DUAL_WRITE` + `SCORING_SHADOW_COMPARE` env flags wire the new `cricrelay_core`/`cricrelay_store` engine in shadow mode; legacy engine stays authoritative until explicit cut-over |
| **iOS OTA install** | `/download/cricrelay-stream-ota.plist` serves an OTA manifest; `itms-services://` URL in Safari triggers install. Bundle ID via `STREAM_APP_IOS_BUNDLE_ID` env var |
| **Focus-lock** | iOS locks focus **and** exposure (`AVCaptureDevice` `focusMode` + `exposureMode`). Android locks focus only — RootEncoder 2.4.8 has no AE-lock API |
| **Broadcast resilience** | Android: RootEncoder `replaceView` offscreen-GL swap + PiP overlay keeps stream alive on locked screen. iOS: best-effort standby slate only (Apple does not allow over-app overlays) |
| **Modular-monolith migration** | Strangler pattern toward a Match spine. `cricrelay_core` scoring engine is the first extracted module; 8 parallel tracks running |
| **Gradle module path** | `:feature:x` not `:android:feature:x` — the `android/` directory is **not** part of the Gradle path |
| **Token auth v2** | Bearer org-token (itsdangerous, `issue_stream_token(org)`). `ClubUser` supports multi-member logins per club (admin/member roles). `Organization` is the unit of auth for the mobile API |
| **CricHeroes as relay source** | CricHeroes is now a first-class relay provider alongside Play Cricket — same `RELAY_PROVIDERS` dispatch table, same overlay pipeline, selectable live from the Scoring sheet |
| **PCS BLE archived** | `feature/pcs-ble` removed from both iOS and Android main apps. Protocol decoder + server ingest endpoint kept dormant for possible future reactivation |
| **Android type-safe nav** | Compose Navigation with `@Serializable` route objects; Studio entry uses zoom+fade instead of slide to signal mode shift |
| **Stream management** | Streams can be renamed and deleted from the Home screen (long-press tile → management sheet) on both platforms; `CricRelayApiClient` exposes `renameStream` / `deleteStream` |

---

## Key External Dependencies

| Dependency | Used by | Purpose |
|---|---|---|
| HaishinKit | iOS `StreamCameraEngine` | RTMP encode + push, MTHKView preview |
| RootEncoder 2.4.8 | Android `streaming/` | RTMP encode + push |
| Ktor | KMP `shared/` `CricRelayApiClient` | HTTP client (Android + future multiplatform) |
| YouTube Data API v3 | Server `youtube_stream.py` | Broadcast CRUD, stream key |
| Twitch Helix API | Server `twitch_stream.py` | Stream key + channel status |
| Play Cricket | Server `play_cricket_scraper.py` | Live scorecard HTML |
| CricHeroes | Server `cricheroes_scraper.py` | Live scorecard scrape (Playwright) |
| SQLAlchemy + Postgres | Server, `cricrelay_store` | Persistence |
| AVFoundation | iOS `StreamCameraEngine` | Camera capture, focus/AE lock, stabilisation |
| Hilt | Android `app/`, feature modules | Dependency injection |
