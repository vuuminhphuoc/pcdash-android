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
$remote = Join-Path $repoRoot 'remote'
$shared = Join-Path $repoRoot 'shared'
$testBuild = Join-Path $remote 'build\test-remote-java'
$classesDir = Join-Path $testBuild 'classes'

Remove-Item $testBuild -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $classesDir -Force | Out-Null

$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$javac = $tools.javac
$androidJar = $tools['android.jar']
$sources = @(
    if (Test-Path "$remote\src") {
        Get-ChildItem "$remote\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
    }
    if (Test-Path "$shared\src") {
        Get-ChildItem "$shared\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
    }
    Get-ChildItem "$remote\test" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
    if (Test-Path "$shared\test") {
        Get-ChildItem "$shared\test" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
    }
)

Write-Host "Compiling Remote Java tests and classes..."
& $javac -encoding UTF-8 -source 8 -target 8 -classpath "$classesDir;$androidJar" -d $classesDir $sources
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

Write-Host "Executing L10nTest..."
& $java -cp $classesDir vn.vuumi.pcdash.i18n.L10nTest
if ($LASTEXITCODE -ne 0) {
    throw "L10nTest failed"
}

Write-Host "Executing AppLockTest..."
& $java -cp $classesDir vn.vuumi.pcdash.lock.AppLockTest
if ($LASTEXITCODE -ne 0) {
    throw "AppLockTest failed"
}

Write-Host "Executing RemoteTailnetTest..."
& $java -cp $classesDir vn.vuumi.pcdash.remote.RemoteTailnetTest
if ($LASTEXITCODE -ne 0) {
    throw "RemoteTailnetTest failed"
}

Write-Host "Executing RemoteControllerTest..."
& $java -cp $classesDir vn.vuumi.pcdash.remote.RemoteControllerTest
if ($LASTEXITCODE -ne 0) {
    throw "RemoteControllerTest failed"
}

Write-Host "Executing DiagnosticsFormatterTest..."
& $java -cp $classesDir vn.vuumi.pcdash.remote.DiagnosticsFormatterTest
if ($LASTEXITCODE -ne 0) {
    throw "DiagnosticsFormatterTest failed"
}

Write-Host "Executing MetricsParserTest..."
& $java -cp $classesDir vn.vuumi.pcdash.remote.MetricsParserTest
if ($LASTEXITCODE -ne 0) {
    throw "MetricsParserTest failed"
}

Write-Host "Executing MetricsFormatterTest..."
& $java -cp $classesDir vn.vuumi.pcdash.remote.MetricsFormatterTest
if ($LASTEXITCODE -ne 0) {
    throw "MetricsFormatterTest failed"
}

Write-Host "Executing MetricsApiTest..."
& $java -cp $classesDir vn.vuumi.pcdash.remote.MetricsApiTest
if ($LASTEXITCODE -ne 0) {
    throw "MetricsApiTest failed"
}

Write-Host "Executing UpdateInfoTest..."
& $java -cp $classesDir vn.vuumi.pcdash.remote.UpdateInfoTest
if ($LASTEXITCODE -ne 0) {
    throw "UpdateInfoTest failed"
}

Write-Host "Executing UpdateDownloadTest..."
& $java -cp $classesDir vn.vuumi.pcdash.remote.UpdateDownloadTest
if ($LASTEXITCODE -ne 0) {
    throw "UpdateDownloadTest failed"
}

Write-Host "Executing ApkInstallerTest..."
& $java -cp $classesDir vn.vuumi.pcdash.remote.ApkInstallerTest
if ($LASTEXITCODE -ne 0) {
    throw "ApkInstallerTest failed"
}
Write-Host "Executing ActionGuardTest..."
& $java -cp $classesDir vn.vuumi.pcdash.remote.ActionGuardTest
if ($LASTEXITCODE -ne 0) {
    throw "ActionGuardTest failed"
}
Write-Host "Executing LogParserTest..."
& $java -cp $classesDir vn.vuumi.pcdash.remote.LogParserTest
if ($LASTEXITCODE -ne 0) {
    throw "LogParserTest failed"
}
Write-Host "Executing HistoryParserTest..."
& $java -cp $classesDir vn.vuumi.pcdash.remote.HistoryParserTest
if ($LASTEXITCODE -ne 0) {
    throw "HistoryParserTest failed"
}
Write-Host "Executing QuotaParserTest..."
& $java -cp $classesDir vn.vuumi.pcdash.remote.QuotaParserTest
if ($LASTEXITCODE -ne 0) {
    throw "QuotaParserTest failed"
}
Write-Host "Executing QuotaFormatterTest..."
& $java -cp $classesDir vn.vuumi.pcdash.remote.QuotaFormatterTest
if ($LASTEXITCODE -ne 0) {
    throw "QuotaFormatterTest failed"
}
Write-Host "Executing QuotaViewModelTest..."
& $java -cp $classesDir vn.vuumi.pcdash.remote.QuotaViewModelTest
if ($LASTEXITCODE -ne 0) {
    throw "QuotaViewModelTest failed"
}
Write-Host "Executing TabNavigatorTest..."
& $java -cp $classesDir vn.vuumi.pcdash.remote.TabNavigatorTest
if ($LASTEXITCODE -ne 0) {
    throw "TabNavigatorTest failed"
}
Write-Host "Executing PollGenerationTest..."
& $java -cp $classesDir vn.vuumi.pcdash.remote.PollGenerationTest
if ($LASTEXITCODE -ne 0) {
    throw "PollGenerationTest failed"
}
Write-Host "Executing HistoryChartViewDataTest..."
& $java -cp "$classesDir;$androidJar" vn.vuumi.pcdash.remote.HistoryChartViewDataTest
if ($LASTEXITCODE -ne 0) {
    throw "HistoryChartViewDataTest failed"
}
Write-Host "Executing HostAlertRulesTest..."
& $java -cp $classesDir vn.vuumi.pcdash.remote.HostAlertRulesTest
if ($LASTEXITCODE -ne 0) {
    throw "HostAlertRulesTest failed"
}
Write-Host "Executing QuickActionsTest..."
& $java -cp $classesDir vn.vuumi.pcdash.remote.QuickActionsTest
if ($LASTEXITCODE -ne 0) {
    throw "QuickActionsTest failed"
}
Write-Host "Executing UiThreadSafetyTest..."
& $java -cp "$classesDir;$androidJar" vn.vuumi.pcdash.remote.UiThreadSafetyTest
if ($LASTEXITCODE -ne 0) {
    throw "UiThreadSafetyTest failed"
}
Write-Host "Executing UI thread safety lint..."
& (Join-Path $PSScriptRoot "lint-ui-thread.ps1")
if ($LASTEXITCODE -ne 0) {
    throw "UI thread safety lint failed"
}
Write-Host "PASS: all remote java tests passed!"
