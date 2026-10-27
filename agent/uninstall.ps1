# agent/uninstall.ps1
# PcAgent uninstaller - run with Administrator privileges
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "Elevating permissions to Administrator..."
    Start-Process -FilePath "powershell.exe" -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`"" -Verb RunAs
    exit
}

# Stop running PcAgent
Get-Process -Name "PcAgent" -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 1

# Remove firewall rule
netsh advfirewall firewall delete rule name="PcAgent 8799" >$null 2>&1

# Delete old urlacl if any
netsh http delete urlacl url="http://+:8799/" >$null 2>&1

# Remove startup shortcut
$startupDir = [Environment]::GetFolderPath([Environment+SpecialFolder]::Startup)
$shortcutPath = Join-Path $startupDir "PcAgent.lnk"
if (Test-Path $shortcutPath) {
    Remove-Item $shortcutPath -Force -ErrorAction SilentlyContinue
}

# Remove %LOCALAPPDATA%\PcAgent
$dstDir = Join-Path $env:LOCALAPPDATA "PcAgent"
if (Test-Path $dstDir) {
    Remove-Item $dstDir -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host "PcAgent successfully uninstalled."
