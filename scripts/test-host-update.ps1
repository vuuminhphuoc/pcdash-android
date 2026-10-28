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
$testBuild = Join-Path $app 'build\test-host-update'
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
    public InputStream open(String name) throws java.io.IOException {
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
    (Join-Path $app 'src\vn\vuumi\pcdash\UpdateAssetSource.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\AndroidUpdateAssetSource.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\CommandLog.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\MetricsHistory.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\RemoteActionExecutor.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\RemoteDeviceStore.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\RemoteApiServer.java'),
    (Join-Path $app 'test\vn\vuumi\pcdash\FakeUpdateAssetSource.java'),
    (Join-Path $app 'test\vn\vuumi\pcdash\HostUpdateTest.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\HostUpdateCheck.java'),
    (Join-Path $app 'test\vn\vuumi\pcdash\HostUpdateCheckTest.java')
)

Write-Host "Compiling HostUpdateTest and update server classes..."
& $javac -encoding UTF-8 -source 8 -target 8 -sourcepath "$app\src;$app\test;$repoRoot\shared\src" -d $classesDir $sources
if ($LASTEXITCODE -ne 0) {
    throw "javac compilation failed"
}

Write-Host "Executing HostUpdateTest..."
& $java -cp $classesDir vn.vuumi.pcdash.HostUpdateTest
if ($LASTEXITCODE -ne 0) {
    throw "HostUpdateTest failed"
}

Write-Host "Executing HostUpdateCheckTest..."
& $java -cp $classesDir vn.vuumi.pcdash.HostUpdateCheckTest
if ($LASTEXITCODE -ne 0) {
    throw "HostUpdateCheckTest failed"
}

Write-Host "PASS: all host update tests passed!"
