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
$testBuild = Join-Path $app 'build\test-host-pcs'
$classesDir = Join-Path $testBuild 'classes'

Remove-Item $testBuild -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $classesDir -Force | Out-Null

$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$javac = $tools.javac
$androidJar = $tools['android.jar']

$sources = @(
    (Join-Path $app 'src\vn\vuumi\pcdash\TimeSource.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\PcConn.java'),
    (Join-Path $app 'test\vn\vuumi\pcdash\PcConnProbeTest.java'),
    (Join-Path $app 'test\vn\vuumi\pcdash\PcListTest.java')
)

Write-Host "Compiling PC connection tests..."
& $javac -encoding UTF-8 -source 8 -target 8 -classpath $androidJar -sourcepath "$app\src;$app\test;$repoRoot\shared\src" -d $classesDir $sources
if ($LASTEXITCODE -ne 0) {
    throw "javac compilation failed"
}

Write-Host "Executing PcConnProbeTest..."
& $java -cp $classesDir vn.vuumi.pcdash.PcConnProbeTest
if ($LASTEXITCODE -ne 0) {
    throw "PcConnProbeTest failed"
}

Write-Host "Executing PcListTest..."
& $java -cp "$classesDir;$androidJar" vn.vuumi.pcdash.PcListTest
if ($LASTEXITCODE -ne 0) {
    throw "PcListTest failed"
}

Write-Host "PASS: host PC connection tests passed!"
