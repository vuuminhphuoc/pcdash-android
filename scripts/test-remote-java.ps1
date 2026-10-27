$ErrorActionPreference = "Stop"

if ($PSVersionTable.PSVersion.Major -lt 7 -and (Get-Command pwsh -ErrorAction SilentlyContinue)) {
    & pwsh -NoProfile -File $PSCommandPath @args
    exit $LASTEXITCODE
}

# The remote's and the shared module's tests, through Gradle (the remote may hold Kotlin, which plain
# javac cannot compile). Each test class runs as one JUnit test (RemoteMainTests, SharedMainTests).
. "$PSScriptRoot\Gradle.ps1"
Invoke-PcdashGradle ':shared:testDebugUnitTest' ':remote:testDebugUnitTest' '-Ppcdash.skipNative=true'

Write-Host "Executing UI thread safety lint..."
& (Join-Path $PSScriptRoot "lint-ui-thread.ps1")
if ($LASTEXITCODE -ne 0) {
    throw "lint-ui-thread failed"
}
Write-Host "PASS: remote and shared tests"
