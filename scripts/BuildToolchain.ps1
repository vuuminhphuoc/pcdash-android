$ErrorActionPreference = 'Stop'

function ConvertTo-GoVersion([string] $GoVersionOutput) {
    if ($GoVersionOutput -notmatch '\bgo([0-9]+\.[0-9]+(?:\.[0-9]+)?)\b') { throw "Unrecognized Go version output" }
    return [version]$Matches[1]
}
function Assert-MinimumGoVersion([version] $Actual, [version] $Minimum) {
    if ($Actual -lt $Minimum) { throw "Go $Actual is below required $Minimum" }
}
function Get-PcdashSdk {
    foreach ($candidate in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))) {
        if ($candidate -and (Test-Path $candidate)) { return (Resolve-Path $candidate).Path }
    }
    throw 'Android SDK not found. Install it or set ANDROID_HOME.'
}
function Get-PcdashNdk([string] $Version = '27.1.12297006') {
    $ndk = Join-Path (Get-PcdashSdk) "ndk\$Version"
    if (-not (Test-Path $ndk)) { throw "Android NDK $Version not found at $ndk. Install it with the SDK manager." }
    return $ndk
}
function Test-PcdashJdk17([string] $JdkPath) {
    if (-not $JdkPath) { return $false }
    $javaExe = Join-Path $JdkPath 'bin\java.exe'
    if (-not (Test-Path $javaExe)) { return $false }
    return ((& $javaExe -version 2>&1 | Out-String) -match 'version "17(?:\.|")')
}
# Points JAVA_HOME at a JDK 17: PCDASH_JAVA_HOME, then JAVA_HOME, then common install folders.
function Use-PcdashJdk17 {
    $candidates = @($env:PCDASH_JAVA_HOME, $env:JAVA_HOME)
    $roots = @((Join-Path $env:USERPROFILE '.jdks'), (Join-Path $env:ProgramFiles 'Java'), (Join-Path $env:ProgramFiles 'Eclipse Adoptium'), (Join-Path $env:ProgramFiles 'Microsoft'), (Join-Path $env:ProgramFiles 'Amazon Corretto'))
    foreach ($root in $roots) {
        if (Test-Path $root) {
            $candidates += @(Get-ChildItem $root -Directory | Where-Object { $_.Name -match '(?<![0-9])17(?![0-9])' } | Sort-Object Name -Descending | ForEach-Object { $_.FullName })
        }
    }
    foreach ($candidate in $candidates) {
        if (Test-PcdashJdk17 $candidate) { $env:JAVA_HOME = $candidate; return }
    }
    throw 'JDK 17 not found. Install it or set PCDASH_JAVA_HOME (or JAVA_HOME) to a JDK 17.'
}
function Get-PcdashToolchain([string] $RepoRoot) {
    $sdk = Get-PcdashSdk
    $bt = Join-Path $sdk 'build-tools\36.0.0'
    if (-not (Test-Path $bt)) {
        $newest = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory -ErrorAction SilentlyContinue | Sort-Object { [version]($_.Name -replace '[^0-9.].*$', '') } -Descending | Select-Object -First 1
        if ($newest) { $bt = $newest.FullName }
    }
    $java = Join-Path $env:JAVA_HOME 'bin'
    $javac = Join-Path $java 'javac.exe'
    if (-not (Test-Path $javac)) { throw "javac not found under JAVA_HOME" }
    $versionText = (& (Join-Path $java 'java.exe') -version 2>&1 | Out-String)
    if ($versionText -notmatch 'version "17(?:\.|")') { throw 'JDK 17 is required' }
    $platform = Join-Path $sdk 'platforms\android-36\android.jar'
    $paths = [ordered]@{ Sdk=$sdk; aapt2=(Join-Path $bt 'aapt2.exe'); d8=(Join-Path $bt 'd8.bat'); zipalign=(Join-Path $bt 'zipalign.exe'); apksigner=(Join-Path $bt 'apksigner.bat'); 'android.jar'=$platform; javac=$javac; keytool=(Join-Path $java 'keytool.exe') }
    foreach ($key in @('aapt2','d8','zipalign','apksigner','android.jar','javac','keytool')) { if (-not (Test-Path $paths[$key])) { throw "Missing Android/JDK tool: $key" } }
    return $paths
}
