# CricRelay — Backup & Restore Runbook

Personal data (club + member accounts, encrypted OAuth tokens, stream history) lives in
a single SQLite file on the EC2 instance: `/app/data/cricrelay.db`. Two backup layers,
both in **eu-west-2 (London)** and encrypted at rest:

| Layer | What | Where | Cadence | Retention |
|-------|------|-------|---------|-----------|
| **EBS snapshots** (DLM) | Whole root volume — DB **+** `.secret_key` + `.env` | EBS snapshots | Daily 03:00 UTC | `ebs_snapshot_daily_retention` (default 7) |
| **S3 SQLite dumps** | DB only (consistent `.backup`, gzipped) | `s3://cricrelay-db-backups-<acct>/daily/` | Daily 02:30 UTC | `s3_backup_retention_days` (default 30) |

> The **encryption key** (`/app/.secret_key`) is only in the EBS snapshot, not in S3.
> A DB restored from S3 needs that key (from the live box or an EBS snapshot) to decrypt
> stored YouTube/Twitch tokens. Account data and password logins work without it.

---

## One-time setup (after `terraform apply`)

```bash
cd infra && terraform apply          # creates DLM policy, S3 bucket, instance profile
terraform output backup_bucket       # e.g. cricrelay-db-backups-123456789012
```

On the instance, set the bucket and confirm the timer:
```bash
sudo sed -i '/^BACKUP_S3_BUCKET=/d' /app/.env
echo "BACKUP_S3_BUCKET=$(cd infra && terraform output -raw backup_bucket)" | sudo tee -a /app/.env
sudo systemctl enable --now cricrelay-backup.timer
systemctl list-timers cricrelay-backup.timer     # confirm next run

# Smoke-test a backup immediately:
sudo systemctl start cricrelay-backup.service
journalctl -u cricrelay-backup.service -n 20 --no-pager   # expect "OK: s3://…"
aws s3 ls s3://$(cd infra && terraform output -raw backup_bucket)/daily/
```

---

## Restore: from S3 (most common — DB rollback / corruption)

```bash
BUCKET=$(cd infra && terraform output -raw backup_bucket)
aws s3 ls "s3://$BUCKET/daily/"                       # pick a timestamp
aws s3 cp "s3://$BUCKET/daily/cricrelay-YYYYMMDDThhmmssZ.db.gz" /tmp/restore.db.gz
gunzip /tmp/restore.db.gz                             # -> /tmp/restore.db
sqlite3 /tmp/restore.db 'PRAGMA integrity_check;'    # expect: ok

sudo systemctl stop cricket
sudo cp /app/data/cricrelay.db /app/data/cricrelay.db.bak.$(date -u +%s)   # keep current
sudo cp /tmp/restore.db /app/data/cricrelay.db
sudo chown ec2-user:ec2-user /app/data/cricrelay.db
sudo systemctl start cricket
curl -s localhost:5000/health                        # sanity
```

OAuth tokens decrypt as long as `/app/.secret_key` and `YOUTUBE_TOKEN_ENCRYPTION_KEY`
are unchanged (they are, unless the instance was rebuilt — then see EBS restore).

## Restore: from an EBS snapshot (instance/volume lost, or you need the keys back)

1. **EC2 → Snapshots**, filter tag `Project=cricrelay`, pick the most recent.
2. **Create volume** from it (same AZ as a target instance), or **Create image (AMI)** → launch.
3. Attach the volume and copy `/data/cricrelay.db`, `/.secret_key`, `/.env` across — or just
   launch the AMI as the new instance and re-associate the Elastic IP.
4. `sudo systemctl start cricket && curl -s localhost:5000/health`.

---

## Verify backups are healthy (do this monthly)

```bash
# S3: a fresh object exists in the last 24h
aws s3 ls s3://$(terraform output -raw backup_bucket)/daily/ | tail -3
# EBS: recent snapshots exist
aws ec2 describe-snapshots --owner-ids self --region eu-west-2 \
  --filters "Name=tag:Project,Values=cricrelay" \
  --query 'reverse(sort_by(Snapshots,&StartTime))[:3].[SnapshotId,StartTime,State]' --output table
```

A backup you have never restored is a hypothesis, not a backup — do a test restore into a
throwaway path at least once after first setup.

---

## RPO / RTO

- **RPO** ≈ 24h (nightly). Lower it by adding a second timer run or switching to Litestream
  (continuous WAL shipping to S3) if the club base grows.
- **RTO**: minutes for an S3 DB restore; ~15–30 min for a full EBS/AMI rebuild.
