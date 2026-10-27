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
$remote = Join-Path $repoRoot 'remote'
$shared = Join-Path $repoRoot 'shared'
$testBuild = Join-Path $repoRoot 'build\test-pairing-e2e'
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
    (Join-Path $app 'src\vn\vuumi\pcdash\SecretCipher.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\AndroidSecretCipher.java'),
    (Join-Path $app 'test\vn\vuumi\pcdash\FakeSecretCipher.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\PairingManager.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\RemoteDeviceStore.java'),
    (Join-Path $remote 'src\vn\vuumi\pcdash\remote\RemoteApiClient.java'),
    (Join-Path $shared 'src\vn\vuumi\pcdash\net\PcnetBridge.java'),
    (Join-Path $shared 'src\vn\vuumi\pcdash\net\PcnetState.java'),
    (Join-Path $remote 'src\vn\vuumi\pcdash\remote\RemoteConfigStore.java'),
    (Join-Path $remote 'src\vn\vuumi\pcdash\remote\PairingClient.java'),
    (Join-Path $app 'test\vn\vuumi\pcdash\PairingE2eTest.java')
)

Write-Host "Compiling Pairing E2E tests and classes..."
& $javac -encoding UTF-8 -source 8 -target 8 -classpath "$classesDir;$androidJar" -sourcepath "$app\src;$app\test;$shared\src;$remote\src" -d $classesDir $sources
if ($LASTEXITCODE -ne 0) {
    throw "javac compilation failed"
}

Write-Host "Executing PairingE2eTest..."
& $java -cp $classesDir vn.vuumi.pcdash.PairingE2eTest
if ($LASTEXITCODE -ne 0) {
    throw "PairingE2eTest failed"
}

Write-Host "PASS: all pairing e2e tests passed!"
