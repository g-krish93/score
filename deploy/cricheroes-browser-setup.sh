#!/bin/bash
# One-time EC2 setup for the CricHeroes persistent-browser spike.
# Run as root on Ubuntu/Debian (primary) or Amazon Linux (partial).
#
#   sudo bash /app/deploy/cricheroes-browser-setup.sh
#
# Does NOT touch cricheroes_scraper.py or production polling.
set -euo pipefail

APP="${APP:-/app}"
SERVICE_NAME="cricheroes-browser.service"
SUDOERS_FILE="/etc/sudoers.d/cricheroes-browser-spike"

echo "=== CricHeroes browser spike — EC2 setup ==="

install_apt() {
  apt-get update -qq
  apt-get install -y xvfb x11vnc wget gnupg ca-certificates fonts-liberation psmisc
}

install_chrome_debian() {
  if command -v google-chrome-stable >/dev/null 2>&1 || command -v google-chrome >/dev/null 2>&1; then
    echo "Google Chrome already installed"
    return 0
  fi
  echo "Installing Google Chrome (Debian/Ubuntu)..."
  install -d -m 0755 /etc/apt/keyrings
  wget -qO- https://dl.google.com/linux/linux_signing_key.pub \
    | gpg --dearmor -o /etc/apt/keyrings/google-chrome.gpg
  echo "deb [arch=amd64 signed-by=/etc/apt/keyrings/google-chrome.gpg] http://dl.google.com/linux/chrome/deb/ stable main" \
    > /etc/apt/sources.list.d/google-chrome.list
  apt-get update -qq
  apt-get install -y google-chrome-stable
}

install_amazon_linux() {
  echo "Amazon Linux detected — installing Xvfb + TigerVNC (x11vnc often unavailable)"
  if command -v dnf >/dev/null 2>&1; then
    dnf install -y xorg-x11-server-Xvfb tigervnc-server-minimal wget psmisc
  else
    yum install -y xorg-x11-server-Xvfb tigervnc-server-minimal wget psmisc
  fi
  if ! command -v google-chrome-stable >/dev/null 2>&1; then
    echo "WARN: Install Google Chrome manually on Amazon Linux, or use Chromium from EPEL." >&2
    echo "      Spike requires a real headed browser, not Playwright headless." >&2
  fi
}

if command -v apt-get >/dev/null 2>&1; then
  install_apt
  install_chrome_debian
elif command -v dnf >/dev/null 2>&1 || command -v yum >/dev/null 2>&1; then
  install_amazon_linux
else
  echo "ERROR: Unsupported OS — need apt (Ubuntu/Debian) or yum/dnf (Amazon Linux)" >&2
  exit 1
fi

echo "--- Python deps (playwright for CDP client only) ---"
if ! pip3 install --no-cache-dir playwright==1.49.1 beautifulsoup4==4.12.3; then
  echo "ERROR: pip install failed — CDP client will not work until playwright is installed" >&2
  exit 1
fi
if ! python3 -m playwright install chrome; then
  echo "WARN: playwright install chrome failed — connect_over_cdp may still work against system Chrome" >&2
fi

echo "--- sudoers (passwordless restart for endurance test) ---"
cat >"$SUDOERS_FILE" <<'EOF'
# CricHeroes browser spike: allow ec2-user to restart browser service non-interactively.
ec2-user ALL=(ALL) NOPASSWD: /usr/bin/systemctl restart cricheroes-browser.service
EOF
chmod 440 "$SUDOERS_FILE"
visudo -cf "$SUDOERS_FILE"

echo "--- systemd unit + preflight ---"
chmod 755 "$APP/deploy/cricheroes-browser-preflight.sh"
install -m 644 "$APP/deploy/cricheroes-browser.service" "/etc/systemd/system/${SERVICE_NAME}"
systemctl daemon-reload
systemctl enable "${SERVICE_NAME}"

PROFILE_DIR="/home/ec2-user/cricheroes-browser-profile"
mkdir -p "$PROFILE_DIR"
chown -R ec2-user:ec2-user "$PROFILE_DIR"

echo ""
echo "Setup complete. Next steps (as ec2-user):"
echo "  1. sudo systemctl start ${SERVICE_NAME}"
echo "  2. Follow docs/CRICHEROES_BROWSER_SPIKE.md to VNC in and solve Cloudflare once"
echo "  3. python3 $APP/scripts/test_cricheroes_browser_session.py --smoke --url <live-match-url>"
echo ""
echo "Instance sizing: use >= t3.small (2GB RAM). t3.micro OOMs with always-on Chrome."
echo "SECURITY: CDP :9222 and VNC :5900 are localhost-only. Use SSH tunnels only."
