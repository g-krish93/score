# CricRelay Architecture

*Auto-maintained by Stop hook (`/.claude/hooks/update-architecture.sh`). Last updated: 2026-07-01 session 14 (code-verified corrections applied to body): (1) `SCROLL_DIR_FIXED` in Android `StreamCameraEngine` sets `sponsorScrollOffsetPct = 0f` — prior docs said 50f, which was wrong; (2) iOS `StreamRtmpPlugin.overlayLayout(from:)` also carries `bottomMarginFraction` (arg ÷ 400) and `horizontalInsetFraction` (arg ÷ 400) in addition to height/width/anchor/font/bg/text — body updated; (3) KMP/iOS `OverlayLayoutPrefs` bounds `ANCHOR_Y_MIN=0.30` / `ANCHOR_Y_MAX=0.97` define the vertical drag range used by `withAnchor()` on all three platforms — added to body; (4) Android Arrange drag: `BOARD_DRAG_MARGIN_SPAN=400.0` / `BOARD_DRAG_MARGIN_MAX=400.0` define the vertical travel range for board lift — added to body; (5) iOS `OverlaySheet` `onDisappear` reverts only when `!savedOnDismiss` — clarified in body. Session 13 changes: (1) `ArrangeOverlay.kt` — dedicated Compose overlay composable for Android Arrange mode; (2) `OverlayLayoutPrefsArrangeTest.kt` — unit tests for overlay prefs mutations in Arrange interactions; (3) `AgentDebugLog.kt` / `AgentDebugLog.swift` — structured debug instrumentation now committed. Session 12 structural changes: (1) Android `RtmpCredentialsStore.kt` now hosts two extension functions: `toEngineLayout(sponsorLogoUrls)` and `bottomMarginPx(frameHeightPx)` — converts `bottomMargin` via `(bottomMargin / 720f).coerceIn(0f, 0.2f) * frameHeightPx`; (2) iOS `StreamRecap` gains `durationText` computed property (`m:ss` / `h:mm:ss`); (3) `OverlaySpriteLayoutTest` expanded to 10 test cases. Session 11: (1) iOS `StudioMenuSheet` gains "Share watch link"; (2) iOS `OverlaySheet` parity with Android; (3) iOS `Models.swift` defines sponsor enums; (4) Android `BroadcastCameraUi` shared private composables; (5) `StreamRtmpPlugin.swift` sponsor fields not forwarded; (6) `OverlaySpriteLayoutTest` added.)*

*Confirmed in current code: iOS Go Live countdown is 3→1; Android `StreamRecap` (`durationSeconds`, `destinationLabel`, `watchUrl`) has no `title` field; iOS `StreamRecap` adds a `title: String` field and a `durationText` computed property (formats `durationSeconds` as `m:ss` / `h:mm:ss`); `StreamOverlayPolicy.refreshMode` accepts `hasPreviewListener` for API compat but does not use it in the decision branch (result is always `None`/`StreamRefresh`/`PreviewGlRefresh`); Android `StudioViewModel` live-timer increments a plain counter (`elapsed++`); iOS `StudioViewModel` stamps `liveStartedAt: Date?` and computes wall-clock elapsed; poll cadences — Android matchDay=8 s, remote-commands=1.5 s; iOS matchDay=5 s, remote-commands=1.5 s; `OverlayLayoutPrefs.sanitizeTheme()` now only accepts `"barlow"` (Barlow is the sole board layout); `RELAY_PROVIDERS` contains only `"play_cricket"` and `"cricheroes"` — `relay_source="native"` has no scraper (manual-only); iOS `StreamCameraEngine.buildStandbyImage()` composes a Floodlight-branded full-frame slate shown while backgrounded; iOS `StreamCameraEngine` has `pauseBlackObject: ImageScreenObject?` + `showPauseBlackOverlay()` / `hidePauseBlackOverlay()`; `CRICHEROES_HOSTS = {"cricheroes.com", "cricheroes.in"}` + `_is_cricheroes_host()`; `canonicalize_cricheroes_scrape_url` returns `""` for non-CricHeroes URLs; `OverlayLayoutPrefs` carries `activeSponsorIds: List<String>` (multi-select) + `effectiveSponsorIds()` + `resolveSponsorLogoUrls()` + `mergeSponsorPatch()` + `sponsorPatchJson()` on all three platforms; KMP `OverlayLayoutPrefs` exposes `boardDisplayScaleX()` / `boardDisplayScaleY()` / `clampedWidthFraction()` / `clampedHeightFraction()` / `effectiveFontScale()` / `boardScale()` / `withBoardScale()` / `withAnchor()`; `OverlayLayoutPrefs` carries `videoStabilization` and `keepScreenOn` on all three platforms; `CompanionSession` struct in iOS `Models.swift`; `previewOverlayPrefs()` / `revertOverlayPreview()` on both platforms; iOS `cameraConfigQueue` serial DispatchQueue; `BroadcastCameraUi` `onShare` crossfades Menu→Share when streaming; `StreamOverlayPolicyTest` covers all branches; Android `StudioViewModel` lifecycle hooks `onStudioVisible()`, `onConfigurationChanged()`, `onDeviceOrientationChanged()`, `onStudioHidden()`.*

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
| `stream_api.py` | Bearer-token auth (`bearer_org_from_request`), stream session CRUD, go-live / stop-live, broadcast status, match-day status; remote command queue (`/api/stream/<slug>/remote-commands` — polled by mobile at 1.5 s cadence; delivers `control` and `overlay` command types to paired companion) |
| `models_cricrelay.py` | SQLAlchemy models: `Organization` (+ `ui_theme` original/light/dark, branding fields), `ClubUser`, `RelayMatch`, `StreamSession` (+ `started_by_user_id`, `match_label`, `peak_viewers`, `viewer_sample_sum/count`, `final_score_json`, `vod_url`), `Sponsor` (+ `active_from`/`active_to` time bounds), `Tournament`, `Team`, `Player`, `Fixture`; `RELAY_PROVIDERS` dispatch table; `CRICHEROES_HOSTS` (`{"cricheroes.com", "cricheroes.in"}`); `_is_cricheroes_host()` (validates netloc against CRICHEROES_HOSTS, including subdomains); `_cricheroes_match_id_from_url()` (extracts numeric match id from `/scorecard/<id>` and `/individual/<id>` CricHeroes URL paths); URL canonicalization helpers (`canonicalize_play_cricket_scrape_url`, `canonicalize_cricheroes_scrape_url` — returns `""` for any non-CricHeroes URL; normalises `/individual/<id>/live` paths to `/scorecard/<id>/live`, `normalize_cricheroes_team_root`); `relay_source_to_provider()` (maps `relay_source` string → provider key); `resolve_provider_callable()` lazy-import; `provider_poll_interval_sec()` |
| `play_cricket_scraper.py` | Scrapes Play Cricket scorecard HTML; multi-pass fallback parsing (SCORE_LINE_RE → compact → fragmented → Total: blocks); rich per-innings batting/bowling/extras tables (`parse_batting_table`, `parse_bowling_table`, `parse_extras`); ball-by-ball via Playwright (`scrape_ball_by_ball`) run in a background daemon thread (`_fetch_bbb_background`) keyed by URL with `_bbb_cache`/`_bbb_threads`/`_bbb_lock` — triggers on over-count change, exposes cached balls to the snapshot while the fetch is in flight |
| `overlay_mapping_common.py` | Shared snapshot→overlay JSON conversion used by both Play Cricket and CricHeroes mappers: `snapshot_to_overlay()` (canonical entry point), `parse_home_away()`, `build_match_meta()`, `derive_live_state()`, `last_over_balls()`, `infer_total_overs()`, `clean_team_name()`, `format_batters()`, `format_bowlers()`, `batter_to_overlay()`, `bowler_to_overlay()` |
| `play_cricket_mapper.py` | Maps scraped rows → overlay JSON schema (delegates to `overlay_mapping_common.snapshot_to_overlay`) |
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

**Native mode models:** `Tournament` → `Team`/`Player` → `Fixture` supports non-ECB club-owned competitions. A `Fixture.score_match_slug` links to a `RelayMatch` for live scoring; `result_json` stores completed-game summary for NRR computation.

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
  - Android `OverlayWebViewCapture`: rasterizes at **encoded canvas width** (dynamic, max 1280); injects viewport + top-pinned `#overlay` CSS via `measureScript()`; GL sprite composites at 100% width at default prefs. Theme class injected via `OverlayThemeBridge.applyThemeScript()` on every measure pass.
  - iOS `OverlayWebViewCapture`: same dynamic capture width + measure loop (2 s interval); `StreamCameraEngine` scales and aligns via `OverlayLayout` geometry.
  - Overlay is now burned into the camera GL surface **during preview** (before Go Live) on both platforms via `StreamOverlayPolicy.PreviewGlRefresh` mode — operator sees WYSIWYG scoreboard in the preview.
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
- `Models.swift` — shared domain structs: `StreamMatch`, `OverlayLayoutPrefs` (full sponsor prefs + board-scale helpers: `boardScale()`, `withBoardScale()`, `withAnchor()`, `toEngineLayout(sponsorLogoUrls:)`, `mergeSponsorPatch(_:)`, `sponsorPatchDictionary()`, `effectiveSponsorIds()`, `resolveSponsorLogoUrls(from:)`); `ScoringConfig`, `GoLiveResult`, `StreamRecap` (includes `title: String` — iOS only; Android `StreamRecap` has no `title` field), `PlatformStatus`, `RtmpCredentials`, `RemoteCommand`, `RemoteCompanionContext`, `PairRemoteResult`, `CompanionSession` (companionToken + matchSlug), `MatchDayStatus`; `SponsorLayoutMode`, `SponsorDisplayMode`, `SponsorScrollDirection` as Swift caseless enums (static constants + `sanitize()` / `allowsMultiSelect()` / `isHorizontal()` / `isVertical()` / `isScroll()` helpers) — mirrors KMP `Models.kt` objects on the iOS side
- `CricMotion.swift` — animation constants and custom SwiftUI transitions
- `CricRelayAPI.shared` — URLSession-backed API client for all server calls
- `StreamCameraEngine.shared` — HaishinKit RTMP engine singleton (MediaMixer + RTMPStream, MTHKView preview, overlay WebView compositor, focus+AE lock via `cameraConfigQueue` serial queue, zoom, stabilisation, keep-screen-on, sponsor GL compositing, background standby slate, pause black overlay (`pauseBlackObject: ImageScreenObject?`), thermal monitoring, `syncEncoderForGoLive()` orientation re-sync)
- `OverlayThemeBridge.swift` — maps mobile theme names to HTML/CSS class names; provides `applyThemeScript(mobileTheme:)` and `urlWithTheme(baseUrl:mobileTheme:)`
- `StreamRtmpPlugin.swift` — Flutter method-channel bridge (MethodChannel `uk.co.cricrelay.stream/rtmp`, EventChannel `uk.co.cricrelay.stream/rtmp_events`, `CricrelayCameraViewFactory` id `cricrelay-camera-preview`). `overlayLayout(from:)` maps channel args to `StreamCameraEngine.OverlayLayout` carrying height/width/anchor fractions, `bottomMarginFraction` (arg ÷ 400), `horizontalInsetFraction` (arg ÷ 400), `fontScale`, `bgColor`, and `textColor` — sponsor and scroll-direction fields are **not** forwarded through this bridge. The native SwiftUI `StudioView` path calls `OverlayLayoutPrefs.toEngineLayout()` directly and carries the full config.
- `AgentDebugLog.swift` — debug instrumentation (location/message/data/hypothesisId/runId) for overlay pipeline diagnostics; no-op in production builds

#### Studio Module (`Features/Studio/`)

The broadcast control screen — the operator's primary live interface.

**`StudioView.swift`**
- `CameraPreviewView` (`UIViewRepresentable`): wraps `MTHKView` (HaishinKit), forwards single-finger taps for focus and two-finger pinch for zoom.
- `StudioView`: full-screen SwiftUI broadcast UI:
  - *Permission gate* — requests both camera and microphone on load; shows `permissionDeniedView` (with "Open Settings" deep-link) if camera access is denied.
  - *Top bar* — back button + ON AIR / PAUSED badge with mm:ss elapsed timer.
  - *Quick toggle row* — Focus Lock, Stabilize, Keep Screen On, Mic Mute (one-tap; surfaced on the camera screen for parity with Android).
  - *Tool button row* — Destination, Overlay, Scoring, Menu (open modal sheets).
  - *Shutter row* — Pause/Resume + main Go-Live/Stop shutter + zoom indicator.
  - *Focus reticle* — animated square with padlock icon when focus is locked.
  - *Countdown overlay* — 3→1 countdown before stream starts.
  - *Recap banner* — post-stream summary (title, destination, duration, watch URL).
  - *Error banner* — dismissible stream error notification.
  - *Thermal warning* — overheat banner with "Lower quality" CTA.
  - *Status handler* — registers a `StreamCameraEngine.setStatusHandler` callback on load to receive "connected" / "error" / "thermal" events; cleared on disappear.
- Six modal sheets: `DestinationSheet`, `OverlaySheet`, `ScoringSheet`, `PreflightSheet`, `StudioMenuSheet`, `PairRemoteSheet`.

**`StudioViewModel.swift`** (`@MainActor ObservableObject`)

Owns all broadcast state and orchestrates the go-live flow:

- *Destination management*: YouTube, Twitch (OAuth, gated on `PlatformStatus.ready/connected`), or Custom RTMP. Prefers a connected OAuth platform on load; falls back to Custom RTMP when `RtmpCredentialsStore` has saved credentials. `persistCustomRtmp()` saves to `RtmpCredentialsStore` (UserDefaults-backed, keyed by match slug).
- *Go-live flow*: camera preview warms up at 1280×720×30fps on Studio load; then `requestGoLive()` → `openPreflight()` → `confirmGoLive()` → 3-2-1 countdown → `goLive()`. Stream output: 1280×720, 2.5 Mbps, 30fps.
- *Live elapsed timer*: wall-clock `Task` ticking once/second while on-air; keeps counting while paused. Consistent with recap duration.
- *Broadcast status sync*: on go-live, pause/resume, and stop, pushes the new state back to the server via `api.updateBroadcastStatus()` (bidirectional).
- *Polling*: 5-second `matchDay` poll to sync broadcast status from the server.
- *Overlay sync*: on load, `syncOverlay()` pushes the match's `overlayEmbedUrl` into `StreamCameraEngine` so the scoreboard composites into the preview before going live. `previewOverlay(_:)` pushes overlay changes without persisting; `revertOverlayPreview()` restores the last saved state.
- *Focus lock*: `tapToFocus` → continuous AF/AE at tap point (iOS locks both focus and AE via `cameraConfigQueue`); `toggleFocusLock` → `StreamCameraEngine.lockFocus()` / `unlockFocus()`; focus reticle stays on screen while locked.
- *Quick toggles*: `toggleStabilization()` / `toggleKeepScreenOn()` mutate `OverlayLayoutPrefs` and persist via `saveOverlay()`.
- *Mic mute*: `toggleMicMuted()` — calls `StreamCameraEngine.setMicMuted(_:)` which detaches/re-attaches `AVCaptureDevice` audio.
- *Thermal*: `thermalLevel` updated via `StreamCameraEngine` thermal events; `onLowerQuality()` → `stepDownQuality()` stub.
- *Remote companion*: `startRemoteCommandPolling()` polls `/api/stream/<slug>/remote-commands` every 1.5 s; `dispatchRemoteCommand()` handles `control` commands; `applyRemoteOverlayPatch()` handles `overlay` commands (sponsor merge). `openPairRemote()` generates a QR deep-link (`cricrelay://pair?slug=…&token=…&base=…`) for `PairRemoteSheet`.
- *Recap*: `liveStartedAt: Date?` is stamped at Go Live; `liveElapsedSeconds` is computed from wall-clock on each timer tick (drift-free in background). On stop, builds a `StreamRecap` (title, destination label, duration from `liveStartedAt`, watch URL) and surfaces it as the recap banner.
- *Platform readiness*: `youtubeStatus` / `twitchStatus` cached as private vars after `loadStudioExtras()`; `recomputeDestinationReady()` derives `destinationReady` from these — mirrors Android's platform-status gating.
- *Camera restart*: `restartCameraPreview()` re-initialises the camera pipeline (error recovery path); `onOrientationChanged()` calls `StreamCameraEngine.updatePreviewForCurrentOrientation()`.

**`RtmpCredentialsStore.swift`** — UserDefaults persistence for custom RTMP URL / stream key / watch URL, keyed by match slug. The iOS equivalent adapter (`toEngineLayout()`) is defined as a method on `OverlayLayoutPrefs` in `Models.swift`.

**`StudioSheets.swift`** — All six sheet components:
- `DestinationSheet` — YouTube / Twitch / Custom RTMP picker.
- `OverlaySheet` (navigation title "Scoreboard overlay"; Android labels its equivalent "Board Edit") — board colour presets (Default/Dark/Black/Light/Teal swatches); sponsor section (enable/disable, layout mode: One logo/All at once/Carousel, sponsor chips, display mode: Fixed/Scroll top/Above board/Below board/Scroll bottom, scroll direction picker: RTL/LTR/TTB/BTT/Fixed when scroll mode active, scroll speed slider); width/height/font/opacity/position sliders; watermark toggle + text field. `onDisappear` calls `viewModel.revertOverlayPreview()` only when `!savedOnDismiss` (saves commit immediately on tap; revert only runs if the user swipes-to-dismiss without saving). Theme always `barlow`.
- `ScoringSheet` — Auto (Play-Cricket), Auto (CricHeroes), Manual.
- `PreflightSheet` — animated checklist before Go Live.
- `StudioMenuSheet` — restart camera preview; pair remote companion; "Share watch link" `ShareLink` action (shown when `viewModel.watchUrl` is non-empty).
- `PairRemoteSheet` — QR code for companion pairing.

---

### 6. Android App (`cricrelay-mobile/android/`)

Kotlin multi-module Gradle project (Hilt DI, Compose UI, KMP shared module).

| Module | Role |
|---|---|
| `app/` | Application shell; Compose `CricRelayNavHost` with type-safe serializable routes |
| `core/` | Shared domain models, network, DI |
| `feature/home` | `HomeScreen` + `CreateStreamScreen`; `HomeViewModel`; `CreateStreamViewModel`; stream management (rename/delete), GlanceRow stats, YouTube/Twitch OAuth cards, CricHeroes stream entry; `StudioBackdrop` with `BackdropMood.OnAir`/`Idle` context-aware theming; `StudioHero` banner; `LiveNowCard` (promotes the active stream above the list when live) |
| `feature/studio` | `StudioScreen`, `StudioViewModel`, `BroadcastCameraUi`, modal sheets (detail below) |
| `feature/scoring` | In-app scoring entry UI |
| `streaming/` | `StreamController` — RootEncoder 2.4.8 RTMP engine; PiP overlay; offscreen-GL WebView swap; BLE overlay push; `StreamOverlayPolicy`; `OverlayThemeBridge`; `AgentDebugLog` |
| `shared/` | Kotlin Multiplatform module (`CricRelayApiClient`, `StreamRepository`, `AuthRepository`, domain models) |

**Navigation (`CricRelayNavHost.kt`):** Type-safe `@Serializable` route objects (Login, Register, Onboarding, Home, `CreateStreamRoute(mode)`, `StudioRoute(matchSlug)`, `ScoringRoute(matchSlug)`). Default slide-in/out with `AppMotion` timing; Studio entry uses zoom+fade ("step into the broadcast").

**Studio module (`feature/studio`):**

`BroadcastCameraUi` — stateless Compose composable for the camera overlay:
- Separate `PortraitControls` and `LandscapeControls` layouts (detected via `BoxWithConstraints`).
- In portrait: tool row (Dest/Board/Score), then quick toggles row (Focus/Stabilize/Screen/Mic), then shutter row with pause button.
- In landscape: left rail = tool column (Dest/Board/Score vertically centered); right rail = quick toggle column (Focus/Stabilize/Screen/Mic) + Pause reveal + shutter (vertically centered, always visible and thumb-reachable).
- Both layouts share private composables (`ToolButtons`, `QuickToggles`, `PauseReveal`, `FocusReticle`, `StudioTopBar`, `StudioStatusMessages`, `ShutterLabel`) — identical control sets in both orientations, neither hides controls behind the other.
- Scoreboard and watermark burned into the camera GL surface (`StreamCameraEngine`) — no separate Compose overlay needed.
- Touch gestures: single-finger tap dispatches `onPreviewTap` for focus; two-finger pinch dispatches `onPinchZoom` via `rememberTransformableState` (same as iOS `CameraPreviewView`).
- `onShare: (() -> Unit)?` callback — when non-null and streaming, the top-bar Menu button is replaced by a Share button (animated crossfade via `AnimatedContent`). Built from `state.watchUrl` in `StudioScreen` and dispatched via `Intent.ACTION_SEND`.
- Thermal warning banner (`THERMAL_STATUS_SEVERE`) with "Lower quality" action (`THERMAL_STATUS_CRITICAL`).
- When `state.arrangeMode == true`, `StudioScreen` renders `ArrangeOverlay` (separate file `ArrangeOverlay.kt`) as a full-screen Compose overlay above the camera preview; `BroadcastCameraUi` itself becomes non-interactive during Arrange.

`StudioViewModel` owns broadcast state and orchestrates the full go-live flow:
- *Camera gate*: `StudioCameraGate.Readiness` (match loaded + permissions + surface bound) guards `prepareCamera()` — no partial starts.
- *Overlay preview*: `streamController.setPreviewOverlayListener` (legacy path retained) and `syncOverlay()`/`syncSponsorLayer()` push overlay to GL on preview; WYSIWYG preview via `PreviewGlRefresh` mode. `previewOverlayPrefs()` pushes overlay/sponsor changes live without persisting to the server; `revertOverlayPreview()` restores the last saved prefs on sheet dismiss.
- *Arrange mode*: `StudioUiState` carries `arrangeMode: Boolean`, `arrangeTarget: ArrangeTarget` (`Board`/`Sponsor`), `arrangeDraft: OverlayLayoutPrefs?`. `enterArrangeMode()` opens Arrange, closes sheets, seeds the draft from saved prefs. `pinchBoard(zoom)` calls `OverlayLayoutPrefs.withBoardScale()` (aspect-locked); `dragArrange(dxFraction, dyFraction)` updates `anchorX`/`bottomMargin` (Board) or `sponsorPositionX/Y` (Sponsor). All changes go to `previewOverlayPrefs()` (GL only, no network); `commitArrangeMode()` persists once via `updateOverlayPrefs()`.
- *PiP mode*: `streamController.pipMode` Flow collapses the UI to camera-only and closes sheets when entering PiP.
- *Go-live flow*: `requestGoLive()` → preflight sheet → `confirmGoLive()` → 3-2-1 countdown → `goLive()`. OAuth platforms call `streamRepository.goLive(slug, platform)` to get RTMP creds from the server; custom RTMP uses `RtmpCredentialsStore` (SharedPrefs, keyed by slug).
- *Broadcast sync*: `updateBroadcastStatus()` pushes streaming/paused/idle state back to server bidirectionally.
- *Focus/zoom*: `tapToFocusAt()` + `lockFocus()`/`unlockFocus()` mirrored from `StreamController`; focus reticle dismissed after 1.5 s unless locked. `onPinchZoom(scale)` multiplies the current zoom level and calls `setZoom()`.
- *Lifecycle hooks*: `onStudioVisible()` and `onConfigurationChanged()` both call `streamController.refreshNativePreview()`; `onDeviceOrientationChanged(surfaceRotationDegrees)` relays sensor rotation to `streamController.setDeviceOrientation()`; `onStudioHidden()` sets `previewSurfaceBound = false` and calls `streamController.hideNativePreview()` (complement to `onStudioVisible()`).
- *Match-day polling*: 8-second coroutine loop via `streamRepository.getMatchDayStatus()`.
- *Scoring mode*: `setScoringMode()` supports `"auto"`, `"manual"`, `"auto:cricheroes"` (provider prefix stripped before API call).
- *Stream cache*: `StreamDao` provides instant local match data while remote fetch completes (12 s timeout fallback).
- *Remote companion*: `startRemoteCommandPolling()` polls every 1.5 s; `handleRemoteCommands()` dispatches `control` (start_broadcast, stop_broadcast, mute_mic, toggle_focus_lock, toggle_sponsor) and `overlay` (sponsor patch via `RemoteCommand.mergeSponsorInto()`). `createPairingCode()` builds the `cricrelay://pair?…` deep-link payload.
- *Mic mute*: `onToggleMicMuted()` → `streamController.setMicMuted(next)`.
- *Thermal*: `thermalStatus` from `StreamCameraEngine` status events; `onLowerQuality()` → `streamController.stepDownQuality()`.
- *Sponsor layer*: `syncSponsorLayer()` resolves logo URLs from `Sponsor` list and calls `streamController.updateOverlay()` or `streamController.setSponsorLayer()` depending on whether an overlay URL is present.
- *Recap*: on stop, builds a `StreamRecap` and surfaces it.

**Studio sheets (`StudioSheets.kt`):**
- `DestinationSheet` — YouTube / Twitch / Custom RTMP picker with animated custom-RTMP field expansion.
- `OverlaySheet` (labelled "Board Edit") — "Arrange on screen" entry button; sponsor logo section (enable/disable, layout mode: One logo/All at once/Carousel, sponsor selector chips, display mode: Fixed/Scroll top/Above board/Below board/Scroll bottom, scroll direction picker: RTL/LTR/TTB/BTT/Fixed when scroll mode active, scroll speed slider); board colour presets (Default/Dark/Black/Light/Teal swatches); width/height/font/opacity/position sliders; stream watermark toggle + text field. Theme is always `barlow` — carousel removed.
- `ScoringSheet` — Auto (Play-Cricket), Auto (CricHeroes), Manual; "Open scorer in browser" deep-link.
- `PreflightSheet` — staggered animated checklist (camera ready, destination set, overlay URL present); gates Go Live button.
- `StudioMenuSheet` — restart camera preview; pair remote companion.

**`RtmpCredentialsStore.kt`** (Android) — SharedPreferences persistence for custom RTMP URL / stream key / watch URL, keyed by match slug (Hilt `@Singleton`). Also hosts the Android adapter layer via Kotlin extension functions: `OverlayLayoutPrefs.toEngineLayout(sponsorLogoUrls)` converts the full KMP domain prefs to `StreamCameraEngine.OverlayLayout` (all board, sponsor, scroll, watermark, and opacity fields); `OverlayLayoutPrefs.bottomMarginPx(frameHeightPx)` converts the raw `bottomMargin` double to physical pixels via `(bottomMargin / 720f).coerceIn(0f, 0.2f) * frameHeightPx`. The iOS equivalent adapter is `OverlayLayoutPrefs.toEngineLayout()` defined in `Models.swift`.

**Streaming infrastructure (`streaming/`):**

`StreamOverlayPolicy` — pure-object policy (no state):
- `RefreshMode` enum: `None` / `PreviewGlRefresh` / `StreamRefresh`.
- `refreshMode(isStreaming, hasPreviewListener, overlayUrlBlank)` → decides which refresh loop runs. `hasPreviewListener` is accepted for API compat but unused in the decision: result is always `None` (blank URL), `StreamRefresh` (streaming), or `PreviewGlRefresh` (otherwise).
- `shouldAttachGlOverlayOnPreview` / `shouldAttachGlOverlayOnStream` helpers.
- Overlay burns into GL during preview (not only stream) so WYSIWYG holds.
- `StreamOverlayPolicyTest` covers all `refreshMode` branches and both `shouldAttach*` helpers.
- `OverlaySpriteLayoutTest` covers `computePosition` (bottom-flush, margin lift, bounds), `fitScale` (aspect preservation under pinch, portrait overflow clamp), and `shouldForceTransparentBackground` (Compose host detection) — 10 test cases total.
- `OverlayLayoutPrefsArrangeTest` (`feature/studio`) covers board-scale bounds (`withBoardScale` clamp), drag-clamping for `anchorX`/`bottomMargin` mutations, and anchor-update helpers exercised by Arrange mode interactions.

`OverlayThemeBridge` — theme name → HTML/CSS mapping:
- `applyThemeScript(mobileTheme)` → JS snippet that sets CSS class on `#overlay`.
- `urlWithTheme(url, theme)` → appends `?theme=<theme>` query param.
- `cricketOverlayTheme(mobileTheme)` → canonical HTML class name.

`AgentDebugLog` — structured debug instrumentation (location/message/data map/hypothesisId/runId). Used in `BroadcastCameraUi`, `StudioViewModel`, `StreamCameraEngine`, `OverlayWebViewCapture`.

`StreamCameraEngine` — RootEncoder 2.4.8 RTMP engine singleton:
- `OverlayLayout` data class carries all scoreboard + sponsor GL parameters: `heightFraction`, `widthFraction`, `anchorX/Y`, `bottomMarginFraction`, `horizontalInsetFraction`, `fontScale`, `bgColor`, `textColor`, `opacity`, `watermarkEnabled/Text`, `sponsorEnabled`, `sponsorLogoUrl`, `sponsorLogoUrls` (up to 6), `sponsorLayoutMode` (single/multi/carousel), `sponsorCarouselIntervalSec`, `sponsorDisplayMode` (static/scroll_*), `sponsorPositionX/Y`, `sponsorSizeScale`, `sponsorOpacity`, `sponsorScrollSpeed`, `sponsorScrollDirection` (ltr/rtl/ttb/btt/fixed), `theme`.
- **Board-scale GL compositing**: `applyOverlaySprite()` uses `OverlaySpriteLayout.fitScale()` to compute a uniform aspect-locked scale from `widthFraction / REF_OVERLAY_WIDTH_FRACTION`, then `OverlaySpriteLayout.computePosition()` for position — the overlay strip never distorts regardless of pinch. This replaces ad-hoc width/height multipliers.
- Sponsor compositing: `ensureSingleSponsorFilter` / `ensureMultiSponsorFilters` / `ensureCarouselSponsorFilter` manage `SponsorSlot` (each holding an `ImageObjectFilterRender`); `fetchAndApplySponsor` fetches logos on a single-thread executor; carousel runs via `carouselRunnable` on main handler.
- **Sponsor scroll direction**: `sponsorScrollRunnable` at 33 ms ticks; local string constants `SCROLL_DIR_LTR`, `SCROLL_DIR_RTL`, `SCROLL_DIR_TTB`, `SCROLL_DIR_FIXED` (duplicated from `:shared` — `:streaming` has no dependency on `:shared`); BTT direction handled implicitly via the else branch in `scrollAxisHorizontal()` (no named constant); `scrollAxisHorizontal()` branches `applySponsorSprite()`: horizontal ticker (X animates, Y from display-mode band), vertical crawl (Y animates, X from drag position); `SCROLL_DIR_FIXED` renders once at `sponsorScrollOffsetPct = 0` (no timer).
- Watermark: `ensureWatermarkFilter` builds/caches a bitmap pill and positions via `WatermarkSpriteLayout.compute()`.
- Thermal: registers `PowerManager.OnThermalStatusChangedListener` (API ≥ Q) or polls every 30 s; adapts `overlayRefreshMs` (moderate: ×1.5, severe: ×2.5 capped at 3.5 s); emits `"thermal"` status events.
- Background resilience: `onEnterBackground()` → `cam.replaceView(ctx)` swaps encoder to offscreen GL interface so broadcast survives screen lock; `onExitBackground()` → `replaceView(openGlView)` restores on-screen rendering; `reattachBurnInsAfterSwap()` re-attaches filters after each GL swap.
- Memory pressure: `onMemoryPressure()` pauses overlay capture; `onMemoryRestored()` resumes.
- Mic mute: `setMicMuted(muted)` calls `cam.disableAudio()` / `cam.enableAudio()`.
- Step-down quality: `stepDownQuality()` stub (TODO: RootEncoder live-bitrate API).
- **Video fallback tiers**: `buildVideoFallbackTiers()` steps through up to 4 resolutions when `prepareVideo` fails at the requested tier — `1280×720@2.5Mbps` → `854×480@1.5Mbps` → `640×360@24fps@800kbps` — so broadcast can start on budget devices without a hard failure.

Note: Android has focus-only lock (RootEncoder 2.4.8 has no AE-lock API); iOS locks both focus and exposure.

---

### 7. Shared KMP Module (`cricrelay-mobile/shared/`)

Kotlin Multiplatform library consumed by both Android and (via Kotlin/JS or future native) iOS.

| Component | Responsibility |
|---|---|
| `CricRelayApiClient` | Ktor-backed HTTP client: login/register, listStreams, listFixtures, createPlayCricketStream, createCricHeroesStream, goLive/stopLive, updateBroadcastStatus, getScoring/setScoring, getOverlayPrefs/setOverlayPrefs, setRelayPause, youtube/twitch OAuth + status, deleteStream, renameStream, getAppBuilds, listSponsors, pollRemoteCommands, pairRemote |
| `AuthRepository` | Wraps `SessionStore` + `CricRelayApiClient`; login/register/logout/onboarding state |
| `StreamRepository` | Thin coroutine wrappers over `ApiClientProvider.get()` calls; used by Android ViewModels |
| Domain models | `StreamMatch`, `OverlayLayoutPrefs` (full sponsor prefs + `activeSponsorIds: List<String>` multi-select + `effectiveSponsorIds()` + `resolveSponsorLogoUrls()` + `SponsorLayoutMode` / `SponsorDisplayMode` / `SponsorScrollDirection` objects + `mergeSponsorPatch()` / `sponsorPatchJson()`; board-scale helpers: `clampedWidthFraction()` / `clampedHeightFraction()` / `boardDisplayScaleX()` / `boardDisplayScaleY()` / `effectiveFontScale()` / `boardScale()` (uniform pinch scale) / `withBoardScale(scale)` (aspect-locked copy, clamps to `BOARD_SCALE_MIN=0.4`–`BOARD_SCALE_MAX=1.0`) / `withAnchor(x, y)` (drag reposition, clears bottomMargin; vertical range clamped to `ANCHOR_Y_MIN=0.30`–`ANCHOR_Y_MAX=0.97` on all three platforms)), `ScoringConfig`, `MatchDayStatus`, `PlatformStatus`, `GoLiveResult`, `FixturesResponse`, `FixtureItem`, `Sponsor`, `RemoteCommand` (with `mergeSponsorInto()`), `RemoteCompanionContext`, `PairRemoteResult`, `BroadcastStatus` |

`CricRelayApiClient` validates `isAllowedApiBaseUrl` (HTTPS or local), normalizes base URLs, and handles HTML-vs-JSON response disambiguation with typed `ApiException` messages.

**URL resolution:** `StreamMatch.fromJson(json, baseUrl)` resolves relative `overlay_embed_url` paths to absolute URLs using `resolveAbsoluteUrl(baseUrl, overlay)` when the path starts with `/` — mobile ViewModels receive ready-to-use absolute URLs regardless of how the server returns them.

**Offline fallback:** `ScoringConfig.localFallback(baseUrl, matchSlug, mode)` constructs a full `ScoringConfig` from local URL conventions (`/m/<slug>/input`, `/m/<slug>/score`, etc.) for use when the server returns no scoring payload.

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
              (KMP shared module)   (rasterized by OverlayWebViewCapture
                    │                → GL sprite via StreamCameraEngine)
                    │
             RTMP push (HaishinKit / RootEncoder)
                    │
              YouTube / Twitch CDN
                    │
                 Viewers

Remote Companion ──1.5s poll──► Flask /api/stream/<slug>/remote-commands
                    │
              StudioViewModel (Android/iOS)
                    │
          dispatchRemoteCommand / applyRemoteOverlayPatch
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
| **Broadcast resilience** | Android: RootEncoder `replaceView` offscreen-GL swap + PiP overlay keeps stream alive on locked screen. iOS: background task + standby slate (branded full-frame card composited over frozen camera via `ImageScreenObject`); audio session stays active under UIBackgroundModes audio. Pause (both platforms): full-frame black overlay — Android uses `BlackFilterRender`; iOS uses `pauseBlackObject: ImageScreenObject?` (`showPauseBlackOverlay()` / `hidePauseBlackOverlay()`) |
| **Overlay in preview (WYSIWYG)** | `StreamOverlayPolicy.PreviewGlRefresh` mode burns the scoreboard into the camera GL surface before Go Live. On Android, the GL filter chain is active during preview; on iOS, `ensureOverlayObject` + `startOverlayRefresh` run from `preparePreview`. The operator sees exactly what viewers will see. |
| **Arrange mode (Android)** | Pre-live direct manipulation of the board and sponsor position. `enterArrangeMode()` seeds a draft from saved prefs; pinch calls `withBoardScale()` (aspect-locked, 0.4×–1.0×); drag moves `anchorX`/`bottomMargin` (Board) — `BOARD_DRAG_MARGIN_SPAN=400.0` maps drag fraction to raw-pixel board lift, capped at `BOARD_DRAG_MARGIN_MAX=400.0` — or `sponsorPositionX/Y` (Sponsor). All changes push to GL preview via `previewOverlayPrefs()` with no network call; `commitArrangeMode()` persists once on Done. iOS Arrange pending. |
| **Barlow-only overlay theme** | `OverlayLayoutPrefs.sanitizeTheme()` now accepts only `"barlow"`. All prior theme strings (classic/neon/minimal/compact/ai/stadium) silently map to `"barlow"`. The Barlow overlay deploys as the sole board layout. `OverlayWebViewCapture` CSS targets `body.board-barlow` selectors. |
| **Sponsor GL compositing** | `OverlayLayout` carries up to 6 sponsor logo URLs; `StreamCameraEngine` supports three layout modes (single/multi/carousel) and five display modes (static/scroll_top/scroll_bottom/scroll_above_board/scroll_below_board). Carousel auto-advances at configurable interval; scroll modes animate at 30 fps with configurable direction (LTR/RTL/TTB/BTT/Fixed). |
| **Sponsor scroll direction** | `SponsorScrollDirection` object (KMP, iOS, Android) provides `LTR`/`RTL`/`TTB`/`BTT`/`FIXED` constants. Android `StreamCameraEngine` handles both axes: horizontal ticker animates the X position of each sprite; vertical crawl animates the Y position. `FIXED` renders once at the scroll-band position (no timer). Direction is persisted in `OverlayLayoutPrefs.sponsorScrollDirection` and synced via remote companion patch. |
| **Remote companion** | StudioViewModel on both platforms polls the server every 1.5 s for queued remote commands. A paired companion (tablet/second phone) can start/stop broadcast, mute mic, toggle focus lock, and push live sponsor overlay changes without touching the camera phone |
| **Modular-monolith migration** | Strangler pattern toward a Match spine. `cricrelay_core` scoring engine is the first extracted module; 8 parallel tracks running |
| **Gradle module path** | `:feature:x` not `:android:feature:x` — the `android/` directory is **not** part of the Gradle path |
| **Token auth v2** | Bearer org-token (itsdangerous, `issue_stream_token(org)`). `ClubUser` supports multi-member logins per club (admin/member roles). `Organization` is the unit of auth for the mobile API |
| **CricHeroes as relay source** | CricHeroes is now a first-class relay provider alongside Play Cricket — same `RELAY_PROVIDERS` dispatch table, same overlay pipeline, selectable live from the Scoring sheet |
| **PCS BLE archived** | `feature/pcs-ble` removed from both iOS and Android main apps. Protocol decoder + server ingest endpoint kept dormant for possible future reactivation |
| **Android type-safe nav** | Compose Navigation with `@Serializable` route objects; Studio entry uses zoom+fade instead of slide to signal mode shift |
| **Stream management** | Streams can be renamed and deleted from the Home screen (long-press tile → management sheet) on both platforms; `CricRelayApiClient` exposes `renameStream` / `deleteStream` |
| **Thermal management** | Both platforms monitor device thermal state and adapt the overlay capture refresh interval under heat pressure (Android: `PowerManager.OnThermalStatusChangedListener`; iOS: `ProcessInfo.thermalStateDidChangeNotification`). Severe heat surfaces a warning banner; critical heat offers a "Lower quality" button (`stepDownQuality()` — stub pending live-bitrate API) |
| **Mic mute** | New quick toggle on both platforms. Android disables RootEncoder audio; iOS detaches AVCaptureDevice audio from the MediaMixer. Muted state is ephemeral — not persisted across Studio loads |

---

## Key External Dependencies

| Dependency | Used by | Purpose |
|---|---|---|
| HaishinKit | iOS `StreamCameraEngine` | RTMP encode + push, MTHKView preview, MediaMixer screen compositor, ImageScreenObject GL compositing |
| RootEncoder 2.4.8 | Android `streaming/` | RTMP encode + push, OpenGlView, ImageObjectFilterRender GL compositing |
| Ktor | KMP `shared/` `CricRelayApiClient` | HTTP client (Android + future multiplatform) |
| YouTube Data API v3 | Server `youtube_stream.py` | Broadcast CRUD, stream key |
| Twitch Helix API | Server `twitch_stream.py` | Stream key + channel status |
| Play Cricket | Server `play_cricket_scraper.py` | Live scorecard HTML |
| CricHeroes | Server `cricheroes_scraper.py` | Live scorecard scrape (Playwright) |
| SQLAlchemy + Postgres | Server, `cricrelay_store` | Persistence |
| AVFoundation | iOS `StreamCameraEngine` | Camera capture, focus+AE lock, stabilisation |
| Hilt | Android `app/`, feature modules | Dependency injection |
