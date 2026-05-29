#!/usr/bin/env bash
# Full pre-release validation — mirrors GitHub Actions before APK/deploy.
# Run from repo: bash cricrelay-stream/scripts/ci_validate.sh
# Known failure patterns: docs/CI_PITFALLS.md
set -eu
set -o pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "=== Flutter ==="
flutter --version
flutter pub get
flutter analyze lib
flutter test

echo "=== android-custom consistency (catch incomplete Kotlin refactors) ==="
if grep -R --include='*.kt' -n 'onPreviewViewSized' android-custom/ 2>/dev/null; then
  echo "ERROR: onPreviewViewSized was removed — use onPreviewSurfaceReady (see docs/CI_PITFALLS.md §3b)"
  exit 1
fi

echo "=== Android overlay + Kotlin compile (catches CI APK failures early) ==="
rm -rf android
flutter create . --platforms=android --org uk.co.cricrelay --project-name stream
sed -i 's/\r$//' android-custom/apply_android_overlay.sh 2>/dev/null || true
chmod +x android-custom/apply_android_overlay.sh
bash android-custom/apply_android_overlay.sh

cd android
chmod +x gradlew
./gradlew :app:compileReleaseKotlin --no-daemon -q

echo "=== Validation passed ==="
