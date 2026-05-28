#!/bin/bash
# Free disk on small EC2 instances before pip / git operations.
# Run on the server: sudo bash /app/deploy/ec2-disk-cleanup.sh
set -euo pipefail

echo "=== Disk before cleanup ==="
df -h / /var 2>/dev/null || df -h /

# Pip download caches (deploy uses sudo pip3).
pip3 cache purge 2>/dev/null || true
rm -rf /root/.cache/pip /home/ec2-user/.cache/pip 2>/dev/null || true

# OS package manager caches.
if command -v dnf >/dev/null 2>&1; then
  dnf clean all 2>/dev/null || true
elif command -v yum >/dev/null 2>&1; then
  yum clean all 2>/dev/null || true
fi

# Systemd journal — keep last 3 days only.
journalctl --vacuum-time=3d 2>/dev/null || true

# Stale temp files (ignore errors).
find /tmp -mindepth 1 -maxdepth 1 -mtime +1 -exec rm -rf {} + 2>/dev/null || true

# Compact git objects in /app (APK commits bloat .git over time).
if [[ -d /app/.git ]]; then
  chown -R ec2-user:ec2-user /app 2>/dev/null || true
  runuser -u ec2-user -- git -C /app gc --prune=now 2>/dev/null || true
fi

echo "=== Disk after cleanup ==="
df -h / /var 2>/dev/null || df -h /

# Warn if root filesystem is still critically low.
avail_kb=$(df / | awk 'NR==2 {print $4}')
if [[ "${avail_kb:-0}" -lt 524288 ]]; then
  echo "WARN: less than 512 MB free on /. Consider expanding the EBS volume in AWS Console." >&2
fi
