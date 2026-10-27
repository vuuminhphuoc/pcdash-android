$ErrorActionPreference = "Stop"

if ($PSVersionTable.PSVersion.Major -lt 7 -and (Get-Command pwsh -ErrorAction SilentlyContinue)) {
    & pwsh -NoProfile -File $PSCommandPath @args
    exit $LASTEXITCODE
}

. "$PSScriptRoot\scripts\BuildToolchain.ps1"
Use-PcdashJdk17

. "$PSScriptRoot\scripts\BuildToolchain.ps1"
$tools = Get-PcdashToolchain $PSScriptRoot
$sdk = $tools.Sdk
$bt = Split-Path $tools.aapt2 -Parent
$app = Join-Path $PSScriptRoot 'app'
$build = Join-Path $app 'build'
$androidJar = $tools['android.jar']
$javac = $tools.javac
$keytool = $tools.keytool

$nativeOut = Join-Path $build 'native'

Write-Host "0. Building native libraries (arm64-v8a, armeabi-v7a)..."
& pwsh -NoProfile -File "$PSScriptRoot\pcnet\build-android.ps1" -OutputRoot $nativeOut
if ($LASTEXITCODE -ne 0) { throw "Native build failed" }

$requiredLibs = @(
    "arm64-v8a\libpcnet.so",
    "arm64-v8a\libpcnet_jni.so",
    "armeabi-v7a\libpcnet.so",
    "armeabi-v7a\libpcnet_jni.so"
)
foreach ($rel in $requiredLibs) {
    $p = Join-Path $nativeOut $rel
    if (-not (Test-Path $p)) {
        throw "Required native library missing: $p"
    }
}

$zxingJar = & "$PSScriptRoot\scripts\download-zxing.ps1"

New-Item -ItemType Directory -Path "$build\classes", "$build\dex" -Force | Out-Null
$versionPropsPath = Join-Path $app 'version.properties'
$versionCode = 1
$versionName = "1.0"
if (Test-Path $versionPropsPath) {
    Get-Content $versionPropsPath | ForEach-Object {
        if ($_ -match '^\s*versionCode\s*=\s*(\d+)') {
            $versionCode = [int]$matches[1]
        } elseif ($_ -match '^\s*versionName\s*=\s*(.+)$') {
            $versionName = $matches[1].Trim()
        }
    }
}
Write-Host "Host version: $versionName (code $versionCode)"

Write-Host "1. aapt2 link..."
& $tools.aapt2 compile --dir "$app\res" -o "$build\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }
& $tools.aapt2 link -o "$build\base.apk" --manifest "$app\AndroidManifest.xml" -I $androidJar -A "$app\assets" "$build\res.zip" --version-code $versionCode --version-name $versionName --replace-version
if ($LASTEXITCODE -ne 0) { throw "aapt2 failed" }

Write-Host "2. javac..."
$sources = @(
    Get-ChildItem "$app\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
    if (Test-Path "$PSScriptRoot\shared\src") {
        Get-ChildItem "$PSScriptRoot\shared\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
    }
)
& $javac -encoding UTF-8 -source 8 -target 8 -nowarn -classpath "$androidJar;$zxingJar" -d "$build\classes" $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "3. d8..."
$classFiles = Get-ChildItem "$build\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
$argsFile = "$build\d8_args.txt"
$classFiles += $zxingJar
$classFiles | Set-Content -Path $argsFile -Encoding UTF8
& $tools.d8 --release --min-api 26 --lib $androidJar --output "$build\dex" "@$argsFile"
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "4. assemble unsigned APK (dex + native libraries)..."
Copy-Item "$build\base.apk" "$build\unsigned.apk" -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$build\unsigned.apk", "Update")
$null = [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$build\dex\classes.dex", "classes.dex", [System.IO.Compression.CompressionLevel]::NoCompression)

# Add native libraries for both ABIs
$abis = @("arm64-v8a", "armeabi-v7a")
foreach ($abi in $abis) {
    $pcnetSo = Join-Path $nativeOut "$abi\libpcnet.so"
    $jniSo = Join-Path $nativeOut "$abi\libpcnet_jni.so"

    $null = [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip,
        $pcnetSo,
        "lib/$abi/libpcnet.so",
        [System.IO.Compression.CompressionLevel]::Optimal
    )
    $null = [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip,
        $jniSo,
        "lib/$abi/libpcnet_jni.so",
        [System.IO.Compression.CompressionLevel]::Optimal
    )
}
$zip.Dispose()
Write-Host "5. zipalign..."
& $tools.zipalign -f 4 "$build\unsigned.apk" "$build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "6. keystore..."
$ks = "$app\pcdash.keystore"
if (-not (Test-Path $ks)) {
    & $keytool -genkeypair -keystore $ks -alias pcdash -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname "CN=PCDash, O=VuuMi, C=VN"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

Write-Host "7. apksigner..."
& $tools.apksigner sign --ks $ks --ks-pass pass:android --key-pass pass:android --out "$build\pcdash-signed.apk" "$build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
& $tools.apksigner verify "$build\pcdash-signed.apk"
if ($LASTEXITCODE -ne 0) { throw "verify failed" }

Write-Host "APK Contents (Native Libraries & Dex):"
$verifyZip = [System.IO.Compression.ZipFile]::OpenRead("$build\pcdash-signed.apk")
foreach ($entry in $verifyZip.Entries) {
    if ($entry.FullName -match '^(lib/|classes\.dex|AndroidManifest\.xml)') {
        Write-Host "  - $($entry.FullName) ($($entry.Length) bytes, compressed: $($entry.CompressedLength) bytes)"
    }
}
$verifyZip.Dispose()

Write-Host ""
Write-Host "BUILD OK: $build\pcdash-signed.apk ($versionName / code $versionCode)"
Get-Item "$build\pcdash-signed.apk" | Select-Object FullName, @{n='SizeKB';e={[math]::Round($_.Length/1KB,1)}}
