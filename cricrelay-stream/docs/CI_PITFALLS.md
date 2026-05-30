# CI pitfalls — Validate CricRelay Stream

The **Validate CricRelay Stream** job (`validate-cricrelay-stream.yml` → `scripts/ci_validate.sh`) runs **before every APK build**. If it fails, **no new APK is published** to cricrelay.co.uk.

Run locally before pushing:

```bash
bash cricrelay-stream/scripts/ci_validate.sh
```

(from repo root, with Flutter SDK installed)

---

## Gate order (all must pass)

| Step | Command | Fails when |
|------|---------|------------|
| 1 | `flutter analyze lib` | **Any** analyzer issue (warnings count too) |
| 2 | `flutter test` | Any test fails |
| 3 | `apply_android_overlay.sh` + `./gradlew :app:compileReleaseKotlin` | Kotlin compile error in `android-custom/` |

---

## Documented failures (do not repeat)

### 1. Unused catch stack (`unused_catch_stack`)

**Symptom:** `warning • The stack trace variable 'st' isn't used • ... unused_catch_stack` → exit code 1.

**Cause:** `catch (e, st)` without using `st`. CI treats analyzer warnings as failures.

**Fix:** Use `st` (e.g. in debug logs) or write `catch (e)` only.

**Date:** 2026-05 — debug instrumentation commit blocked APK 1.2.7.

---

### 2. Dart digit separators (`2_500_000`)

**Symptom:** `This requires the experimental 'digit-separators' language feature`.

**Cause:** Underscores in numeric literals. Project SDK is `>=3.2.0 <4.0.0`; digit separators are not enabled in CI.

**Fix:** Use plain numbers: `2500000`, not `2_500_000`. Same in **Dart and avoid in Kotlin** if ever copied to Dart-facing docs.

**Date:** 2026-05 — `native_encoder_profile.dart` blocked tests.

---

### 3. Kotlin syntax / API mismatches

**Symptom:** `compileReleaseKotlin` fails (e.g. missing function body, wrong `prepareVideo` args, `autoHandleOrientation` missing in RootEncoder 2.4.8, **`Unresolved reference`** after a rename).

**Fix:** Always run the Kotlin compile step locally or wait for CI validate; edit files under `android-custom/` only (overlay copies into `android/` in CI).

**Examples:**

- Broken `ensureOverlayCapture()` body after refactor → compile error.
- `prepareVideo(w, h, fps, bitrate, 0, 320)` — wrong overload (320 is not rotation).
- `autoHandleOrientation` — not in RootEncoder 2.4.8.
- Renamed `StreamCameraEngine.onPreviewViewSized` → `onPreviewSurfaceReady` but left old name in `CameraPreviewHost.kt` → CI Kotlin compile fail.

---

### 3b. Incomplete refactor across `android-custom/` (Dart green, Kotlin red)

**Symptom:** `flutter analyze` and `flutter test` pass; **`compileReleaseKotlin` fails** with `Unresolved reference 'someMethod'`.

**Cause:** Method renamed/removed in one Kotlin file but not all call sites updated. Easy to miss because Dart CI steps pass first and Kotlin compile runs last (~4+ min).

**Fix before every push that touches native code:**

```bash
# From repo root — replace OldName with the symbol you removed/renamed
grep -r "OldName" cricrelay-stream/android-custom/

# Full gate (required — analyze + test alone is NOT enough)
bash cricrelay-stream/scripts/ci_validate.sh
```

**Rule:** Never push `android-custom/` changes after only running `flutter analyze` / `flutter test`. The Kotlin compile step is mandatory.

**Date:** 2026-05 — `onPreviewViewSized` left in `CameraPreviewHost.kt` blocked 1.2.7+12 APK.

---

### 4. RootEncoder preview before GL surface ready

**Symptom:** App closes on broadcast screen; device log shows `getSurfaceTexture(...) must not be null` in `preparePreviewOnMain`.

**Cause:** Calling `prepareVideo` / `startPreview` from layout size callbacks before `OpenGlView` `SurfaceHolder.surfaceChanged` (RootEncoder requires a valid surface).

**Fix:** Register `openGlView.holder.addCallback` and call `preparePreview` only when `holder.surface.isValid`. Guard with `isPreviewSurfaceValid()`; do not hammer `prepareCamera` in a Dart loop.

**Date:** 2026-05 — Pixel 9 broadcast-screen crash (session 0ad848).

---

### 6. EIS (video stabilization) device-dependent

**Symptom:** Shaky stream outdoors; EIS toggle has no effect on some phones.

**Cause:** `enableVideoStabilization()` returns false on unsupported OEMs.

**Fix:** Test on target volunteer devices; do not enable OIS together with EIS (RootEncoder warns against both).

---

### 7. Pushing without running validate

**Symptom:** Users install an old APK; fixes never reach cricrelay.co.uk.

**Fix:** After every change under `cricrelay-stream/`, ensure validate is green on GitHub Actions before telling anyone to reinstall.

---

### 8. Slider missing `value:` (compile / runtime)

**Symptom:** `flutter analyze` error on `Slider` without required `value` parameter.

**Fix:** Every `Slider` must bind `value:` to state (see `overlay_layout_sheet.dart` width slider).

---

### 9. Missing imports after refactor

**Symptom:** `Undefined name 'Permission'` or `CrGlassPanel` not found.

**Fix:** Keep `permission_handler` import in `broadcast_screen.dart`; keep `studio_shell.dart` import where `CrGlassPanel` is used.

---

### 10. Wrong relative imports under `lib/widgets/studio/`

**Symptom:** `flutter analyze` — target of URI doesn't exist for `../theme/app_theme.dart` from `studio_hero.dart`.

**Fix:** From `lib/widgets/studio/`, use `../../theme/app_theme.dart`, `../ui_kit.dart`, and `studio_shell.dart` (same folder).

---

### 11. Switch cases without `break` (Dart 3)

**Symptom:** Analyzer error in `rtmp_platform.dart` `waitForConnected` event switch.

**Fix:** Add explicit `break;` after each non-empty case body.

---

## Agent / developer checklist before push

- [ ] `flutter analyze lib` — zero issues
- [ ] `flutter test` — all green
- [ ] If `android-custom/` changed: overlay script + Kotlin compile (or full `ci_validate.sh`)
- [ ] After any Kotlin rename/remove: `grep -r OldSymbol cricrelay-stream/android-custom/` — zero hits
- [ ] Do **not** push native changes validated with analyze/test only — Kotlin compile must pass
- [ ] No `2_500_000`-style literals in Dart
- [ ] No unused variables in `catch`, callbacks, or debug code
- [ ] Bump `pubspec.yaml` version when shipping user-facing APK

---

## Related files

- Workflow: `.github/workflows/validate-cricrelay-stream.yml`
- Build (depends on validate): `.github/workflows/build-cricrelay-stream-apk.yml`
- Script: `cricrelay-stream/scripts/ci_validate.sh`
- Native source of truth: `cricrelay-stream/android-custom/` (not checked-in `android/` stub)
