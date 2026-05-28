# iOS CI signing — full setup guide

GitHub Actions needs **5 secrets** to build an installable iPhone app (IPA). Without them, CI only checks that the iOS project compiles.

| GitHub secret | What it is | You create it |
|---------------|------------|---------------|
| `APPLE_TEAM_ID` | 10-character Apple team ID | Developer portal |
| `APPLE_CERTIFICATE_BASE64` | Distribution certificate as base64 | Export `.p12` from Keychain |
| `APPLE_CERTIFICATE_PASSWORD` | Password you chose when exporting `.p12` | You choose at export |
| `APPLE_PROVISIONING_PROFILE_BASE64` | Ad Hoc profile as base64 | Developer portal → download |
| `KEYCHAIN_PASSWORD` | Any long random string | You invent (e.g. `openssl rand -hex 32`) |

**App bundle ID (must match):** `uk.co.cricrelay.stream`

**Cost:** [Apple Developer Program](https://developer.apple.com/programs/) — about **£99 / $99 per year**.

---

## Part 1 — Apple Developer account

1. Go to [https://developer.apple.com/programs/enroll/](https://developer.apple.com/programs/enroll/).
2. Sign in with an Apple ID (use a club/organisation Apple ID if possible).
3. Enrol as **Individual** or **Organisation** and pay the annual fee.
4. Wait until membership status is **Active** (can take 24–48 hours for new org accounts).

---

## Part 2 — Team ID (`APPLE_TEAM_ID`)

1. Open [https://developer.apple.com/account](https://developer.apple.com/account).
2. **Membership** (left menu) → find **Team ID** (10 characters, e.g. `A1B2C3D4E5`).
3. Copy it → GitHub repo → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**:
   - Name: `APPLE_TEAM_ID`
   - Value: your Team ID

---

## Part 3 — App ID

1. [Certificates, Identifiers & Profiles](https://developer.apple.com/account/resources/identifiers/list) → **Identifiers** → **+**.
2. Choose **App IDs** → **App** → Continue.
3. **Description:** `CricRelay Live`
4. **Bundle ID:** Explicit → `uk.co.cricrelay.stream`
5. Enable capabilities you need (defaults are usually enough; camera/mic are declared in the app, not always as capabilities here).
6. **Register**.

---

## Part 4 — Register iPhones (Ad Hoc only)

Ad Hoc installs only work on devices you register.

**On each volunteer iPhone:**

1. Connect to a Mac with Finder/iTunes, or use a site your org trusts to read UDID, or install a profile from the developer portal.
2. Apple’s way: on the phone open the link from **Devices** → **+** in the developer portal (Apple can email a registration link), or copy UDID from Finder (select phone → click serial until UDID shows).

**In the portal:**

1. **Devices** → **+** → **iPhone**.
2. Name it (e.g. `Volunteer John`) → paste **UDID** → Continue → Register.
3. Repeat for every phone that should install the app (max 100 per year on standard accounts).

---

## Part 5 — Distribution certificate (`.p12`)

You need a **Apple Distribution** certificate (used for Ad Hoc and App Store).

### Option A — Mac with Keychain Access (simplest)

1. Portal → **Certificates** → **+**.
2. **Apple Distribution** → Continue.
3. It asks for a **Certificate Signing Request (CSR)**:
   - On Mac: **Keychain Access** → menu **Keychain Access** → **Certificate Assistant** → **Request a Certificate From a Certificate Authority**.
   - Email: your email, **Common Name:** `CricRelay Distribution`, **Saved to disk** → save `CricRelay.certSigningRequest`.
4. Upload that CSR in the portal → **Continue** → **Download** the `.cer` file (e.g. `distribution.cer`).
5. Double-click the `.cer` to add it to Keychain.
6. In **Keychain Access**, under **My Certificates**, find **Apple Distribution: …** (with a private key underneath).
7. Expand the row, select **both** the certificate and the private key → right-click → **Export 2 items…**.
8. Format: **Personal Information Exchange (.p12)** → save as `CricRelay_Distribution.p12`.
9. Set an **export password** (remember it — this is `APPLE_CERTIFICATE_PASSWORD`).

### Option B — No Mac

Use a Mac in the cloud (MacStadium, borrow a Mac, or GitHub’s macOS runner only helps *after* you have the files). Apple requires a CSR from Keychain or Xcode to create the distribution cert the usual way.

---

## Part 6 — Ad Hoc provisioning profile

1. Portal → **Profiles** → **+**.
2. **Distribution** → **Ad Hoc** → Continue.
3. **App ID:** select `uk.co.cricrelay.stream` → Continue.
4. **Certificate:** tick your **Apple Distribution** certificate → Continue.
5. **Devices:** select all iPhones that should install the app → Continue.
6. **Profile name:** `CricRelay Live Ad Hoc` → **Generate** → **Download** (file ends in `.mobileprovision`, e.g. `CricRelay_Live_Ad_Hoc.mobileprovision`).

When you add new phones later, edit the profile (or create a new one), re-download, and update the GitHub secret.

---

## Part 7 — Base64 for GitHub (`APPLE_CERTIFICATE_BASE64`, `APPLE_PROVISIONING_PROFILE_BASE64`)

GitHub secrets must be **text** (base64), not raw binary uploads.

### On macOS

```bash
base64 -i CricRelay_Distribution.p12 | pbcopy
# → paste into secret APPLE_CERTIFICATE_BASE64

base64 -i CricRelay_Live_Ad_Hoc.mobileprovision | pbcopy
# → paste into secret APPLE_PROVISIONING_PROFILE_BASE64
```

### On Windows (PowerShell)

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\CricRelay_Distribution.p12")) | Set-Clipboard
# → paste into APPLE_CERTIFICATE_BASE64

[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\CricRelay_Live_Ad_Hoc.mobileprovision")) | Set-Clipboard
# → paste into APPLE_PROVISIONING_PROFILE_BASE64
```

Tip: the secret value is one long line with no spaces. If the workflow fails import, re-encode without line breaks.

---

## Part 8 — Remaining secrets

| Secret | Value |
|--------|--------|
| `APPLE_CERTIFICATE_PASSWORD` | The password you set when exporting the `.p12` |
| `KEYCHAIN_PASSWORD` | Any random string CI uses for a temporary keychain, e.g. run `openssl rand -hex 32` or make up 32+ random characters |

---

## Part 9 — Add secrets in GitHub

1. Open **https://github.com/g-krish93/score** (your repo).
2. **Settings** → **Secrets and variables** → **Actions**.
3. **New repository secret** for each row in the table at the top.
4. Push any commit to `main` (or re-run workflow **Build CricRelay Stream (Android + iOS)**).

When all five are set, the **build-ios** job should produce `static/cricrelay-stream.ipa` and deploy it to EC2 with the APK.

---

## Part 10 — Install on iPhone

1. Sign in to **CricRelay Stream** (or club dashboard on cricrelay.co.uk).
2. Tap **Install on iPhone** / **iPhone (install)** — must use **Safari**.
3. Allow the install → **Settings** → **General** → **VPN & Device Management** → trust your developer name.
4. Open **CricRelay Live**.

If install is blocked, the phone’s UDID is probably not on the Ad Hoc profile.

---

## Checklist

- [ ] Apple Developer Program active
- [ ] `APPLE_TEAM_ID` in GitHub
- [ ] App ID `uk.co.cricrelay.stream` created
- [ ] All test iPhones registered under **Devices**
- [ ] Apple Distribution certificate created and exported as `.p12`
- [ ] `APPLE_CERTIFICATE_BASE64` + `APPLE_CERTIFICATE_PASSWORD` in GitHub
- [ ] Ad Hoc profile downloaded with app + cert + devices
- [ ] `APPLE_PROVISIONING_PROFILE_BASE64` in GitHub
- [ ] `KEYCHAIN_PASSWORD` in GitHub
- [ ] GitHub Actions **build-ios** job green
- [ ] Install link works in Safari on a registered iPhone

---

## Easier alternative: TestFlight

If Ad Hoc UDIDs are too painful for a large club:

1. Build IPA locally or from Actions artifact.
2. Upload to [App Store Connect](https://appstoreconnect.apple.com) → your app → **TestFlight**.
3. Add testers by email (no UDID list). Apple reviews each build (often 24–48 h first time).

TestFlight is usually better for **many volunteers**; Ad Hoc + OTA is better for **a few fixed club phones**.

---

## Streaming note

Live **camera + burned-in scoreboard** RTMP is **Android-first**. The iOS app supports sign-in, stream setup, and stream-key RTMP where implemented; full overlay encoding parity is planned separately.
