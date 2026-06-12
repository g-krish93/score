# Backups & at-rest encryption for CricRelay personal data (UK GDPR Art. 32).
#
# Two independent layers:
#   1. Daily encrypted EBS snapshots of the whole instance volume (disaster safety-net)
#      via AWS Data Lifecycle Manager (DLM).
#   2. A nightly app-level SQLite dump pushed to a versioned, encrypted S3 bucket in
#      eu-west-2 (granular, off-host, point-in-time restore). See deploy/backup-sqlite-to-s3.sh.
#
# All data stays in eu-west-2 (London) — no cross-border transfer.

data "aws_caller_identity" "current" {}

# ── Account-wide default EBS encryption (non-destructive; affects NEW volumes/snapshots) ──
# Makes every future EBS snapshot — including the DLM ones below — encrypted at rest.
# Does NOT alter the existing root volume; see deploy/ebs-encryption.md to encrypt that.
resource "aws_ebs_encryption_by_default" "this" {
  enabled = var.enable_ebs_encryption_by_default
}

# ──────────────────────────────────────────────────────────────────────────────
# 1. EBS snapshots via Data Lifecycle Manager
# ──────────────────────────────────────────────────────────────────────────────

resource "aws_iam_role" "dlm" {
  name = "cricrelay-dlm-snapshot-role"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "dlm.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "dlm" {
  role       = aws_iam_role.dlm.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSDataLifecycleManagerServiceRole"
}

resource "aws_dlm_lifecycle_policy" "cricrelay_ebs" {
  description        = "CricRelay daily EBS snapshots - DB and secrets volume"
  execution_role_arn = aws_iam_role.dlm.arn
  state              = "ENABLED"

  policy_details {
    resource_types = ["VOLUME"]
    # Targets the root volume tagged Backup=cricrelay (set in main.tf).
    target_tags = { Backup = "cricrelay" }

    schedule {
      name = "daily-7d-weekly-4w"

      create_rule {
        interval      = 24
        interval_unit = "HOURS"
        times         = ["03:00"] # UTC, low-traffic window
      }

      retain_rule {
        count = var.ebs_snapshot_daily_retention
      }

      tags_to_add = {
        SnapshotCreator = "dlm"
        Project         = "cricrelay"
      }
      copy_tags = true
    }
  }
}

# ──────────────────────────────────────────────────────────────────────────────
# 2. S3 bucket for nightly SQLite dumps (DB only — no secrets; EBS snapshot covers .env/.secret_key)
# ──────────────────────────────────────────────────────────────────────────────

locals {
  backup_bucket_name = "cricrelay-db-backups-${data.aws_caller_identity.current.account_id}"
}

resource "aws_s3_bucket" "db_backups" {
  bucket = local.backup_bucket_name
  tags   = { Project = "cricrelay", Purpose = "db-backups" }
}

resource "aws_s3_bucket_versioning" "db_backups" {
  bucket = aws_s3_bucket.db_backups.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "db_backups" {
  bucket = aws_s3_bucket.db_backups.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "db_backups" {
  bucket                  = aws_s3_bucket.db_backups.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_lifecycle_configuration" "db_backups" {
  bucket = aws_s3_bucket.db_backups.id
  rule {
    id     = "retain-then-expire"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration { noncurrent_days = var.s3_backup_retention_days }
    expiration { days = var.s3_backup_retention_days }
    abort_incomplete_multipart_upload { days_after_initiation = 7 }
  }
}

# Deny any non-TLS access (UK GDPR Art. 32 — encryption in transit).
resource "aws_s3_bucket_policy" "db_backups_tls" {
  bucket = aws_s3_bucket.db_backups.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource = [
        aws_s3_bucket.db_backups.arn,
        "${aws_s3_bucket.db_backups.arn}/*"
      ]
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  })
}

# ──────────────────────────────────────────────────────────────────────────────
# 3. Least-privilege instance profile so the EC2 box can push backups to S3
# ──────────────────────────────────────────────────────────────────────────────

resource "aws_iam_role" "instance" {
  name = "cricrelay-instance-role"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "instance_s3_backup" {
  name = "cricrelay-s3-backup-write"
  role = aws_iam_role.instance.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ListBackupBucket"
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = aws_s3_bucket.db_backups.arn
      },
      {
        Sid      = "WriteAndReadBackups"
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:GetObject"]
        Resource = "${aws_s3_bucket.db_backups.arn}/*"
      }
    ]
  })
}

resource "aws_iam_instance_profile" "instance" {
  name = "cricrelay-instance-profile"
  role = aws_iam_role.instance.name
}

output "backup_bucket" {
  value       = aws_s3_bucket.db_backups.bucket
  description = "S3 bucket holding nightly SQLite backups (set BACKUP_S3_BUCKET to this on the instance)."
}
