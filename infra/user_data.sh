#!/bin/bash
set -e
yum update -y
yum install -y python3 python3-pip git nginx

git clone ${github_repo} /app
cd /app
pip3 install --ignore-installed -r requirements.txt
chown -R ec2-user:ec2-user /app

# Minimal .env; stable SECRET_KEY + STATE_DIR via persist-auth-env.sh
umask 077
{
  echo "PORT=5000"
  echo "PUBLIC_BASE_URL=https://cricrelay.co.uk"
  echo "RELAY_AUTO_POLL=1"
} >/app/.env
chown ec2-user:ec2-user /app/.env
if [[ -f /app/deploy/persist-auth-env.sh ]]; then
  bash /app/deploy/persist-auth-env.sh
fi

if [[ -f /app/deploy/nginx-cricrelay.conf ]]; then
  install -m 644 /app/deploy/nginx-cricrelay.conf /etc/nginx/conf.d/cricrelay.conf
fi

if [[ -f /app/deploy/cricket.service ]]; then
  install -m 644 /app/deploy/cricket.service /etc/systemd/system/cricket.service
fi

# Nightly S3 backup (needs AWS CLI + BACKUP_S3_BUCKET in /app/.env to actually run).
yum install -y awscli || pip3 install awscli || true
if [[ -f /app/deploy/cricrelay-backup.service ]]; then
  install -m 644 /app/deploy/cricrelay-backup.service /etc/systemd/system/cricrelay-backup.service
  install -m 644 /app/deploy/cricrelay-backup.timer /etc/systemd/system/cricrelay-backup.timer
fi

systemctl daemon-reload
systemctl enable cricket nginx
systemctl start cricket
systemctl start nginx
# Enable the backup timer; it no-ops safely until BACKUP_S3_BUCKET is set in /app/.env.
systemctl enable --now cricrelay-backup.timer || true
