#!/bin/bash
# Clear stale spike browser processes before systemd start (orphans after OOM/segfault).
# Run as root via ExecStartPre on cricheroes-browser.service.
set -euo pipefail

DISPLAY_NUM="${CRICHEROES_DISPLAY_NUM:-99}"
CDP_PORT="${CRICHEROES_CDP_PORT:-9222}"
VNC_PORT="${CRICHEROES_VNC_PORT:-5900}"

kill_tcp_port() {
  local port="$1"
  if command -v fuser >/dev/null 2>&1; then
    fuser -k "${port}/tcp" 2>/dev/null || true
    return
  fi
  if command -v ss >/dev/null 2>&1; then
    local pids
    pids=$(ss -tlnp "sport = :${port}" 2>/dev/null | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -u || true)
    for pid in $pids; do
      kill -9 "$pid" 2>/dev/null || true
    done
  fi
}

echo "[cricheroes-browser-preflight] clearing stale session on :${DISPLAY_NUM} ports ${CDP_PORT}/${VNC_PORT}"
kill_tcp_port "$CDP_PORT"
kill_tcp_port "$VNC_PORT"

pkill -f "Xvfb :${DISPLAY_NUM} " 2>/dev/null || true
pkill -f "Xvnc :${DISPLAY_NUM} " 2>/dev/null || true
pkill -f "x11vnc.*-display :${DISPLAY_NUM}" 2>/dev/null || true
pkill -f "cricheroes-browser-profile" 2>/dev/null || true

rm -f "/tmp/.X${DISPLAY_NUM}-lock" 2>/dev/null || true
rm -f "/tmp/.X11-unix/X${DISPLAY_NUM}" 2>/dev/null || true

sleep 1
