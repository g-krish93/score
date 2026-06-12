# AWS security hardening — CricRelay (Tier 1)

Manual steps for the current EC2 deployment.

## SSH access (deferred lockdown)

**Status (reverted 2026):** Restricting SSH (port 22) to a single admin `/32` was **deferred** until a dedicated IAM user and **SSM Session Manager** deploy path are in place. Live security group and `infra/main.tf` again allow SSH from `0.0.0.0/0` so GitHub Actions and ad-hoc admin SSH keep working. All other Tier 1 hardening (CORS, rate limits, nginx headers, OAuth encryption, mobile Keychain) remains in place.

When you are ready to close SSH, prefer SSM (below) over IP allowlists.

## Future: SSM Session Manager (recommended)

Use this path after creating a new IAM user (or role) for operations and CI, instead of relying on inbound SSH.

1. **Instance prerequisites**
   - Attach an IAM instance profile with `AmazonSSMManagedInstanceCore` (and any S3/Secrets permissions you need for deploy).
   - Confirm the SSM agent is running on Amazon Linux 2023 (default).
   - Verify in **Systems Manager → Fleet Manager** that the instance is **Online**.

2. **Operator access**
   - Grant humans `ssm:StartSession` on the instance (via IAM user/role + `ssm:StartSession` on `arn:aws:ec2:region:account:instance/i-...`).
   - Connect: `aws ssm start-session --target i-xxxxxxxx --region eu-west-2` (no SSH key or port 22).

3. **GitHub Actions**
   - Replace SSH/rsync deploy with `aws ssm send-command` or a wrapper that runs your deploy script on the instance, using OIDC or access keys on the new IAM principal (least privilege).
   - After CI deploy works without SSH, revoke port 22 from the security group (see optional lockdown below).

4. **Close port 22**
   - Revoke `0.0.0.0/0` on TCP 22 only; keep 80/443 unchanged.

## Optional future step: Restrict SSH to admin IP

Use only if you must keep SSH temporarily (not recommended long term). **Do not run AWS CLI commands until you have confirmed the admin IP and security group ID.**

### Find resources

```bash
# Instance and security group (replace region if needed)
aws ec2 describe-instances \
  --region eu-west-2 \
  --filters "Name=tag:Name,Values=cricket-overlay" \
  --query "Reservations[].Instances[].{Id:InstanceId,SG:SecurityGroups[0].GroupId,IP:PublicIpAddress}"

# Or list SGs by name
aws ec2 describe-security-groups \
  --region eu-west-2 \
  --filters "Name=group-name,Values=cricket-overlay-sg" \
  --query "SecurityGroups[].GroupId"
```

Set `ADMIN_CIDR` to your office/home public IP with `/32` (e.g. `203.0.113.10/32`). Find your IP: `curl -s https://ifconfig.me`.

### AWS CLI — restrict SSH (console-safe, idempotent-ish)

```bash
export AWS_REGION=eu-west-2
export SG_ID=sg-xxxxxxxx          # from describe above
export ADMIN_CIDR=203.0.113.10/32 # your IP only

# Remove world-open SSH rule
aws ec2 revoke-security-group-ingress \
  --region "$AWS_REGION" \
  --group-id "$SG_ID" \
  --protocol tcp \
  --port 22 \
  --cidr 0.0.0.0/0

# Allow SSH only from admin IP
aws ec2 authorize-security-group-ingress \
  --region "$AWS_REGION" \
  --group-id "$SG_ID" \
  --protocol tcp \
  --port 22 \
  --cidr "$ADMIN_CIDR"
```

**GitHub Actions deploy:** If CI still deploys over SSH (`EC2_HOST` / `EC2_KEY`), you must add runner egress IP(s) as `/32` rules, use a self-hosted runner with a fixed IP, or migrate to SSM first.

**Terraform (optional — do not apply without review):** You can reintroduce a variable such as `admin_ssh_cidr` and set the SSH ingress `cidr_blocks` to that value. **Never commit real IPs to the repo.**

## 2. Production env vars (server `/app/.env`)

After deploy, confirm on the instance:

| Variable | Purpose |
|----------|---------|
| `PUBLIC_BASE_URL=https://cricrelay.co.uk` | Canonical URL + secure session cookies |
| `YOUTUBE_TOKEN_ENCRYPTION_KEY` | Dedicated Fernet key for OAuth tokens (not `SECRET_KEY`) |
| `CORS_ORIGINS` | Optional override; defaults include cricrelay.co.uk + localhost dev |
| `SECRET_KEY` | Strong random value for Flask sessions |

Generate encryption key:

```bash
python3 -c "import secrets; print(secrets.token_urlsafe(32))"
```

## 3. nginx security headers

Copy updated `deploy/nginx-cricrelay.conf` to the server, test, reload:

```bash
sudo nginx -t && sudo systemctl reload nginx
```

## 4. Post-deploy checks

```bash
curl -sI https://cricrelay.co.uk/ | grep -iE 'strict-transport|content-security|x-frame|x-content-type|referrer-policy'
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://cricrelay.co.uk/api/auth/login \
  -H 'Content-Type: application/json' -d '{"email":"x","password":"y"}'
# Repeat 6+ times from same IP — expect 429 on auth endpoints after ~5 attempts in 15 minutes
```

## 5. OAuth token migration

Existing tokens encrypted with `SECRET_KEY` still decrypt (legacy fallback). New tokens require `YOUTUBE_TOKEN_ENCRYPTION_KEY`. Set the dedicated key in production before the next YouTube/Twitch connect; existing connections keep working until reconnect.

## Related

- Tier 1 code changes: CORS, rate limits, session cookies, Keychain (mobile), nginx headers
- Tier 2 (not in scope): AWS Secrets Manager, WAF, SSM-only access, RDS credential rotation