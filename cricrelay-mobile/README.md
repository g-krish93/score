# CricRelay Mobile (Native)

Next-generation native mobile app for CricRelay Live — Kotlin Multiplatform shared core, Jetpack Compose (Android), SwiftUI (iOS).

## Structure

```
cricrelay-mobile/
├── shared/          # KMP: API client, models, repositories, session
├── android/         # Compose UI + Hilt + Room + streaming (RootEncoder)
└── ios/             # SwiftUI + native streaming overlay
```

## Build (Android)

```bash
cd score/cricrelay-mobile
./gradlew :shared:check :app:assembleRelease
```

Release APK: `android/app/build/outputs/apk/release/app-release.apk`

Package ID: `uk.co.cricrelay.stream` (same as legacy Flutter app for seamless upgrade).

## Features

- Login, onboarding, stream list, create Play-Cricket / PCS BLE streams
- Broadcast studio with native RTMP + scoreboard overlay (Android)
- PCS BLE relay mode (in-app, replaces separate `pcs-relay.apk`)
- Offline stream list cache (Room)

## Legacy apps

The Flutter apps under `cricrelay-stream/` and `pcs-ble-relay-android/` are **deprecated** — see [docs/MIGRATION.md](docs/MIGRATION.md).

## Backend

No Flask API changes required. Same endpoints as the Flutter app.
