#!/bin/bash
# Nightly CricRelay SQLite backup → encrypted, versioned S3 bucket (eu-west-2).
#
# Uses SQLite's online ".backup" (consistent snapshot while the app runs), gzips it,
# uploads server-side-encrypted, then verifies the object exists. DB-only: the
# encryption key (.secret_key) and .env are captured by the daily EBS snapshot, so we
# don't duplicate secrets into S3.
#
# Auth: the EC2 instance profile (cricrelay-instance-profile) grants s3:PutObject — no
# static keys. Schedule via deploy/cricrelay-backup.timer.
#
# Required env (from /app/.env or systemd unit):
#   BACKUP_S3_BUCKET   target bucket (terraform output backup_bucket)
# Optional:
#   STATE_DIR          dir holding cricrelay.db (default /app/data)
#   AWS_REGION         default eu-west-2
set -euo pipefail

STATE_DIR="${STATE_DIR:-/app/data}"
DB_PATH="${STATE_DIR%/}/cricrelay.db"
AWS_REGION="${AWS_REGION:-eu-west-2}"
BUCKET="${BACKUP_S3_BUCKET:-}"

if [[ -z "$BUCKET" ]]; then
  echo "ERROR: BACKUP_S3_BUCKET not set (terraform output backup_bucket)." >&2
  exit 1
fi
if [[ ! -f "$DB_PATH" ]]; then
  echo "ERROR: SQLite DB not found at $DB_PATH" >&2
  exit 1
fi

TS="$(date -u +%Y%m%dT%H%M%SZ)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
SNAP="$WORK/cricrelay-${TS}.db"
GZ="${SNAP}.gz"
KEY="daily/cricrelay-${TS}.db.gz"

# 1. Consistent online snapshot (safe while gunicorn holds the live DB open).
if command -v sqlite3 >/dev/null 2>&1; then
  sqlite3 "$DB_PATH" ".backup '$SNAP'"
else
  # Fallback: VACUUM INTO via python stdlib if sqlite3 CLI is absent.
  python3 - "$DB_PATH" "$SNAP" <<'PY'
import sqlite3, sys
src, dst = sys.argv[1], sys.argv[2]
con = sqlite3.connect(src)
con.execute("VACUUM INTO ?", (dst,))
con.close()
PY
fi

# 2. Integrity check before we trust this as a backup.
if command -v sqlite3 >/dev/null 2>&1; then
  CHECK="$(sqlite3 "$SNAP" 'PRAGMA integrity_check;' || echo 'failed')"
  if [[ "$CHECK" != "ok" ]]; then
    echo "ERROR: integrity_check on snapshot failed: $CHECK" >&2
    exit 1
  fi
fi

gzip -9 "$SNAP"

# 3. Upload (bucket enforces SSE + TLS; we also set SSE explicitly).
aws s3 cp "$GZ" "s3://${BUCKET}/${KEY}" \
  --region "$AWS_REGION" \
  --sse AES256 \
  --only-show-errors

# 4. Verify the object landed.
if aws s3api head-object --bucket "$BUCKET" --key "$KEY" --region "$AWS_REGION" >/dev/null 2>&1; then
  SIZE="$(stat -c%s "$GZ" 2>/dev/null || echo '?')"
  echo "OK: s3://${BUCKET}/${KEY} (${SIZE} bytes, gzipped)"
else
  echo "ERROR: upload verification failed for ${KEY}" >&2
  exit 1
fi
