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
$testBuild = Join-Path $app 'build\test-host-log-history'
$classesDir = Join-Path $testBuild 'classes'

Remove-Item $testBuild -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $classesDir -Force | Out-Null

$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$javac = $tools.javac

$sources = @(
    (Join-Path $app 'src\vn\vuumi\pcdash\CommandLog.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\MetricsHistory.java'),
    (Join-Path $app 'test\vn\vuumi\pcdash\CommandLogTest.java'),
    (Join-Path $app 'test\vn\vuumi\pcdash\MetricsHistoryTest.java')
)

Write-Host "Compiling CommandLogTest and MetricsHistoryTest..."
& $javac -encoding UTF-8 -source 8 -target 8 -sourcepath "$app\src;$app\test" -d $classesDir $sources
if ($LASTEXITCODE -ne 0) {
    throw "javac compilation failed"
}

Write-Host "Executing CommandLogTest..."
& $java -cp $classesDir vn.vuumi.pcdash.CommandLogTest
if ($LASTEXITCODE -ne 0) {
    throw "CommandLogTest failed"
}

Write-Host "Executing MetricsHistoryTest..."
& $java -cp $classesDir vn.vuumi.pcdash.MetricsHistoryTest
if ($LASTEXITCODE -ne 0) {
    throw "MetricsHistoryTest failed"
}

Write-Host "PASS: host log and metrics history tests passed!"
