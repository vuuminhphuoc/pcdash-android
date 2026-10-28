param(
    [Parameter(Mandatory = $true)][string]$Tag,
    [string]$OutDir
)

# Collects the release files into dist\ with the names used on GitHub Releases:
# pcdash-host-<tag>.apk, pcdash-remote-<tag>.apk, pcdash-agent-windows-<tag>.zip, SHA256SUMS.txt.
# Run build-all.ps1 and agent\build_agent.ps1 -Out agent\build\ -WithHelper first.
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path "$PSScriptRoot\..").Path
if (-not $OutDir) { $OutDir = Join-Path $repoRoot "dist" }

$hostApk = Join-Path $repoRoot "app\build\pcdash-signed.apk"
$remoteApk = Join-Path $repoRoot "remote\build\pcdash-remote-signed.apk"
$agentBuild = Join-Path $repoRoot "agent\build"
$agentBinaries = @("PcAgent.exe", "CpuTempHelper.exe", "LibreHardwareMonitorLib.dll")
$agentScripts = @("install.bat", "install.ps1", "uninstall.ps1", "show-qr.bat", "install-cputemp-task.ps1")

foreach ($p in @($hostApk, $remoteApk) + ($agentBinaries | ForEach-Object { Join-Path $agentBuild $_ })) {
    if (-not (Test-Path $p)) { throw "Missing build output: $p" }
}

Remove-Item $OutDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null

$hostOut = Join-Path $OutDir "pcdash-host-$Tag.apk"
$remoteOut = Join-Path $OutDir "pcdash-remote-$Tag.apk"
Copy-Item $hostApk $hostOut
Copy-Item $remoteApk $remoteOut

# The installers look for PcAgent.exe next to them or in build\, and install-cputemp-task.ps1
# reads CpuTempHelper.exe and the LibreHardwareMonitor DLL from build\.
$stage = Join-Path ([IO.Path]::GetTempPath()) ("pcdash-agent-" + [guid]::NewGuid().ToString("N"))
$stageAgent = Join-Path $stage "pcdash-agent"
New-Item -ItemType Directory -Path (Join-Path $stageAgent "build") -Force | Out-Null
try {
    foreach ($f in $agentScripts) { Copy-Item (Join-Path $repoRoot "agent\$f") $stageAgent }
    foreach ($f in $agentBinaries) { Copy-Item (Join-Path $agentBuild $f) (Join-Path $stageAgent "build") }
    Copy-Item (Join-Path $repoRoot "LICENSE"), (Join-Path $repoRoot "THIRD_PARTY.md") $stageAgent
    $agentOut = Join-Path $OutDir "pcdash-agent-windows-$Tag.zip"
    Compress-Archive -Path (Join-Path $stageAgent "*") -DestinationPath $agentOut
} finally {
    Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
}

$sums = foreach ($f in @($hostOut, $remoteOut, $agentOut)) {
    "{0}  {1}" -f (Get-FileHash $f -Algorithm SHA256).Hash.ToLowerInvariant(), (Split-Path $f -Leaf)
}
[IO.File]::WriteAllText((Join-Path $OutDir "SHA256SUMS.txt"), (($sums -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding($false)))

# What this release holds, so an installed host can tell whether it is newer than itself
# (the tag is the product's version; the apps have their own).
function Read-Version([string]$Path) {
    $props = @{}
    foreach ($line in Get-Content $Path) { if ($line -match '^\s*(\w+)\s*=\s*(.+?)\s*$') { $props[$Matches[1]] = $Matches[2] } }
    return $props
}
$hostVersion = Read-Version (Join-Path $repoRoot "app\version.properties")
$remoteVersion = Read-Version (Join-Path $repoRoot "remote\version.properties")
$versionJson = '{{"tag":"{0}","host":{{"versionName":"{1}","versionCode":{2}}},"remote":{{"versionName":"{3}","versionCode":{4}}}}}' -f `
    $Tag, $hostVersion.versionName, $hostVersion.versionCode, $remoteVersion.versionName, $remoteVersion.versionCode
[IO.File]::WriteAllText((Join-Path $OutDir "pcdash-version.json"), $versionJson + "`n", (New-Object System.Text.UTF8Encoding($false)))

Get-ChildItem $OutDir | Select-Object Name, @{n='SizeKB';e={[math]::Round($_.Length/1KB,1)}}
