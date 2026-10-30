param(
    [string] $RemoteApk,
    [string] $HostApk,
    # Builds made with -Ppcdash.skipNative=true have no native libraries.
    [switch] $NoNative
)

# Checks what the release APKs must contain and declare, whatever built them.
$ErrorActionPreference = "Stop"
. "$PSScriptRoot\BuildToolchain.ps1"
Use-PcdashJdk17
$tools = Get-PcdashToolchain (Split-Path $PSScriptRoot -Parent)
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Get-Entries([string] $Apk) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($Apk)
    try { return @($zip.Entries | ForEach-Object { $_.FullName }) } finally { $zip.Dispose() }
}

function Assert-NativeLibs([string] $Apk, [string[]] $Entries) {
    if ($NoNative) { return }
    foreach ($abi in @('arm64-v8a', 'armeabi-v7a')) {
        foreach ($lib in @('libpcnet.so', 'libpcnet_jni.so')) {
            if ($Entries -notcontains "lib/$abi/$lib") { throw "$Apk is missing lib/$abi/$lib" }
        }
    }
}

function Get-Manifest([string] $Apk) {
    $xmlTree = (& $tools.aapt2 dump xmltree $Apk --file AndroidManifest.xml 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) { throw "aapt2 dump xmltree failed for $Apk" }
    return $xmlTree
}

if ($RemoteApk) {
    $entries = Get-Entries $RemoteApk
    Assert-NativeLibs $RemoteApk $entries
    $xmlTree = Get-Manifest $RemoteApk
    if ($xmlTree -notmatch 'usesCleartextTraffic\(.*?\)=(?:true|0xffffffff)') {
        throw "Manifest check FAILED: missing android:usesCleartextTraffic='true'"
    }
    if ($xmlTree -notmatch 'allowBackup\(.*?\)=(?:false|0x0+)') {
        throw "Manifest check FAILED: missing android:allowBackup='false'"
    }
    # Native libraries are extracted at install (legacy packaging): the attribute is true or left out.
    if ($xmlTree -match 'extractNativeLibs\(.*?\)=(?:false|0x0+)') {
        throw "Manifest check FAILED: android:extractNativeLibs='false'"
    }
    if ($xmlTree -match 'VpnService') {
        throw "Manifest check FAILED: illegal VpnService declared"
    }
    # Nothing runs in the background on its own. Allowed services: the Quick Settings tile (System UI
    # binds it while the panel is open; it just opens the app) and the opt-in host-alert job
    # (JobScheduler runs it only after the user turns on alerts or new-version notices). No long-running
    # or foreground service.
    $serviceCount = ([regex]::Matches($xmlTree, 'E:\s+service\b')).Count
    $tileCount = ([regex]::Matches($xmlTree, 'permission\([^)]*\)="android\.permission\.BIND_QUICK_SETTINGS_TILE"')).Count
    $jobCount = ([regex]::Matches($xmlTree, 'permission\([^)]*\)="android\.permission\.BIND_JOB_SERVICE"')).Count
    if ($serviceCount -ne $tileCount + $jobCount) {
        throw "Manifest check FAILED: only Quick Settings tile and JobScheduler services are allowed ($serviceCount services, $tileCount tiles, $jobCount jobs)"
    }
    if ($xmlTree -match 'BOOT_COMPLETED|BootReceiver') {
        throw "Manifest check FAILED: illegal BootReceiver declared"
    }
    & $tools.apksigner verify $RemoteApk
    if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed for $RemoteApk" }
    Write-Host "Remote APK verified: $RemoteApk"
    Write-Host "  - cleartext to 127.0.0.1, no backup, native libraries extracted [OK]"
    Write-Host "  - No VpnService, BootReceiver or long-running Service ($tileCount tile, $jobCount job) [OK]"
}

if ($HostApk) {
    $entries = Get-Entries $HostApk
    Assert-NativeLibs $HostApk $entries
    foreach ($asset in @('assets/remote-update.apk', 'assets/remote-update.json', 'assets/dash.html')) {
        if ($entries -notcontains $asset) { throw "$HostApk is missing $asset" }
    }
    $zip = [System.IO.Compression.ZipFile]::OpenRead($HostApk)
    try {
        $reader = New-Object System.IO.StreamReader($zip.GetEntry('assets/remote-update.json').Open())
        $update = $reader.ReadToEnd() | ConvertFrom-Json
        $reader.Dispose()
        $sha = [System.Security.Cryptography.SHA256]::Create()
        $stream = $zip.GetEntry('assets/remote-update.apk').Open()
        $actual = -join ($sha.ComputeHash($stream) | ForEach-Object { $_.ToString('x2') })
        $stream.Dispose()
    } finally {
        $zip.Dispose()
    }
    if ($actual -ne $update.sha256) { throw "remote-update.json sha256 does not match the bundled remote APK" }
    & $tools.apksigner verify $HostApk
    if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed for $HostApk" }
    Write-Host "Host APK verified: $HostApk (bundles remote $($update.versionName), code $($update.versionCode))"
}
