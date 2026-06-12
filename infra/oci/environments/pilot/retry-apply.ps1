# Re-run terraform apply until success or max attempts.
# Run from this directory: .\retry-apply.ps1
# Optional: $env:MAX_ATTEMPTS = "48"; $env:SLEEP_SECONDS = "300"

$max = 48
if ($env:MAX_ATTEMPTS) { $max = [int]$env:MAX_ATTEMPTS }
$sleepSec = 300
if ($env:SLEEP_SECONDS) { $sleepSec = [int]$env:SLEEP_SECONDS }

for ($i = 1; $i -le $max; $i++) {
    Write-Host "=== Attempt $i / $max ($(Get-Date -Format o)) ==="
    terraform apply -auto-approve
    if ($LASTEXITCODE -eq 0) {
        Write-Host "Apply succeeded."
        exit 0
    }
    Write-Host "Apply failed; sleeping ${sleepSec}s..."
    Start-Sleep -Seconds $sleepSec
}

Write-Host "Giving up after $max attempts."
exit 1
