# CricRelay Architecture

*Auto-updated by Stop hook. Last updated: 2026-06-29.*

---

## Overview

CricRelay is a live cricket broadcast platform. An operator uses the mobile app to stream a match via RTMP to YouTube or Twitch, while a scoreboard overlay (scraped from Play Cricket or entered via BLE scoring device) composites onto the video in real time. A Flask backend coordinates stream sessions, platform OAuth, overlays, and viewer stats.

---

## Components

### 1. Python Flask Server (`server/`)

Central API and orchestration layer.

| File | Responsibility |
|---|---|
| `app.py` | Flask app factory, routes, CORS |
| `stream_api.py` | Stream session CRUD, go-live / stop-live, broadcast status |
| `models_cricrelay.py` | SQLAlchemy models: `ClubUser`, `StreamSession`, `Sponsor` |
| `play_cricket_scraper.py` | Scrapes Play Cricket scorecard HTML |
| `play_cricket_mapper.py` | Maps scraped rows → overlay JSON schema |
| `scoring_bridge.py` | Routes scoring events to overlay + cricrelay_core |
| `scoring_shadow.py` | Shadow-writes live score to a secondary store |
| `relay_poller.py` | Polls BLE relay for PCS device score packets |
| `pcs_protocol.py` | Decodes PCS BLE binary scoring protocol |
| `youtube_stream.py` | YouTube Data API v3 broadcast lifecycle |
| `twitch_stream.py` | Twitch Helix stream key / status |
| `rate_limit.py` | Per-IP rate limiting middleware |
| `scraper_worker.py` | Background worker: polls Play Cricket on interval |

**Data stores:** PostgreSQL (primary), SQLite (migration source via `migrate_sqlite_to_postgres.py`).

**Auth:** Token v2 (invite-code based club-user tokens). Multi-user per-club dashboard.

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

- Served by Flask at `/stream` (rich overlay endpoint).
- Receives score JSON via polling or WebSocket push.
- Team names are dash-formatted; T20 format is inferred pre-match.
- The `cricket_overlay.html` / `overlay_lovable_export.html` files are standalone variants for development.

---

### 5. iOS App (`cricrelay-mobile/ios/`)

Swift/SwiftUI native app. Feature modules under `Features/`:

| Feature | Contents |
|---|---|
| `Auth/` | Login, token storage, club selection |
| `Home/` | Match list, stream session selection |
| `CreateStream/` | New match / stream session wizard |
| `Studio/` | Camera preview, broadcast controls (detail below) |
| `Scoring/` | In-app scoring entry UI |
| `PcsBle/` | BLE scan + pair with PCS scoring device |

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
- *Go-live flow*: `requestGoLive()` → `openPreflight()` → `confirmGoLive()` → 5-second countdown → `startStream()`. Sends camera preview warm-up at 1280×720×30fps before the stream starts.
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

Kotlin multi-module Gradle project.

| Module | Role |
|---|---|
| `app/` | Application shell, navigation |
| `core/` | Shared domain models, network, DI |
| `feature/` | Feature modules (Studio, Home, Scoring, …) |
| `streaming/` | RootEncoder-based RTMP streaming engine (camera, encoder, BLE overlay push) |

Android Studio module is the equivalent of iOS's `StudioViewModel` + `StreamCameraEngine`; RootEncoder 2.4.8 handles encoding (note: no AE-lock API — iOS has full focus+exposure lock, Android has focus-only).

---

### 7. BLE PCS Scoring Relay

Two relay implementations that receive ball-by-ball data from a PCS Digi-scorer hardware device over BLE and forward it to the server:

- `pcs-ble-relay/` — Flutter/Dart cross-platform relay (scan, pair, decode, POST to server)
- `pcs-ble-relay-android/` — Android-native BLE relay (lighter weight, no Flutter runtime)

Protocol decoder lives in `server/pcs_protocol.py` (server-side) and `cricrelay_core/codec.py` (domain-level).

---

## Data Flow

```
PCS Device (BLE)
      │  BLE packets
      ▼
pcs-ble-relay ──POST──► Flask server (/relay/score)
                               │
Play Cricket website           │  scoring_bridge
      │  HTTP scrape           │  → cricrelay_core state machine
      ▼                        │
scraper_worker ─────────────── │
                               ▼
                         Postgres (StreamSession, BallEvent)
                               │
                    ┌──────────┴──────────┐
                    │                     │
               WebSocket / poll       /stream overlay
                    │                     │
              Mobile app            Browser HTML/CSS
              (status + scores)     (composited into video via
                                     StreamCameraEngine WebView)
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
| **ECB relay** | Stream + relay an official ECB match; Play Cricket scraper drives the overlay |
| **Non-ECB native** | Club creates its own match; scoring entered via PCS device or in-app scoring UI |

The `StudioViewModel` / `StudioView` support both modes via `ScoringConfig` — scoring mode is set per-match on the server and reflected in the Scoring sheet.

---

## Key External Dependencies

| Dependency | Used by | Purpose |
|---|---|---|
| HaishinKit | iOS `StreamCameraEngine` | RTMP encode + push, MTHKView preview |
| RootEncoder 2.4.8 | Android `streaming/` | RTMP encode + push |
| YouTube Data API v3 | Server `youtube_stream.py` | Broadcast CRUD, stream key |
| Twitch Helix API | Server `twitch_stream.py` | Stream key + channel status |
| Play Cricket | Server `play_cricket_scraper.py` | Live scorecard HTML |
| SQLAlchemy + Postgres | Server, `cricrelay_store` | Persistence |
| AVFoundation | iOS `StreamCameraEngine` | Camera capture, focus/AE lock, stabilisation |
