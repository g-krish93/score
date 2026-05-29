# Firebase (Crashlytics + Analytics) — optional

Crashlytics and Analytics are **optional**. The app and CI build without Firebase when `google-services.json` is absent.

## Enable Firebase

1. Create a Firebase project for `uk.co.cricrelay.stream`.
2. Add an Android app with package name `uk.co.cricrelay.stream`.
3. Download `google-services.json` to:

   ```
   cricrelay-stream/android/app/google-services.json
   ```

   For CI, either commit this file (if acceptable for your repo) or add a workflow step that writes it from a secret before `apply_android_overlay.sh`.

4. Run `flutter pub get` and a release build. Gradle applies Google Services + Crashlytics plugins only when the file exists (see `android-custom/app_build.gradle`).

## What gets wired

- `lib/main.dart` — `AppAnalytics.initialize()` (fails silently without config)
- `lib/services/analytics_service.dart` — events: `login_success`, `stream_created`, `go_live_started`, `go_live_connected`, `go_live_failed`, `stream_stopped`
- RTMP errors logged as Crashlytics breadcrumbs (no stream keys)
- `templates/privacy.html` — crash/analytics disclosure

## iOS

Firebase for iOS requires `GoogleService-Info.plist` and additional setup; not required for this Android Play sprint.
