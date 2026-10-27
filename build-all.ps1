$ErrorActionPreference = "Stop"

if ($PSVersionTable.PSVersion.Major -lt 7 -and (Get-Command pwsh -ErrorAction SilentlyContinue)) {
    & pwsh -NoProfile -File $PSCommandPath @args
    exit $LASTEXITCODE
}

# Both apps in one Gradle run: the native libraries (Go + NDK), PCDash Remote, then the host with the
# remote APK in its assets. Outputs: remote\build\pcdash-remote-signed.apk and app\build\pcdash-signed.apk.
. "$PSScriptRoot\scripts\Gradle.ps1"
Invoke-PcdashGradle ':remote:assembleRelease' ':app:assembleRelease'
Copy-PcdashApks -Remote -HostApp

$remoteApk = Join-Path $PSScriptRoot 'remote\build\pcdash-remote-signed.apk'
$hostApk = Join-Path $PSScriptRoot 'app\build\pcdash-signed.apk'
& pwsh -NoProfile -File "$PSScriptRoot\scripts\verify-apks.ps1" -RemoteApk $remoteApk -HostApk $hostApk
if ($LASTEXITCODE -ne 0) { throw "verify-apks.ps1 failed" }

Write-Host ""
Write-Host "BUILD-ALL SUCCESSFUL"
Get-Item $remoteApk, $hostApk | Select-Object FullName, @{n='SizeKB';e={[math]::Round($_.Length/1KB,1)}}
