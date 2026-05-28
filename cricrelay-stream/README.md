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

Uses `rtmp_broadcaster` camera RTMP; overlay appears in preview. Full window capture (burn-in) is Android-first; use landscape + score WebView at bottom of preview.

## API

- `POST /api/auth/login` — `{ email, password }` → `{ token }`
- `GET /api/streams` — Bearer token
- `POST /api/stream/go-live` — `{ match_slug }` → RTMP credentials
- `POST /api/stream/stop`

See [docs/STREAM_APP_BETA.md](../docs/STREAM_APP_BETA.md) for beta checklist.
