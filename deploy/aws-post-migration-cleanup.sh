#!/bin/bash
# Post-RDS→SQLite migration cleanup — idempotent, safe to re-run daily.
#
# Runs on EC2 via cricrelay-post-migration-cleanup.timer (04:00 UTC, after DLM at 03:00).
# Uses the instance profile (see cricrelay-post-migration-cleanup IAM policy).
#
# Actions:
#   1. If any DLM snapshot exists (tag SnapshotCreator=dlm), delete the one-off manual
#      pre-migration snapshot snap-03ec0172c1a87fad8 when it still exists.
#   2. Log RDS cricrelay-db status (gone / deleting / unexpected alive).
#   3. Verify S3 backup bucket reachable and app /health OK.
set -euo pipefail

AWS_REGION="${AWS_REGION:-eu-west-2}"
MANUAL_SNAP="snap-03ec0172c1a87fad8"
RDS_ID="cricrelay-db"
LOG_TAG="cricrelay-post-migration-cleanup"

log() { echo "[$(date -u +%Y-%m-%dT%H:%M:%SZ)] $LOG_TAG: $*"; }

# ── 1. Manual snapshot cleanup (only after DLM has taken over) ──────────────
dlm_count="$(aws ec2 describe-snapshots --region "$AWS_REGION" \
  --owner-ids self \
  --filters "Name=tag:SnapshotCreator,Values=dlm" \
  --query 'length(Snapshots)' --output text 2>/dev/null || echo 0)"

if [[ "${dlm_count:-0}" =~ ^[0-9]+$ ]] && [[ "$dlm_count" -gt 0 ]]; then
  log "DLM snapshots present ($dlm_count); checking manual snapshot $MANUAL_SNAP"
  manual_state="$(aws ec2 describe-snapshots --region "$AWS_REGION" \
    --snapshot-ids "$MANUAL_SNAP" \
    --query 'Snapshots[0].State' --output text 2>/dev/null || echo 'not-found')"
  if [[ "$manual_state" == "completed" || "$manual_state" == "pending" ]]; then
    if aws ec2 delete-snapshot --region "$AWS_REGION" --snapshot-id "$MANUAL_SNAP" >/dev/null 2>&1; then
      log "Deleted manual snapshot $MANUAL_SNAP (DLM backups confirmed)"
    else
      log "WARN: failed to delete $MANUAL_SNAP (may already be gone or lack permission)"
    fi
  elif [[ "$manual_state" == "not-found" || "$manual_state" == "None" ]]; then
    log "Manual snapshot $MANUAL_SNAP already absent — nothing to do"
  else
    log "Manual snapshot $MANUAL_SNAP state=$manual_state — skipping delete"
  fi
else
  log "No DLM snapshots yet ($dlm_count); keeping manual snapshot $MANUAL_SNAP as safety net"
fi

# ── 2. RDS migration instance ───────────────────────────────────────────────
if aws rds describe-db-instances --region "$AWS_REGION" \
    --db-instance-identifier "$RDS_ID" >/dev/null 2>&1; then
  rds_status="$(aws rds describe-db-instances --region "$AWS_REGION" \
    --db-instance-identifier "$RDS_ID" \
    --query 'DBInstances[0].DBInstanceStatus' --output text)"
  log "RDS $RDS_ID still exists (status=$rds_status)"
else
  log "RDS $RDS_ID not found — migration teardown complete"
fi

# ── 3. S3 backup bucket + app health ────────────────────────────────────────
BUCKET="${BACKUP_S3_BUCKET:-}"
if [[ -z "$BUCKET" && -f /app/.env ]]; then
  # shellcheck disable=SC1091
  source /app/.env 2>/dev/null || true
  BUCKET="${BACKUP_S3_BUCKET:-}"
fi
if [[ -z "$BUCKET" ]]; then
  BUCKET="cricrelay-db-backups-973646734579"
fi

if aws s3api head-bucket --bucket "$BUCKET" --region "$AWS_REGION" >/dev/null 2>&1; then
  recent="$(aws s3 ls "s3://${BUCKET}/daily/" --region "$AWS_REGION" 2>/dev/null | tail -1 || true)"
  if [[ -n "$recent" ]]; then
    log "S3 backup OK: latest daily object — $recent"
  else
    log "WARN: S3 bucket $BUCKET reachable but no daily/ objects yet"
  fi
else
  log "WARN: cannot reach S3 bucket $BUCKET"
fi

if curl -sf --max-time 10 http://127.0.0.1:5000/health >/dev/null 2>&1; then
  log "App health endpoint OK"
else
  log "WARN: /health check failed"
fi

log "Done"
