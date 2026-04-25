#!/bin/bash
set -e
yum update -y
yum install -y python3 python3-pip git

git clone ${github_repo} /app
cd /app
pip3 install -r requirements.txt
chown -R ec2-user:ec2-user /app

cat > /etc/systemd/system/cricket.service << 'EOF'
[Unit]
Description=Cricket Score Overlay
After=network.target

[Service]
WorkingDirectory=/app
EnvironmentFile=/app/.env
ExecStart=/usr/local/bin/gunicorn -w 1 -b 0.0.0.0:5000 server.app:app
Restart=always
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable cricket
systemctl start cricket
