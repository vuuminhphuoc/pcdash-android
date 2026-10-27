$ErrorActionPreference = "Stop"

if ($PSVersionTable.PSVersion.Major -lt 7 -and (Get-Command pwsh -ErrorAction SilentlyContinue)) {
    & pwsh -NoProfile -File $PSCommandPath @args
    exit $LASTEXITCODE
}

. "$PSScriptRoot\BuildToolchain.ps1"
Use-PcdashJdk17

. "$PSScriptRoot\BuildToolchain.ps1"
$repoRoot = Split-Path $PSScriptRoot -Parent
$tools = Get-PcdashToolchain $repoRoot
$app = Join-Path $repoRoot 'app'
$agent = Join-Path $repoRoot 'agent'
$testBuild = Join-Path $app 'build\test-qr-roundtrip'
$classesDir = Join-Path $testBuild 'classes'
$pngDir = Join-Path $testBuild 'png'

Remove-Item $testBuild -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $classesDir, $pngDir -Force | Out-Null

$csc = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if (-not (Test-Path $csc)) { $csc = "C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe" }
if (-not (Test-Path $csc)) { throw "csc.exe not found (install .NET Framework)" }

$driver = Join-Path $testBuild 'QrDump.exe'
Write-Host "Compiling C# QR encoder + driver..."
& $csc /nologo /target:exe /r:System.Drawing.dll /out:$driver (Join-Path $agent 'QrCode.cs') (Join-Path $agent 'testdata\QrDump.cs')
if ($LASTEXITCODE -ne 0) { throw "csc failed" }

Write-Host "Rendering QR codes with the C# encoder..."
& $driver $pngDir
if ($LASTEXITCODE -ne 0) { throw "QrDump failed" }

$zxingJar = & "$PSScriptRoot\download-zxing.ps1"
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$javac = $tools.javac
$androidJar = $tools['android.jar']

$sources = @(
    (Join-Path $app 'src\vn\vuumi\pcdash\LanAddressPolicy.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\SecretCipher.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\AndroidSecretCipher.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\PcSettingsSource.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\PcSettings.java'),
    (Join-Path $app 'src\vn\vuumi\pcdash\PcProvisioning.java'),
    (Join-Path $repoRoot 'shared\src\vn\vuumi\pcdash\qr\QrDecoder.java'),
    (Join-Path $app 'test\vn\vuumi\pcdash\QrRoundTripTest.java')
)

Write-Host "Compiling Java decoder test..."
& $javac -encoding UTF-8 -source 8 -target 8 -nowarn -classpath "$androidJar;$zxingJar" -sourcepath "$repoRoot\app\src;$repoRoot\app\test;$repoRoot\shared\src" -d $classesDir $sources
if ($LASTEXITCODE -ne 0) { throw "javac compilation failed" }

Write-Host "Decoding with ZXing..."
& $java -cp "$classesDir;$androidJar;$zxingJar" vn.vuumi.pcdash.QrRoundTripTest $pngDir
if ($LASTEXITCODE -ne 0) { throw "QrRoundTripTest failed" }

Write-Host "PASS: C# QR encoder output decodes with the host's ZXing decoder!"
