variable "availability_domain_index" {
  type        = number
  description = "Which AD to use (0 = first). If A1 is out of capacity in AD0, try 1 or 2."
  default     = 0
}

variable "tenancy_ocid" {
  type        = string
  description = "Tenancy OCID (from OCI Console: Tenancy details)."
}

variable "user_ocid" {
  type        = string
  description = "User OCID for API key auth."
}

variable "fingerprint" {
  type        = string
  description = "API key fingerprint."
}

variable "private_key_path" {
  type        = string
  description = "Path to PEM private key matching the uploaded public key in OCI."
}

variable "region" {
  type        = string
  description = "OCI region identifier, e.g. uk-london-1."
  default     = "uk-london-1"
}

variable "compartment_id" {
  type        = string
  description = "Compartment OCID where VCN and instance are created."
}

variable "resource_prefix" {
  type    = string
  default = "cricket"
}

variable "vcn_cidr" {
  type    = string
  default = "10.42.0.0/16"
}

variable "public_subnet_cidr" {
  type    = string
  default = "10.42.1.0/24"
}

variable "private_subnet_cidr" {
  type    = string
  default = "10.42.2.0/24"
}

variable "enable_service_gateway" {
  type    = bool
  default = true
}

variable "instance_shape" {
  type        = string
  description = "OCI compute shape. Always Free: VM.Standard.A1.Flex (ARM, needs Flex shape_config) or VM.Standard.E2.1.Micro (x86, tiny). Paid x86: VM.Standard.E4.Flex."
  default     = "VM.Standard.A1.Flex"
}

variable "instance_ocpus" {
  type        = number
  description = "OCPUs for Flex shapes only (A1/E4). Always Free A1: up to 4 OCPUs total across all A1 instances in your home region."
  default     = 1
}

variable "instance_memory_gbs" {
  type        = number
  description = "Memory (GB) for Flex shapes. A1 Flex minimum is 1 GB per OCPU; 6 GB is a reasonable default for this app."
  default     = 6
}

variable "ssh_public_key" {
  type        = string
  description = "SSH public key for the opc user (single line)."
}

variable "github_repo" {
  type        = string
  description = "Git repository URL to clone into /app."
}

variable "public_base_url" {
  type        = string
  description = "PUBLIC_BASE_URL for .env (use staging hostname during pilot)."
  default     = "https://staging.example.invalid"
}
