# PCS BLE Relay APK (R&D)

The site serves **`/download/pcs-relay.apk`** from **`static/pcs-relay.apk`** (this folder’s parent).

## Rebuild

```bash
cd pcs-ble-relay-android
./gradlew assembleRelease   # Windows: gradlew.bat assembleRelease
cp app/build/outputs/apk/release/app-release.apk ../static/pcs-relay.apk
```

CI: `.github/workflows/build-pcs-relay-apk.yml` on push to `main`.

## App setup

1. Install APK on Android phone (enable “Install unknown apps” if needed).
2. CricRelay dashboard → PCS BLE stream → copy **ingest URL** + **Bearer token**.
3. App **Settings** → paste both → **Scan BLE** → connect to PCS iPad.
4. Copy **overlay URL** into OBS.
