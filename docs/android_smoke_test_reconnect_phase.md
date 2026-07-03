# Android on-device smoke test — reconnect + StreamPhase (July 2026)

Validates the P0/P1 changes before the engine decomposition: RTMP auto-reconnect,
stream-lost teardown, burn-in warnings, and the StreamPhase state machine.

**Build under test:** `C:\tmp\gradle-builds\cricrelay-mobile\app\outputs\apk\release\app-release.apk`
(local build, debug-signed fallback).

## Setup (5 min)

1. **Install.** The local build is debug-signed, so if the phone has a CI/store copy the
   signature won't match — uninstall it first (this clears saved custom-RTMP keys; you'll
   re-enter them):
   ```
   adb uninstall uk.co.cricrelay.stream
   adb install C:\tmp\gradle-builds\cricrelay-mobile\app\outputs\apk\release\app-release.apk
   ```
2. **Start the log capture** in a PowerShell window and leave it running for the whole session:
   ```
   adb logcat -c
   adb logcat -s Cricrelay | Tee-Object -FilePath smoke-test.log
   ```
3. Sign in, pick a test stream. Custom RTMP or YouTube both work; Test 7 needs Custom RTMP.

**The one line that matters all session:** any log line matching
`phase: <Intent> refused in <Phase>` outside of Test 7 is a finding — note what you were
doing when it appeared. `phase: Idle -> Prepared (Prepare)` style lines are normal.

## Tests

### 1. Baseline broadcast (2 min)
Open Studio → Go Live (through the countdown) → confirm the platform shows video with the
scoreboard burned in → Stop.
- ✅ Log shows `Idle -> Prepared`, `Prepared -> Live` on start; `Live -> Idle` then
  `Idle -> Prepared` on stop. No `refused` lines.

### 2. Pause / resume (1 min)
Go Live → Pause → confirm viewer side goes black + silent → Resume.
- ✅ Log: `Live(...) -> Live(paused=true...)` and back. Overlay returns after resume.

### 3. Lock screen mid-broadcast — twice (3 min)
While live: lock the phone, wait 10 s, unlock. Then immediately lock/unlock again.
This is the double-surface-loss path the phase machine now guards.
- ✅ Stream stays live on the platform both times (offscreen GL swap).
- ✅ Log: `Live -> Live(background=true)` and back; **no `Release refused in Live` panic and
  no frozen frame on the viewer side.** A `refused` line here that coincides with a healthy
  stream means the machine correctly blocked the old bug — note it either way.

### 4. PiP + navigation (2 min)
While live: home gesture → PiP window appears → reopen. Then navigate back out of Studio to
Home while live, and re-enter Studio.
- ✅ Broadcast never drops; preview re-binds; controls elevated above the camera.

### 5. Reconnect self-heal (3 min) — the headline test
While live: enable airplane mode **and check Wi-Fi actually turned off** (newer Android keeps
Wi-Fi on in airplane mode — toggle it off manually if needed). Wait ~5 seconds, then restore
the network.
- ✅ Amber "Connection lost — reconnecting…" banner appears; LIVE badge stays.
- ✅ Stream resumes on the platform within a few seconds of network return, scoreboard intact.
- ✅ Log: `RTMP reconnect 1/3 in 1000ms…` (maybe 2/3), then a `connected` event. No manual
  Go Live needed.

### 6. Give-up teardown (3 min)
While live: airplane mode on and **leave it on ~60 s** (3 attempts + backoff + socket
timeouts need that long), network back on after.
- ✅ Red "Broadcast lost: … Go Live again." banner; LIVE badge and timer gone; the
  foreground-service notification disappears; preview still runs.
- ✅ After network returns, the dashboard/server shows the match idle (the app POSTs it).
- ✅ Go Live again succeeds cleanly (phase went `Live -> Idle -> Prepared`).

### 7. Rejected stream key (2 min, Custom RTMP)
Set destination to Custom RTMP with a real URL but a garbage stream key → Go Live.
- ✅ "Stream key rejected…" (or connection-failure) banner after the retries; **no stuck LIVE
  badge**, notification gone, next Go Live with the real key works.

### 8. Orientation + arrange sanity (2 min, not live)
Before going live: rotate the phone (Auto), toggle the orientation lock, enter Arrange →
pinch/drag the board → Done; kill and reopen the app — layout persisted.
- ✅ Each rotation logs `Prepared -> Idle` + `Idle -> Prepared` (re-prepare), no `refused`.
  Arrange still saves (this exercises the extracted ArrangeMode/OverlaySync controllers).

## Optional
- **rtmps:** if you have YouTube's `rtmps://a.rtmps.youtube.com/live2` endpoint handy as a
  Custom RTMP target, one Go Live proves the TLS path.
- **Burn-in warning:** with sponsors enabled, on a fresh install (empty logo cache) go live in
  airplane mode → banner "A sponsor logo failed to load…" should appear once.

## What to report back
1. `smoke-test.log` (or just any `refused` / `RTMP reconnect` / `burn-in degraded` lines).
2. Which test numbers passed/failed and anything visually off on the viewer side.

Green across 1–6 = the phase machine matches reality → safe to start the engine
decomposition (OverlayCompositor / SponsorLayer / CameraSession extraction).
