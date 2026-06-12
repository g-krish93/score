# iOS CI signing — full setup guide

GitHub Actions builds the native SwiftUI `cricrelay-mobile/ios` app. There are **two distribution paths** — choose one or both:

| Path | Who installs | Setup effort |
|------|-------------|--------------|
| **TestFlight** ✅ recommended | Any invited tester via TestFlight app | 8 secrets (includes 3 extra ASC API secrets) |
| **Ad Hoc OTA** (fallback) | Registered iPhones only, via Safari link on cricrelay.co.uk | 5 secrets (original path) |

CI chooses automatically: if `ASC_API_KEY_BASE64` is set → TestFlight; otherwise → Ad Hoc OTA.

---

## Required secrets — both paths

| GitHub secret | What it is | You create it |
|---------------|------------|---------------|
| `APPLE_TEAM_ID` | 10-character Apple team ID | Developer portal → Membership |
| `APPLE_CERTIFICATE_BASE64` | Apple Distribution certificate as base64 | Export `.p12` from Keychain |
| `APPLE_CERTIFICATE_PASSWORD` | Password you chose when exporting `.p12` | You choose at export |
| `KEYCHAIN_PASSWORD` | Any long random string CI uses for a temp keychain | `openssl rand -hex 32` |

## Additional secrets — TestFlight path only

| GitHub secret | What it is | You create it |
|---------------|------------|---------------|
| `APPLE_APPSTORE_PROFILE_BASE64` | App Store provisioning profile as base64 | Developer portal → Profiles |
| `ASC_API_KEY_ID` | App Store Connect API key ID (10 chars) | App Store Connect → Users → Keys |
| `ASC_API_ISSUER_ID` | API key issuer ID (UUID) | Same page as above |
| `ASC_API_KEY_BASE64` | API private key `.p8` file as base64 | Download once at creation |

## Additional secret — Ad Hoc OTA path only

| GitHub secret | What it is | You create it |
|---------------|------------|---------------|
| `APPLE_PROVISIONING_PROFILE_BASE64` | Ad Hoc provisioning profile as base64 | Developer portal → Profiles |

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

Ad Hoc installs only work on devices you register. **No Mac required.**

### Easiest: register from the iPhone itself (Safari)

1. On a **Windows PC** (or iPad), open [developer.apple.com/account](https://developer.apple.com/account) and sign in.
2. **Devices** → **+** → register a device.
3. Choose **Register a single device** and follow Apple’s steps to open a link **on the iPhone** (Safari).
4. The phone installs a small profile, reports its **UDID** to Apple, and the device appears in your list.
5. Repeat for each volunteer phone (or use TestFlight instead — see end of doc).

### Manual UDID (if you already have it)

**Devices** → **+** → **iPhone** → name + paste UDID → Register.

On Windows 11 you can also install the free **Apple Devices** app from Microsoft Store, connect an iPhone by cable, and view device info (if shown).

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

### Option B — Windows only (OpenSSL, no Mac)

You can create the distribution certificate and `.p12` entirely on Windows. The Apple Developer website works in any browser.

#### B1. Install OpenSSL on Windows

Pick one:

- **Git for Windows** (if installed): use **Git Bash** — it includes `openssl`.
- Or: `winget install ShiningLight.OpenSSL.Light` then use **PowerShell** (new window).

#### B2. Create private key + CSR

In PowerShell or Git Bash, `cd` to a folder where you will keep these files (back them up securely):

```bash
openssl genrsa -out distribution.key 2048

openssl req -new -key distribution.key -out distribution.csr \
  -subj "/CN=CricRelay Distribution/email=YOUR_EMAIL@example.com"
```

(PowerShell one line: use the same commands; for `req` you can use `-subj "/CN=CricRelay Distribution/email=you@club.com"`.)

#### B3. Upload CSR in Apple Developer portal

1. **Certificates** → **+** → **Apple Distribution** → Continue.
2. Upload **`distribution.csr`** → Continue → **Download** the certificate (e.g. `distribution.cer`).

#### B4. Build `.p12` on Windows

Still in the same folder (with `distribution.key` and downloaded `distribution.cer`):

```bash
openssl x509 -in distribution.cer -inform DER -out distribution.pem -outform PEM

openssl pkcs12 -export -out CricRelay_Distribution.p12 \
  -inkey distribution.key -in distribution.pem \
  -passout pass:CHOOSE_A_STRONG_PASSWORD
```

`CHOOSE_A_STRONG_PASSWORD` → GitHub secret **`APPLE_CERTIFICATE_PASSWORD`**.

Keep **`distribution.key`** and **`CricRelay_Distribution.p12`** private. Never commit them to git.

#### B5. Base64 the `.p12` (PowerShell)

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\CricRelay_Distribution.p12")) | Set-Clipboard
```

Paste into GitHub → **`APPLE_CERTIFICATE_BASE64`**.

### Option C — Rent a cloud Mac (optional)

If OpenSSL steps fail, rent one hour on [MacinCloud](https://www.macincloud.com/) or similar and use Option A (Keychain). Usually not needed if Option B works.

### What you cannot do without *some* macOS

- **Building the IPA** in CI is already on GitHub’s **macOS** runners — you do not need your own Mac for that once secrets are set.
- Running Xcode locally on Windows is not possible.

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

## Part 10 — App Store Connect API key (TestFlight path only)

This is what lets CI upload the IPA to TestFlight without a Mac.

1. Open [App Store Connect](https://appstoreconnect.apple.com) → **Users and Access** → **Integrations** → **App Store Connect API**.
2. Click **+** → Name: `CricRelay CI` → Access: **App Manager** → **Generate**.
3. **Download the `.p8` file immediately** (you cannot download it again — if lost, generate a new key).
4. Note the **Key ID** (10 chars, e.g. `ABC1234567`) → GitHub secret `ASC_API_KEY_ID`.
5. Note the **Issuer ID** (UUID at the top of the Keys page) → GitHub secret `ASC_API_ISSUER_ID`.
6. Base64 the `.p8` key file on Windows:
   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\AuthKey_ABC1234567.p8")) | Set-Clipboard
   ```
   → paste into GitHub secret `ASC_API_KEY_BASE64`.

### Create the app record in App Store Connect (first time only)

Before the first TestFlight upload, the app must exist:

1. [App Store Connect](https://appstoreconnect.apple.com) → **My Apps** → **+** → **New App**.
2. **Platform:** iOS.
3. **Name:** `CricRelay Live`.
4. **Bundle ID:** `uk.co.cricrelay.stream` (select from list — must match your App ID from Part 3).
5. **SKU:** `cricrelay-live` (any unique string).
6. Create. The app record is ready — you don’t need to fill in screenshots yet.

---

## Part 11 — App Store provisioning profile (TestFlight path)

You need a separate **App Store** profile (different from Ad Hoc):

1. Portal → **Profiles** → **+** → **Distribution** → **App Store** → Continue.
2. **App ID:** `uk.co.cricrelay.stream`.
3. **Certificate:** select your Apple Distribution cert → Continue.
4. **Profile name:** `CricRelay Live App Store` → Generate → Download.
5. Base64 on Windows:
   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\CricRelay_Live_App_Store.mobileprovision")) | Set-Clipboard
   ```
   → paste into GitHub secret `APPLE_APPSTORE_PROFILE_BASE64`.

---

## Part 12 — Install on iPhone (TestFlight)

1. Testers install the **TestFlight** app from the App Store on their iPhone.
2. In App Store Connect → **TestFlight** → **Internal Testing** → add testers by Apple ID email.
3. Testers receive an email invite → open in TestFlight → install **CricRelay Live**.

No UDID registration required. You can invite up to 10,000 external testers after a brief Apple review (~24 hours).

---

## Checklist — TestFlight (recommended)

- [ ] Apple Developer Program active
- [ ] `APPLE_TEAM_ID` in GitHub
- [ ] App ID `uk.co.cricrelay.stream` registered
- [ ] Apple Distribution certificate created and exported as `.p12`
- [ ] `APPLE_CERTIFICATE_BASE64` + `APPLE_CERTIFICATE_PASSWORD` + `KEYCHAIN_PASSWORD` in GitHub
- [ ] App Store provisioning profile downloaded
- [ ] `APPLE_APPSTORE_PROFILE_BASE64` in GitHub
- [ ] App Store Connect API key created
- [ ] `ASC_API_KEY_ID` + `ASC_API_ISSUER_ID` + `ASC_API_KEY_BASE64` in GitHub
- [ ] App record created in App Store Connect (`uk.co.cricrelay.stream`)
- [ ] Push to `main` → GitHub Actions **build-ios** job green
- [ ] TestFlight shows new build — invite testers by email

## Checklist — Ad Hoc OTA (fallback, devices must be pre-registered)

- [ ] Apple Developer Program active
- [ ] `APPLE_TEAM_ID` in GitHub
- [ ] All test iPhones registered under **Devices** in developer portal
- [ ] Apple Distribution certificate + `.p12` exported
- [ ] `APPLE_CERTIFICATE_BASE64` + `APPLE_CERTIFICATE_PASSWORD` + `KEYCHAIN_PASSWORD` in GitHub
- [ ] Ad Hoc profile (with registered devices) downloaded
- [ ] `APPLE_PROVISIONING_PROFILE_BASE64` in GitHub
- [ ] Push to `main` → **build-ios** job green
- [ ] Install link works in Safari on a registered iPhone

TestFlight is usually better for **many rotating volunteers**. Ad Hoc is better for **a few club phones** without App Store review wait.

---

## Quick answer: Windows vs iPhone/iPad

| Task | Windows PC | iPhone / iPad |
|------|------------|----------------|
| Developer portal (Team ID, App ID, cert, profile) | Yes — browser | Yes — Safari (portal) |
| Create `.p12` | Yes — OpenSSL | No |
| Register device UDID | Yes — portal | Yes — portal link on phone (easiest) |
| Add GitHub secrets | Yes | Awkward — use PC |
| Install app for testing | No | Yes — Safari (Ad Hoc) or TestFlight app |
| Build IPA | No — GitHub Actions does this | No |

---

## Streaming note

Live **camera + burned-in scoreboard** RTMP is **Android-first**. The iOS app supports sign-in, stream setup, and stream-key RTMP where implemented; full overlay encoding parity is planned separately.
