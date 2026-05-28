#!/usr/bin/env bash
# Run from cricrelay-stream/ after `flutter create`.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CUSTOM="$(cd "$(dirname "$0")" && pwd)"
ANDROID="$ROOT/android"

cp -r "$CUSTOM/uk" "$ANDROID/app/src/main/kotlin/"
cp "$CUSTOM/AndroidManifest.xml" "$ANDROID/app/src/main/AndroidManifest.xml"
cp "$CUSTOM/app_build.gradle" "$ANDROID/app/build.gradle"
cp "$CUSTOM/gradle.properties" "$ANDROID/gradle.properties"

# Avoid "Both build.gradle and build.gradle.kts exist" + duplicate Gradle watchers.
rm -f "$ANDROID/build.gradle.kts"

SETTINGS="$ANDROID/settings.gradle"
if [ -f "$SETTINGS" ]; then
  # Allow allprojects / subproject repos (JitPack in app/build.gradle is not enough alone).
  sed -i 's/RepositoriesMode.FAIL_ON_PROJECT_REPOS/RepositoriesMode.PREFER_PROJECT/g' "$SETTINGS" || true
  if ! grep -q 'jitpack.io' "$SETTINGS"; then
    sed -i '/mavenCentral()/a\        maven { url "https://jitpack.io" }' "$SETTINGS"
    sed -i '/google()/a\        maven { url "https://jitpack.io" }' "$SETTINGS"
  fi
fi

# Root build.gradle: JitPack for app dependencies (no evaluationDependsOn).
cat > "$ANDROID/build.gradle" <<'EOF'
allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url "https://jitpack.io" }
    }
}

rootProject.buildDir = "../build"
subprojects {
    project.buildDir = "${rootProject.buildDir}/${project.name}"
}
EOF

echo "=== android/settings.gradle (repos) ==="
grep -n 'repositories\|jitpack\|PREFER_PROJECT' "$SETTINGS" 2>/dev/null | head -25 || true
