#!/bin/bash
# Merge Twitch OAuth settings into /app/.env
set -euo pipefail

APP="${APP:-/app}"
ENV_FILE="${APP}/.env"
ID_FILE="${APP}/secrets/twitch_client_id"
SECRET_FILE="${APP}/secrets/twitch_client_secret"

read_secret() {
  local file="$1"
  if [[ -f "$file" ]]; then
    tr -d '\r\n' <"$file"
    return
  fi
  echo ""
}

TWITCH_CLIENT_ID="${TWITCH_CLIENT_ID:-}"
TWITCH_CLIENT_SECRET="${TWITCH_CLIENT_SECRET:-}"

if [[ -z "$TWITCH_CLIENT_ID" ]]; then
  TWITCH_CLIENT_ID="$(read_secret "$ID_FILE")"
fi
if [[ -z "$TWITCH_CLIENT_SECRET" ]]; then
  TWITCH_CLIENT_SECRET="$(read_secret "$SECRET_FILE")"
fi

export TWITCH_CLIENT_ID TWITCH_CLIENT_SECRET TWITCH_REDIRECT_URI

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
data.setdefault("PUBLIC_BASE_URL", "https://cricrelay.co.uk")

cid = os.environ.get("TWITCH_CLIENT_ID", "").strip()
csec = os.environ.get("TWITCH_CLIENT_SECRET", "").strip()
redirect = os.environ.get("TWITCH_REDIRECT_URI", "").strip()

if cid:
    data["TWITCH_CLIENT_ID"] = cid
if csec:
    data["TWITCH_CLIENT_SECRET"] = csec
if redirect:
    data["TWITCH_REDIRECT_URI"] = redirect
elif not data.get("TWITCH_REDIRECT_URI"):
    base = (data.get("PUBLIC_BASE_URL") or "https://cricrelay.co.uk").rstrip("/")
    data["TWITCH_REDIRECT_URI"] = f"{base}/dashboard/twitch/callback"

order = [
    "PORT", "PUBLIC_BASE_URL", "SECRET_KEY", "STATE_DIR", "DATABASE_URL",
    "YOUTUBE_CLIENT_ID", "YOUTUBE_CLIENT_SECRET", "YOUTUBE_REDIRECT_URI",
    "TWITCH_CLIENT_ID", "TWITCH_CLIENT_SECRET", "TWITCH_REDIRECT_URI",
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
print("TWITCH_CLIENT_ID set:", bool(data.get("TWITCH_CLIENT_ID")))
print("TWITCH_CLIENT_SECRET set:", bool(data.get("TWITCH_CLIENT_SECRET")))
print("TWITCH_REDIRECT_URI=", data.get("TWITCH_REDIRECT_URI", ""))
PY

chmod 600 "$ENV_FILE" 2>/dev/null || true
chown ec2-user:ec2-user "$ENV_FILE" 2>/dev/null || true
