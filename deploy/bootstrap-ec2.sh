#!/bin/bash
# Full server setup / repair on EC2 (run as root after SSH).
#
#   ssh -i YOUR.pem ec2-user@YOUR_PUBLIC_IP
#   sudo bash /app/deploy/bootstrap-ec2.sh
#
# Optional: GIT_PULL=0 to skip git pull. PUBLIC_BASE_URL=https://… for first .env seed.
set -euo pipefail

APP="${APP:-/app}"
PUBLIC_BASE_URL="${PUBLIC_BASE_URL:-https://cricrelay.co.uk}"
GIT_PULL="${GIT_PULL:-1}"

if [[ ! -d "$APP" ]]; then
  echo "ERROR: $APP not found. Clone the app there first (see infra/user_data.sh)." >&2
  exit 1
fi

if [[ "$GIT_PULL" != "0" ]] && [[ -d "$APP/.git" ]]; then
  echo "--- git pull (latest deploy unit + app) ---"
  if id ec2-user &>/dev/null 2>&1; then
    runuser -u ec2-user -- git -C "$APP" pull origin main || {
      echo "WARN: git pull as ec2-user failed — fix deploy keys or run: cd $APP && git pull" >&2
    }
  else
    git -C "$APP" pull origin main || echo "WARN: git pull failed" >&2
  fi
fi

if [[ ! -f "$APP/.env" ]]; then
  echo "Creating minimal $APP/.env (merge DATABASE_URL, SMTP, etc. later)."
  umask 077
  {
    echo "PORT=5000"
    echo "SECRET_KEY=$(openssl rand -hex 32)"
    echo "PUBLIC_BASE_URL=$PUBLIC_BASE_URL"
    echo "RELAY_AUTO_POLL=1"
  } >"$APP/.env"
  chown ec2-user:ec2-user "$APP/.env" || true
fi

if command -v dnf >/dev/null 2>&1; then
  dnf install -y nginx python3 python3-pip git || true
else
  yum install -y nginx python3 python3-pip git || true
fi

echo "--- pip (same as CI: root, site-packages) ---"
pip3 install --ignore-installed -r "$APP/requirements.txt"

if [[ -f "$APP/deploy/nginx-cricrelay.conf" ]]; then
  install -m 644 "$APP/deploy/nginx-cricrelay.conf" /etc/nginx/conf.d/cricrelay.conf
else
  echo "WARN: missing $APP/deploy/nginx-cricrelay.conf" >&2
fi

if [[ -f "$APP/deploy/cricket.service" ]]; then
  install -m 644 "$APP/deploy/cricket.service" /etc/systemd/system/cricket.service
else
  echo "ERROR: missing $APP/deploy/cricket.service — git pull failed?" >&2
  exit 1
fi

systemctl daemon-reload
systemctl enable nginx cricket 2>/dev/null || true

echo "--- restart cricket (stop → start avoids stuck restart) ---"
systemctl stop cricket 2>/dev/null || true
sleep 2
systemctl start cricket
sleep 4
if ! systemctl is-active --quiet cricket; then
  echo "=== cricket failed — journalctl -u cricket (last 120 lines) ===" >&2
  journalctl -u cricket -n 120 --no-pager || true
  exit 1
fi

systemctl restart nginx || true

echo "--- local checks ---"
curl -sfS "http://127.0.0.1:5000/health" && echo " Gunicorn OK" || {
  echo " Gunicorn FAILED" >&2
  journalctl -u cricket -n 40 --no-pager || true
  exit 1
}
curl -sfS -H "Host: cricrelay.co.uk" "http://127.0.0.1/health" && echo " nginx -> app OK" || echo " WARN: nginx proxy check failed (Host header / nginx config)"

echo "--- done ---"
systemctl --no-pager status cricket || true
