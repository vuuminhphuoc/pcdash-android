$ErrorActionPreference = "Stop"
if ($PSVersionTable.PSVersion.Major -lt 7 -and (Get-Command pwsh -ErrorAction SilentlyContinue)) {
    & pwsh -NoProfile -File $MyInvocation.MyCommand.Path @args
    exit $LASTEXITCODE
}

. "$PSScriptRoot\BuildToolchain.ps1"
Use-PcdashJdk17

. "$PSScriptRoot\BuildToolchain.ps1"
$tools = Get-PcdashToolchain (Split-Path $PSScriptRoot -Parent)
$repoRoot = Split-Path $PSScriptRoot -Parent
$app = Join-Path $repoRoot 'app'
$testBuild = Join-Path $app 'build\test-host-api'
$classesDir = Join-Path $testBuild 'classes'

Remove-Item $testBuild -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $classesDir -Force | Out-Null

$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$javac = $tools.javac

$mockDir = Join-Path $testBuild 'mock-android'
$mockContentDir = Join-Path $mockDir 'android\content'
$mockResDir = Join-Path $mockContentDir 'res'
New-Item -ItemType Directory -Path $mockContentDir, $mockResDir -Force | Out-Null

$mockContextFile = Join-Path $mockContentDir 'Context.java'
@"
package android.content;
import android.content.res.AssetManager;
public class Context {
    public AssetManager getAssets() {
        return new AssetManager();
    }
}
"@ | Set-Content -Path $mockContextFile -Encoding UTF8

$mockAssetFile = Join-Path $mockResDir 'AssetManager.java'
@"
package android.content.res;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
public class AssetManager {
    public InputStream open(String name) {
        return new ByteArrayInputStream("<!DOCTYPE html><html><body><h1>PCDash</h1></body></html>".getBytes(StandardCharsets.UTF_8));
    }
}
"@ | Set-Content -Path $mockAssetFile -Encoding UTF8

$mockPcConnFile = Join-Path $mockDir 'PcConn.java'
@"
package vn.vuumi.pcdash;
public class PcConn {
    public static String statusJson() {
        return "{\"online\":true,\"port\":8799,\"rtt_ms\":10}";
    }
    public static String doAction(String action) {
        return "ok";
    }
}
"@ | Set-Content -Path $mockPcConnFile -Encoding UTF8

$sources = @(
    $mockContextFile,
    $mockAssetFile,
    $mockPcConnFile,
    (Join-Path $app 'src\vn\vuumi\pcdash\CommandLog.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\MetricsHistory.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\WebDashboard.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\RemoteActionExecutor.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\RemoteDeviceStore.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\RemoteApiServer.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\RemoteHostController.java'),
    (Join-Path $app 'test\vn\vuumi\pcdash\HostApiTest.java')
)

Write-Host "Compiling HostApiTest, WebDashboard, and host API classes..."
& $javac -encoding UTF-8 -source 8 -target 8 -sourcepath "$app\src;$app\test;$repoRoot\shared\src" -d $classesDir $sources
if ($LASTEXITCODE -ne 0) {
    throw "javac compilation failed"
}

Write-Host "Executing HostApiTest..."
& $java -cp $classesDir vn.vuumi.pcdash.HostApiTest
if ($LASTEXITCODE -ne 0) {
    throw "HostApiTest failed"
}

Write-Host "Executing WebDashboard and RemoteApiServer smoke verification..."
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $java
$psi.Arguments = "-cp `"$classesDir`" vn.vuumi.pcdash.HostApiTest --smoke-server"
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.UseShellExecute = $false
$proc = [System.Diagnostics.Process]::Start($psi)

try {
    $ready8090 = $proc.StandardOutput.ReadLine()
    if ($ready8090 -ne "READY_8090") {
        throw "WebDashboard failed to start: $ready8090"
    }

    # 1. Before RemoteApiServer starts: query /healthz and /api/status on :8090
    $h1 = Invoke-RestMethod -Uri "http://127.0.0.1:8090/healthz" -Method Get -TimeoutSec 3
    if ($h1.ok -ne $true) { throw "Smoke failed: /healthz did not return ok:true before :8091" }
    $s1 = Invoke-RestMethod -Uri "http://127.0.0.1:8090/api/status" -Method Get -TimeoutSec 3
    if ($null -eq $s1) { throw "Smoke failed: /api/status returned null before :8091" }
    Write-Host "  [Smoke] Before :8091: WebDashboard /healthz OK, /api/status OK"

    # 2. Tell process to start RemoteApiServer on :8091
    $proc.StandardInput.WriteLine("START_8091")
    $ready8091 = $proc.StandardOutput.ReadLine()
    if ($ready8091 -ne "READY_8091") {
        throw "RemoteApiServer failed to start: $ready8091"
    }

    # 3. Verify RemoteApiServer on :8091 serves /v1/status
    $headers = @{ Authorization = "Bearer smoke-token-secret-123" }
    $r1 = Invoke-RestMethod -Uri "http://127.0.0.1:8091/v1/status" -Method Get -Headers $headers -TimeoutSec 3
    if ($null -eq $r1) { throw "Smoke failed: RemoteApiServer /v1/status returned null" }
    Write-Host "  [Smoke] RemoteApiServer (:8091) authenticated /v1/status OK"

    # 4. After RemoteApiServer started: query /healthz and /api/status on :8090 again
    $h2 = Invoke-RestMethod -Uri "http://127.0.0.1:8090/healthz" -Method Get -TimeoutSec 3
    if ($h2.ok -ne $true) { throw "Smoke failed: /healthz did not return ok:true after :8091" }
    $s2 = Invoke-RestMethod -Uri "http://127.0.0.1:8090/api/status" -Method Get -TimeoutSec 3
    if ($null -eq $s2) { throw "Smoke failed: /api/status returned null after :8091" }
    Write-Host "  [Smoke] After :8091: WebDashboard /healthz STILL OK, /api/status STILL OK"

    Write-Host "  [Smoke] PROVEN: WebDashboard (:8090) serves before and after RemoteApiServer (:8091) starts"
} finally {
    try {
        $proc.StandardInput.WriteLine("STOP")
    } catch {}
    if (-not $proc.WaitForExit(3000)) {
        $proc.Kill()
    }
}

Write-Host "PASS: host API tests and WebDashboard coexistence smoke"
