# CricRelay Stream — security notes

## Client (Flutter / Android)

| Control | Status |
|--------|--------|
| API bearer token | Stored in **Android EncryptedSharedPreferences** via `flutter_secure_storage`; legacy tokens migrated from plain prefs |
| Password | Never stored; sent once over HTTPS at login |
| Transport | **HTTPS required** for production server URLs; HTTP allowed only for localhost / private LAN (dev) |
| Cleartext traffic | Disabled in release manifest (`network_security_config.xml`) |
| RTMP stream keys | Stored in SharedPreferences per match slug (device-local); treat device as trusted for volunteers |
| Screen capture | **Not used** — camera + overlay only; app UI is not encoded |
| Permissions | Camera, microphone, notifications, foreground service (camera/mic) |

## Server (cricrelay.co.uk)

- Stream API uses Bearer tokens issued by `/api/auth/login`
- OAuth secrets (YouTube/Twitch) live on server only, not in the APK
- Volunteers should use **custom RTMP + stream key** so club OAuth is not on volunteer phones

## Recommendations before wide rollout

1. **Certificate pinning** — optional hardening for fixed production host
2. **Stream key encryption** — move volunteer RTMP keys to secure storage if devices are shared
3. **Session expiry** — server-side token TTL + refresh (if not already on API)
4. **Play Store** — use release signing, not debug keystore

## Reporting

Contact your club admin or CricRelay operator for security issues on the hosted service.
