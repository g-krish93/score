#!/bin/bash
# Install Playwright Chromium browser binaries on EC2 (run after requirements.txt changes).
# Safe to re-run; skips when requirements hash unchanged.
set -euo pipefail

APP="${APP:-/app}"
STAMP="${PLAYWRIGHT_STAMP:-/var/lib/cricrelay-playwright.sha256}"

if [[ ! -f "$APP/requirements.txt" ]] || ! grep -q '^playwright' "$APP/requirements.txt"; then
  echo "playwright not in requirements.txt — skipping browser install"
  exit 0
fi

REQ_HASH=$(sha256sum "$APP/requirements.txt" | awk '{print $1}')
if [[ -f "$STAMP" ]] && [[ "$(cat "$STAMP")" == "$REQ_HASH" ]]; then
  echo "Playwright browsers up to date (requirements unchanged)"
  exit 0
fi

echo "Installing Playwright Chromium (--with-deps)..."
if command -v playwright >/dev/null 2>&1; then
  playwright install --with-deps chromium
elif python3 -m playwright --version >/dev/null 2>&1; then
  python3 -m playwright install --with-deps chromium
else
  echo "ERROR: playwright CLI not found — run pip install -r requirements.txt first" >&2
  exit 1
fi

echo "$REQ_HASH" | tee "$STAMP" > /dev/null
echo "Playwright Chromium install complete"
