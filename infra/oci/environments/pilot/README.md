# OCI pilot — cricket overlay

Provisioning: **VCN + public/private subnets + NSG + Oracle Linux 9 instance + reserved public IP**, with **cloud-init** mirroring [../../user_data.sh](../../user_data.sh) for Oracle Linux (`opc` user, `dnf`, `pip3 --break-system-packages`).

## Prerequisites

1. OCI **API key** (upload public key, note fingerprint, user OCID, tenancy OCID).
2. A **compartment OCID** with quota for Compute + Networking.
3. Local **Terraform >= 1.5**.
4. **Windows:** put the downloaded API private key at `C:\Users\<you>\.oci\oci_api_key.pem` and set `private_key_path` to `C:/Users/<you>/.oci/oci_api_key.pem` (forward slashes). The path `~/.oci/...` often triggers: `did not find a proper configuration for private key`.
5. Run `terraform plan` from this directory so auto-loaded `terraform.tfvars` is picked up (avoid typing OCIDs at prompts — easy to truncate an OCID).

## Always Free compute (defaults)

Terraform defaults to **Always Free** [Ampere A1 Flex](https://docs.oracle.com/iaas/Content/FreeTier/resourceref.htm): `VM.Standard.A1.Flex` with **1 OCPU** and **6 GB** RAM (within the home-region cap of **4 OCPUs and 24 GB total** across all A1 instances). The OS image picker follows the shape (**ARM64** Oracle Linux 9 for A1).

- If `terraform apply` fails with **`Out of host capacity`** for A1, set **`availability_domain_index`** to `1` or `2` in `terraform.tfvars` (different AD in the same region) and run **`terraform apply`** again. London and other busy regions often exhaust A1 in AD-1 first.
- **There is no Oracle “wait list”** that holds a slot and provisions the VM for you when A1 capacity returns. Frees are unpredictable; keep retrying, change AD/region/shape, or run a local loop (see below).
- If all ADs fail, wait and retry, try another **home region**, or temporarily use **`VM.Standard.E2.1.Micro`** (Always Free x86, 1 GB) or a paid **E4 Flex** shape.
- **Automatic retries (self-managed):** from this folder, Git Bash: `./retry-apply.sh` (optional env `MAX_ATTEMPTS`, `SLEEP_SECONDS`). Windows: `.\retry-apply.ps1`. Official troubleshooting: [Resolving Out of Host Capacity](https://docs.oracle.com/iaas/Content/Compute/Tasks/troubleshooting-out-of-host-capacity.htm).
- **ARM:** most Python wheels install fine; if a dependency lacks ARM wheels, switch to **`VM.Standard.E2.1.Micro`** / **E4 Flex**.

## Commands

```bash
cd score/infra/oci/environments/pilot
cp terraform.tfvars.example terraform.tfvars
# Edit terraform.tfvars with your OCIDs, region, ssh key, github_repo, public_base_url

terraform init
terraform plan
terraform apply
terraform output reserved_public_ip
```

## Post-apply validation

```bash
curl -sS "http://$(terraform output -raw reserved_public_ip):5000/health"
```

For TLS and DNS, follow [../../migration-aws-oci/RUNBOOK_DNS_TLS.md](../../migration-aws-oci/RUNBOOK_DNS_TLS.md).

## GitHub Actions (OCI)

Configure repository secrets: `OCI_COMPUTE_HOST` (same IP as output), `OCI_SSH_KEY` (private key matching `ssh_public_key`), optional `SMTP_PASSWORD`. Use workflow [../../../.github/workflows/deploy-oci.yml](../../../.github/workflows/deploy-oci.yml).

## TLS note

Cloud-init installs nginx from repo config in the app if present; **HTTPS certificates** are still your responsibility (certbot or LB) — see DNS/TLS runbook.
