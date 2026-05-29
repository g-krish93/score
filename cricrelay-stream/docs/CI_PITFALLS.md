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

**Symptom:** `compileReleaseKotlin` fails (e.g. missing function body, wrong `prepareVideo` args, `autoHandleOrientation` missing in RootEncoder 2.4.8).

**Fix:** Always run the Kotlin compile step locally or wait for CI validate; edit files under `android-custom/` only (overlay copies into `android/` in CI).

**Examples:**

- Broken `ensureOverlayCapture()` body after refactor → compile error.
- `prepareVideo(w, h, fps, bitrate, 0, 320)` — wrong overload (320 is not rotation).
- `autoHandleOrientation` — not in RootEncoder 2.4.8.

---

### 4. RootEncoder preview before GL surface ready

**Symptom:** App closes on broadcast screen; device log shows `getSurfaceTexture(...) must not be null` in `preparePreviewOnMain`.

**Cause:** Calling `prepareVideo` / `startPreview` from layout size callbacks before `OpenGlView` `SurfaceHolder.surfaceChanged` (RootEncoder requires a valid surface).

**Fix:** Register `openGlView.holder.addCallback` and call `preparePreview` only when `holder.surface.isValid`. Guard with `isPreviewSurfaceValid()`; do not hammer `prepareCamera` in a Dart loop.

**Date:** 2026-05 — Pixel 9 broadcast-screen crash (session 0ad848).

---

### 5. Pushing without running validate

**Symptom:** Users install an old APK; fixes never reach cricrelay.co.uk.

**Fix:** After every change under `cricrelay-stream/`, ensure validate is green on GitHub Actions before telling anyone to reinstall.

---

## Agent / developer checklist before push

- [ ] `flutter analyze lib` — zero issues
- [ ] `flutter test` — all green
- [ ] If `android-custom/` changed: overlay script + Kotlin compile (or full `ci_validate.sh`)
- [ ] No `2_500_000`-style literals in Dart
- [ ] No unused variables in `catch`, callbacks, or debug code
- [ ] Bump `pubspec.yaml` version when shipping user-facing APK

---

## Related files

- Workflow: `.github/workflows/validate-cricrelay-stream.yml`
- Build (depends on validate): `.github/workflows/build-cricrelay-stream-apk.yml`
- Script: `cricrelay-stream/scripts/ci_validate.sh`
- Native source of truth: `cricrelay-stream/android-custom/` (not checked-in `android/` stub)
