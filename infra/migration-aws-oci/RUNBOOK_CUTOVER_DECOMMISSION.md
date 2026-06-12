# Cutover, soak, monitoring, and AWS decommission

## Roles

- **Deployer:** runs Terraform, DNS updates, GitHub secret rotation (human).
- **Agent / automation:** can prepare checklists and PRs; **does not** replace production execution approval.

## Pre-cutover checklist

- [ ] Staging hostname validated on OCI (overlay + input + auth flows).
- [ ] TLS active on OCI path matching production expectations.
- [ ] `OCI_COMPUTE_HOST` set to **reserved public IP** or stable LB hostname in GitHub secrets.
- [ ] Database/object storage (if any) migrated and connection strings updated in `/app/.env` or Vault.
- [ ] Rollback owner and **rollback DNS values** recorded in a ticket.

## Cutover window (ordered)

1. Announce maintenance / risk window if applicable.
2. Final **git pull** / deploy to **both** AWS and OCI so code revision matches (optional but reduces drift).
3. **Pause** writes that depend on hostname (OAuth apps, webhooks) or update them in parallel.
4. Apply DNS change per [RUNBOOK_DNS_TLS.md](./RUNBOOK_DNS_TLS.md).
5. Smoke test from external network (not VPN split-horizon only).
6. Enable **OCI alarms** (CPU, instance down, 5xx) and **notifications** for on-call.

## Soak (24–72 hours typical)

- Monitor application logs, nginx access/error logs, and user-reported issues.
- Compare error rates to baseline from AWS week if metrics exist.
- **Secrets:** rotate SMTP/API keys if any were duplicated across both environments during migration.

## Rollback triggers

- Sustained **5xx** or auth failure rate above agreed threshold.
- Data corruption or **split-brain** between old and new backends.
- **DNS** misconfiguration confirmed.

Rollback: revert DNS per DNS runbook; restore traffic to AWS; post-incident review.

## AWS decommission (only after soak + backups)

1. [ ] Confirm **no DNS** points to AWS Elastic IP.
2. [ ] **Snapshot** or backup final EC2 volume; export Terraform state reference for archive.
3. [ ] Remove or disable **GitHub Actions** AWS deploy workflow triggers; remove `EC2_*` secrets when OCI is sole target.
4. [ ] `terraform destroy` in [../main.tf](../main.tf) AWS stack **or** manual terminate instance, release EIP, delete security group (order: instance → EIP → SG).
5. [ ] Close AWS budget alerts / support tickets when account is empty or closed per finance.

## Data replication notes (repo context)

- **SQLite / local files on VM:** copy `/app/data` or documented `STATE_DIR` from AWS to OCI via **rsync/scp** during a short read-only window if you must preserve live state.
- **Postgres (optional):** use logical dump/restore or OCI **Database Migration** tools; update `.env` **DATABASE_URL** on OCI before cutover.
- **Object Storage:** `oci os object sync` or third-party tools from S3 → OCI Object Storage; re-point app config.

## Post-migration

- Update [INVENTORY.md](./INVENTORY.md) to mark AWS as decommissioned and OCI as canonical.
- Tighten security lists / NSGs (remove `0.0.0.0/0` on SSH where possible; use **Bastion** or VPN).
