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
$shared = Join-Path $repoRoot 'shared'
$testBuild = Join-Path $app 'build\test-host-pairing'
$classesDir = Join-Path $testBuild 'classes'

Remove-Item $testBuild -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $classesDir -Force | Out-Null

$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$javac = $tools.javac
$androidJar = $tools['android.jar']

$sources = @(
    (Join-Path $shared 'src\vn\vuumi\pcdash\pairing\PairingMessages.java'),
    (Join-Path $shared 'src\vn\vuumi\pcdash\pairing\PairingProtocol.java'),
    (Join-Path $shared 'src\vn\vuumi\pcdash\net\PcnetState.java'),
    (Join-Path $shared 'src\vn\vuumi\pcdash\net\PcnetBridge.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\LanAddressPolicy.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\TimeSource.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\HostTailnet.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\PairingManager.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\RemoteDeviceStore.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\CommandLog.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\MetricsHistory.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\RemoteActionExecutor.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\RemoteApiServer.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\RemoteHostController.java'),
    (Join-Path $shared 'test\vn\vuumi\pcdash\pairing\PairingProtocolTest.java'),
    (Join-Path $shared 'test\vn\vuumi\pcdash\net\PcnetStateParseTest.java'),
    (Join-Path $app 'test\vn\vuumi\pcdash\PairingManagerTest.java'),
    (Join-Path $app 'test\vn\vuumi\pcdash\HostTailnetTest.java'),
    (Join-Path $app 'test\vn\vuumi\pcdash\RemoteHostControllerTest.java')
)

Write-Host "Compiling Task 4 pairing tests and classes..."
& $javac -encoding UTF-8 -source 8 -target 8 -classpath "$classesDir;$androidJar" -sourcepath "$app\src;$app\test;$repoRoot\shared\src" -d $classesDir $sources
if ($LASTEXITCODE -ne 0) {
    throw "javac compilation failed"
}

Write-Host "Executing PairingProtocolTest..."
& $java -cp $classesDir vn.vuumi.pcdash.pairing.PairingProtocolTest
if ($LASTEXITCODE -ne 0) {
    throw "PairingProtocolTest failed"
}

Write-Host "Executing PcnetStateParseTest..."
& $java -cp $classesDir vn.vuumi.pcdash.net.PcnetStateParseTest
if ($LASTEXITCODE -ne 0) {
    throw "PcnetStateParseTest failed"
}

Write-Host "Executing PairingManagerTest..."
& $java -cp $classesDir vn.vuumi.pcdash.PairingManagerTest
if ($LASTEXITCODE -ne 0) {
    throw "PairingManagerTest failed"
}

Write-Host "Executing HostTailnetTest..."
& $java -cp $classesDir vn.vuumi.pcdash.HostTailnetTest
if ($LASTEXITCODE -ne 0) {
    throw "HostTailnetTest failed"
}

Write-Host "Executing RemoteHostControllerTest..."
& $java -cp $classesDir vn.vuumi.pcdash.RemoteHostControllerTest
if ($LASTEXITCODE -ne 0) {
    throw "RemoteHostControllerTest failed"
}


Write-Host "Verifying production build isolation from FakeSecretCipher..."
if (Test-Path (Join-Path $app 'src\vn\vuumi\pcdash\FakeSecretCipher.java')) {
    throw "Assertion failed: FakeSecretCipher.java must not exist in app/src"
}
$prodClasses = Join-Path $app 'build\classes'
if (Test-Path $prodClasses) {
    $fakeClass = Join-Path $prodClasses 'vn\vuumi\pcdash\FakeSecretCipher.class'
    if (Test-Path $fakeClass) {
        throw "Assertion failed: app\build\classes must not contain FakeSecretCipher.class"
    }
}
$signedApk = Join-Path $app 'build\pcdash-signed.apk'
if (Test-Path $signedApk) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($signedApk)
    $hasFake = ($zip.Entries | Where-Object { $_.FullName -like "*FakeSecretCipher*" }).Count -gt 0
    $zip.Dispose()
    if ($hasFake) {
        throw "Assertion failed: signed APK must not contain FakeSecretCipher"
    }
}
Write-Host "Verified: FakeSecretCipher is excluded from product sources and build classes."
Write-Host "PASS: all host pairing and device revocation tests passed!"
