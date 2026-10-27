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
$testBuild = Join-Path $app 'build\test-host-quota'
$classesDir = Join-Path $testBuild 'classes'

Remove-Item $testBuild -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $classesDir -Force | Out-Null

$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$javac = $tools.javac

$sources = @(
    (Join-Path $app 'src\vn\vuumi\pcdash\TimeSource.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\PcConn.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\MetricsJson.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\QuotaStore.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\PcQuotaClient.java'),
    (Join-Path $app 'test\vn\vuumi\pcdash\QuotaStoreTest.java'),
    (Join-Path $app 'test\vn\vuumi\pcdash\PcQuotaClientTest.java')
)

Write-Host "Compiling QuotaStoreTest and PcQuotaClientTest..."
& $javac -encoding UTF-8 -source 8 -target 8 -sourcepath "$app\src;$app\test" -d $classesDir $sources
if ($LASTEXITCODE -ne 0) {
    throw "javac compilation failed"
}

Write-Host "Executing QuotaStoreTest..."
& $java -cp $classesDir vn.vuumi.pcdash.QuotaStoreTest
if ($LASTEXITCODE -ne 0) {
    throw "QuotaStoreTest failed"
}

Write-Host "Executing PcQuotaClientTest..."
& $java -cp $classesDir vn.vuumi.pcdash.PcQuotaClientTest
if ($LASTEXITCODE -ne 0) {
    throw "PcQuotaClientTest failed"
}

Write-Host "PASS: host quota tests passed!"
