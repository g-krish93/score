# CricRelay Stream — beta checklist

Use this before de-emphasising the OBS browser-overlay path.

## Club setup

- [ ] YouTube OAuth connected on dashboard (correct Google account for club channel)
- [ ] Play-Cricket stream created and auto-poll receiving scores
- [ ] Stream app login with club credentials

## Match day

- [ ] Phone on tripod, power connected or battery pack
- [ ] 5G or club Wi‑Fi stable (~15 GB per 40-over HD stream on cellular)
- [ ] Go Live → YouTube watch URL plays within 60s
- [ ] Score updates on stream within **3s** of Play-Cricket / PCS BLE (measure 10 ball changes)
- [ ] Stop stream ends YouTube broadcast cleanly

## Overlay parity

- [ ] Team names, runs/wickets, overs visible
- [ ] Batsmen lines when PCS BLE sends B1S/B2S
- [ ] Target / runs required in 2nd innings

## Platforms

| Platform | Burn-in | Notes |
|----------|---------|--------|
| Android | Screen capture + WebView | Recommended |
| iOS | Camera RTMP + overlay preview | Overlay on preview; verify acceptability |

## Known limits (MVP)

- No multi-camera, highlights, or stump cam
- Scraper lag follows Play-Cricket (not ball-by-ball)
- YouTube API quotas — one broadcast per stream session

## Gate OBS deprecation

Only switch homepage to “Stream app first” when **all** match-day rows above pass for **two** real club matches.
