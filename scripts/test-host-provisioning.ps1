$ErrorActionPreference = "Stop"

if ($PSVersionTable.PSVersion.Major -lt 7 -and (Get-Command pwsh -ErrorAction SilentlyContinue)) {
    & pwsh -NoProfile -File $PSCommandPath @args
    exit $LASTEXITCODE
}

. "$PSScriptRoot\BuildToolchain.ps1"
Use-PcdashJdk17

. "$PSScriptRoot\BuildToolchain.ps1"
$tools = Get-PcdashToolchain (Split-Path $PSScriptRoot -Parent)
$repoRoot = Split-Path $PSScriptRoot -Parent
$app = Join-Path $repoRoot 'app'
$testBuild = Join-Path $app 'build\test-host-provisioning'
$classesDir = Join-Path $testBuild 'classes'

Remove-Item $testBuild -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $classesDir -Force | Out-Null

$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$javac = $tools.javac
$androidJar = $tools['android.jar']

$mockDir = Join-Path $testBuild 'mock'
New-Item -ItemType Directory -Path $mockDir -Force | Out-Null
$mockPcConn = Join-Path $mockDir 'PcConn.java'
@"
package vn.vuumi.pcdash;
public class PcConn {
    public static PcSettingsSource getSettings() { return null; }
}
"@ | Set-Content -Path $mockPcConn -Encoding UTF8

$sources = @(
    $mockPcConn,
    (Join-Path $app 'src\vn\vuumi\pcdash\TimeSource.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\MetricsJson.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\PcMetricsClient.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\PcQuotaClient.java'),
    (Join-Path $app 'test\vn\vuumi\pcdash\FakeSecretCipher.java'),
    (Join-Path $app 'test\vn\vuumi\pcdash\FakeSharedPreferences.java'),
    (Join-Path $app 'test\vn\vuumi\pcdash\PcLiveSettingsTest.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\LanAddressPolicy.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\SecretCipher.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\AndroidSecretCipher.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\PcSettingsSource.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\PcSettings.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\PcProvisioning.java'),
    (Join-Path $app 'test\vn\vuumi\pcdash\PcProvisioningTest.java')
)

Write-Host "Compiling setup-QR payload tests..."
& $javac -encoding UTF-8 -source 8 -target 8 -nowarn -classpath $androidJar -sourcepath "$app\src;$app\test;$repoRoot\shared\src" -d $classesDir $sources
if ($LASTEXITCODE -ne 0) { throw "javac compilation failed" }

Write-Host "Executing PcProvisioningTest..."
& $java -cp "$classesDir;$androidJar" vn.vuumi.pcdash.PcProvisioningTest
if ($LASTEXITCODE -ne 0) { throw "PcProvisioningTest failed" }

Write-Host "Executing PcLiveSettingsTest..."
& $java -cp "$classesDir;$androidJar" vn.vuumi.pcdash.PcLiveSettingsTest
if ($LASTEXITCODE -ne 0) { throw "PcLiveSettingsTest failed" }

Write-Host "PASS: setup-QR payload and live settings tests passed!"
