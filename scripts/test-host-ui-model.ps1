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
$testBuild = Join-Path $app 'build\test-host-ui-model'
$classesDir = Join-Path $testBuild 'classes'

Remove-Item $testBuild -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $classesDir -Force | Out-Null

$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$javac = $tools.javac
$androidJar = $tools['android.jar']

$sources = @(
    (Join-Path $shared 'src\vn\vuumi\pcdash\pairing\PairingMessages.java'),
    (Join-Path $shared 'src\vn\vuumi\pcdash\pairing\PairingProtocol.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\LanAddressPolicy.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\TimeSource.java'),
    (Join-Path $shared 'src\vn\vuumi\pcdash\net\PcnetBridge.java'),
    (Join-Path $shared 'src\vn\vuumi\pcdash\net\PcnetState.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\HostTailnet.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\PairingManager.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\RemoteDeviceStore.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\CommandLog.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\MetricsHistory.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\RemoteActionExecutor.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\RemoteApiServer.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\RemoteHostController.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\RemoteDevicesPresenter.java'),
    (Join-Path $app 'test\vn\vuumi\pcdash\RemoteDevicesPresenterTest.java')
)

Write-Host "Compiling Task 5 UI presenter tests and classes..."
& $javac -encoding UTF-8 -source 8 -target 8 -classpath "$classesDir;$androidJar" -d $classesDir $sources
if ($LASTEXITCODE -ne 0) {
    throw "javac compilation failed"
}

Write-Host "Executing RemoteDevicesPresenterTest..."
& $java -cp "$classesDir;$androidJar" vn.vuumi.pcdash.RemoteDevicesPresenterTest
if ($LASTEXITCODE -ne 0) {
    throw "RemoteDevicesPresenterTest failed"
}

Write-Host "PASS: all host UI model and presenter tests passed!"
