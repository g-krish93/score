# OCI landing zone (this repo)

Terraform module: [../oci/modules/landing-zone](../oci/modules/landing-zone)

## What it provisions

- VCN with DNS label
- Internet Gateway and public route table (`0.0.0.0/0` → IGW)
- Optional **Service Gateway** + private route to Oracle Services Network (OSN) for private subnet egress to OCI APIs (Object Storage, Vault, etc.)
- **Public subnet** (public IPs allowed) with a **security list** mirroring the legacy AWS rules: TCP 22, 80, 443, 5000 from `0.0.0.0/0` (tighten before production)
- **Private subnet** (no public IP on VNICs) with a **restrictive security list** (egress all; SSH only from the VCN CIDR)
- **Network Security Group** `cricket` with the same ingress ports for attachment to compute VNICs (OCI evaluates subnet SL **and** NSG)

## Compartments (human / console)

Terraform expects a **compartment OCID** (`compartment_id`). Typical layout:

| Compartment | Purpose |
|-------------|---------|
| `network` | Shared VCN (optional) |
| `cricket-dev` / `cricket-prod` | Workload + compute |

Create compartments in **Identity & Security → Compartments**, then pass the target OCID into the pilot stack.

## IAM policies (templates)

Replace `GROUP_NAME` and `COMPARTMENT_NAME` with your IdP group and compartment **as named in policies** (or use OCIDs where required).

**Operators** (Terraform + day-2 on cricket compartment):

```text
Allow group GROUP_NAME to manage virtual-network-family in compartment COMPARTMENT_NAME
Allow group GROUP_NAME to manage instance-family in compartment COMPARTMENT_NAME
Allow group GROUP_NAME to use volume-family in compartment COMPARTMENT_NAME
Allow group GROUP_NAME to manage object-family in compartment COMPARTMENT_NAME
```

**CI/CD dynamic group** (if using instance principal from OCI Compute — optional):

1. Create **Dynamic Group** matching your worker instances, e.g. `ALL {instance.compartment.id = 'ocid1.compartment.oc1...'}`  
2. Policy:

```text
Allow dynamic-group WORKER_DG to read secret-bundles in compartment COMPARTMENT_NAME
Allow dynamic-group WORKER_DG to use vaults in compartment COMPARTMENT_NAME
```

For GitHub Actions, **API key in GitHub secrets** is often simpler than instance principal; rotate keys when migrating.

## Vault (secrets)

- Create a **Vault** in the workload compartment (or a shared `security` compartment).
- Store SMTP and DB credentials as **secrets**; grant read to the operator group or a dynamic group for the app host.
- Application change: read secret files or env injection via **cloud-init** from Vault (implementation outside this module — use OCI CLI/SDK with instance principal or boot-time script).

## Logging and alarms

- **Service Connector Hub** or **Logging** export for subnet flow logs, instance logs, and Load Balancer logs (when added).
- **Alarms** on instance state, high CPU, **500** rate from nginx access logs (custom metric), and **budget alerts** at tenancy level.

Concrete Terraform for connectors/alarms is environment-specific; track as a follow-up after the pilot VM is stable.

## Files

- Example operator policy pack (copy into your root module or OCI Console): [../oci/policies/operator-iam.tf.example](../oci/policies/operator-iam.tf.example)
