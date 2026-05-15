#!/bin/bash
# Keep logins stable across reboots: persistent STATE_DIR + stable SECRET_KEY.
# Run on EC2 as root: sudo bash /app/deploy/persist-auth-env.sh
set -euo pipefail

APP="${APP:-/app}"
DATA_DIR="${APP}/data"
SECRET_FILE="${APP}/.secret_key"
ENV_FILE="${APP}/.env"

mkdir -p "$DATA_DIR"
chown ec2-user:ec2-user "$DATA_DIR" 2>/dev/null || true

if [[ ! -f "$SECRET_FILE" ]]; then
  openssl rand -hex 32 >"$SECRET_FILE"
  chmod 600 "$SECRET_FILE"
  chown ec2-user:ec2-user "$SECRET_FILE" 2>/dev/null || true
fi
SECRET_VAL="$(tr -d '\r\n' <"$SECRET_FILE")"

touch "$ENV_FILE"
chmod 600 "$ENV_FILE" 2>/dev/null || true

upsert() {
  local key="$1"
  local val="$2"
  if grep -q "^${key}=" "$ENV_FILE" 2>/dev/null; then
    sed -i "s|^${key}=.*|${key}=${val}|" "$ENV_FILE"
  else
    echo "${key}=${val}" >>"$ENV_FILE"
  fi
}

upsert "STATE_DIR" "$DATA_DIR"
upsert "SECRET_KEY" "$SECRET_VAL"
grep -q "^PORT=" "$ENV_FILE" || upsert "PORT" "5000"
grep -q "^PUBLIC_BASE_URL=" "$ENV_FILE" || upsert "PUBLIC_BASE_URL" "https://cricrelay.co.uk"
grep -q "^RELAY_AUTO_POLL=" "$ENV_FILE" || upsert "RELAY_AUTO_POLL" "1"

chown ec2-user:ec2-user "$ENV_FILE" 2>/dev/null || true

if [[ -f /tmp/cricrelay.db ]] && [[ ! -f "${DATA_DIR}/cricrelay.db" ]]; then
  echo "Migrating SQLite from /tmp/cricrelay.db -> ${DATA_DIR}/cricrelay.db"
  cp -a /tmp/cricrelay.db "${DATA_DIR}/cricrelay.db"
  chown ec2-user:ec2-user "${DATA_DIR}/cricrelay.db" 2>/dev/null || true
fi

echo "STATE_DIR=$DATA_DIR"
echo "SECRET_KEY set from $SECRET_FILE (unchanged if file already existed)"
if command -v sqlite3 >/dev/null 2>&1 && [[ -f "${DATA_DIR}/cricrelay.db" ]]; then
  echo -n "Organizations in DB: "
  sqlite3 "${DATA_DIR}/cricrelay.db" "SELECT COUNT(*) FROM cricrelay_org;" 2>/dev/null || echo "?"
fi
