terraform {
  # Remote state: versioned, encrypted, private S3 bucket with native S3 state
  # locking (use_lockfile, Terraform >= 1.10). Replaces local state-in-repo.
  backend "s3" {
    bucket       = "cricrelay-tfstate-973646734579"
    key          = "infra/terraform.tfstate"
    region       = "eu-west-2"
    encrypt      = true
    use_lockfile = true
  }

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

resource "aws_security_group" "cricket_sg" {
  name        = "cricket-overlay-sg"
  description = "Cricket score overlay"

  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    from_port   = 5000
    to_port     = 5000
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # Public site (nginx -> Gunicorn). Without these, https://cricrelay.co.uk does not reach the instance.
  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

data "aws_ami" "amazon_linux" {
  most_recent = true
  owners      = ["amazon"]
  filter {
    name   = "name"
    values = ["al2023-ami-*-x86_64"]
  }
}

resource "aws_instance" "cricket" {
  ami                    = data.aws_ami.amazon_linux.id
  instance_type          = var.instance_type
  key_name               = var.key_name
  vpc_security_group_ids = [aws_security_group.cricket_sg.id]
  user_data              = templatefile("user_data.sh", { github_repo = var.github_repo })
  tags                   = { Name = "cricket-overlay" }

  # Lets the box push nightly SQLite backups to S3 without static keys (see backups.tf).
  iam_instance_profile = aws_iam_instance_profile.instance.name

  root_block_device {
    volume_size = var.root_volume_size_gb
    volume_type = "gp3"
    # Tag so Data Lifecycle Manager picks this volume up for daily snapshots (backups.tf).
    tags = { Backup = "cricrelay" }
  }

  lifecycle {
    ignore_changes = [ami, user_data]
  }
}

resource "aws_eip" "cricket_ip" {
  instance = aws_instance.cricket.id
  domain   = "vpc"
}

output "public_ip" {
  value       = aws_eip.cricket_ip.public_ip
  description = "Overlay URL: http://THIS_IP:5000  |  Input UI: http://THIS_IP:5000/input"
}
