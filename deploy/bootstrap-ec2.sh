#!/bin/bash
# Run on the EC2 host as root (fixes site after rebuild or first boot gaps).
#   curl -fsSL https://raw.githubusercontent.com/g-krish93/score/main/deploy/bootstrap-ec2.sh | sudo bash
# Or: sudo bash deploy/bootstrap-ec2.sh
set -euo pipefail

APP="${APP:-/app}"
PUBLIC_BASE_URL="${PUBLIC_BASE_URL:-https://cricrelay.co.uk}"

if [[ ! -d "$APP" ]]; then
  echo "ERROR: $APP not found. Clone the app there first (see infra/user_data.sh)." >&2
  exit 1
fi

if [[ ! -f "$APP/.env" ]]; then
  echo "Creating minimal $APP/.env (add DATABASE_URL, SMTP, etc. and restart cricket)."
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

if [[ -f "$APP/deploy/nginx-cricrelay.conf" ]]; then
  install -m 644 "$APP/deploy/nginx-cricrelay.conf" /etc/nginx/conf.d/cricrelay.conf
else
  echo "WARN: missing $APP/deploy/nginx-cricrelay.conf — pull latest repo on server." >&2
fi

if [[ -f "$APP/deploy/cricket.service" ]]; then
  install -m 644 "$APP/deploy/cricket.service" /etc/systemd/system/cricket.service
fi

systemctl daemon-reload
systemctl enable nginx cricket 2>/dev/null || true
systemctl restart cricket || true
systemctl restart nginx || true

echo "--- local checks ---"
curl -sfS "http://127.0.0.1:5000/health" && echo " Gunicorn OK" || echo " Gunicorn FAILED (see journalctl -u cricket)"
curl -sfS -H "Host: cricrelay.co.uk" "http://127.0.0.1/health" && echo " nginx -> app OK" || echo " nginx proxy FAILED (see journalctl -u nginx)"
