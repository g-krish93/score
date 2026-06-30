# Archived: PCS BLE Relay (Android)

**Status:** Unmaintained as of 2026-06-30.

Superseded by removing the PCS BLE feature from the main CricRelay apps. Server-side
decode/ingest (`server/pcs_protocol.py`, `/relay/pcs-ingest`) is kept dormant — this
standalone APK would still technically work if resurrected, but is no longer built or
shipped.

## Manual build (historical)

Previously built by `.github/workflows/build-pcs-relay-apk.yml` (removed):

```bash
cd archive/pcs-ble-relay-android
chmod +x gradlew && ./gradlew assembleRelease --no-daemon
# APK: app/build/outputs/apk/release/app-release.apk
```

Requires Java 17 and Android SDK.
