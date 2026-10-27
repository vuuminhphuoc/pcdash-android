[CmdletBinding()]
param(
    [string]$SourceDir = "$PSScriptRoot\build",
    [string]$TaskName = "PcAgentCpuTempHelper",
    [switch]$Uninstall
)

# Chay mot lan voi quyen Administrator. Helper chay quyen cao nhat nen exe va dll duoc cai vao
# Program Files (chi admin ghi duoc) va tep ket qua nam trong ProgramData voi ACL chat,
# de tien trinh quyen thuong khong the thay the file hoac tro duong ghi di noi khac.

$ErrorActionPreference = "Stop"

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Error "Script nay can quyen Administrator. Mo PowerShell bang 'Run as Administrator' roi chay lai."
    exit 1
}

$installDir = Join-Path $env:ProgramFiles "PcAgentCpuTemp"
$dataDir = Join-Path $env:ProgramData "PcAgentCpuTemp"
$exeInstalled = Join-Path $installDir "CpuTempHelper.exe"

function Set-AdminOnlyWrite([string]$dir, [bool]$usersRead) {
    $acl = New-Object System.Security.AccessControl.DirectorySecurity
    $acl.SetAccessRuleProtection($true, $false)
    $inherit = [System.Security.AccessControl.InheritanceFlags]"ContainerInherit,ObjectInherit"
    $prop = [System.Security.AccessControl.PropagationFlags]::None
    $allow = [System.Security.AccessControl.AccessControlType]::Allow
    foreach ($sid in @("S-1-5-18", "S-1-5-32-544")) {
        $id = New-Object System.Security.Principal.SecurityIdentifier($sid)
        $acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($id, "FullControl", $inherit, $prop, $allow)))
    }
    if ($usersRead) {
        $id = New-Object System.Security.Principal.SecurityIdentifier("S-1-5-32-545")
        $acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($id, "ReadAndExecute", $inherit, $prop, $allow)))
    }
    Set-Acl -Path $dir -AclObject $acl
}

if ($Uninstall) {
    Write-Host "Dang xoa tac vu '$TaskName' va thu muc cai dat..."
    schtasks.exe /end /tn $TaskName 2>$null | Out-Null
    schtasks.exe /delete /tn $TaskName /f
    Get-Process CpuTempHelper -ErrorAction SilentlyContinue | Stop-Process -Force
    if (Test-Path $installDir) { Remove-Item $installDir -Recurse -Force }
    if (Test-Path $dataDir) { Remove-Item $dataDir -Recurse -Force }
    Write-Host "Da go cai dat."
    return
}

$srcExe = Join-Path $SourceDir "CpuTempHelper.exe"
$srcDll = Join-Path $SourceDir "LibreHardwareMonitorLib.dll"
foreach ($f in @($srcExe, $srcDll)) {
    if (-not (Test-Path $f)) { throw "Khong thay $f. Chay agent\build_cputemp_helper.ps1 truoc." }
}

# Dll phai dung ban da ghim hash.
$expectedDll = "A24C7CBB3D6CA12950E570FBAD82778A87C87311CDE6218914A283A2C0A04E19"
$dllHash = (Get-FileHash $srcDll -Algorithm SHA256).Hash
if ($dllHash -ne $expectedDll) { throw "LibreHardwareMonitorLib.dll sai hash ($dllHash)." }
$exeHash = (Get-FileHash $srcExe -Algorithm SHA256).Hash
Write-Host "CpuTempHelper.exe SHA-256: $exeHash"

foreach ($d in @($installDir, $dataDir)) {
    if (-not (Test-Path $d)) { New-Item -ItemType Directory -Path $d -Force | Out-Null }
}
schtasks.exe /end /tn $TaskName 2>$null | Out-Null
Get-Process CpuTempHelper -ErrorAction SilentlyContinue | Stop-Process -Force

Set-AdminOnlyWrite $installDir $true
Set-AdminOnlyWrite $dataDir $true
Copy-Item $srcExe $exeInstalled -Force
Copy-Item $srcDll (Join-Path $installDir "LibreHardwareMonitorLib.dll") -Force

Write-Host "Dang tao tac vu '$TaskName' (chay quyen cao nhat khi dang nhap)..."
schtasks.exe /create /tn $TaskName /tr "`"$exeInstalled`"" /sc onlogon /rl highest /f
if ($LASTEXITCODE -ne 0) { throw "Khong the tao tac vu (schtasks exit code: $LASTEXITCODE)" }
schtasks.exe /run /tn $TaskName | Out-Null

Write-Host "Xong. Cai dat tai $installDir, ket qua ghi vao $dataDir\cputemp.txt."
Write-Host "Go cai dat: chay lai script voi -Uninstall."
