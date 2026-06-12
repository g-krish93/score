variable "compartment_id" {
  type        = string
  description = "OCID of the compartment for all resources (create in OCI Console or reuse root)."
}

variable "vcn_cidr" {
  type        = string
  description = "CIDR for the VCN (must not overlap peered networks or on-prem ranges)."
  default     = "10.42.0.0/16"
}

variable "public_subnet_cidr" {
  type        = string
  description = "Public subnet CIDR (routable via Internet Gateway)."
  default     = "10.42.1.0/24"
}

variable "private_subnet_cidr" {
  type        = string
  description = "Private subnet for future workloads (NAT optional; no route to IGW by default)."
  default     = "10.42.2.0/24"
}

variable "prefix" {
  type        = string
  description = "Display name prefix for resources."
  default     = "cricket"
}

variable "enable_service_gateway" {
  type        = bool
  description = "If true, add Service Gateway and route Oracle Services traffic from private subnet."
  default     = true
}
