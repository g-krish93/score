# CricRelay Camera Quality (Focus + Stabilization) — Pending Work

**Status:** Implementation COMPLETE on both platforms (July 2026). Android compiles and all unit
tests pass (shared: 7, feature/studio: 12); debug APK assembles. iOS is **compile-unverified**
(implemented on Windows — no Mac available). What remains is on-device verification and the iOS
build, listed below.

## What shipped (for context)

- **Android tap-to-focus** — `cricrelay-mobile/android/streaming/src/main/java/uk/co/cricrelay/stream/Camera2Controls.kt`
  reflects into RootEncoder 2.4.8's private `builderInputSurface` / `cameraCaptureSession` /
  `cameraHandler` (field names verified against the 2.4.8 AAR bytecode) and runs a correct
  sensor-space, weight-1000, AF+AE, trigger-cycled focus. `StreamCameraEngine.tapToFocusAt` uses it
  first, falling back to RootEncoder's broken `tapToFocus(MotionEvent)` only if reflection fails.
- **Stabilization levels** — boolean replaced by `StabilizationLevel` (0 Off / 1 Standard / 2
  Cinematic) in shared `Models.kt` and iOS `Models.swift`. Android: Standard = EIS 1 + OIS,
  Cinematic = EIS `PREVIEW_STABILIZATION`(2) via reflected builder + OIS (clamps down when mode 2
  unsupported). iOS: `.off` / `.standard` / `.cinematicExtended`. Default = Standard.
  On-device finding (July 2026): RootEncoder's builder/session only exist once the camera opens
  (`startPreview`), NOT at the prepare-time hook the original handoff assumed — so the engine
  re-applies Cinematic (and logs `reflectOk`) right after `startPreview` in
  `preparePreviewOnMain`; the pre-prepare pass covers Off/Standard via RootEncoder's flag replay.
- **Wire format** — new `stabilization_level` int is source of truth; legacy `video_stabilization`
  bool still written/read for old clients (kept in sync via `withStabilizationLevel` helpers).
- **UI** — quick-toggle chip cycles Off → Standard → Cinematic on both platforms (hidden while
  streaming on Android, FOV caption only for Cinematic); iOS Board sheet has a segmented picker.
- **ProGuard** — keep rules in `cricrelay-mobile/android/app/proguard-rules.pro`, wired into the
  release build type (inert today: `isMinifyEnabled = false`).
- **Stream quality upgrade (July 2026, after user feedback that quality trailed the stock
  camera):** HIGH-tier Android devices now capture AND stream 1920×1080 @ 4.5 Mbps (engine caps
  raised to 1920×1080 / 8 Mbps; `DeviceCapabilities.defaultStream*` picks per tier; fallback
  tiers still step down 1080→720→480→360 if prepare fails; overlay raster up to 1920 on HIGH).
  `Camera2Controls.applyCaptureQuality` additionally sets `CONTROL_CAPTURE_INTENT_VIDEO_RECORD`
  + FAST NR/edge after startPreview — RootEncoder uses TEMPLATE_PREVIEW (verified in bytecode),
  which gets the leaner preview processing pipeline on Pixel-class HALs; the video-record intent
  selects the video-tuned path. Never use HIGH_QUALITY NR/edge on a live stream (HAL may drop
  fps). iOS: encode targets raised to 1080p/4.5 Mbps (`StreamCameraEngine.defaultStream*`);
  TODO on Mac — verify the pinned HaishinKit MediaMixer captures ≥1080p (if its session preset
  defaults to 720p the encode is an upscale; raise the preset).
- **Auto resolution at Go Live (July 2026):** right before connecting, the app times a discarded
  2 MB POST to the club server (`/api/net-probe`, auth-required — NEEDS SERVER DEPLOY) and sizes
  the encoder to the measured uplink: ≥6 Mbps → 1080p @ 4.5 Mbps, less (or probe timeout) →
  720p @ 2.5 Mbps (`GoLiveQualityPolicy`, unit-tested; `ensurePreparedQuality` re-prepares
  pre-stream only). Probe unavailable (old server / offline) ⇒ tier default stands — never
  downgrade without evidence. Status line shows "Checking connection…" during the probe.
- **Adaptive bitrate + broadcast HUD (July 2026):** RootEncoder's `BitrateAdapter` is wired on
  RTMP connect — `onNewBitrate` (1/sec) + `RtmpStreamClient.hasCongestion(20f)` drive
  `setVideoBitrateOnFly`, so the encoder bitrate follows the real uplink (resolution stays fixed
  per session — mid-stream re-prepare is the golden-path crash). `StreamStats` flows engine →
  `StreamController.streamStats` → studio HUD: a pill under the LIVE badge showing quality
  ("1080p30"), the actual sent Mbps, and a health dot (green = at target, amber = adapter stepped
  down, red = congested). Android only; iOS needs a hand-rolled adapter (HaishinKit has none) —
  Mac follow-up.

## Pending

### 1. Android on-device verification (debug build)

- **Tap-to-focus works at all:** tap different areas of the preview → the tapped subject becomes
  sharp (previously it did nothing). Watch logcat for the one-time
  `Camera2Controls.reflectOk=true` line (emitted via `CricrelayLog`) to confirm the correct path —
  not the fallback — is running.
- **Focus under zoom:** zoom to ~2×, tap a subject off-center → focus still lands on it.
- **Stabilization modes actually apply:** set Standard, then Cinematic; confirm via a
  `CaptureCallback` reading `CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE` (expect 1 vs 2 on an
  API 33+ device that supports mode 2) and `LENS_OPTICAL_STABILIZATION_MODE` (expect 1). On a
  device without mode 2, Cinematic must clamp to 1 — no crash, still stabilized.
- **Regression:** Go Live still works end-to-end; the stabilization chip stays hidden while
  streaming; padlock focus lock/unlock still works on top of the new tap (tap releases the lock,
  as before).

### 2. ~~Server round-trip~~ DONE — prefs are now local-first (July 2026)

On-device testing confirmed the server was stripping `stabilization_level` (whitelist in
`server/app.py`), silently resetting Cinematic to Standard on every studio re-entry. Two fixes
landed, and the architecture changed by product decision:

- **Local-first storage (the real fix):** camera/device settings (stabilization level,
  keep-screen-on) now live ONLY on the phone (`StudioLocalPrefsStore` — SharedPreferences on
  Android, UserDefaults on iOS inside `RtmpCredentialsStore.swift`) and are never read back from
  the server. Board/sponsor overlay prefs are cached per match slug on-device; the cache is
  authoritative for the studio. The server copy is only a seed for fresh installs and a
  best-effort mirror (fire-and-forget POST) so the web dashboard and remote companion stay
  informed. The studio works fully offline.
- **Server whitelist fix (kept, but no longer load-bearing for the phone):** `app.py` now
  persists `stabilization_level` (sanitized 0–2, kept consistent with the legacy bool) for old
  clients and dashboard mirroring. Deploy whenever convenient.

### 3. iOS build + verification (needs a Mac)

- Compile the app — the Swift changes (`Models.swift`, `StreamCameraEngine.swift`,
  `StreamRtmpPlugin.swift`, `StudioViewModel.swift`, `StudioView.swift`, `StudioSheets.swift`)
  are pattern-following but unverified.
- Verify the quick pill cycles Off/Standard/Cinematic and maps to
  `.off`/`.standard`/`.cinematicExtended`; the Board-sheet segmented picker keeps the legacy bool
  in sync; tap-to-focus still works (it was already correct — untouched).

### 4. Release-build check (only when minify is enabled)

Release currently ships with `isMinifyEnabled = false`, so R8 never runs and reflection is safe.
If minification is ever enabled, repeat the focus + stabilization checks on a release build; if
`reflectOk=false` there, the keep rules in `proguard-rules.pro` aren't matching — fix before
shipping.

### 5. ~~Optional polish~~ DONE — focus lock is now a full 3A freeze (focus + exposure + WB)

On-device testing (July 2026) confirmed RootEncoder's `disableAutoFocus()` loses the tapped focus
on lock: it sets `AF_MODE_OFF` without a `LENS_FOCUS_DISTANCE`, so the HAL applies the builder
default (0 = infinity). Fixed: `Camera2Controls.lockFocusAtCurrentDistance` reads the converged
`LENS_FOCUS_DISTANCE` from a capture result, then holds `AF_MODE_OFF` at that exact distance
(RootEncoder path kept as reflection-unavailable fallback).

**Extended (user feedback: stream still "went out of focus" under a locked camera when players
crossed the boundary-line lens / in wind).** Root cause: at a boundary the depth of field spans the
pitch, so the lens was never the issue — auto-exposure re-metered (brightness pulse) and
auto-white-balance shifted (colour pulse) as an object crossed. The lock is now a **full 3A freeze**:
`lockFocusAtCurrentDistance` also sets `CONTROL_AE_LOCK` + `CONTROL_AWB_LOCK` (each guarded by its
`*_LOCK_AVAILABLE` characteristic) and returns the converged distance; `unlockFocusReleasing3A`
clears both and resumes `AF_MODE_CONTINUOUS_VIDEO`. The lock also **survives Go Live**: the
pre-RTMP rotation re-sync used to drop it via `resetFocusState`, so the engine now remembers the
operator's intent (`focusLockDesired` + `lockedFocusDistance`) and re-applies it with
`Camera2Controls.reapplyLock` after the re-prepare (safe — pre-RTMP, reuses the existing
builder/session). iOS: `lockFocus` now also sets `whiteBalanceMode = .locked` (it already locked
focus + exposure), and `tapToFocus` converges-and-holds via one-shot `.autoFocus` (was continuous)
to match Android. Reflection-unavailable Android devices still get focus-hold only (AE/AWB keep
adapting) — acceptable degradation. Re-verify tap → lock kills brightness/colour pulsing under a
crossing player, survives Go Live, and unlock resumes all three. **Trade-off:** a full lock won't
track gradual light changes (dusk, floodlights, cloud) — the operator re-taps the pitch and re-locks.

## Standing constraints

- **Do not bump RootEncoder past 2.4.8** without re-verifying the three reflected field names in
  `Camera2ApiManager` (and `Camera2Base.cameraManager`).
- Never re-`prepareVideo` / `stopPreview` mid-stream (crashes Pixel); the new code only reuses the
  existing builder + session — keep it that way.
- Stabilization is a pre-stream setting; keep the UI gate while streaming.
- Zoom is at parity — leave it alone.
