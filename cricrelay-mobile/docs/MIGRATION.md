# Migration from Flutter to Native

## Status

| App | Status |
|-----|--------|
| `cricrelay-stream/` (Flutter) | Deprecated — use `cricrelay-mobile/` |
| `archive/pcs-ble-relay-android/` | Archived — PCS BLE removed from main apps (see `ARCHIVED.md`) |
| `archive/pcs-ble-relay/` (Flutter) | Archived |

## Cutover checklist

1. Run beta matches per [STREAM_APP_BETA.md](../../docs/STREAM_APP_BETA.md) on native app
2. CI builds from `cricrelay-mobile/` → `static/cricrelay-stream.apk` → EC2 (`build-cricrelay-mobile.yml` on every `cricrelay-mobile/**` push)
3. Dashboard `/api/stream/app-builds` serves new APK/IPA URLs
4. Archive Flutter overlay scripts after two successful club matches

## Session migration

Native app reads the same secure token keys as Flutter (`stream_api_token_secure`, `stream_api_base`) for seamless upgrade without re-login.

## Rollback

Keep last Flutter APK in git history; revert static hosting commit if needed.
