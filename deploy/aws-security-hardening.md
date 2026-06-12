# AWS security hardening — CricRelay (Tier 1)

Manual steps for the current EC2 deployment. **Do not run AWS CLI commands until you have confirmed the admin IP and security group ID.**

## 1. Restrict SSH (port 22) to admin IP

`infra/main.tf` now requires `admin_ssh_cidr` in `terraform.tfvars` (see `infra/terraform.tfvars.example`). If the live SG still allows `0.0.0.0/0` on port 22, lock it down below.

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

# Remove world-open SSH rule (same as current Terraform ingress)
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

**GitHub Actions deploy:** If CI deploys over SSH (`EC2_HOST` / `EC2_KEY`), either:

- Add the GitHub Actions runner egress IP(s) as additional `/32` rules, or  
- Use a self-hosted runner with a fixed IP, or  
- Deploy via SSM Session Manager (no inbound SSH) — Tier 2.

### Terraform (optional — do not apply without review)

Add to `infra/variables.tf`:

```hcl
variable "admin_ssh_cidr" {
  type        = string
  description = "CIDR allowed for SSH (e.g. 203.0.113.10/32). Required in production."
}
```

Replace the SSH ingress block in `infra/main.tf`:

```hcl
  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.admin_ssh_cidr]
  }
```

Apply only after setting `admin_ssh_cidr` in tfvars. **Never commit real IPs to the repo.**

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
