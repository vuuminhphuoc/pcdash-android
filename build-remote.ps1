$ErrorActionPreference = "Stop"

if ($PSVersionTable.PSVersion.Major -lt 7 -and (Get-Command pwsh -ErrorAction SilentlyContinue)) {
    & pwsh -NoProfile -File $PSCommandPath @args
    exit $LASTEXITCODE
}

# Builds PCDash Remote with Gradle (native libraries included) into remote\build\pcdash-remote-signed.apk.
. "$PSScriptRoot\scripts\Gradle.ps1"
Invoke-PcdashGradle ':remote:assembleRelease'
Copy-PcdashApks -Remote
$apk = Join-Path $PSScriptRoot 'remote\build\pcdash-remote-signed.apk'
& pwsh -NoProfile -File "$PSScriptRoot\scripts\verify-apks.ps1" -RemoteApk $apk
if ($LASTEXITCODE -ne 0) { throw "verify-apks.ps1 failed" }
Write-Host "BUILD OK: $apk"
