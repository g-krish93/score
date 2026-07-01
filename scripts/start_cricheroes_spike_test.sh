#!/bin/bash
# Start 2-hour CricHeroes browser spike endurance test (run on EC2 as ec2-user).
# Usage: URL='https://cricheroes.com/scorecard/.../live' bash start_cricheroes_spike_test.sh
set -euo pipefail

URL="${URL:?Set URL to a currently-live CricHeroes scorecard}"
LOG="$HOME/cricheroes-spike-test.log"
PIDFILE="$HOME/cricheroes-spike-test.pid"

pkill -f "test_cricheroes_browser_session.py" 2>/dev/null || true
sleep 1

nohup python3 /app/scripts/test_cricheroes_browser_session.py \
  --url "$URL" \
  --restart-after-min 30 \
  --duration-sec 7200 \
  --interval-sec 300 \
  >> "$LOG" 2>&1 &

echo $! > "$PIDFILE"
echo "Started endurance test pid=$(cat "$PIDFILE")"
echo "Log: $LOG"
echo "Tail: tail -f $LOG"
