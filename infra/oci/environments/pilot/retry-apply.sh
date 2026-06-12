#!/usr/bin/env bash
# Re-run `terraform apply` until it succeeds or max attempts is reached.
# Use when OCI returns "Out of host capacity" for Always Free A1 — there is no Oracle waitlist;
# capacity appears as other workloads terminate (timing is unpredictable).
#
# Usage (from this directory):
#   chmod +x retry-apply.sh
#   ./retry-apply.sh
#
# Defaults: 48 attempts, 300s (5 min) between attempts (~4 hours total).

set -uo pipefail
MAX_ATTEMPTS="${MAX_ATTEMPTS:-48}"
SLEEP_SECONDS="${SLEEP_SECONDS:-300}"

for ((i = 1; i <= MAX_ATTEMPTS; i++)); do
  echo "=== Attempt ${i}/${MAX_ATTEMPTS} ($(date -u +%Y-%m-%dT%H:%M:%SZ)) ==="
  if terraform apply -auto-approve; then
    echo "Apply succeeded."
    exit 0
  fi
  echo "Apply failed; sleeping ${SLEEP_SECONDS}s before retry..."
  sleep "${SLEEP_SECONDS}"
done

echo "Giving up after ${MAX_ATTEMPTS} attempts."
exit 1
