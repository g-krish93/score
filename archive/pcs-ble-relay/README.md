# CricRelay PCS BLE Relay (Android)

Forwards Play Cricket Scorer BLE notifications to your CricRelay ingest URL.

## Configure (from dashboard)

1. Create a **PCS BLE stream** on [CricRelay Streams](https://cricrelay.co.uk/dashboard).
2. Open **Relay app setup** — copy **ingest URL** and **Bearer token**.
3. In the app: **Settings** → paste both → **Save**.
4. **Scan BLE** → tap the iPad / PCS device → keep app open during the match.

## Build APK

```bash
# Docker (no local Flutter required)
cd score
docker run --rm -v "%cd%/pcs-ble-relay:/app" -w /app ghcr.io/cirruslabs/flutter:stable bash -lc "flutter pub get && flutter build apk --release"
copy pcs-ble-relay\build\app\outputs\flutter-apk\app-release.apk static\pcs-relay.apk
```

CI also builds on push (see `.github/workflows/build-pcs-relay-apk.yml`).
