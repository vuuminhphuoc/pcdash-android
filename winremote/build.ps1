param(
    [string]$Version = "dev",
    [string]$OutDir
)

# Builds PCDashRemote.exe, the Windows remote: a GUI program (no console window) with a notification-area
# icon. Output: winremote\build\PCDashRemote.exe (or -OutDir).
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path "$PSScriptRoot\..").Path
if (-not $OutDir) { $OutDir = Join-Path $PSScriptRoot "build" }

# The repository's own Go 1.27.1 when it is installed (scripts\install-go-toolchain.ps1), else the one on PATH.
$go = Join-Path $repoRoot "tools\go1.27.1\bin\go.exe"
if (-not (Test-Path $go)) { $go = (Get-Command go -ErrorAction Stop).Source }

New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
$exe = Join-Path $OutDir "PCDashRemote.exe"
Push-Location $PSScriptRoot
try {
    $env:GOOS = "windows"; $env:GOARCH = "amd64"; $env:CGO_ENABLED = "0"
    & $go build -trimpath -ldflags "-H windowsgui -s -w -X main.version=$Version" -o $exe .
    if ($LASTEXITCODE -ne 0) { throw "go build failed" }
} finally {
    Pop-Location
}
Write-Host ("Built {0} ({1:N1} MB)" -f $exe, ((Get-Item $exe).Length / 1MB))
