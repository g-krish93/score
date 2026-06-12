# DNS, TLS, and edge — migration runbook (CricRelay / cricket overlay)

Use this during **pilot** (staging hostname) and **production** cutover. Execute DNS changes only with registrar/DNS access.

## Preconditions

- OCI pilot stack applied; note `terraform output reserved_public_ip` from [../oci/environments/pilot](../oci/environments/pilot).
- Health check passes: `curl -sS http://RESERVED_IP:5000/health` (and HTTPS if nginx + certs are configured).
- **TTL lowered** on affected records at least one previous TTL cycle before cutover (e.g. 300s).

## Staging (pilot)

1. Create a hostname (e.g. `overlay-staging.yourdomain`) pointing **A** record to the **reserved** public IP from OCI.
2. Set `public_base_url` in `terraform.tfvars` to `https://overlay-staging.yourdomain` and re-run cloud-init **or** edit `/app/.env` on the instance and restart `cricket` if the app validates host/callbacks.
3. Issue TLS:
   - **Option A — On-VM Let’s Encrypt:** install certbot (nginx plugin), obtain cert for staging hostname, reload nginx (same pattern as production).
   - **Option B — OCI Certificates / LB:** front the VM with a **Network Load Balancer** or **Application Load Balancer**, attach certificate, update DNS to LB hostname instead of A-to-IP.

## Production cutover (`cricrelay.co.uk` or equivalent)

1. Freeze GitHub deploy workflows or pause merges during the window if desired.
2. **Backup** current AWS EIP/DNS values and AWS instance snapshot (console).
3. Point production **A/AAAA** (or CNAME to LB) to **OCI reserved public IP** (or LB).
4. Wait for DNS propagation; verify from multiple resolvers (`dig +short`).
5. Verify: HTTPS, `/health`, `/input`, overlay route, and SMTP if used.
6. Keep AWS stack **running** in rollback posture until the soak period ends (see [RUNBOOK_CUTOVER_DECOMMISSION.md](./RUNBOOK_CUTOVER_DECOMMISSION.md)).

## WAF / CDN

If AWS used **CloudFront + WAF**, replicate on OCI (**API Gateway + WAF**, **OCI CDN**, or third-party CDN) and update origin to the new endpoint. This is not represented in-repo; track as a separate work item.

## Rollback (DNS)

1. Revert DNS **A** record to the previous AWS Elastic IP (or prior target).
2. Lower TTL again if raising it post-migration.
3. Confirm traffic returns to AWS and app health restores.

## GitHub Actions

- AWS workflow: [../../.github/workflows/deploy.yml](../../.github/workflows/deploy.yml) uses `EC2_HOST` / `EC2_KEY` / `ec2-user`.
- OCI workflow: [../../.github/workflows/deploy-oci.yml](../../.github/workflows/deploy-oci.yml) uses `OCI_COMPUTE_HOST` / `OCI_SSH_KEY` / `opc`.
- After cutover, either **disable** the AWS workflow branch triggers or remove AWS secrets to avoid mistaken deploys.
