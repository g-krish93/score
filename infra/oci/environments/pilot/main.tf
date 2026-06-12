terraform {
  required_version = ">= 1.5"
  required_providers {
    oci = {
      source  = "oracle/oci"
      version = "~> 6.0"
    }
  }
}

provider "oci" {
  tenancy_ocid     = var.tenancy_ocid
  user_ocid        = var.user_ocid
  fingerprint      = var.fingerprint
  private_key_path = var.private_key_path
  region           = var.region
}

data "oci_identity_availability_domains" "ads" {
  compartment_id = var.tenancy_ocid
}

locals {
  # Image API is architecture-specific (A1 = ARM, E4/E2 = x86).
  image_catalog_shape = (
    strcontains(lower(var.instance_shape), "a1") ? "VM.Standard.A1.Flex" :
    strcontains(lower(var.instance_shape), "e2.1.micro") ? "VM.Standard.E2.1.Micro" :
    "VM.Standard.E4.Flex"
  )
}

data "oci_core_images" "ol" {
  compartment_id           = var.compartment_id
  operating_system         = "Oracle Linux"
  operating_system_version = "9"
  shape                    = local.image_catalog_shape
  sort_by                  = "TIMECREATED"
  sort_order               = "DESC"
  state                    = "AVAILABLE"
}

module "landing_zone" {
  source = "../../modules/landing-zone"

  compartment_id         = var.compartment_id
  vcn_cidr               = var.vcn_cidr
  public_subnet_cidr     = var.public_subnet_cidr
  private_subnet_cidr    = var.private_subnet_cidr
  prefix                 = var.resource_prefix
  enable_service_gateway = var.enable_service_gateway
}

locals {
  cloud_init = templatefile("${path.module}/cloud-init.yaml.tftpl", {
    github_repo     = var.github_repo
    public_base_url = var.public_base_url
  })
}

resource "oci_core_instance" "cricket" {
  compartment_id      = var.compartment_id
  availability_domain = data.oci_identity_availability_domains.ads.availability_domains[var.availability_domain_index].name
  display_name        = "${var.resource_prefix}-overlay"
  shape               = var.instance_shape

  dynamic "shape_config" {
    for_each = can(regex("Flex", var.instance_shape)) ? [1] : []
    content {
      ocpus         = var.instance_ocpus
      memory_in_gbs = var.instance_memory_gbs
    }
  }

  source_details {
    source_type = "image"
    source_id   = data.oci_core_images.ol.images[0].id
  }

  create_vnic_details {
    subnet_id        = module.landing_zone.public_subnet_id
    assign_public_ip = false
    nsg_ids          = [module.landing_zone.cricket_nsg_id]
  }

  metadata = {
    ssh_authorized_keys = var.ssh_public_key
    user_data           = base64encode(local.cloud_init)
  }

  freeform_tags = {
    workload      = "cricket-overlay"
    migrated_from = "aws-ec2-pilot"
  }
}

data "oci_core_private_ips" "cricket_primary" {
  subnet_id  = module.landing_zone.public_subnet_id
  ip_address = oci_core_instance.cricket.private_ip
  depends_on = [oci_core_instance.cricket]
}

resource "oci_core_public_ip" "cricket" {
  compartment_id = var.compartment_id
  lifetime       = "RESERVED"
  display_name   = "${var.resource_prefix}-reserved-public-ip"
  private_ip_id  = data.oci_core_private_ips.cricket_primary.private_ips[0].id
}
