# Testing CricRelay Live on Mac + iPhone

Two ways to get the app on your test iPhone from a Mac:

| Method | Best for | Requires |
|--------|----------|----------|
| **A. Xcode (direct)** | Active development / quick iteration | Free Apple ID |
| **B. Sideloadly (unsigned IPA)** | Just want to test the app once | Free Sideloadly tool |

---

## Method A — Run directly from Xcode (recommended for testing)

### 1. Install prerequisites on Mac

```bash
# Install Homebrew (if not already installed)
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

# Install xcodegen
brew install xcodegen
```

Install **Xcode** from the Mac App Store (free, ~10 GB — skip if already installed).

### 2. Clone the repo

```bash
git clone https://github.com/g-krish93/score.git
cd score
```

### 3. Generate the Xcode project

```bash
cd cricrelay-mobile/ios
xcodegen generate
# Creates CricRelayLive.xcodeproj
```

### 4. Prepare the iPhone

1. Connect iPhone to Mac with a USB cable.
2. On iPhone: tap **Trust** when "Trust This Computer?" appears.
3. On **iOS 16 or later** — enable Developer Mode:
   - Settings → Privacy & Security → Developer Mode → toggle On → restart iPhone.

### 5. Set up code signing in Xcode

1. Open `cricrelay-mobile/ios/CricRelayLive.xcodeproj` in Xcode.
2. **Add your Apple ID**: Xcode menu → Settings → Accounts → **+** → Apple ID (free account is fine).
3. In the project navigator, click **CricRelayLive** (top-level blue icon).
4. Select the **CricRelayLive** target → **Signing & Capabilities** tab.
5. Make sure **Automatically manage signing** is checked.
6. Set **Team** to your personal team (shown as "Your Name (Personal Team)").
7. Xcode will register the device and create a provisioning profile automatically.

> **Bundle ID conflict?** If Xcode complains that `uk.co.cricrelay.stream` is taken,
> change **Bundle Identifier** to `uk.co.cricrelay.stream.dev` (just for your test build).

### 6. Build and run

1. In the Xcode toolbar, select your iPhone from the device picker (next to the scheme name).
2. Press **▶ Run** (or `Cmd+R`).
3. Xcode builds and installs the app on your iPhone.

**First run only** — iPhone shows "Untrusted Developer" alert:
- Settings → General → VPN & Device Management → tap your Apple ID email → **Trust**.
- Re-launch the app.

---

## Method B — Install unsigned IPA with Sideloadly (no Apple Developer setup)

Use this if you don't want to set up Xcode signing.

### 1. Get the unsigned IPA

- Go to the [GitHub Actions](https://github.com/g-krish93/score/actions/workflows/build-cricrelay-mobile.yml) page.
- Open the latest successful run → **Artifacts** → download `cricrelay-live-ipa`.

  *(The CI produces an unsigned IPA when no signing secrets are configured — labelled "for Sideloadly".)*

### 2. Install Sideloadly

Download from **sideloadly.io** — available for Mac and Windows.

### 3. Install the IPA on iPhone

1. Open Sideloadly on Mac.
2. Connect iPhone via USB.
3. Drag the `.ipa` file into Sideloadly.
4. Enter your Apple ID when prompted (used only for signing locally — free account works).
5. Click **Start** — Sideloadly signs and installs the app.
6. Trust the certificate on iPhone: Settings → General → VPN & Device Management → trust your email.

> **Note:** Free Apple ID installs expire after **7 days** — just re-install via Sideloadly.

---

## What to test

| Feature | How to test |
|---------|-------------|
| Login / register | Open app → log in with your cricrelay.co.uk account |
| Stream list | Dashboard should show active streams from the server |
| Create stream | Tap + → enter a Play-Cricket URL or match ID |
| Live broadcast | Open a stream → Studio → grant Camera + Mic → go live |
| Score overlay | The scoreboard should appear over the camera preview |
| PCS BLE relay | Tap BLE relay → scan for scoreboards (needs PCS hardware) |

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| "Untrusted Developer" on iPhone | Settings → General → VPN & Device Management → Trust |
| Xcode can't find device | Unplug/replug USB; try a different cable (data cable, not charge-only) |
| `xcodegen generate` fails | Make sure Xcode command-line tools are installed: `xcode-select --install` |
| Camera/mic permission denied | Settings → Privacy → Camera/Microphone → enable CricRelay Live |
| App connects but "Awaiting data" | Check the server URL in the stream matches your EC2 host |
