output "reserved_public_ip" {
  value       = oci_core_public_ip.cricket.ip_address
  description = "Point staging DNS A record here; update GitHub OCI_HOST secret."
}

output "instance_ocid" {
  value = oci_core_instance.cricket.id
}

output "vcn_ocid" {
  value = module.landing_zone.vcn_id
}

output "public_subnet_ocid" {
  value = module.landing_zone.public_subnet_id
}
