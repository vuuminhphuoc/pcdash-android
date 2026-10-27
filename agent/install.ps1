# agent/install.ps1
# PcAgent installer - run with Administrator privileges
[CmdletBinding()]
param([switch]$NoPause)

$ErrorActionPreference = "Stop"

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "Elevating permissions to Administrator..."
    Start-Process -FilePath "powershell.exe" -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`"" -Verb RunAs
    exit
}

$srcDir = $PSScriptRoot
$srcExe = Join-Path $srcDir "PcAgent.exe"
if (-not (Test-Path $srcExe)) {
    $buildExe = Join-Path $srcDir "build\PcAgent.exe"
    if (Test-Path $buildExe) {
        $srcExe = $buildExe
    } else {
        throw "PcAgent.exe not found in $srcDir or $srcDir\build"
    }
}

$dstDir = Join-Path $env:LOCALAPPDATA "PcAgent"
if (-not (Test-Path $dstDir)) {
    New-Item -ItemType Directory -Path $dstDir -Force | Out-Null
}

# Stop old instance first so file is not locked
Get-Process -Name "PcAgent" -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 1

# Copy PcAgent.exe to %LOCALAPPDATA%\PcAgent
$dstExe = Join-Path $dstDir "PcAgent.exe"
Copy-Item -Path $srcExe -Destination $dstExe -Force

# Agent token: the agent refuses every request without it. Kept on reinstall so the phone
# does not need to be reconfigured. Readable only by the current user.
$tokenFile = Join-Path $dstDir "agent.token"
$token = $null
if (Test-Path $tokenFile) { $token = (Get-Content $tokenFile -TotalCount 1).Trim() }
if (-not $token -or $token.Length -lt 16) {
    $bytes = New-Object byte[] 16
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($bytes)
    $rng.Dispose()
    $token = -join ($bytes | ForEach-Object { $_.ToString("x2") })
    Set-Content -Path $tokenFile -Value $token -Encoding ascii
}
icacls $tokenFile /inheritance:r /grant:r "${env:USERNAME}:(R,W)" >$null 2>&1

# Firewall rule: Inbound, TCP 8799, Private+Public, RemoteAddress=LocalSubnet
netsh advfirewall firewall delete rule name="PcAgent 8799" >$null 2>&1
netsh advfirewall firewall add rule name="PcAgent 8799" dir=in action=allow protocol=TCP localport=8799 profile=private,public remoteip=localsubnet >$null 2>&1

# Delete old urlacl if any (ignore errors)
netsh http delete urlacl url="http://+:8799/" >$null 2>&1

# Startup shortcut
$wsh = New-Object -ComObject WScript.Shell
$startupDir = [Environment]::GetFolderPath([Environment+SpecialFolder]::Startup)
$shortcutPath = Join-Path $startupDir "PcAgent.lnk"
$shortcut = $wsh.CreateShortcut($shortcutPath)
$shortcut.TargetPath = $dstExe
$shortcut.WorkingDirectory = $dstDir
$shortcut.Save()

# Start Menu shortcut that re-opens the setup QR code (PcAgent.exe --setup)
$programsDir = [Environment]::GetFolderPath([Environment+SpecialFolder]::Programs)
$qrShortcut = $wsh.CreateShortcut((Join-Path $programsDir "PCDash - Ma QR cai dat.lnk"))
$qrShortcut.TargetPath = $dstExe
$qrShortcut.Arguments = "--setup"
$qrShortcut.WorkingDirectory = $dstDir
$qrShortcut.Save()

# Start agent with the normal user token (explorer.exe drops the elevated token of this script)
Start-Process -FilePath "explorer.exe" -ArgumentList "`"$dstExe`""

Write-Host "PcAgent successfully installed and started: $dstExe"
Write-Host "Endpoint: http://127.0.0.1:8799/healthz"
Write-Host ""
Write-Host "Ma truy cap agent / Agent token (nhap vao PCDash host > Cai dat PC):"
Write-Host "  $token" -ForegroundColor Yellow
try { Set-Clipboard -Value $token; Write-Host "(da sao chep vao clipboard / copied to clipboard)" } catch {}
Write-Host "Luu o / stored in: $tokenFile"
if (-not $NoPause) {
    # Setup QR: the host phone scans it once instead of typing IP, MAC and token.
    Start-Process -FilePath $dstExe -ArgumentList "--setup"
    Write-Host "Da mo cua so ma QR. Tren dien thoai host: PCDash > Cai dat PC > Quet ma QR tu PC."
    Write-Host "Mo lai sau nay: Start menu > 'PCDash - Ma QR cai dat'."
}
if (-not $NoPause) { Read-Host "Nhan Enter de dong / press Enter to close" | Out-Null }
