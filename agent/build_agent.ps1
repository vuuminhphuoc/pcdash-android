param(
    [string]$Out = "$PSScriptRoot\PcAgent.exe",
    [switch]$WithHelper,
    # Release version stamped into the agent (1.2.3, or v1.2.3). Without one the agent is a build from
    # source (0.0.0) and never updates itself.
    [string]$Version = $env:PCAGENT_VERSION
)

$ErrorActionPreference = "Stop"
$csc = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if (-not (Test-Path $csc)) { $csc = "C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe" }
if (-not (Test-Path $csc)) { throw "csc.exe not found (install .NET Framework)" }

$src = @("$PSScriptRoot\PcAgent.cs", "$PSScriptRoot\QuotaAggregator.cs", "$PSScriptRoot\QrCode.cs", "$PSScriptRoot\SetupWindow.cs", "$PSScriptRoot\AgentUpdater.cs")

$stamp = if ($Version) { $Version.Trim().TrimStart('v') } else { '0.0.0' }
if ($stamp -notmatch '^[0-9]+(\.[0-9]+){1,3}$') { throw "Bad -Version '$Version' (want 1.2.3)" }
$versionFile = Join-Path ([IO.Path]::GetTempPath()) ('AgentVersion-' + [guid]::NewGuid().ToString('N') + '.cs')
[IO.File]::WriteAllText($versionFile, "namespace PcAgent { static class AgentVersion { public const string Current = `"$stamp`"; } }", (New-Object System.Text.UTF8Encoding($false)))
$src += $versionFile
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
    & $csc /nologo /optimize+ /codepage:65001 /target:winexe /r:System.Windows.Forms.dll /r:System.Drawing.dll /r:System.IO.Compression.dll /r:System.IO.Compression.FileSystem.dll /out:"$out" $src
    if ($LASTEXITCODE -eq 0) { break }
    Start-Sleep -Milliseconds 200
}
Remove-Item $versionFile -Force -ErrorAction SilentlyContinue
if ($LASTEXITCODE -ne 0) { throw "csc failed" }

if ($WithHelper) {
    $helperOut = if ($outDir) { Join-Path $outDir "CpuTempHelper.exe" } else { "$PSScriptRoot\CpuTempHelper.exe" }
    & "$PSScriptRoot\build_cputemp_helper.ps1" -Out $helperOut
}
"OK: $out"
