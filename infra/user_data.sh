#!/bin/bash
set -e
yum update -y
yum install -y python3 python3-pip git nginx

git clone ${github_repo} /app
cd /app
pip3 install --ignore-installed -r requirements.txt
chown -R ec2-user:ec2-user /app

# Required for systemd: missing /app/.env otherwise prevents cricket from starting.
umask 077
{
  echo "PORT=5000"
  echo "SECRET_KEY=$(openssl rand -hex 32)"
  echo "PUBLIC_BASE_URL=https://cricrelay.co.uk"
  echo "RELAY_AUTO_POLL=1"
} >/app/.env
chown ec2-user:ec2-user /app/.env

if [[ -f /app/deploy/nginx-cricrelay.conf ]]; then
  install -m 644 /app/deploy/nginx-cricrelay.conf /etc/nginx/conf.d/cricrelay.conf
fi

if [[ -f /app/deploy/cricket.service ]]; then
  install -m 644 /app/deploy/cricket.service /etc/systemd/system/cricket.service
fi

systemctl daemon-reload
systemctl enable cricket nginx
systemctl start cricket
systemctl start nginx
