#!/bin/bash
# Emergency disk cleanup — safe to run before git pull on a full EC2 root volume.
# Fetched from GitHub raw in CI so it works even when /app cannot pull yet.
# Manual: curl -sfSL https://raw.githubusercontent.com/g-krish93/score/main/deploy/ec2-emergency-cleanup.sh | sudo bash
set +e

echo "=== Emergency disk cleanup ==="
df -h / /var 2>/dev/null || df -h /

pip3 cache purge 2>/dev/null
rm -rf /root/.cache/pip /home/ec2-user/.cache/pip 2>/dev/null

if command -v dnf >/dev/null 2>&1; then
  dnf clean all -y 2>/dev/null
fi
if command -v yum >/dev/null 2>&1; then
  yum clean all -y 2>/dev/null
fi

journalctl --vacuum-size=100M 2>/dev/null
journalctl --vacuum-time=1d 2>/dev/null

rm -rf /tmp/pip-* /tmp/pip-unpack-* 2>/dev/null
find /tmp -mindepth 1 -maxdepth 1 -mmin +60 -exec rm -rf {} + 2>/dev/null

find /var/log -type f \( -name '*.gz' -o -name '*.1' -o -name '*.old' \) -delete 2>/dev/null
rm -rf /var/cache/dnf /var/cache/yum 2>/dev/null

find /app -type d -name '__pycache__' -exec rm -rf {} + 2>/dev/null

# Git maintenance must run as ec2-user — sudo git gc leaves root-owned .git/logs and breaks deploy.
if [[ -d /app/.git ]]; then
  chown -R ec2-user:ec2-user /app 2>/dev/null
  runuser -u ec2-user -- git -C /app reflog expire --expire=now --all 2>/dev/null
  runuser -u ec2-user -- git -C /app gc --prune=now 2>/dev/null
fi

echo "=== Disk after emergency cleanup ==="
df -h / /var 2>/dev/null || df -h /

avail_kb=$(df / 2>/dev/null | awk 'NR==2 {print $4}')
if [[ "${avail_kb:-0}" -lt 65536 ]] 2>/dev/null; then
  echo "CRITICAL: less than 64 MB free — expand EBS to 16 GB+ in AWS Console." >&2
  exit 1
fi
if [[ "${avail_kb:-0}" -lt 262144 ]] 2>/dev/null; then
  echo "WARN: less than 256 MB free — expand EBS volume (this instance shows ~2 GB root; use 16 GB+)." >&2
fi

exit 0
