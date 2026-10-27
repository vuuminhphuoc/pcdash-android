param([version] $Version = [version]'1.27.1')
$ErrorActionPreference = 'Stop'
if ($Version -lt [version]'1.27.1') { throw 'Go 1.27.1 or newer is required' }
$repo = Split-Path $PSScriptRoot -Parent
$dest = Join-Path $repo "tools\go$Version"
if (Test-Path (Join-Path $dest 'bin\go.exe')) { $installed = (& (Join-Path $dest 'bin\go.exe') version | Out-String); . "$PSScriptRoot\BuildToolchain.ps1"; Assert-MinimumGoVersion (ConvertTo-GoVersion $installed) $Version; exit 0 }
$versionName = "go$Version"
$downloads = Invoke-RestMethod 'https://go.dev/dl/?mode=json&include=all'
$release = $downloads | Where-Object { $_.version -eq $versionName } | Select-Object -First 1
if (-not $release) { throw "Official Go release $versionName not listed at go.dev" }
$file = $release.files | Where-Object { $_.os -eq 'windows' -and $_.arch -eq 'amd64' -and $_.kind -eq 'archive' } | Select-Object -First 1
if (-not $file -or -not $file.sha256) { throw 'Official Windows amd64 archive/checksum missing' }
$archive = Join-Path $env:TEMP $file.filename
Invoke-WebRequest "https://go.dev/dl/$($file.filename)" -OutFile $archive
$actual = (Get-FileHash $archive -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actual -ne $file.sha256.ToLowerInvariant()) { Remove-Item $archive -Force; throw 'Go archive SHA256 does not match official go.dev checksum' }
$stage = Join-Path $env:TEMP ([guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $stage | Out-Null
try {
    tar -xzf $archive -C $stage
    New-Item -ItemType Directory -Path (Split-Path $dest -Parent) -Force | Out-Null
    if (Test-Path $dest) { throw "Destination exists without a Go installation: $dest" }
    Move-Item (Join-Path $stage 'go') $dest
} finally { Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue; Remove-Item $archive -Force -ErrorAction SilentlyContinue }
. "$PSScriptRoot\BuildToolchain.ps1"
Assert-MinimumGoVersion (ConvertTo-GoVersion (& (Join-Path $dest 'bin\go.exe') version | Out-String)) $Version
Write-Host "Installed $versionName under $dest"
