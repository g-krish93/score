#!/usr/bin/env bash
# Run from cricrelay-stream/ after `flutter create`.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CUSTOM="$(cd "$(dirname "$0")" && pwd)"
ANDROID="$ROOT/android"

cp -r "$CUSTOM/uk" "$ANDROID/app/src/main/kotlin/"
cp "$CUSTOM/AndroidManifest.xml" "$ANDROID/app/src/main/AndroidManifest.xml"
cp "$CUSTOM/app_build.gradle" "$ANDROID/app/build.gradle"
cp "$CUSTOM/build.gradle" "$ANDROID/build.gradle"

add_jitpack() {
  local file="$1"
  [ -f "$file" ] || return 0
  grep -q 'jitpack.io' "$file" && return 0
  if [[ "$file" == *.kts ]]; then
    sed -i '/mavenCentral()/a\        maven { url = uri("https://jitpack.io") }' "$file"
    sed -i '/google()/a\        maven { url = uri("https://jitpack.io") }' "$file"
  else
    sed -i '/mavenCentral()/a\        maven { url "https://jitpack.io" }' "$file"
    sed -i '/google()/a\        maven { url "https://jitpack.io" }' "$file"
  fi
}

add_jitpack "$ANDROID/settings.gradle"
add_jitpack "$ANDROID/settings.gradle.kts"

echo "=== settings.gradle repositories ==="
grep -A6 'repositories' "$ANDROID/settings.gradle" 2>/dev/null | head -20 || true
