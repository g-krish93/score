# CricRelay Stream

One-phone YouTube live streaming with CricRelay score overlay (ThirdMan-style).

## Setup

1. **Server:** set `YOUTUBE_CLIENT_ID`, `YOUTUBE_CLIENT_SECRET`, `PUBLIC_BASE_URL` (or `YOUTUBE_REDIRECT_URI`), optional `YOUTUBE_TOKEN_ENCRYPTION_KEY`.
2. **Dashboard:** Streams → **Connect YouTube** → create a Play-Cricket stream.
3. **Build app** (requires Flutter SDK + Android SDK):

```bash
cd cricrelay-stream
flutter pub get
flutter build apk --release
```

Copy APK to `static/cricrelay-stream.apk` for dashboard download (optional).

## Android burn-in

On Android, **Go Live** streams the **rear camera** plus a **scoreboard overlay** composited in hardware (OpenGL). App buttons and settings are **not** sent to YouTube — only camera + overlay.

## iOS

GitHub Actions builds iOS on `macos-14` when [Apple signing secrets](docs/IOS_CI_SETUP.md) are set. The IPA is served at `/download/cricrelay-stream.ipa` with an OTA install link for Safari.

After login, the app and dashboard show **Android (APK)** and **iPhone (install)** links from `/api/stream/app-builds`.

Live camera + burned-in scoreboard RTMP is **Android-first** on iOS; volunteers can still sign in and use stream-key RTMP where supported.

## API

- `POST /api/auth/login` — `{ email, password }` → `{ token }`
- `GET /api/streams` — Bearer token
- `POST /api/stream/go-live` — `{ match_slug }` → RTMP credentials
- `POST /api/stream/stop`

See [docs/STREAM_APP_BETA.md](../docs/STREAM_APP_BETA.md) for beta checklist.
