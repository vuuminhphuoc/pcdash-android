[CmdletBinding()]
param(
    [string]$TargetDir
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path "$PSScriptRoot\..").Path
if (-not $TargetDir) {
    $TargetDir = Join-Path $repoRoot "tools\lhm"
}

$pkgVersion = "0.9.3"
$pkgUrl = "https://api.nuget.org/v3-flatcontainer/librehardwaremonitorlib/$pkgVersion/librehardwaremonitorlib.$pkgVersion.nupkg"
$expectedPkgSha256 = "CB4CF5C1269335E81B977FA844C7CA32604E4325258F60760001E9496704604A"
$expectedDllSha256 = "A24C7CBB3D6CA12950E570FBAD82778A87C87311CDE6218914A283A2C0A04E19"

if (-not (Test-Path $TargetDir)) {
    New-Item -ItemType Directory -Path $TargetDir -Force | Out-Null
}

$targetDll = Join-Path $TargetDir "LibreHardwareMonitorLib.dll"
if (Test-Path $targetDll) {
    $currentHash = (Get-FileHash -Path $targetDll -Algorithm SHA256).Hash
    if ($currentHash -eq $expectedDllSha256) {
        Write-Host "LibreHardwareMonitorLib.dll verified at $targetDll"
        return $targetDll
    }
}

Write-Host "Downloading LibreHardwareMonitorLib $pkgVersion from NuGet..."
$tempPkg = Join-Path $env:TEMP "lhm_$pkgVersion.nupkg"

try {
    [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12 -bor [System.Net.SecurityProtocolType]::Tls13
    Invoke-WebRequest -Uri $pkgUrl -OutFile $tempPkg -UseBasicParsing

    $actualPkgHash = (Get-FileHash -Path $tempPkg -Algorithm SHA256).Hash
    if ($actualPkgHash -ne $expectedPkgSha256) {
        throw "NuGet package hash mismatch! Expected: $expectedPkgSha256, got: $actualPkgHash"
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($tempPkg)
    try {
        $entry = $zip.GetEntry("lib/net472/LibreHardwareMonitorLib.dll")
        if (-not $entry) {
            throw "lib/net472/LibreHardwareMonitorLib.dll not found in NuGet package"
        }
        $tempDll = Join-Path $TargetDir "LibreHardwareMonitorLib.dll.tmp"
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $tempDll, $true)
        $actualDllHash = (Get-FileHash -Path $tempDll -Algorithm SHA256).Hash
        if ($actualDllHash -ne $expectedDllSha256) {
            Remove-Item $tempDll -Force
            throw "Extracted DLL hash mismatch! Expected: $expectedDllSha256, got: $actualDllHash"
        }
        Move-Item -Path $tempDll -Destination $targetDll -Force
    }
    finally {
        $zip.Dispose()
    }
    Write-Host "OK: LibreHardwareMonitorLib.dll saved to $targetDll"
}
finally {
    if (Test-Path $tempPkg) {
        Remove-Item $tempPkg -Force -ErrorAction SilentlyContinue
    }
}

return $targetDll
