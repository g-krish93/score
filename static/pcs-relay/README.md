# PCS BLE Relay APK (R&D)

## BLE modes in the app (Settings)

| Mode | Use when |
|------|----------|
| **Scan all devices** | Discovering what PCS actually advertises (nRF Connect style) |
| **Scan PCS preset UUIDs** | Testing community UUIDs without hardcoding behaviour |
| **Advertise as scoreboard** | Matches [buildyourownscoreboard](https://buildyourownscoreboard.wordpress.com/optional-play-cricket-scorer-app-integration/) — PCS connects **to** the phone |

Preset UUIDs (optional, may need verification on your kit):

- Service: `5a0d6a15-b664-4304-8530-3a0ec53e5bc1`
- Characteristic: `df531f62-fc0b-40ce-81b2-32a6262ea440`
- Advertise name: `BT-Scoreboard`

## CricRelay

1. Dashboard → PCS BLE stream → copy ingest URL + Bearer token.
2. App Settings → paste those → pick BLE mode.
3. Overlay URL in OBS (not entered in the app).

## Rebuild APK

```bash
cd pcs-ble-relay-android
gradlew.bat assembleRelease
copy app\build\outputs\apk\release\app-release.apk ..\static\pcs-relay.apk
```
