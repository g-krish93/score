#!/bin/bash
# Sync /app to origin/main as ec2-user (fixes root-owned .git from sudo git gc).
set -euo pipefail

APP="${APP:-/app}"
OWNER="${OWNER:-ec2-user}"

if [[ ! -d "$APP/.git" ]]; then
  echo "ERROR: $APP/.git missing" >&2
  exit 1
fi

chown -R "${OWNER}:${OWNER}" "$APP"

# Shallow fetch uses far less RAM than a full fetch on t3.micro (~1 GB).
runuser -u "$OWNER" -- git -C "$APP" fetch --depth=1 origin main
runuser -u "$OWNER" -- git -C "$APP" reset --hard FETCH_HEAD

echo "=== synced to $(runuser -u "$OWNER" -- git -C "$APP" rev-parse --short HEAD) ==="

# Playwright browser binaries (after git pull; pip install runs in deploy.yml / cricket.service restart path).
if [[ -f "$APP/deploy/playwright-install.sh" ]]; then
  sudo APP="$APP" bash "$APP/deploy/playwright-install.sh" || {
    echo "WARN: Playwright install failed — CricHeroes scrape will not work until fixed" >&2
  }
fi
