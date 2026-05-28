#!/bin/bash
# Grow root partition/filesystem when EBS was expanded in AWS but not on the instance.
# Safe to run every deploy. No-op if already using full disk.
set +e

ROOT_DEV=$(findmnt -n -o SOURCE / | sed 's/p[0-9]*$//')
PART=$(findmnt -n -o SOURCE /)
if [[ -z "$ROOT_DEV" || -z "$PART" ]]; then
  echo "WARN: could not detect root device" >&2
  exit 0
fi

DISK_KB=$(lsblk -b -dn -o SIZE "$ROOT_DEV" 2>/dev/null | awk '{print int($1/1024)}')
PART_KB=$(lsblk -b -dn -o SIZE "$PART" 2>/dev/null | awk '{print int($1/1024)}')
if [[ -n "$DISK_KB" && -n "$PART_KB" && "$DISK_KB" -gt "$PART_KB" ]]; then
  echo "Growing $PART on $ROOT_DEV (disk ${DISK_KB}K vs part ${PART_KB}K)"
  growpart "$ROOT_DEV" "${PART##*p}" 2>/dev/null || growpart "$ROOT_DEV" 1 2>/dev/null || true
  xfs_growfs / 2>/dev/null || resize2fs "$PART" 2>/dev/null || true
fi

df -h /
