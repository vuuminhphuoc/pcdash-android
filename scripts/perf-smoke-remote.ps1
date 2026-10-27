# Performance & ANR Smoke Test for PCDash Remote
# Parameter -Serial defaults to $env:PCDASH_REMOTE_SERIAL, else the only connected device
# Switches tabs 30 loops and verifies UI thread safety, gfxinfo, logcat, threads, and memory.

[CmdletBinding()]
param(
    [string]$Serial = $env:PCDASH_REMOTE_SERIAL,
    [int]$Rounds = 30
)

$ErrorActionPreference = "Stop"

if (-not $Serial) {
    $found = @(& adb.exe devices | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" } | ForEach-Object { ($_ -split "\s+")[0] })
    if ($found.Count -eq 1) {
        $Serial = $found[0]
    } else {
        throw "Pass -Serial <adb serial> or set PCDASH_REMOTE_SERIAL ($($found.Count) devices connected)."
    }
}
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

function Invoke-Adb {
    param([string[]]$AdbArgs)
    $allArgs = @("-s", $Serial) + $AdbArgs
    & adb $allArgs
}

function Get-TotalPssKb {
    param([string]$PackageName)
    $raw = (Invoke-Adb @("shell", "dumpsys meminfo $PackageName")) -join "`n"
    if ($raw -match 'TOTAL PSS:\s*(\d+)') {
        return [long]$Matches[1]
    }
    if ($raw -match 'TOTAL\s+(\d+)\s+') {
        return [long]$Matches[1]
    }
    return 0L
}

function Get-ThreadCount {
    param([string]$PidStr)
    if (-not $PidStr) { return 0 }
    $res = (Invoke-Adb @("shell", "ls -1 /proc/$PidStr/task 2>/dev/null | wc -l")) -join ""
    $cnt = 0
    if ([int]::TryParse($res.Trim(), [ref]$cnt)) {
        return $cnt
    }
    return 0
}

Write-Host "=========================================================="
Write-Host "PCDash Remote Performance & ANR Smoke Test"
Write-Host "Target Device: $Serial"
Write-Host "Rounds:        $Rounds"
Write-Host "=========================================================="

# Check device connection
$devices = & adb devices
if ($devices -notmatch [regex]::Escape($Serial)) {
    Write-Warning "Device $Serial is not listed in 'adb devices'."
    Write-Warning "Ensure device is connected or pass -Serial <id>."
}

$packageName = "vn.vuumi.pcdash.remote"
$activityName = "vn.vuumi.pcdash.remote/vn.vuumi.pcdash.remote.RemoteMainActivity"

Write-Host "`n[Step 1] Opening Remote App..."
Invoke-Adb @("shell", "am start -n $activityName") | Out-Null
Start-Sleep -Seconds 2

# Resolve PID
$pidStr = ((Invoke-Adb @("shell", "pidof $packageName")) -join "").Trim()
if (-not $pidStr) {
    throw "App $packageName is not running after launch"
}
Write-Host "App PID: $pidStr"

# Time marker for logcat
$startTimeMarker = ((Invoke-Adb @("shell", "date '+%Y-%m-%d %H:%M:%S.000'")) -join "").Trim()
Write-Host "Logcat start marker: $startTimeMarker"

# Baseline measurements
$threadBefore = Get-ThreadCount $pidStr
$pssBeforeKb = Get-TotalPssKb $packageName
$pssBeforeMb = [Math]::Round($pssBeforeKb / 1024.0, 2)
Write-Host "Baseline Threads: $threadBefore"
Write-Host "Baseline PSS:     $pssBeforeMb MB"

# Reset gfxinfo
Invoke-Adb @("shell", "dumpsys gfxinfo $packageName reset") | Out-Null

Write-Host "`n[Step 2] Finding 5 tabs via uiautomator dump..."
$env:MSYS_NO_PATHCONV = "1"
Invoke-Adb @("shell", "uiautomator dump /sdcard/u.xml") | Out-Null
$xmlRaw = (Invoke-Adb @("shell", "cat /sdcard/u.xml")) -join "`n"

$tabLabels = @("Điều khiển", "Giám sát", "Quota", "Nhật ký", "Thêm")
# The same tabs when the phone runs PCDash Remote in English.
$tabLabelsEn = @{ "Điều khiển" = "Control"; "Giám sát" = "Monitor"; "Quota" = "Quota"; "Nhật ký" = "Log"; "Thêm" = "More" }
$tabCoords = @{}

foreach ($lbl in $tabLabels) {
    $nodePattern = '<node\b[^>]*?\btext="(?:' + [regex]::Escape($lbl) + '|' + [regex]::Escape($tabLabelsEn[$lbl]) + ')"[^>]*?>'
    if ($xmlRaw -match $nodePattern) {
        $nodeStr = $Matches[0]
        if ($nodeStr -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
            $x1 = [int]$Matches[1]
            $y1 = [int]$Matches[2]
            $x2 = [int]$Matches[3]
            $y2 = [int]$Matches[4]
            $cx = [int](($x1 + $x2) / 2)
            $cy = [int](($y1 + $y2) / 2)
            $tabCoords[$lbl] = @{ X = $cx; Y = $cy }
            Write-Host "  Found tab '$lbl' -> ($cx, $cy)"
        }
    }
}

if ($tabCoords.Count -lt 5) {
    Write-Warning "Could not find all 5 tabs via UIAutomator (Found: $($tabCoords.Count)/5). Using proportional tab-bar coordinates."
    $wm = (Invoke-Adb @("shell", "wm size")) -join " "
    if ($wm -notmatch '(\d+)x(\d+)') {
        throw "Failed to locate tabs and cannot read screen size"
    }
    $sw = [int]$Matches[1]
    $sh = [int]$Matches[2]
    $fractions = @(0.148, 0.323, 0.499, 0.675, 0.852)
    for ($i = 0; $i -lt $tabLabels.Count; $i++) {
        $tabCoords[$tabLabels[$i]] = @{ X = [int]($sw * $fractions[$i]); Y = [int]($sh * 0.9732) }
    }
}

Write-Host "`n[Step 3] Switching tabs for $Rounds rounds..."
$roundOrder = @("Điều khiển", "Giám sát", "Quota", "Nhật ký", "Thêm")

for ($r = 1; $r -le $Rounds; $r++) {
    foreach ($tab in $roundOrder) {
        if ($tabCoords.ContainsKey($tab)) {
            $c = $tabCoords[$tab]
            Invoke-Adb @("shell", "input tap $($c.X) $($c.Y)") | Out-Null
            if ($tab -eq "Thêm") {
                Start-Sleep -Milliseconds 200
                # Dismiss MoreSheet with Back key
                Invoke-Adb @("shell", "input keyevent 4") | Out-Null
                Start-Sleep -Milliseconds 150
            } else {
                Start-Sleep -Milliseconds 150
            }
        }
    }
    if ($r % 5 -eq 0 -or $r -eq $Rounds) {
        Write-Host "  Completed round $r / $Rounds"
    }
}

Write-Host "`n[Step 4] Collecting post-run metrics..."
# Post measurements
$threadAfter = Get-ThreadCount $pidStr
$pssAfterKb = Get-TotalPssKb $packageName
$pssAfterMb = [Math]::Round($pssAfterKb / 1024.0, 2)
$threadDelta = $threadAfter - $threadBefore
$pssDeltaMb = [Math]::Round(($pssAfterKb - $pssBeforeKb) / 1024.0, 2)

Write-Host "Post Threads: $threadAfter (Delta: $threadDelta)"
Write-Host "Post PSS:     $pssAfterMb MB (Delta: $pssDeltaMb MB)"

# Collect gfxinfo
$gfxRaw = (Invoke-Adb @("shell", "dumpsys gfxinfo $packageName")) -join "`n"
$jankyPct = 0.0
if ($gfxRaw -match 'Janky frames:\s*\d+\s*\(([\d\.]+)%\)') {
    $jankyPct = [double]$Matches[1]
}
$totalFrames = 0
if ($gfxRaw -match 'Total frames rendered:\s*(\d+)') {
    $totalFrames = [int]$Matches[1]
}
Write-Host "Gfxinfo Total Frames:  $totalFrames"
$p99Ms = 0
if ($gfxRaw -match '99th percentile:\s*(\d+)ms') {
    $p99Ms = [int]$Matches[1]
}
Write-Host "Gfxinfo Janky Frames: $jankyPct%"
Write-Host "Gfxinfo 99th Percentile: ${p99Ms}ms"

# Logcat check
Write-Host "`n[Step 5] Checking logcat for ANR, crashes, and StrictMode..."
$logcatRaw = (Invoke-Adb @("logcat", "-d", "-t", $startTimeMarker)) -join "`n"
$logcatLines = $logcatRaw -split "`n"

$anrMatches = $logcatLines | Where-Object { $_ -match "ANR in vn\.vuumi\.pcdash" }
$fatalMatches = $logcatLines | Where-Object { $_ -match "FATAL EXCEPTION" -and $_ -match "vn\.vuumi\.pcdash" }
$strictMatches = $logcatLines | Where-Object { $_ -match "StrictMode" -and $_ -match "vn\.vuumi\.pcdash" }

$anrCount = ($anrMatches | Measure-Object).Count
$fatalCount = ($fatalMatches | Measure-Object).Count
$strictCount = ($strictMatches | Measure-Object).Count

Write-Host "ANR count:         $anrCount"
Write-Host "FATAL crashes:     $fatalCount"
Write-Host "StrictMode logs:   $strictCount"

if ($strictCount -gt 0) {
    Write-Host "StrictMode Summary (First 5):"
    $strictMatches | Select-Object -First 5 | ForEach-Object { Write-Host "  [StrictMode] $_" }
}

Write-Host "`n=========================================================="
Write-Host "EVALUATION RESULTS"
Write-Host "=========================================================="

$failures = @()

if ($jankyPct -gt 15.0) {
    $failures += "Janky frames ($jankyPct%) exceeded limit of 15%"
}
if ($totalFrames -le 0) {
    $failures += "no frames rendered during the run, test is invalid (tab switching did not reach the app)"
} elseif ($p99Ms -gt 100) {
    $failures += "99th percentile render time (${p99Ms}ms) exceeded limit of 100ms"
}
if ($anrCount -gt 0) {
    $failures += "Found $anrCount ANR events in logcat"
}
if ($fatalCount -gt 0) {
    $failures += "Found $fatalCount FATAL crashes in logcat"
}
if ($threadDelta -gt 10) {
    $failures += "Thread count increased by $threadDelta (limit is <= 10)"
}
if ($pssDeltaMb -gt 40.0) {
    $failures += "PSS increased by $pssDeltaMb MB (limit is <= 40 MB)"
}

if ($failures.Count -gt 0) {
    Write-Host "FAILED with $($failures.Count) issue(s):" -ForegroundColor Red
    foreach ($f in $failures) {
        Write-Host "  [FAIL] $f" -ForegroundColor Red
    }
    exit 1
} else {
    Write-Host "SUCCESS: ALL CRITERIA PASSED!" -ForegroundColor Green
    Write-Host "  Janky: $jankyPct% <= 15%"
    Write-Host "  99th percentile: ${p99Ms}ms <= 100ms"
    Write-Host "  ANR: 0, Crashes: 0"
    Write-Host "  Thread delta: $threadDelta <= 10"
    Write-Host "  PSS delta: $pssDeltaMb MB <= 40 MB"
    exit 0
}
