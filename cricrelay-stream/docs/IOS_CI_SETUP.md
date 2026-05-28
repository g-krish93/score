# iOS build on GitHub Actions

The workflow builds a signed **IPA** when Apple signing secrets are configured. Without them, CI only verifies the iOS project compiles.

## Requirements

- Apple Developer Program membership
- App ID / bundle ID: `uk.co.cricrelay.stream`
- **Apple Distribution** certificate (`.p12`) for ad-hoc or App Store export
- **Ad Hoc** provisioning profile that includes tester device UDIDs (for direct install)

## GitHub secrets

| Secret | Description |
|--------|-------------|
| `APPLE_CERTIFICATE_BASE64` | Base64 of `.p12` distribution certificate |
| `APPLE_CERTIFICATE_PASSWORD` | Password for the `.p12` |
| `APPLE_PROVISIONING_PROFILE_BASE64` | Base64 of `.mobileprovision` (Ad Hoc) |
| `APPLE_TEAM_ID` | 10-character Team ID |
| `KEYCHAIN_PASSWORD` | Random string for temporary CI keychain |

### Encode files (macOS)

```bash
base64 -i Certificates.p12 | pbcopy
base64 -i CricRelay_AdHoc.mobileprovision | pbcopy
```

## Install on iPhone

1. Log in to **CricRelay Stream** (or club dashboard).
2. Open the **Install on iPhone** link (Safari only).
3. After install: **Settings → General → VPN & Device Management** → trust the developer.

## TestFlight (optional)

For wider distribution without UDIDs, upload the IPA from the Actions artifact to App Store Connect → TestFlight manually, or add a Fastlane step later.

## Streaming note

Full camera + burned-in overlay RTMP is **Android-first**. iOS can log in, manage streams, and use volunteer RTMP where supported; native overlay encoding matches Android in a future release.
