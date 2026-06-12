variable "aws_region" {
  default = "eu-west-2"
}

variable "instance_type" {
  description = "EC2 size. New AWS accounts often only allow free-tier-eligible types (e.g. t3.micro); t2.micro may be rejected."
  default     = "t3.micro"
}

variable "key_name" {
  description = "EC2 key pair name - create in AWS console first"
  type        = string
}

variable "github_repo" {
  description = "GitHub repo URL e.g. https://github.com/USERNAME/cricket-overlay.git"
  type        = string
}

variable "root_volume_size_gb" {
  description = "Root EBS volume size (GB) for the cricket EC2 instance."
  type        = number
  default     = 20
}

variable "enable_ebs_encryption_by_default" {
  description = "Turn on account/region default EBS encryption. Non-destructive: only affects NEW volumes/snapshots (incl. DLM backups). Set false if another workload in this account relies on unencrypted volumes."
  type        = bool
  default     = true
}

variable "ebs_snapshot_daily_retention" {
  description = "How many daily EBS snapshots DLM keeps before aging them out."
  type        = number
  default     = 7
}

variable "s3_backup_retention_days" {
  description = "How long nightly SQLite backups (and old versions) live in S3 before deletion. Keep this aligned with your stated retention policy."
  type        = number
  default     = 30
}