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
