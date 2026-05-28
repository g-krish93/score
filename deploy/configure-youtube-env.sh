#!/bin/bash
# Merge YouTube OAuth settings into /app/.env (same pattern as configure-smtp-env.sh).
#
# Credentials from deploy (GitHub secrets via SSH):
#   YOUTUBE_CLIENT_ID / YOUTUBE_CLIENT_SECRET
#   or GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET
# Or files: /app/secrets/youtube_client_id, /app/secrets/youtube_client_secret
set -euo pipefail

APP="${APP:-/app}"
ENV_FILE="${APP}/.env"
ID_FILE="${APP}/secrets/youtube_client_id"
SECRET_FILE="${APP}/secrets/youtube_client_secret"

read_secret() {
  local file="$1"
  if [[ -f "$file" ]]; then
    tr -d '\r\n' <"$file"
    return
  fi
  echo ""
}

YOUTUBE_CLIENT_ID="${YOUTUBE_CLIENT_ID:-${GOOGLE_CLIENT_ID:-}}"
YOUTUBE_CLIENT_SECRET="${YOUTUBE_CLIENT_SECRET:-${GOOGLE_CLIENT_SECRET:-}}"

if [[ -z "$YOUTUBE_CLIENT_ID" ]]; then
  YOUTUBE_CLIENT_ID="$(read_secret "$ID_FILE")"
fi
if [[ -z "$YOUTUBE_CLIENT_SECRET" ]]; then
  YOUTUBE_CLIENT_SECRET="$(read_secret "$SECRET_FILE")"
fi

export YOUTUBE_CLIENT_ID YOUTUBE_CLIENT_SECRET YOUTUBE_REDIRECT_URI

python3 <<PY
import os
from pathlib import Path

env_path = Path("${ENV_FILE}")
lines = env_path.read_text().splitlines() if env_path.exists() else []

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

cid = os.environ.get("YOUTUBE_CLIENT_ID", "").strip()
csec = os.environ.get("YOUTUBE_CLIENT_SECRET", "").strip()
redirect = os.environ.get("YOUTUBE_REDIRECT_URI", "").strip()

if cid:
    data["YOUTUBE_CLIENT_ID"] = cid
if csec:
    data["YOUTUBE_CLIENT_SECRET"] = csec
if redirect:
    data["YOUTUBE_REDIRECT_URI"] = redirect
elif not data.get("YOUTUBE_REDIRECT_URI"):
    base = (data.get("PUBLIC_BASE_URL") or "https://cricrelay.co.uk").rstrip("/")
    data["YOUTUBE_REDIRECT_URI"] = f"{base}/dashboard/youtube/callback"

order = [
    "PORT", "PUBLIC_BASE_URL", "SECRET_KEY", "STATE_DIR", "DATABASE_URL",
    "RELAY_INGEST_TOKEN", "PUSH_TARGET_URL", "RELAY_AUTO_POLL",
    "RELAY_POLL_INTERVAL_SEC", "RELAY_STALE_AFTER_SEC",
    "YOUTUBE_CLIENT_ID", "YOUTUBE_CLIENT_SECRET", "YOUTUBE_REDIRECT_URI",
    "YOUTUBE_TOKEN_ENCRYPTION_KEY", "STREAM_API_TOKEN_TTL_SEC",
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
print("YOUTUBE_CLIENT_ID set:", bool(data.get("YOUTUBE_CLIENT_ID")))
print("YOUTUBE_CLIENT_SECRET set:", bool(data.get("YOUTUBE_CLIENT_SECRET")))
print("YOUTUBE_REDIRECT_URI=", data.get("YOUTUBE_REDIRECT_URI", ""))
PY

chmod 600 "$ENV_FILE" 2>/dev/null || true
chown ec2-user:ec2-user "$ENV_FILE" 2>/dev/null || true
