#!/usr/bin/env bash
# Run from cricrelay-stream/ after `flutter create --platforms=ios`.
set -eu
set -o pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 "$ROOT/ios-custom/patch_ios.py" "$ROOT"
echo "=== iOS native streaming overlay applied ==="
