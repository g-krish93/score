#!/bin/bash
# Reload nginx after config change. systemctl reload can fail (226/NAMESPACE) if
# /run/systemd/unit-root/tmp was removed when the disk was full — use nginx -s reload.
set -euo pipefail

mkdir -p /run/systemd/unit-root/tmp 2>/dev/null || true

if ! nginx -t 2>&1; then
  echo "nginx config test failed" >&2
  exit 1
fi

if nginx -s reload 2>/dev/null; then
  echo "nginx reloaded (signal)"
  exit 0
fi

echo "nginx -s reload failed — restarting service"
systemctl restart nginx
