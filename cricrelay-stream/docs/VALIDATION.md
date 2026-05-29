# CricRelay Stream — validation before push

CI runs the same checks automatically. Run locally before pushing to avoid failed APK builds.

## Quick check (Git Bash on Windows)

From the repo root:

```bash
bash cricrelay-stream/scripts/ci_validate.sh
```

Requires: Flutter 3.27.x, Java 17, Android SDK 35 (same as GitHub Actions).

## What gets validated

| Step | Catches |
|------|---------|
| `flutter pub get` | Broken `pubspec.yaml` / dependencies |
| `flutter analyze lib` | Dart errors in app code |
| `flutter create` + `apply_android_overlay.sh` | Drift between `android-custom/` and generated `android/` |
| `./gradlew :app:compileReleaseKotlin` | **Kotlin compile errors** (e.g. `Val cannot be reassigned`) before the 4+ min APK build |

## GitHub Actions

| Workflow | When |
|----------|------|
| **Validate CricRelay Stream** | Push/PR when `cricrelay-stream/**` changes |
| **Build CricRelay Stream** | Runs **after** validate passes; builds APK + optional IPA |
| **Deploy to EC2** | Server deploy (skips on `[skip deploy]` APK-only commits) |
| **CI** | Python server smoke tests |

## Rules for native Android changes

1. Edit files under `cricrelay-stream/android-custom/` — never hand-edit generated `android/` (CI regenerates it).
2. Run `ci_validate.sh` before pushing Kotlin changes.
3. Do not assign to read-only properties in `OpenGlView` / View (use methods like `setOpaque` only if the API supports it).

## Go Live (volunteer test on device)

1. Camera preview visible before tapping Go Live.
2. Pre-flight sheet: camera, stream key, network all OK.
3. Tap Go Live — app must **not** close; status shows Connecting then Live.
4. Scoreboard overlay may appear on stream 1–2s after connect (by design).
5. Stop stream confirms before ending.

## If CI still fails

1. Open the failed job log.
2. For Kotlin: search `compileReleaseKotlin` or `e: file://`.
3. For Flutter: search `error •` in analyze output.
4. Fix in `android-custom/` or `lib/`, re-run `ci_validate.sh`, push again.
