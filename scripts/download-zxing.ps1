[CmdletBinding()]
param(
    [string]$TargetDir
)

# Fetches the ZXing core jar (Apache-2.0) used by the host app to decode the setup QR code.
# The jar is not stored in this repository; the SHA-256 below is pinned.
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path "$PSScriptRoot\..").Path
if (-not $TargetDir) {
    $TargetDir = Join-Path $repoRoot "tools\zxing"
}

$version = "3.5.3"
$url = "https://repo1.maven.org/maven2/com/google/zxing/core/$version/core-$version.jar"
$expectedSha256 = "8D8064C1636FDAEF7189DD9055C7D59950A8940A12F2293956446EC3C109FD82"

if (-not (Test-Path $TargetDir)) {
    New-Item -ItemType Directory -Path $TargetDir -Force | Out-Null
}

$target = Join-Path $TargetDir "core-$version.jar"
if (Test-Path $target) {
    if ((Get-FileHash -Path $target -Algorithm SHA256).Hash -eq $expectedSha256) {
        return $target
    }
    Write-Host "Existing $target has the wrong hash, downloading again."
}

Write-Host "Downloading ZXing core $version from Maven Central..."
$temp = "$target.tmp"
try {
    [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12 -bor [System.Net.SecurityProtocolType]::Tls13
    Invoke-WebRequest -Uri $url -OutFile $temp -UseBasicParsing
    $actual = (Get-FileHash -Path $temp -Algorithm SHA256).Hash
    if ($actual -ne $expectedSha256) {
        throw "ZXing jar hash mismatch! Expected: $expectedSha256, got: $actual"
    }
    Move-Item -Path $temp -Destination $target -Force
} finally {
    if (Test-Path $temp) { Remove-Item $temp -Force -ErrorAction SilentlyContinue }
}
return $target
