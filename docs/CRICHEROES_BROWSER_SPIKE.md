# CricHeroes persistent browser spike

Standalone experiment to bypass CricHeroes Cloudflare by reusing a long-lived, real
(non-headless) Chrome session on EC2. **Not integrated** into `server/cricheroes_scraper.py`,
`relay_poller.py`, or `CRICHEROES_AUTO_POLL`.

## Why

Fresh headless Playwright launches get `Cf-Mitigated: challenge` on every request.
Theory: one human-solved session on a stable EC2 IP/fingerprint can serve many scrapes
via CDP without re-challenge.

## Components

| File | Role |
|------|------|
| `scripts/cricheroes_browser_session.py` | Long-lived Xvfb + x11vnc + Chrome (CDP on localhost) |
| `scripts/cricheroes_cdp_client.py` | `connect_over_cdp` scraper + challenge detection |
| `scripts/test_cricheroes_browser_session.py` | Endurance test (5 min interval, 2 h default) |
| `deploy/cricheroes-browser.service` | systemd keep-alive across reboots |
| `deploy/cricheroes-browser-setup.sh` | One-time EC2 package install |

## Security

**CDP port 9222 and VNC port 5900 must never be exposed publicly.**

Both are bound to `127.0.0.1` only. An open CDP port is equivalent to full control of
the browser session (cookies, logged-in state). Access VNC only via SSH tunnel:

```bash
ssh -L 5900:localhost:5900 ec2-user@<EC2_HOST>
```

Then connect a VNC viewer to `localhost:5900`.

## EC2 setup (Ubuntu/Debian or Amazon Linux)

**Minimum instance size: t3.small (2 GB RAM).** Always-on Chrome + Xvfb uses ~300–500 MB
idle; on a t3.micro (1 GB) the box OOMs and SSH becomes unreliable. Add swap as a
stopgap only (`sudo fallocate -l 1G /swapfile && ...`), not a substitute for RAM.

```bash
cd /app
sudo bash deploy/cricheroes-browser-setup.sh
sudo systemctl start cricheroes-browser.service
sudo systemctl status cricheroes-browser.service
```

The setup script installs a passwordless sudoers rule so the endurance test can run
`sudo systemctl restart cricheroes-browser.service` non-interactively (`--restart-after-min`).

Profile directory (cookies persist here):

```
~/cricheroes-browser-profile/
```

**Assumption (verify):** `cf_clearance` and session cookies live in `user_data_dir` on
disk. A systemd restart or Chrome crash should **not** require re-solving Cloudflare —
only wiping that directory, ephemeral disk loss, or a materially new IP/fingerprint would.

## Manual Cloudflare solve (one-time, or when re-challenged)

1. Ensure the browser service is running:
   ```bash
   sudo systemctl status cricheroes-browser.service
   ```

2. From your laptop, open an SSH tunnel:
   ```bash
   ssh -L 5900:localhost:5900 ec2-user@<EC2_HOST>
   ```

3. Connect a VNC client to `localhost:5900` (TigerVNC, RealVNC, etc.).

4. In the remote Chrome window, navigate to the live scorecard URL, e.g.:
   ```
   https://cricheroes.com/scorecard/25903161/telangana-talent-hunt-u-16-cricket-championship-2026-season-i/vediri-cricket-academy-vs-mvrca/live
   ```

5. Complete the Turnstile / Cloudflare challenge like a normal user. Confirm the
   scorecard renders (tables, innings scores).

6. Record when you solved (for trust-window reporting):
   ```bash
   date -u +%Y-%m-%dT%H:%M:%SZ > ~/.cricheroes-last-manual-solve
   ```

Cookies are written to `~/cricheroes-browser-profile/` and survive process restarts.

## Smoke test

```bash
# Always pass --url for a match that is live during your test window.
python3 /app/scripts/test_cricheroes_browser_session.py --smoke \
  --url 'https://cricheroes.com/scorecard/<id>/.../live' \
  --save-html ~/cricheroes-first-fetch.html
```

Expect `OK` and scorecard HTML markers (not "Just a moment...").

**After the first successful fetch**, inspect `~/cricheroes-first-fetch.html` — the spike
and production parser both assume `<table>` markup, which has never been validated against
real post-challenge CricHeroes HTML. If the live page is a div/CSS-grid SPA, update
`is_scorecard_html()` and `cricheroes_scraper.py` selectors accordingly.

## Full endurance test (acceptance criteria)

```bash
# Default: scrape every 5 min for 2 hours — pass --url for a currently-live match
python3 /app/scripts/test_cricheroes_browser_session.py --url '<live-scorecard-url>'

# Include mid-run systemd restart (disk persistence proof; needs setup sudoers rule)
python3 /app/scripts/test_cricheroes_browser_session.py --url '<live-scorecard-url>' --restart-after-min 30
```

Report written to `~/cricheroes-spike-report.json`.

### Mid-run restart test (manual alternative)

In a second SSH session while the test runs:

```bash
sudo systemctl restart cricheroes-browser.service
```

The next scrape after CDP comes back should succeed **without** VNC re-solve if disk
persistence works.

## Logs

```bash
journalctl -u cricheroes-browser.service -f
```

Re-challenge detection logs:

```
[cricheroes-spike] Session re-challenged — human must VNC in and re-solve
```

## Troubleshooting

| Symptom | Action |
|---------|--------|
| CDP not reachable | `systemctl restart cricheroes-browser.service`; check `journalctl` |
| Challenge on every scrape | VNC in, solve manually, confirm `cf_clearance` in profile |
| Not challenged but `scorecard=False` | Match may be ended/not started, or DOM is non-`<table>` SPA — use `--save-html` |
| Restart test FAIL with sudo error | Re-run `cricheroes-browser-setup.sh` (installs NOPASSWD sudoers rule) |
| OOM / SSH hangs | Upgrade to t3.small+; preflight script clears stale CDP on service start |
| No Chrome binary | Re-run `cricheroes-browser-setup.sh` |
| VNC black screen | Wait for Xvfb; confirm `DISPLAY=:99` in service logs |

## Explicit non-goals

- No changes to `cricheroes_scraper.py`, `relay_poller.py`, or production polling
- No parsing bug fixes (batting/bowling mappers)
- No public exposure of CDP or VNC
