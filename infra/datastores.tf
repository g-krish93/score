# ---------------------------------------------------------------------------
# Self-hosted Postgres + Redis on one dedicated instance.
#
# ADDITIVE BY DESIGN: every resource here is NEW. This file never references
# the prod web box for modification — it only *reads* the app security group id
# so the datastore can accept connections from it. The plan must therefore show
# 0 to change / 0 to destroy on existing resources.
#
# SECRET HYGIENE: the Postgres password lives only in SSM SecureString, created
# out-of-band (see infra/README_datastores or the runbook). Terraform never
# stores the password value, so it cannot leak through the git-tracked state.
# ---------------------------------------------------------------------------

# Note: required_providers and data.aws_caller_identity.current are already
# declared in main.tf / backups.tf — a module allows only one of each, so we
# reference them here rather than redeclaring.

data "aws_kms_alias" "ssm" {
  name = "alias/aws/ssm"
}

# Read-only reference to the app's security group. Using a DATA source (not the
# managed aws_security_group.cricket_sg) deliberately keeps the prod SG out of
# this file's apply graph, so deploying datastores can never modify it — even if
# the prod SG has drifted from main.tf.
data "aws_security_group" "app" {
  name = "cricket-overlay-sg"
}

locals {
  pg_password_param_name = "/cricrelay/datastore/pg_password"
  pg_password_param_arn  = "arn:aws:ssm:${var.aws_region}:${data.aws_caller_identity.current.account_id}:parameter${local.pg_password_param_name}"
}

# Reachable ONLY from the app box. No public ingress — the opposite of the
# legacy wide-open security group.
resource "aws_security_group" "datastore_sg" {
  name        = "cricrelay-datastore-sg"
  description = "Postgres + Redis, reachable only from the app security group"

  ingress {
    description     = "Postgres from app box"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [data.aws_security_group.app.id]
  }

  ingress {
    description     = "Redis from app box"
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [data.aws_security_group.app.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "cricrelay-datastore-sg" }
}

# Instance role: managed via SSM Session Manager (no SSH port open at all) and
# allowed to read just the one DB-password parameter at boot.
resource "aws_iam_role" "datastore" {
  name = "cricrelay-datastore-role"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "datastore_ssm" {
  role       = aws_iam_role.datastore.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy" "datastore_secret" {
  name = "read-pg-password"
  role = aws_iam_role.datastore.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = "ssm:GetParameter"
        Resource = local.pg_password_param_arn
      },
      {
        Effect   = "Allow"
        Action   = "kms:Decrypt"
        Resource = data.aws_kms_alias.ssm.target_key_arn
      }
    ]
  })
}

resource "aws_iam_instance_profile" "datastore" {
  name = "cricrelay-datastore-profile"
  role = aws_iam_role.datastore.name
}

resource "aws_instance" "datastore" {
  ami                    = data.aws_ami.amazon_linux.id
  instance_type          = "t3.micro" # cheapest viable; bump to t3.small if RAM-bound
  vpc_security_group_ids = [aws_security_group.datastore_sg.id]
  iam_instance_profile   = aws_iam_instance_profile.datastore.name

  user_data = templatefile("${path.module}/templates/datastore_user_data.sh.tftpl", {
    pg_password_param = local.pg_password_param_name
    region            = var.aws_region
  })

  tags = { Name = "cricrelay-datastore" }

  root_block_device {
    volume_size = 20
    volume_type = "gp3"
    tags        = { Backup = "cricrelay" } # picked up by the existing DLM snapshot policy
  }

  # Same guard as the prod box: never silently replace on AMI/user_data drift.
  lifecycle {
    ignore_changes = [ami, user_data]
  }
}

output "datastore_private_ip" {
  value       = aws_instance.datastore.private_ip
  description = "Private IP the app box uses: Postgres on 5432, Redis on 6379."
}
