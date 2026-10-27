$ErrorActionPreference = "Stop"

if ($PSVersionTable.PSVersion.Major -lt 7 -and (Get-Command pwsh -ErrorAction SilentlyContinue)) {
    & pwsh -NoProfile -File $PSCommandPath @args
    exit $LASTEXITCODE
}

# Builds the host with Gradle into app\build\pcdash-signed.apk. Gradle builds the release remote first
# and puts it into the host's assets (remote-update.apk + remote-update.json) for self-update.
. "$PSScriptRoot\scripts\Gradle.ps1"
Invoke-PcdashGradle ':app:assembleRelease'
Copy-PcdashApks -HostApp
$apk = Join-Path $PSScriptRoot 'app\build\pcdash-signed.apk'
& pwsh -NoProfile -File "$PSScriptRoot\scripts\verify-apks.ps1" -HostApk $apk
if ($LASTEXITCODE -ne 0) { throw "verify-apks.ps1 failed" }
Write-Host "BUILD OK: $apk"
