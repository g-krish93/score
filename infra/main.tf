terraform {
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
}

resource "aws_eip" "cricket_ip" {
  instance = aws_instance.cricket.id
  domain   = "vpc"
}

output "public_ip" {
  value       = aws_eip.cricket_ip.public_ip
  description = "Overlay URL: http://THIS_IP:5000  |  Input UI: http://THIS_IP:5000/input"
}
