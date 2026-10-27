param(
    [string]$OutputRoot = "build\test-libs"
)

$ErrorActionPreference = 'Stop'

$scriptDir = $PSScriptRoot
$repoRoot = (Resolve-Path "$scriptDir\..").Path

$goExe = Join-Path $repoRoot "tools\go1.27.1\bin\go.exe"
if (-not (Test-Path $goExe)) {
    throw "Go 1.27.1 not found at $goExe"
}

. "$repoRoot\scripts\BuildToolchain.ps1"
$ndkRoot = Get-PcdashNdk

$llvmBin = Join-Path $ndkRoot "toolchains\llvm\prebuilt\windows-x86_64\bin"
$readelf = Join-Path $llvmBin "llvm-readelf.exe"
if (-not (Test-Path $readelf)) {
    throw "llvm-readelf not found at $readelf"
}

$outDir = [System.IO.Path]::GetFullPath($OutputRoot)

$targets = @(
    @{
        Abi = "arm64-v8a"
        Arch = "arm64"
        Arm = $null
        Clang = "aarch64-linux-android24-clang.cmd"
        ExpectedMachine = "AArch64"
    },
    @{
        Abi = "armeabi-v7a"
        Arch = "arm"
        Arm = "7"
        Clang = "armv7a-linux-androideabi24-clang.cmd"
        ExpectedMachine = "ARM"
    }
)

Push-Location $scriptDir
try {
    foreach ($target in $targets) {
        $abiDir = Join-Path $outDir $target.Abi
        if (-not (Test-Path $abiDir)) {
            New-Item -ItemType Directory -Path $abiDir -Force | Out-Null
        }

        $outFile = Join-Path $abiDir "libpcnet.so"
        $clangPath = Join-Path $llvmBin $target.Clang
        if (-not (Test-Path $clangPath)) {
            throw "Clang not found for $($target.Abi) at $clangPath"
        }

        $env:GOOS = "android"
        $env:GOARCH = $target.Arch
        if ($target.Arm) {
            $env:GOARM = $target.Arm
        } else {
            Remove-Item env:\GOARM -ErrorAction SilentlyContinue
        }
        $env:CGO_ENABLED = "1"
        $env:CC = $clangPath

        Write-Host "Building libpcnet.so for $($target.Abi)..."
        & $goExe build -trimpath -ldflags="-s -w -checklinkname=0" -buildmode=c-shared -o $outFile .
        if ($LASTEXITCODE -ne 0) {
            throw "Build failed for $($target.Abi) with code $LASTEXITCODE"
        }

        if (-not (Test-Path $outFile)) {
            throw "Output file not found: $outFile"
        }

        $elfHeader = (& $readelf -h $outFile 2>&1 | Out-String)
        if ($elfHeader -notmatch "Machine:\s+.*$($target.ExpectedMachine)") {
            throw "llvm-readelf did not match expected machine $($target.ExpectedMachine) for $($target.Abi)`n$elfHeader"
        }

        $item = Get-Item $outFile
        Write-Host "$($target.Abi) libpcnet.so size: $($item.Length) bytes (Machine: $($target.ExpectedMachine) verified)"
        $jniSrc = Join-Path $repoRoot "remote\jni\pcnet_jni.c"
        if (Test-Path $jniSrc) {
            $outJni = Join-Path $abiDir "libpcnet_jni.so"
            Write-Host "Building libpcnet_jni.so for $($target.Abi)..."
            & $clangPath -shared -fPIC -O2 -Wall "-L$abiDir" -lpcnet -o $outJni $jniSrc
            if ($LASTEXITCODE -ne 0) {
                throw "Clang failed to build libpcnet_jni.so for $($target.Abi)"
            }
            if (-not (Test-Path $outJni)) {
                throw "Output JNI file not found: $outJni"
            }
            $elfJniHeader = (& $readelf -h $outJni 2>&1 | Out-String)
            if ($elfJniHeader -notmatch "Machine:\s+.*$($target.ExpectedMachine)") {
                throw "llvm-readelf did not match expected machine $($target.ExpectedMachine) for JNI $($target.Abi)`n$elfJniHeader"
            }
            $jniItem = Get-Item $outJni
            Write-Host "$($target.Abi) libpcnet_jni.so size: $($jniItem.Length) bytes (Machine: $($target.ExpectedMachine) verified)"
        }
    }
} finally {
    Remove-Item env:\GOOS -ErrorAction SilentlyContinue
    Remove-Item env:\GOARCH -ErrorAction SilentlyContinue
    Remove-Item env:\GOARM -ErrorAction SilentlyContinue
    Remove-Item env:\CGO_ENABLED -ErrorAction SilentlyContinue
    Remove-Item env:\CC -ErrorAction SilentlyContinue
    Pop-Location
}
