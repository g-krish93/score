output "vcn_id" {
  value = oci_core_vcn.this.id
}

output "public_subnet_id" {
  value = oci_core_subnet.public.id
}

output "private_subnet_id" {
  value = oci_core_subnet.private.id
}

output "cricket_nsg_id" {
  value       = oci_core_network_security_group.cricket.id
  description = "Attach to compute VNIC; rules mirror AWS security group for pilot parity."
}

output "internet_gateway_id" {
  value = oci_core_internet_gateway.this.id
}
