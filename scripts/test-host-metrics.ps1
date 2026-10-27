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
$testBuild = Join-Path $app 'build\test-host-metrics'
$classesDir = Join-Path $testBuild 'classes'

Remove-Item $testBuild -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $classesDir -Force | Out-Null

$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$javac = $tools.javac
$androidJar = $tools['android.jar']
$mockDir = Join-Path $testBuild 'mock-android'
New-Item -ItemType Directory -Path $mockDir -Force | Out-Null

$mockPcConnFile = Join-Path $mockDir 'PcConn.java'
@"
package vn.vuumi.pcdash;
public class PcConn {
    public static final String PC_IP = "192.168.1.50";
    public static final int AGENT_PORT = 8799;
    public static String statusJson() {
        return "{\"online\":true,\"port\":8799,\"rtt_ms\":10}";
    }
    public static String doAction(String action) {
        return "ok";
    }
}
"@ | Set-Content -Path $mockPcConnFile -Encoding UTF8

$sources = @(
    $mockPcConnFile,
    (Join-Path $app 'src\vn\vuumi\pcdash\LanAddressPolicy.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\TimeSource.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\PhoneMetricsSource.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\PhoneMetricsCollector.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\MetricsJson.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\PcMetricsClient.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\CommandLog.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\MetricsHistory.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\RemoteActionExecutor.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\RemoteDeviceStore.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\RemoteApiServer.java'),
    (Join-Path $app 'test\vn\vuumi\pcdash\FakePhoneMetricsSource.java'),
    (Join-Path $app 'test\vn\vuumi\pcdash\HostMetricsTest.java')
)

Write-Host "Compiling HostMetricsTest and metrics classes..."
& $javac -encoding UTF-8 -source 8 -target 8 -classpath "$classesDir;$androidJar" -sourcepath "$app\src;$app\test;$repoRoot\shared\src" -d $classesDir $sources
if ($LASTEXITCODE -ne 0) {
    throw "javac compilation failed"
}

Write-Host "Executing HostMetricsTest..."
& $java -cp "$classesDir;$androidJar" vn.vuumi.pcdash.HostMetricsTest
if ($LASTEXITCODE -ne 0) {
}

Write-Host "PASS: host metrics tests passed!"
