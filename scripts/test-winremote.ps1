$ErrorActionPreference = "Stop"

# Tests of the Windows remote (winremote\): go vet, its unit tests, then a pairing with the real Java
# PairingManager over a socket, so the Go port cannot drift from the host byte for byte.

if ($PSVersionTable.PSVersion.Major -lt 7 -and (Get-Command pwsh -ErrorAction SilentlyContinue)) {
    & pwsh -NoProfile -File $PSCommandPath @args
    exit $LASTEXITCODE
}

. "$PSScriptRoot\BuildToolchain.ps1"
Use-PcdashJdk17
$repoRoot = Split-Path $PSScriptRoot -Parent
$tools = Get-PcdashToolchain $repoRoot
$app = Join-Path $repoRoot 'app'
$shared = Join-Path $repoRoot 'shared'
$win = Join-Path $repoRoot 'winremote'

$go = Join-Path $repoRoot "tools\go1.27.1\bin\go.exe"
if (-not (Test-Path $go)) { $go = (Get-Command go -ErrorAction Stop).Source }

Push-Location $win
try {
    Write-Host "go vet..."
    & $go vet ./...
    if ($LASTEXITCODE -ne 0) { throw "go vet failed" }
    $gofmt = Join-Path (Split-Path $go -Parent) 'gofmt.exe'
    if (Test-Path $gofmt) {
        $unformatted = & $gofmt -l .
        if ($unformatted) { throw "gofmt: these files need formatting: $($unformatted -join ', ')" }
    }
    Write-Host "go test..."
    & $go test -count=1 ./...
    if ($LASTEXITCODE -ne 0) { throw "go test failed" }
} finally {
    Pop-Location
}

# --- pairing with the Java host ---
$build = Join-Path $repoRoot 'build\test-winremote'
$classes = Join-Path $build 'classes'
Remove-Item $build -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $classes -Force | Out-Null

# The host accepts pairing only from a private (RFC 1918) peer address, never loopback, so the Go client
# connects to one of this machine's own private addresses.
$lanIp = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -match '^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)' -and $_.PrefixOrigin -ne 'WellKnown' } |
    Select-Object -First 1).IPAddress
if (-not $lanIp) {
    Write-Host "SKIPPED: the pairing check against the Java host needs a private IPv4 address on this machine (found none)."
    Write-Host "PASS: winremote unit tests passed (pairing with the Java host not run)."
    exit 0
}

$sources = @(
    "$shared\src\vn\vuumi\pcdash\pairing\PairingMessages.java",
    "$shared\src\vn\vuumi\pcdash\pairing\PairingProtocol.java",
    "$shared\src\vn\vuumi\pcdash\net\PcnetState.java",
    "$shared\src\vn\vuumi\pcdash\net\PcnetBridge.java",
    "$app\src\vn\vuumi\pcdash\LanAddressPolicy.java",
    "$app\src\vn\vuumi\pcdash\TimeSource.java",
    "$app\src\vn\vuumi\pcdash\HostTailnet.java",
    "$app\src\vn\vuumi\pcdash\PairingManager.java",
    "$app\src\vn\vuumi\pcdash\RemoteDeviceStore.java",
    "$app\test\vn\vuumi\pcdash\PairHostE2E.java"
)
& $tools.javac -encoding UTF-8 -source 8 -target 8 -nowarn -classpath "$classes;$($tools['android.jar'])" -sourcepath "$app\src;$app\test;$shared\src" -d $classes $sources 2>&1 | Where-Object { $_ -notmatch 'warning' }
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

$tokenFile = Join-Path $build 'token.txt'
$out = Join-Path $build 'java.out'
$err = Join-Path $build 'java.err'
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$hostProc = Start-Process -FilePath $java -ArgumentList @('-cp', $classes, 'vn.vuumi.pcdash.PairHostE2E', $tokenFile) `
    -RedirectStandardOutput $out -RedirectStandardError $err -PassThru -WindowStyle Hidden
try {
    $code = $null; $port = $null
    for ($i = 0; $i -lt 100 -and -not $code; $i++) {
        Start-Sleep -Milliseconds 200
        if (Test-Path $out) {
            $m = [regex]::Match((Get-Content $out -Raw -ErrorAction SilentlyContinue), 'CODE=(\w+) PORT=(\d+)')
            if ($m.Success) { $code = $m.Groups[1].Value; $port = $m.Groups[2].Value }
        }
    }
    if (-not $code) { throw "The Java pairing host did not start: $(Get-Content $err -Raw -ErrorAction SilentlyContinue)" }
    Write-Host "Java PairingManager ready on ${lanIp}:$port"

    $env:PCDASH_E2E_HOST = "${lanIp}:$port"
    $env:PCDASH_E2E_CODE = $code
    $env:PCDASH_E2E_TOKEN_FILE = $tokenFile
    Push-Location $win
    try {
        & $go test -count=1 -run TestE2EJavaHost -v ./...
        if ($LASTEXITCODE -ne 0) { throw "The Go client could not pair with the Java host" }
    } finally {
        Pop-Location
    }
    if (-not $hostProc.WaitForExit(30000)) { throw "The Java host did not finish" }
    $said = (Get-Content $out -Raw)
    Write-Host $said
    if ($said -notmatch 'IDENTIFIED') { throw "The host does not recognise the token the Go client received" }
    if ($hostProc.ExitCode -ne 0) { throw "The Java host exited with $($hostProc.ExitCode)" }
} finally {
    if (-not $hostProc.HasExited) { $hostProc.Kill() }
}

Write-Host "PASS: winremote unit tests and pairing with the Java host passed!"
