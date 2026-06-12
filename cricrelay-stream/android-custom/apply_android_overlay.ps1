# Sync android-custom/ into android/ before local builds (Windows).
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not (Test-Path "$Root\android\app\src\main\kotlin")) {
    $Root = Split-Path -Parent $PSScriptRoot
}
$Custom = $PSScriptRoot
$Android = Join-Path $Root "android"

Copy-Item -Recurse -Force (Join-Path $Custom "uk") (Join-Path $Android "app\src\main\kotlin\")
Copy-Item -Force (Join-Path $Custom "AndroidManifest.xml") (Join-Path $Android "app\src\main\AndroidManifest.xml")
New-Item -ItemType Directory -Force -Path (Join-Path $Android "app\src\main\res\xml") | Out-Null
Copy-Item -Force (Join-Path $Custom "res\xml\network_security_config.xml") (Join-Path $Android "app\src\main\res\xml\network_security_config.xml")
New-Item -ItemType Directory -Force -Path (Join-Path $Android "app\src\main\res\values") | Out-Null
Copy-Item -Force (Join-Path $Custom "res\values\styles.xml") (Join-Path $Android "app\src\main\res\values\styles.xml")
Copy-Item -Force (Join-Path $Custom "app_build.gradle") (Join-Path $Android "app\build.gradle")
Copy-Item -Force (Join-Path $Custom "gradle.properties") (Join-Path $Android "gradle.properties")
New-Item -ItemType Directory -Force -Path (Join-Path $Android "gradle\wrapper") | Out-Null
Copy-Item -Force (Join-Path $Custom "gradle-wrapper.properties") (Join-Path $Android "gradle\wrapper\gradle-wrapper.properties")
New-Item -ItemType Directory -Force -Path (Join-Path $Root "test") | Out-Null
Copy-Item -Force (Join-Path $Custom "widget_test.dart") (Join-Path $Root "test\widget_test.dart")
if (Test-Path (Join-Path $Android "build.gradle.kts")) { Remove-Item -Force (Join-Path $Android "build.gradle.kts") }
Copy-Item -Force (Join-Path $Custom "root_build.gradle") (Join-Path $Android "build.gradle")
Write-Host "android-custom overlay applied to $Android"
