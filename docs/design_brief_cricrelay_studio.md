# Design Brief: CricRelay Studio (Broadcast Screen) Redesign

## What this is
The **Studio** is CricRelay's live-broadcast screen — a volunteer at a UK cricket club points their phone at the pitch and streams the match (with a burned-in scoreboard and sponsor logos) to YouTube, Twitch, or any RTMP server. This brief asks for a full UI redesign of that screen and its satellite surfaces. Deliver design references as self-contained HTML prototypes (same convention as the splash handoff): every state, both orientations, using the tokens below.

## The user & context (design for this, not a generic camera app)
- One volunteer operator, often also scoring/managing the team. Not a broadcast professional.
- **Outdoors in direct sunlight** — the palette was built for it; keep contrast floors: 7:1 body text, 4.5:1 interactive, ~10:1 for anything over live video.
- Time pressure before the first ball; setup must be doable in under a minute after the first match.
- Phone is usually on a tripod once live — during the match the operator glances from distance; live-state indicators must read from ~2m.
- Everything floats over a full-screen live camera preview. The scoreboard/sponsor/watermark you see in preview are **composited into the actual stream** (the preview is the truth, not a mockup).

## Design system (fixed — do not invent a new one)
"Floodlight" palette, dark-only:

| Token | Value | Use |
|---|---|---|
| Background / Canvas / Surface / Elevated / Sunken | `#0A0E15` / `#0D1219` / `#141A26` / `#1C2433` / `#070A10` | surfaces |
| Primary (stadium gold) | `#FFC233` (bright `#FFD15C`, deep `#E8A912`) | hero CTA, LIVE. **Always ink text on gold (`#1A1305`), never white** |
| Accent (sky) | `#57C7FF` / blue `#4DA3FF` | ready / info / success |
| Warning (coral) | `#FF9466` | caution (thermal, paused) |
| Error | `#FF5C7A` | danger only |
| Text | `#FFFFFF`, muted `#C7CDD9`, dim `#98A1B3` | |
| Borders | `#323B4D`, subtle `#222A3A`, glass `rgba(255,255,255,0.20)` | |
| Platform | YouTube `#FF0033`, Twitch `#9146FF` | destination chips |

- CTA gradient: PrimaryBright→PrimaryDeep. Accent sweep: `#57C7FF→#4DA3FF`.
- Spacing 4/8/16/24/32; radii 10/14/18/24; **min touch target 48dp** (outdoor thumbs — bias larger for live controls).
- Motion: enters 240ms ease-out from 95% scale (never pop from zero), exits 160ms, sheets 260/180ms, press ≤160ms at 97%. Respect reduced-motion.
- Brand: 1e pitch-mark logo (green `#2E5E32` / cream `#D8C9A3`); wordmark Archivo 800; body DM Sans. App UI type: SansSerif bold headlines (−0.5 tracking), 15sp body.

## Hard product constraints
1. **Portrait AND landscape layouts required.** Landscape is the primary broadcast orientation (rails left/right of preview); portrait is the setup/handheld case (stacked bottom controls).
2. Orientation has a 3-state mode (Auto / Landscape / Portrait lock) — **cannot change while live** (stream is locked to starting orientation).
3. Stabilization (Off / Standard / Cinematic) **cannot change while live** (requires camera re-prepare). Cinematic narrows field of view — needs a caption/warning.
4. Go Live is gated: camera ready + destination ready (+ scoreboard URL present). Design the blocked state as guidance, not a dead button.
5. Android uses bottom sheets, iOS uses navigation sheets — design one sheet pattern; we adapt per platform.
6. Remote commands (from a paired second phone) can flip mic/focus/sponsor/start/stop at any time — states must be able to change without a local tap.
7. Overlay edits preview live (~80ms debounce) — sliders/pickers must work while the camera preview stays visible behind or beside them.

## Screen states to design (all of them)
1. **Loading** — "Opening studio…"
2. **Permission denied** — camera/mic refused; full-screen recovery with Settings link.
3. **Preview / idle (pre-live)** — the home state. Camera full-screen, setup tools, GO LIVE.
4. **First-run precheck** — guided 3 steps: Camera (auto) → Arrange board & sponsor → Ready. Skippable, never shows again.
5. **Arrange mode** — full-screen direct manipulation over the preview: drag scoreboard or sponsor (target picker), pinch to resize board. Cancel / Done. Everything else suppressed.
6. **Preflight** ("Ready to go live?") — 3 pass/fail checks (camera, destination, scoreboard) with fix hints → Go Live / Cancel.
7. **Countdown** — cinematic 3-2-1 "Going live…" with Cancel.
8. **LIVE** — ON AIR badge (pulsing) + elapsed timer, stream-health HUD (quality e.g. "1080p30", bitrate, green/amber/red health dot), pause button, STOP, share watch-URL.
9. **Paused** — distinct PAUSED (coral) state; play to resume.
10. **Reconnecting / error** — status line + dismissible error banner.
11. **Thermal warning** — overheating banner (severe = warn; critical = adds "Lower quality" action). Can coexist with LIVE.
12. **Stream ended → recap** — duration, destination, watch URL, share; back to idle.

## Functionality inventory (design a home for each)

**Camera controls (on-screen):**
- Tap-to-focus with reticle (white; gold + padlock when locked)
- Focus lock toggle (locks focus+exposure on the pitch — flagship feature, deserves prominence)
- Pinch zoom + zoom level pill (hidden ≤1.1×)
- Mic mute toggle (red when muted)
- Stabilization 3-level (pre-live only)
- Orientation mode (pre-live only)
- Keep-screen-on toggle
- No torch/flash (intentionally absent)

**Setup tools (open sheets):**
- **Destination**: YouTube (OAuth) / Twitch (OAuth) / Custom RTMP (server URL + masked stream key + optional watch URL, persisted locally). Chip shows current destination + ready state.
- **Board Edit (overlay)**: Arrange entry point; board colour (5 presets); width 25–98%; height 10–28%; font 60–200%; opacity 20–100%; watermark toggle + custom text (default "Visit cricrelay.co.uk").
- **Sponsors** (inside Board Edit today): enable toggle; layout Single / All at once / Carousel (interval 2–30s, up to 6 logos); display Fixed / Scroll top / Above board / Below board / Scroll bottom; scroll direction (RTL/LTR/TTB/BTT) + speed 0.3–3×; size 30–300%; opacity. Empty state points to the web dashboard upload. This is the **Pro/sponsor revenue surface** — make it feel premium.
- **Scoring source**: Auto Play-Cricket / Auto CricHeroes / Manual scorer + "Open scorer in browser".
- **Menu**: Restart camera preview; Pair Remote.
- **Pair Remote**: QR code (deep link), expiry hint; remote can start/stop, mute mic, toggle focus lock, toggle sponsor.

**Live-only:** pause/resume, share, health HUD, thermal actions, recap.

## Known problems with the current UI (fix these)
- **Control soup**: 3 tool buttons + 4 quick-toggle pills + shutter + pause + top bar all visible at once. Propose a clear hierarchy: *setup things* (destination, board, scoring — used before live) vs *glance things* (focus lock, mic, live status — used during). Consider collapsing setup tools once live.
- **"Board Edit" sheet is overloaded**: it contains scoreboard styling AND sponsors AND watermark AND stabilization AND keep-screen-on. Camera settings do not belong in an overlay sheet — regroup.
- **iOS/Android drift**: iOS lacks the stream-health HUD and live share button; Android hides some toggles in landscape. Design once, both platforms implement the same thing.

## Redundancies — design these OUT (proposed removals)
1. **Board "Position" slider + sponsor X/Y sliders** in the Board Edit sheet — Arrange mode (drag/pinch on the preview) does the same job better. Arrange becomes the *only* placement tool; sheets keep style-only controls.
2. **Duplicate stabilization control** (quick-toggle pill AND sheet picker) — one home, in a camera-settings group, pre-live only.
3. **Duplicate keep-screen-on** (quick toggle AND sheet toggle) — set-once setting; one home in camera settings, off the main toolbar.
4. **Destination chip (top) and "Dest" tool button (bottom) both open the same sheet** — merge into one destination affordance that shows platform + ready state.
5. **Watermark** — keep, but demote (set-once, low frequency).

## Deliverables
1. Studio idle (portrait + landscape)
2. Studio LIVE (portrait + landscape) incl. paused, thermal, reconnecting variants
3. Precheck, Arrange, Preflight, Countdown, Recap
4. All sheets (Destination, Board/Overlay, Sponsors, Scoring, Menu, Pair Remote)
5. Component states: shutter (enabled/blocked/live), toggles (on/off/disabled), badges (ON AIR/PAUSED), health dot, focus reticle (free/locked)
6. Motion notes for: go-live countdown, ON AIR pulse, sheet transitions, arrange enter/exit — within the token timings above

**Fidelity:** high-fidelity HTML prototypes, self-contained, tokens above, no external assets beyond Google Fonts (Archivo, DM Sans).
