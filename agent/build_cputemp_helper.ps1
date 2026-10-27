param(
    [string]$Out = "$PSScriptRoot\CpuTempHelper.exe"
)

$ErrorActionPreference = "Stop"

$csc = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if (-not (Test-Path $csc)) { $csc = "C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe" }
if (-not (Test-Path $csc)) { throw "csc.exe not found (install .NET Framework)" }

$repoRoot = (Resolve-Path "$PSScriptRoot\..").Path
$lhmDll = Join-Path $repoRoot "tools\lhm\LibreHardwareMonitorLib.dll"
if (-not (Test-Path $lhmDll)) {
    $downloadScript = Join-Path $PSScriptRoot "download_lhm.ps1"
    & $downloadScript
    if (-not (Test-Path $lhmDll)) {
        throw "LibreHardwareMonitorLib.dll not found at $lhmDll"
    }
}

$src = "$PSScriptRoot\CpuTempHelper.cs"
if ($Out.EndsWith("\") -or (Test-Path $Out -PathType Container)) {
    $out = Join-Path $Out "CpuTempHelper.exe"
} else {
    $out = $Out
}

$outDir = Split-Path -Parent $out
if ($outDir -and -not (Test-Path $outDir)) {
    New-Item -ItemType Directory -Path $outDir -Force | Out-Null
}

for ($i = 0; $i -lt 5; $i++) {
    & $csc /nologo /optimize+ /target:exe /r:"$lhmDll" /out:"$out" "$src"
    if ($LASTEXITCODE -eq 0) { break }
    Start-Sleep -Milliseconds 200
}
if ($LASTEXITCODE -ne 0) { throw "csc failed to build CpuTempHelper" }

# Copy LibreHardwareMonitorLib.dll to the output directory alongside CpuTempHelper.exe
if ($outDir) {
    Copy-Item -Path $lhmDll -Destination $outDir -Force
}

"OK: $out"
