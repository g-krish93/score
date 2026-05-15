#!/bin/bash
# Merge Gmail SMTP settings into /app/.env without wiping SECRET_KEY / STATE_DIR.
# Password: set SMTP_PASSWORD env var, or put it in /app/secrets/smtp_password (chmod 600).
#
#   SMTP_PASSWORD='your-google-app-password' sudo -E bash deploy/configure-smtp-env.sh
# Or on server after creating /app/secrets/smtp_password:
#   echo 'your-16-char-app-password' | sudo tee /app/secrets/smtp_password && sudo chmod 600 /app/secrets/smtp_password
set -euo pipefail

APP="${APP:-/app}"
ENV_FILE="${APP}/.env"
SECRET_FILE="${APP}/secrets/smtp_password"
SMTP_USER="${SMTP_USERNAME:-g.krish93@gmail.com}"
SMTP_FROM_ADDR="${SMTP_FROM:-$SMTP_USER}"

if [[ -z "${SMTP_PASSWORD:-}" ]] && [[ -f "$SECRET_FILE" ]]; then
  SMTP_PASSWORD="$(tr -d '\r\n' <"$SECRET_FILE")"
fi
export SMTP_PASSWORD

python3 <<PY
import os
from pathlib import Path

env_path = Path("${ENV_FILE}")
lines = []
if env_path.exists():
    lines = env_path.read_text().splitlines()

def parse(lines):
    out = {}
    for line in lines:
        s = line.strip()
        if not s or s.startswith("#") or "=" not in s:
            continue
        k, v = s.split("=", 1)
        out[k.strip()] = v
    return out

data = parse(lines)
data.setdefault("PORT", "5000")
data.setdefault("PUBLIC_BASE_URL", "https://cricrelay.co.uk")
data.setdefault("RELAY_AUTO_POLL", "1")
data["PASSWORD_RESET_TTL_SEC"] = data.get("PASSWORD_RESET_TTL_SEC") or "3600"
data["SMTP_HOST"] = "smtp.gmail.com"
data["SMTP_PORT"] = "587"
data["SMTP_USERNAME"] = "${SMTP_USER}"
data["SMTP_FROM"] = "${SMTP_FROM_ADDR}"
data["SMTP_USE_TLS"] = "1"
pw = os.environ.get("SMTP_PASSWORD", "").strip()
if pw:
    data["SMTP_PASSWORD"] = pw
elif "SMTP_PASSWORD" not in data:
    data["SMTP_PASSWORD"] = ""

order = [
    "PORT", "PUBLIC_BASE_URL", "SECRET_KEY", "STATE_DIR", "DATABASE_URL",
    "RELAY_INGEST_TOKEN", "PUSH_TARGET_URL", "RELAY_AUTO_POLL",
    "RELAY_POLL_INTERVAL_SEC", "RELAY_STALE_AFTER_SEC",
    "PASSWORD_RESET_TTL_SEC",
    "SMTP_HOST", "SMTP_PORT", "SMTP_USERNAME", "SMTP_PASSWORD", "SMTP_FROM", "SMTP_USE_TLS",
]
seen = set()
out_lines = []
for key in order:
    if key in data:
        out_lines.append(f"{key}={data[key]}")
        seen.add(key)
for key, val in sorted(data.items()):
    if key not in seen:
        out_lines.append(f"{key}={val}")

env_path.write_text("\n".join(out_lines) + "\n")
print("Updated", env_path)
print("SMTP_HOST=", data.get("SMTP_HOST"))
print("SMTP_USERNAME=", data.get("SMTP_USERNAME"))
print("SMTP_FROM=", data.get("SMTP_FROM"))
print("SMTP_PASSWORD set:", bool(data.get("SMTP_PASSWORD")))
PY

chmod 600 "$ENV_FILE" 2>/dev/null || true
chown ec2-user:ec2-user "$ENV_FILE" 2>/dev/null || true

if [[ -n "${SMTP_PASSWORD:-}" ]]; then
  mkdir -p "${APP}/secrets"
  umask 077
  printf '%s' "$SMTP_PASSWORD" >"$SECRET_FILE"
  chmod 600 "$SECRET_FILE"
  chown ec2-user:ec2-user "$SECRET_FILE" 2>/dev/null || true
fi

systemctl restart cricket 2>/dev/null || true
sleep 2
if [[ -n "${SMTP_PASSWORD:-}" ]] || [[ -s "$SECRET_FILE" ]]; then
  cd "$APP" && python3 <<'TEST'
import os
from dotenv import load_dotenv
load_dotenv("/app/.env")
from server.app import _smtp_enabled, _send_password_reset_email
print("smtp_enabled", _smtp_enabled())
ok = _send_password_reset_email(
    "g.krish93@gmail.com",
    "https://cricrelay.co.uk/login",
)
print("test_send", ok)
TEST
fi
