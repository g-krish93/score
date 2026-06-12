# AWS dependency inventory (repository + CI)

This document reflects **what is visible in this workspace**. Anything not represented here must be confirmed in the **AWS Console**, **AWS Organizations**, billing exports, and vendor systems.

**OCI migration artifacts in this repo:** [OCI_LANDING_ZONE.md](./OCI_LANDING_ZONE.md) (landing zone design), [RUNBOOK_DNS_TLS.md](./RUNBOOK_DNS_TLS.md), [RUNBOOK_CUTOVER_DECOMMISSION.md](./RUNBOOK_CUTOVER_DECOMMISSION.md), Terraform under [../oci/](../oci/).

## Summary

| Category | In-repo finding | Console verification |
|----------|-----------------|----------------------|
| Compute | Terraform: single EC2 (Amazon Linux 2023), Elastic IP | Other accounts/regions, ASG, ECS/EKS, Lambda |
| Network / edge | Security group: 22, 5000, 80, 443 open to `0.0.0.0/0` | ALB/NLB, CloudFront, WAF, Global Accelerator |
| DNS / TLS | `PUBLIC_BASE_URL=https://cricrelay.co.uk`, nginx deploy config | Route 53, registrar, ACM/certs |
| Secrets / auth | GitHub Actions: `EC2_HOST`, `EC2_KEY`, `SMTP_PASSWORD` | Secrets Manager, SSM Parameter Store, IAM users/roles for CI |
| Data stores | App supports Postgres via `.env` (see `.env.example`); default path appears local/SQLite | RDS, Aurora, DynamoDB, S3 buckets used in prod |
| Observability | Not defined in IaC | CloudWatch, alarms, dashboards, X-Ray |
| CI/CD | `.github/workflows/deploy.yml` SSH to EC2 as `ec2-user` | CodePipeline, other workflows, OIDC to AWS |

## Infrastructure as code (Terraform)

| File | Resources |
|------|-----------|
| [../main.tf](../main.tf) | `aws_security_group`, `data.aws_ami` (AL2023), `aws_instance`, `aws_eip` |
| [../variables.tf](../variables.tf) | `aws_region` (default `eu-west-2`), `instance_type`, `key_name`, `github_repo` |

**Gap:** No VPC/subnet resources in Terraform (default VPC implied). OCI pilot uses an explicit VCN; align production networking with org standards.

## Bootstrap (user data)

| File | Notes |
|------|-------|
| [../user_data.sh](../user_data.sh) | `yum`, clone `github_repo` to `/app`, `pip3`, nginx, systemd `cricket` service, `.env` with `PUBLIC_BASE_URL` |

**OCI migration:** Prefer **Oracle Linux** cloud image; adjust package manager (`dnf`/`yum`), default user (`opc` not `ec2-user`), and file ownership.

## Application code

- No `boto3` / `@aws-sdk` usage found under `score/` in a repo-wide search for typical AWS SDK patterns.
- `.env.example` documents optional Postgres (could be **OCI Autonomous Database** or **Base Database** after migration).
- Documentation mentions S3/DynamoDB for state/Terraform backend as **recommendations**, not current pinned infra in this repo.

## GitHub Actions

| Workflow | AWS / host coupling |
|----------|---------------------|
| [../../.github/workflows/deploy.yml](../../.github/workflows/deploy.yml) | `appleboy/ssh-action`: `secrets.EC2_HOST`, `secrets.EC2_KEY`, user `ec2-user`, deploys to `/app` |

**OCI migration:** Add parallel secrets (e.g. `OCI_COMPUTE_HOST`, `OCI_SSH_KEY`) and user **`opc`** for Oracle Linux, or unify behind a bastion.

## DNS and public endpoints

- Production hostname implied: **cricrelay.co.uk** (in `user_data.sh` and docs).
- **Cutover:** Update A/AAAA (or CNAME) records to the OCI public IP or load balancer as documented in [RUNBOOK_DNS_TLS.md](./RUNBOOK_DNS_TLS.md).

## Security items (from existing docs)

- [score_documentation.md](../../score_documentation.md) flags: wide-open SSH, keys in repo risk, CORS, TLS recommendations. Track these during migration; do not regress on OCI.

## Action checklist (owner)

1. [ ] Export full AWS resource inventory (Resource Explorer / Config / manual spreadsheet) per account/region.
2. [ ] Confirm whether production uses RDS/S3/CloudFront beyond this repo.
3. [ ] List all GitHub (and other) secrets that reference AWS hosts or keys; plan OCI replacements.
4. [ ] Confirm data residency (UK/EU) for OCI region choice vs. current `eu-west-2`.
