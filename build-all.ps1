$ErrorActionPreference = "Stop"

if ($PSVersionTable.PSVersion.Major -lt 7 -and (Get-Command pwsh -ErrorAction SilentlyContinue)) {
    & pwsh -NoProfile -File $PSCommandPath @args
    exit $LASTEXITCODE
}

. "$PSScriptRoot\scripts\BuildToolchain.ps1"
Use-PcdashJdk17

$toolsGo = Join-Path $PSScriptRoot 'tools\go1.27.1\bin'
if (Test-Path $toolsGo) {
    $env:PATH = "$toolsGo;$env:PATH"
}

Write-Host "========================================"
Write-Host "1. Building PCDash Remote APK..."
Write-Host "========================================"
& pwsh -NoProfile -File "$PSScriptRoot\build-remote.ps1"
if ($LASTEXITCODE -ne 0) { throw "build-remote.ps1 failed" }

$remoteApk = "$PSScriptRoot\remote\build\pcdash-remote-signed.apk"
if (-not (Test-Path $remoteApk)) {
    throw "Remote APK not found at $remoteApk"
}

Write-Host ""
Write-Host "========================================"
Write-Host "2. Packaging Remote APK into Host assets..."
Write-Host "========================================"

$updateDir = "$PSScriptRoot\app\assets"
New-Item -ItemType Directory -Path $updateDir -Force | Out-Null

$targetApk = "$updateDir\remote-update.apk"
Copy-Item $remoteApk $targetApk -Force

# Read version from remote/version.properties
$remoteVersionProps = Join-Path $PSScriptRoot 'remote\version.properties'
$remoteVersionCode = 1
$remoteVersionName = "1.0"
if (Test-Path $remoteVersionProps) {
    Get-Content $remoteVersionProps | ForEach-Object {
        if ($_ -match '^\s*versionCode\s*=\s*(\d+)') {
            $remoteVersionCode = [int]$matches[1]
        } elseif ($_ -match '^\s*versionName\s*=\s*(.+)$') {
            $remoteVersionName = $matches[1].Trim()
        }
    }
}

$apkItem = Get-Item $targetApk
$apkSize = $apkItem.Length
$sha256 = (Get-FileHash -Path $targetApk -Algorithm SHA256).Hash.ToLowerInvariant()

$updateObj = [ordered]@{
    versionCode = $remoteVersionCode
    versionName = $remoteVersionName
    sha256 = $sha256
    size = $apkSize
}
$updateJson = $updateObj | ConvertTo-Json -Compress
[IO.File]::WriteAllText("$updateDir\remote-update.json", $updateJson, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "Packaged Remote APK into app/assets (remote-update.apk + remote-update.json):"
Write-Host "  - Version: $remoteVersionName (code $remoteVersionCode)"
Write-Host "  - Size: $apkSize bytes"
Write-Host "  - SHA-256: $sha256"
Write-Host "  - Manifest: $updateJson"

Write-Host ""
Write-Host "========================================"
Write-Host "3. Building PCDash Host APK..."
Write-Host "========================================"
& pwsh -NoProfile -File "$PSScriptRoot\build.ps1"
if ($LASTEXITCODE -ne 0) { throw "build.ps1 failed" }

Write-Host ""
Write-Host "========================================"
Write-Host "BUILD-ALL SUCCESSFUL"
Write-Host "========================================"
Get-Item "$PSScriptRoot\remote\build\pcdash-remote-signed.apk", "$PSScriptRoot\app\build\pcdash-signed.apk" |
    Select-Object FullName, @{n='SizeKB';e={[math]::Round($_.Length/1KB,1)}}
