#!/usr/bin/env bash
# Run from cricrelay-stream/ after `flutter create`.
set -eu
set -o pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CUSTOM="$(cd "$(dirname "$0")" && pwd)"
ANDROID="$ROOT/android"

cp -r "$CUSTOM/uk" "$ANDROID/app/src/main/kotlin/"
cp "$CUSTOM/AndroidManifest.xml" "$ANDROID/app/src/main/AndroidManifest.xml"
mkdir -p "$ANDROID/app/src/main/res/xml"
cp "$CUSTOM/res/xml/network_security_config.xml" "$ANDROID/app/src/main/res/xml/network_security_config.xml"
cp "$CUSTOM/app_build.gradle" "$ANDROID/app/build.gradle"
cp "$CUSTOM/gradle.properties" "$ANDROID/gradle.properties"
mkdir -p "$ANDROID/gradle/wrapper"
cp "$CUSTOM/gradle-wrapper.properties" "$ANDROID/gradle/wrapper/gradle-wrapper.properties"

# flutter create --project-name stream generates a broken default test (package:stream).
mkdir -p "$ROOT/test"
cp "$CUSTOM/widget_test.dart" "$ROOT/test/widget_test.dart"

# Avoid "Both build.gradle and build.gradle.kts exist" + duplicate Gradle watchers.
rm -f "$ANDROID/build.gradle.kts"

python3 "$CUSTOM/patch_android_gradle.py" "$ANDROID"

cp "$CUSTOM/root_build.gradle" "$ANDROID/build.gradle"

echo "=== Gradle wrapper ==="
grep distributionUrl "$ANDROID/gradle/wrapper/gradle-wrapper.properties"
echo "=== android/settings.gradle (repos + AGP) ==="
grep -n 'repositories\|jitpack\|PREFER_PROJECT\|com.android.application' "$ANDROID/settings.gradle" | head -20
