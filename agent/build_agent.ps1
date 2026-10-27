param(
    [string]$Out = "$PSScriptRoot\PcAgent.exe",
    [switch]$WithHelper
)

$ErrorActionPreference = "Stop"
$csc = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if (-not (Test-Path $csc)) { $csc = "C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe" }
if (-not (Test-Path $csc)) { throw "csc.exe not found (install .NET Framework)" }

$src = @("$PSScriptRoot\PcAgent.cs", "$PSScriptRoot\QuotaAggregator.cs", "$PSScriptRoot\QrCode.cs", "$PSScriptRoot\SetupWindow.cs")
if ($Out.EndsWith("\") -or (Test-Path $Out -PathType Container)) {
    $out = Join-Path $Out "PcAgent.exe"
} else {
    $out = $Out
}

$outDir = Split-Path -Parent $out
if ($outDir -and -not (Test-Path $outDir)) {
    New-Item -ItemType Directory -Path $outDir -Force | Out-Null
}

for ($i = 0; $i -lt 5; $i++) {
    & $csc /nologo /optimize+ /codepage:65001 /target:winexe /r:System.Windows.Forms.dll /r:System.Drawing.dll /out:"$out" $src
    if ($LASTEXITCODE -eq 0) { break }
    Start-Sleep -Milliseconds 200
}
if ($LASTEXITCODE -ne 0) { throw "csc failed" }

if ($WithHelper) {
    $helperOut = if ($outDir) { Join-Path $outDir "CpuTempHelper.exe" } else { "$PSScriptRoot\CpuTempHelper.exe" }
    & "$PSScriptRoot\build_cputemp_helper.ps1" -Out $helperOut
}
"OK: $out"
