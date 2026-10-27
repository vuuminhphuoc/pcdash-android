# Screenshot walkthrough of every PCDash screen on the remote and host phones.
# Read-only: only taps tab bar, the "Thêm" sheet, Quota detail toggle, and "Thiết bị điều khiển từ xa".
# Never taps power/destructive controls (Wake, Sleep, Hibernate, Shutdown, Hủy lệnh, Xóa, Quên host, Thêm thiết bị).
#
# Usage: pwsh -File scripts\ux-walkthrough.ps1 [-Remote <serial>] [-HostSerial <serial>] [-Out <dir>] [-SkipRemote] [-SkipHost]

[CmdletBinding()]
param(
    [string]$Remote = $env:PCDASH_REMOTE_SERIAL,
    [string]$HostSerial = $env:PCDASH_HOST_SERIAL,
    [string]$Out = (Join-Path (Split-Path $PSScriptRoot -Parent) 'remote\build\ux-walkthrough'),
    [switch]$SkipRemote,
    [switch]$SkipHost
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
New-Item -ItemType Directory -Path $Out -Force | Out-Null

if ((-not $SkipRemote -and -not $Remote) -or (-not $SkipHost -and -not $HostSerial)) {
    throw "Pass -Remote and -HostSerial (adb serials) or set PCDASH_REMOTE_SERIAL and PCDASH_HOST_SERIAL. Use -SkipRemote or -SkipHost to review one phone."
}

$forbidden = 'Wake|Đánh thức|Sleep|Hibernate|Shutdown|Tắt máy|Hủy lệnh|Xóa|Quên host|Thêm thiết bị|Bắt đầu ghép đôi'

function Invoke-Adb([string]$Serial, [string[]]$AdbArgs) {
    & adb.exe -s $Serial @AdbArgs
}

function Shot([string]$Serial, [string]$Name) {
    $path = Join-Path $Out "$Name.png"
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = "adb.exe"
    $psi.Arguments = "-s $Serial exec-out screencap -p"
    $psi.RedirectStandardOutput = $true
    $psi.UseShellExecute = $false
    $p = [System.Diagnostics.Process]::Start($psi)
    $fs = [System.IO.File]::Create($path)
    $p.StandardOutput.BaseStream.CopyTo($fs)
    $fs.Close()
    $p.WaitForExit()
    Write-Host "  shot $Name"
}

function Get-ScreenSize([string]$Serial) {
    $raw = (Invoke-Adb $Serial @('shell', 'wm size')) -join ' '
    if ($raw -match '(\d+)x(\d+)') { return @{ W = [int]$Matches[1]; H = [int]$Matches[2] } }
    throw "Cannot read screen size of $Serial"
}

function Swipe-Up([string]$Serial, $Size) {
    $x = [int]($Size.W / 2)
    Invoke-Adb $Serial @('shell', 'input', 'swipe', $x, [int]($Size.H * 0.78), $x, [int]($Size.H * 0.28), 400) | Out-Null
    Start-Sleep -Milliseconds 700
}

# Find a clickable node by visible text through uiautomator and tap its centre.
function Tap-Text([string]$Serial, [string]$Pattern) {
    if ($Pattern -match $forbidden) { throw "Refusing to tap forbidden control: $Pattern" }
    Invoke-Adb $Serial @('shell', 'uiautomator', 'dump', '/sdcard/ux.xml') | Out-Null
    $xml = (Invoke-Adb $Serial @('shell', 'cat', '/sdcard/ux.xml')) -join ''
    $m = [regex]::Matches($xml, '<node[^>]*text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    foreach ($n in $m) {
        if ($n.Groups[1].Value -match $Pattern) {
            $cx = [int](([int]$n.Groups[2].Value + [int]$n.Groups[4].Value) / 2)
            $cy = [int](([int]$n.Groups[3].Value + [int]$n.Groups[5].Value) / 2)
            Invoke-Adb $Serial @('shell', 'input', 'tap', $cx, $cy) | Out-Null
            Start-Sleep -Seconds 2
            return $true
        }
    }
    Write-Warning "Text not found on screen: $Pattern"
    return $false
}

if (-not $SkipRemote) {
    Write-Host "== Remote ($Remote) =="
    $size = Get-ScreenSize $Remote
    $tabY = [int]($size.H * 0.9732)
    $tabX = @(0.148, 0.323, 0.499, 0.675, 0.852) | ForEach-Object { [int]($size.W * $_) }
    Invoke-Adb $Remote @('shell', 'am', 'start', '-n', 'vn.vuumi.pcdash.remote/.RemoteMainActivity') | Out-Null
    Start-Sleep -Seconds 3

    $tabs = @('control', 'monitor', 'quota', 'log')
    for ($i = 0; $i -lt $tabs.Count; $i++) {
        Invoke-Adb $Remote @('shell', 'input', 'tap', $tabX[$i], $tabY) | Out-Null
        Start-Sleep -Seconds ($(if ($tabs[$i] -eq 'quota') { 9 } else { 2 }))
        Shot $Remote ("remote_{0}_1" -f $tabs[$i])
        if ($tabs[$i] -in @('monitor', 'quota')) {
            Swipe-Up $Remote $size
            Shot $Remote ("remote_{0}_2" -f $tabs[$i])
            Swipe-Up $Remote $size
            Shot $Remote ("remote_{0}_3" -f $tabs[$i])
        }
    }

    # Quota: expand the first "Chi tiết" card
    Invoke-Adb $Remote @('shell', 'input', 'tap', $tabX[2], $tabY) | Out-Null
    Start-Sleep -Seconds 2
    if (Tap-Text $Remote '^Chi tiết') { Shot $Remote 'remote_quota_detail' }

    # More sheet, close with back (never "Quên host này")
    Invoke-Adb $Remote @('shell', 'input', 'tap', $tabX[4], $tabY) | Out-Null
    Start-Sleep -Seconds 2
    Shot $Remote 'remote_more'
    Invoke-Adb $Remote @('shell', 'input', 'keyevent', 'KEYCODE_BACK') | Out-Null
}

if (-not $SkipHost) {
    Write-Host "== Host ($HostSerial) =="
    $size = Get-ScreenSize $HostSerial
    Invoke-Adb $HostSerial @('shell', 'am', 'start', '-n', 'vn.vuumi.pcdash/.MainActivity') | Out-Null
    Start-Sleep -Seconds 3
    Shot $HostSerial 'host_main_1'
    Swipe-Up $HostSerial $size
    Shot $HostSerial 'host_main_2'
    Invoke-Adb $HostSerial @('shell', 'input', 'swipe', [int]($size.W / 2), [int]($size.H * 0.28), [int]($size.W / 2), [int]($size.H * 0.78), 300) | Out-Null
    Start-Sleep -Milliseconds 700
    if (Tap-Text $HostSerial 'Thiết bị điều khiển từ xa') {
        Shot $HostSerial 'host_devices_1'
        Swipe-Up $HostSerial $size
        Shot $HostSerial 'host_devices_2'
        Invoke-Adb $HostSerial @('shell', 'input', 'keyevent', 'KEYCODE_BACK') | Out-Null
    }
}

Write-Host "Done. Screenshots in $Out"
