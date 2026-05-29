# Google Play Console checklist — CricRelay Live

Use this when publishing `uk.co.cricrelay.stream` (Flutter app in `cricrelay-stream/`).

## Build artifacts

- **Play Store:** upload the signed **AAB** from CI (`cricrelay-stream-aab` artifact) or `flutter build appbundle --release` locally with release keystore env vars set.
- **Club sideload / EC2:** APK at `static/cricrelay-stream.apk` (same CI workflow).

Version in `pubspec.yaml` must match `STREAM_APP_VERSION` on the server (default env).

### GitHub Actions secrets (release signing)

| Secret | Description |
|--------|-------------|
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded upload keystore (`.jks` or `.keystore`) |
| `ANDROID_STORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias |
| `ANDROID_KEY_PASSWORD` | Key password |

Without these secrets, CI builds a **debug-signed** release (fine for internal APK sideload, not for Play).

### Firebase (optional)

| Secret / file | Description |
|---------------|-------------|
| `google-services.json` | Place under `android/app/` before overlay apply, or commit for your Firebase project |
| See `docs/FIREBASE_SETUP.md` | Crashlytics is skipped in Gradle when the file is missing |

## Store listing copy (draft)

**Short description (80 chars):**  
Live cricket with burned-in scores — one phone, RTMP to YouTube or Twitch.

**Full description:**  
CricRelay Live helps club volunteers stream matches with a live scoreboard overlay. Paste your YouTube Studio or Twitch stream key, lock the overlay, and go live from one Android phone. Club scoring can run automatically from Play-Cricket or manual entry.

## Screenshots (capture on a physical device)

1. Login (club server + email)
2. Home — stream list
3. Broadcast — camera preview + overlay frame
4. Go Live pre-flight checklist
5. Live — red LIVE badge + dock
6. Stream key dialog (antenna)
7. Overlay locked chip
8. Create stream / fixtures

## Data safety (Play Console form)

Declare collection/use as applicable:

| Data type | Purpose | Encrypted at rest |
|-----------|---------|-------------------|
| Email / account | Club login | Server-side (your deployment) |
| Camera | Live video to RTMP | In transit to ingest |
| Microphone | Stream audio | In transit |
| Stream keys (RTMP) | Publish to YouTube/Twitch | **Android EncryptedSharedPreferences** (`flutter_secure_storage`) |
| Crash logs | Stability (Firebase Crashlytics, if configured) | Firebase |
| Product analytics | Funnel events (Firebase Analytics, if configured) | Firebase — no PII in event params |

## Permissions & policies

- **Privacy policy URL:** `https://cricrelay.co.uk/privacy` (in-app links on login + About)
- **Terms:** `{baseUrl}/terms`
- **Foreground service:** `dataSync` — keeps CPU awake while live; camera runs in Activity (see `AndroidManifest.xml`).
- **POST_NOTIFICATIONS:** requested on Android 13+ before going live (FGS notification).
- **Camera / microphone:** rationale dialogs with Open Settings on denial.

## Content rating

Typical questionnaire: sports streaming tool, user-generated live video, no simulated gambling, club-managed accounts.

## Internal testing track

1. Upload AAB to **Internal testing**.
2. Install from Play; verify Go Live, stop confirmation, Crashlytics (if Firebase configured).
3. Fix any FGS / Data safety review feedback before production.

## Keystore backup

Document keystore location and passwords offline. Enabling **Play App Signing** is one-way — keep upload key backup.
