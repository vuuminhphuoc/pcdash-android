# Runs the Gradle wrapper with JDK 17 and the Android SDK the other build scripts use.
. "$PSScriptRoot\BuildToolchain.ps1"

function Invoke-PcdashGradle {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]] $GradleArgs)
    $repoRoot = Split-Path $PSScriptRoot -Parent
    Use-PcdashJdk17
    if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Get-PcdashSdk }
    $gradlew = if ($env:OS -eq 'Windows_NT') { Join-Path $repoRoot 'gradlew.bat' } else { Join-Path $repoRoot 'gradlew' }
    & $gradlew --console=plain @GradleArgs
    if ($LASTEXITCODE -ne 0) { throw "gradle $($GradleArgs -join ' ') failed" }
}

# Gradle's release APKs, copied to the paths the release and test scripts have always used.
function Copy-PcdashApks {
    param([switch] $Remote, [switch] $HostApp)
    $repoRoot = Split-Path $PSScriptRoot -Parent
    if ($Remote) {
        Copy-Item (Join-Path $repoRoot 'remote\build\outputs\apk\release\remote-release.apk') (Join-Path $repoRoot 'remote\build\pcdash-remote-signed.apk') -Force
    }
    if ($HostApp) {
        Copy-Item (Join-Path $repoRoot 'app\build\outputs\apk\release\app-release.apk') (Join-Path $repoRoot 'app\build\pcdash-signed.apk') -Force
    }
}
