variable "aws_region" {
  default = "eu-west-2"
}

variable "instance_type" {
  default = "t2.micro"
}

variable "key_name" {
  description = "EC2 key pair name - create in AWS console first"
  type        = string
}

variable "github_repo" {
  description = "GitHub repo URL e.g. https://github.com/USERNAME/cricket-overlay.git"
  type        = string
}
