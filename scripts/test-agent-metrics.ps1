# scripts/test-agent-metrics.ps1
# PHẦN A: Kiểm tra GET /metrics từ PcAgent
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path "$PSScriptRoot\..").Path
$buildDir = Join-Path $repoRoot "agent\build"
$exePath = Join-Path $buildDir "PcAgent.exe"
$buildScript = Join-Path $repoRoot "agent\build_agent.ps1"

Write-Host "=== 1. Building PcAgent into fixed directory: $buildDir ==="
if (-not (Test-Path $buildDir)) {
    New-Item -ItemType Directory -Path $buildDir -Force | Out-Null
}

& $buildScript -Out $exePath
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $exePath)) {
    throw "Build failed: $exePath not found"
}
Write-Host "Build success: $exePath"

# A test agent must never go to GitHub for a real update; the self-update test turns this off for one agent.
$env:PCAGENT_NO_UPDATE = "1"

$testPort = 18799
$testToken = "test-token-0123456789abcdef"
$auth = @{ "X-PcAgent-Token" = $testToken }

function Get-HttpStatus {
    param([string]$Uri, [hashtable]$Headers = @{}, [string]$Method = "GET", [string]$Body = $null)
    try {
        if ($Body) {
            $r = Invoke-WebRequest -Uri $Uri -Method $Method -Headers $Headers -Body $Body -UseBasicParsing -TimeoutSec 5
        } else {
            $r = Invoke-WebRequest -Uri $Uri -Method $Method -Headers $Headers -UseBasicParsing -TimeoutSec 5
        }
        return [int]$r.StatusCode
    } catch {
        if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }
        throw
    }
}
function New-SignedHeaders {
    param([string]$Token, [string]$Method, [string]$Target, [string]$Body = "", [long]$Ts = 0, [string]$Nonce = "")
    if ($Ts -eq 0) { $Ts = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() }
    if (-not $Nonce) { $Nonce = [guid]::NewGuid().ToString("N") }
    $data = "$Ts`n$Nonce`n$Method`n$Target`n$Body"
    $h = New-Object System.Security.Cryptography.HMACSHA256 (,[Text.Encoding]::UTF8.GetBytes($Token))
    $sig = -join ($h.ComputeHash([Text.Encoding]::UTF8.GetBytes($data)) | ForEach-Object { $_.ToString("x2") })
    return @{ "X-PcAgent-Ts" = "$Ts"; "X-PcAgent-Nonce" = $Nonce; "X-PcAgent-Sig" = $sig }
}

Write-Host "=== 2. Starting test PcAgent on port $testPort (local bind) ==="
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $exePath
$psi.WorkingDirectory = $buildDir
$psi.UseShellExecute = $false
$psi.CreateNoWindow = $true
$psi.EnvironmentVariables["PCAGENT_PORT"] = "$testPort"
$psi.EnvironmentVariables["PCAGENT_TOKEN"] = $testToken
$psi.EnvironmentVariables["PCAGENT_BIND_LOCAL"] = "1"
$testTempFile = Join-Path $buildDir "test-cputemp.txt"
if (Test-Path $testTempFile) { Remove-Item $testTempFile -Force }
$psi.EnvironmentVariables["PCAGENT_CPUTEMP_FILE"] = "$testTempFile"
$fakeOmpCmd = Join-Path $repoRoot "agent\testdata\fake-omp.cmd"
$fakeOmpCountFile = Join-Path $repoRoot "agent\testdata\fake-omp-count.txt"
if (Test-Path $fakeOmpCountFile) { Remove-Item $fakeOmpCountFile -Force }
$psi.EnvironmentVariables["PCAGENT_OMP_PATH"] = $fakeOmpCmd

$proc = [System.Diagnostics.Process]::Start($psi)
if (-not $proc -or $proc.HasExited) {
    throw "Failed to start PcAgent on port $testPort"
}
$targetPid = $proc.Id
Write-Host "Process started, PID = $targetPid (real agent on 8799 will NOT be touched)"

try {
    $url = "http://127.0.0.1:$testPort/metrics"

    # Wait until the agent accepts connections, so the timing below measures /quota itself rather
    # than Windows retrying refused connects while the process is still starting (seen as ~3 s on CI).
    # /healthz needs no token and does not start the lazy quota fetch.
    $ready = $false
    for ($i = 0; $i -lt 50 -and -not $ready; $i++) {
        try {
            $null = Invoke-WebRequest -Uri "http://127.0.0.1:$testPort/healthz" -UseBasicParsing -TimeoutSec 2
            $ready = $true
        } catch {
            Start-Sleep -Milliseconds 200
        }
    }
    if (-not $ready) {
        throw "PcAgent did not answer /healthz on port $testPort"
    }

    $quotaUrl = "http://127.0.0.1:$testPort/quota"
    Write-Host "=== 3.0 Immediate check of /quota right after startup (< 1 s) ==="
    $quotaSw = [System.Diagnostics.Stopwatch]::StartNew()
    $initQuotaResp = Invoke-WebRequest -Headers $auth -Uri $quotaUrl -UseBasicParsing -TimeoutSec 3
    $quotaSw.Stop()
    if ($quotaSw.ElapsedMilliseconds -gt 1000) {
        throw "Immediate /quota call took > 1s ($($quotaSw.ElapsedMilliseconds) ms)"
    }
    if ($initQuotaResp.StatusCode -ne 200) {
        throw "Immediate /quota check expected 200, got $($initQuotaResp.StatusCode)"
    }
    $initQuotaData = $initQuotaResp.Content | ConvertFrom-Json
    if (-not $initQuotaData.loading) {
        throw "Immediate /quota before cache must return loading:true, got: $($initQuotaResp.Content)"
    }
    Write-Host "Immediate /quota check PASS: returned loading:true in $($quotaSw.ElapsedMilliseconds) ms"

    Write-Host "=== 3. Immediate check of /metrics right after startup (< 1 s) ==="
    Start-Sleep -Milliseconds 250
    if ($proc.HasExited) {
        throw "PcAgent PID $targetPid exited unexpectedly with code $($proc.ExitCode)"
    }
    $initResp = Invoke-WebRequest -Headers $auth -Uri $url -UseBasicParsing -TimeoutSec 3
    if ($initResp.StatusCode -ne 200) {
        throw "Immediate /metrics check expected 200, got $($initResp.StatusCode)"
    }
    $initData = $initResp.Content | ConvertFrom-Json
    if ($null -eq $initData) {
        throw "Failed to parse immediate /metrics JSON"
    }
    $initTopKeys = @($initData.PSObject.Properties.Name)
$requiredTopKeys = @("cpu", "sys", "ram", "gpu", "net", "disks", "uptimeSec")
foreach ($k in $requiredTopKeys) {
    if ($initTopKeys -notcontains $k) {
        throw "Immediate /metrics missing schema key: '$k'"
    }
}
if ($null -eq $initData.sys) { throw "Immediate /metrics: sys is null" }
if (@($initData.sys.PSObject.Properties.Name) -notcontains "tempC") { throw "Immediate /metrics: Missing sys property 'tempC'" }
    if ($initTopKeys -contains "online") {
        throw "Schema violation: 'online' key MUST NOT be present in PcAgent /metrics"
    }
    if ($null -eq $initData.cpu) { throw "Immediate /metrics: cpu is null" }
    foreach ($k in @("percent", "tempC", "name")) {
        if (@($initData.cpu.PSObject.Properties.Name) -notcontains $k) { throw "Immediate /metrics: Missing cpu property '$k'" }
    }
    if ($null -eq $initData.ram) { throw "Immediate /metrics: ram is null" }
    foreach ($k in @("usedMB", "totalMB", "percent")) {
        if (@($initData.ram.PSObject.Properties.Name) -notcontains $k) { throw "Immediate /metrics: Missing ram property '$k'" }
    }
    if ($null -eq $initData.gpu) { throw "Immediate /metrics: gpu is null" }
    foreach ($k in @("percent", "tempC", "memUsedMB", "memTotalMB", "name")) {
        if (@($initData.gpu.PSObject.Properties.Name) -notcontains $k) { throw "Immediate /metrics: Missing gpu property '$k'" }
    }
    if ($null -eq $initData.net) { throw "Immediate /metrics: net is null" }
    foreach ($k in @("downBps", "upBps")) {
        if (@($initData.net.PSObject.Properties.Name) -notcontains $k) { throw "Immediate /metrics: Missing net property '$k'" }
    }
    if (-not ($initData.disks -is [System.Array])) {
        throw "Immediate /metrics: disks must be an array"
    }
    if (@($initData.PSObject.Properties.Name) -notcontains "uptimeSec") {
        throw "Immediate /metrics: Missing uptimeSec property"
    }
    Write-Host "Immediate /metrics check PASS: valid JSON with all required keys before sampling"

    Write-Host "=== 4. Waiting for performance counters & sampling to populate ==="
    $deadline = (Get-Date).AddSeconds(15)
    $resp = $null
    $data = $null
    while ((Get-Date) -lt $deadline) {
        if ($proc.HasExited) {
            throw "PcAgent PID $targetPid exited unexpectedly with code $($proc.ExitCode)"
        }
        try {
            $resp = Invoke-WebRequest -Headers $auth -Uri $url -UseBasicParsing -TimeoutSec 5
            if ($resp.StatusCode -eq 200) {
                $tempData = $resp.Content | ConvertFrom-Json
                if ($tempData -and $null -ne $tempData.cpu -and $null -ne $tempData.cpu.percent) {
                    $data = $tempData
                    break
                }
            }
        } catch {}
        Start-Sleep -Milliseconds 500
    }
    if ($null -eq $data) {
        if ($resp) {
            Write-Host "Last received content: $($resp.Content)"
            $data = $resp.Content | ConvertFrom-Json
        }
        if ($null -eq $data -or $null -eq $data.cpu -or $null -eq $data.cpu.percent) {
            throw "Timed out waiting for cpu.percent to be populated in /metrics"
        }
    }
    $rawJson = $resp.Content
    Write-Host "Received populated JSON response:"
    Write-Host $rawJson
    Write-Host "=== 5. Running schema and value assertions ==="

    # Check top-level keys
    $topKeys = @($data.PSObject.Properties.Name)
    Write-Host "Top-level keys: $($topKeys -join ', ')"

$requiredTopKeys = @("cpu", "sys", "ram", "gpu", "net", "disks", "uptimeSec")
foreach ($k in $requiredTopKeys) {
    if ($topKeys -notcontains $k) {
        throw "Missing top-level schema key: '$k'"
    }
}

    # Inviolable requirement: NO "online" key in agent /metrics
    if ($topKeys -contains "online") {
        throw "Schema violation: 'online' key MUST NOT be present in PcAgent /metrics"
    }

    # 5.1 CPU assertions
    if ($null -eq $data.cpu) { throw "cpu object is null" }
    $cpuKeys = @($data.cpu.PSObject.Properties.Name)
    foreach ($k in @("percent", "tempC", "name")) {
        if ($cpuKeys -notcontains $k) { throw "Missing cpu property '$k'" }
    }
    if ($null -eq $data.cpu.percent) {
        throw "cpu.percent must not be null"
    }
    $cpuPct = [double]$data.cpu.percent
    if ($cpuPct -lt 0.0 -or $cpuPct -gt 100.0) {
        throw "cpu.percent out of range 0..100: $cpuPct"
    }
    Write-Host "Assertion PASS: cpu.percent is $cpuPct (0..100), name='$($data.cpu.name)', tempC=$($data.cpu.tempC)"

    # 5.1b SYS assertions
    if ($null -eq $data.sys) { throw "sys object is null" }
    $sysKeys = @($data.sys.PSObject.Properties.Name)
    if ($sysKeys -notcontains "tempC") { throw "Missing sys property 'tempC'" }
    if ($null -ne $data.sys.tempC) {
        $sysTemp = [double]$data.sys.tempC
        if ($sysTemp -lt -50.0 -or $sysTemp -gt 150.0) {
            throw "sys.tempC out of realistic range -50..150: $sysTemp"
        }
    }
    Write-Host "Assertion PASS: sys.tempC=$($data.sys.tempC)"

    # 5.2 RAM assertions
    if ($null -eq $data.ram) { throw "ram object is null" }
    $ramKeys = @($data.ram.PSObject.Properties.Name)
    foreach ($k in @("usedMB", "totalMB", "percent")) {
        if ($ramKeys -notcontains $k) { throw "Missing ram property '$k'" }
    }
    if ([long]$data.ram.totalMB -le 0) {
        throw "ram.totalMB must be > 0, got $($data.ram.totalMB)"
    }
    $ramPct = [double]$data.ram.percent
    if ($ramPct -lt 0.0 -or $ramPct -gt 100.0) {
        throw "ram.percent out of range 0..100: $ramPct"
    }
    Write-Host "Assertion PASS: ram.totalMB=$($data.ram.totalMB), ram.usedMB=$($data.ram.usedMB), ram.percent=$ramPct"

    # 5.3 GPU assertions
    if ($null -ne $data.gpu) {
        $gpuKeys = @($data.gpu.PSObject.Properties.Name)
        foreach ($k in @("percent", "tempC", "memUsedMB", "memTotalMB", "name")) {
            if ($gpuKeys -notcontains $k) { throw "Missing gpu property '$k'" }
        }
        Write-Host "Assertion PASS: gpu keys consistent. name='$($data.gpu.name)', pct=$($data.gpu.percent), tempC=$($data.gpu.tempC), memUsed=$($data.gpu.memUsedMB)/$($data.gpu.memTotalMB)"
    }

    # 5.4 Net assertions
    if ($null -eq $data.net) { throw "net object is null" }
    $netKeys = @($data.net.PSObject.Properties.Name)
    foreach ($k in @("downBps", "upBps")) {
        if ($netKeys -notcontains $k) { throw "Missing net property '$k'" }
    }
    if ([long]$data.net.downBps -lt 0) {
        throw "net.downBps must be >= 0, got $($data.net.downBps)"
    }
    if ([long]$data.net.upBps -lt 0) {
        throw "net.upBps must be >= 0, got $($data.net.upBps)"
    }
    Write-Host "Assertion PASS: net.downBps=$($data.net.downBps), net.upBps=$($data.net.upBps)"

    # 5.5 Disks assertions
    if (-not ($data.disks -is [System.Array])) {
        throw "disks must be an array"
    }
    foreach ($disk in $data.disks) {
        $dKeys = @($disk.PSObject.Properties.Name)
        foreach ($k in @("name", "usedGB", "totalGB")) {
            if ($dKeys -notcontains $k) { throw "Missing disk property '$k'" }
        }
        if ([double]$disk.totalGB -lt 0.0 -or [double]$disk.usedGB -lt 0.0) {
            throw "Invalid disk size in disk $($disk.name)"
        }
        Write-Host "  Disk $($disk.name): used $($disk.usedGB) GB / total $($disk.totalGB) GB"
    }
    Write-Host "Assertion PASS: disks is array with $($data.disks.Count) drives"

    # 5.6 Uptime assertions
    if ([long]$data.uptimeSec -le 0) {
        throw "uptimeSec must be > 0, got $($data.uptimeSec)"
    }
    Write-Host "Assertion PASS: uptimeSec=$($data.uptimeSec)"

    # 5.7 Existing routes intact
    $hz = (Invoke-WebRequest -Uri "http://localhost:$testPort/healthz" -UseBasicParsing -TimeoutSec 5).Content
    if ($hz.Trim() -ne "ok") {
        throw "Existing route /healthz failed: got '$hz', expected 'ok'"
    }
    $pingResp = (Invoke-WebRequest -Headers $auth -Uri "http://localhost:$testPort/exec" -Method Post -Body '{"action":"ping"}' -UseBasicParsing -TimeoutSec 5).Content
    if ($pingResp.Trim() -ne "pong") {
        throw "Existing route /exec ping failed: got '$pingResp', expected 'pong'"
    }
    Write-Host "Assertion PASS: /healthz = ok and /exec ping = pong"

    Write-Host "=== 5.x Token enforcement ==="
    $base = "http://localhost:$testPort"
    $badAuth = @{ "X-PcAgent-Token" = "wrong-token-0123456789abcdef" }
    foreach ($route in @("/metrics", "/quota")) {
        $code = Get-HttpStatus -Uri "$base$route"
        if ($code -ne 403) { throw "$route without token expected 403, got $code" }
        $code = Get-HttpStatus -Uri "$base$route" -Headers $badAuth
        if ($code -ne 403) { throw "$route with wrong token expected 403, got $code" }
    }
    $code = Get-HttpStatus -Uri "$base/exec" -Method Post -Body '{"action":"ping"}'
    if ($code -ne 403) { throw "/exec without token expected 403, got $code" }
    $code = Get-HttpStatus -Uri "$base/exec" -Method Post -Body '{"action":"ping"}' -Headers $badAuth
    if ($code -ne 403) { throw "/exec with wrong token expected 403, got $code" }
    $code = Get-HttpStatus -Uri "$base/healthz"
    if ($code -ne 200) { throw "/healthz must stay open, got $code" }
    Write-Host "Assertion PASS: missing or wrong token gets 403 on /metrics, /quota, /exec; /healthz stays open"

    Write-Host "=== 5.x2 Signed requests (HMAC) ==="
    $okSig = New-SignedHeaders -Token $testToken -Method "GET" -Target "/metrics"
    $code = Get-HttpStatus -Uri "$base/metrics" -Headers $okSig
    if ($code -ne 200) { throw "Signed GET /metrics expected 200, got $code" }
    $code = Get-HttpStatus -Uri "$base/metrics" -Headers $okSig
    if ($code -ne 403) { throw "Replayed signed request expected 403, got $code" }
    $quotaTarget = "/quota?refresh=0"
    $code = Get-HttpStatus -Uri "$base$quotaTarget" -Headers (New-SignedHeaders -Token $testToken -Method "GET" -Target $quotaTarget)
    if ($code -ne 200) { throw "Signed GET with a query expected 200 (the query is part of the signature), got $code" }
    $execBody = '{"action":"ping"}'
    $code = Get-HttpStatus -Uri "$base/exec" -Method Post -Body $execBody -Headers (New-SignedHeaders -Token $testToken -Method "POST" -Target "/exec" -Body $execBody)
    if ($code -ne 200) { throw "Signed POST /exec expected 200, got $code" }
    $code = Get-HttpStatus -Uri "$base/exec" -Method Post -Body '{"action":"sleep"}' -Headers (New-SignedHeaders -Token $testToken -Method "POST" -Target "/exec" -Body $execBody)
    if ($code -ne 403) { throw "A signature over another body expected 403, got $code" }
    $code = Get-HttpStatus -Uri "$base/metrics" -Headers (New-SignedHeaders -Token "wrong-token-0123456789abcdef" -Method "GET" -Target "/metrics")
    if ($code -ne 403) { throw "Signature with the wrong token expected 403, got $code" }
    $code = Get-HttpStatus -Uri "$base/metrics" -Headers (New-SignedHeaders -Token $testToken -Method "GET" -Target "/quota")
    if ($code -ne 403) { throw "A signature for another target expected 403, got $code" }
    $old = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() - 3600
    $code = Get-HttpStatus -Uri "$base/metrics" -Headers (New-SignedHeaders -Token $testToken -Method "GET" -Target "/metrics" -Ts $old)
    if ($code -ne 401) { throw "A signature an hour old expected 401 (clock skew), got $code" }
    Write-Host "Assertion PASS: signed requests work; replay, wrong token, other body/target get 403; stale time gets 401"

    Write-Host "=== 5.x3 Strict mode refuses the plain token ==="
    $strictFile = Join-Path $buildDir "strict"
    Set-Content -Path $strictFile -Value "1"
    try {
        $code = Get-HttpStatus -Uri "$base/metrics" -Headers $auth
        if ($code -ne 403) { throw "Plain token with strict mode expected 403, got $code" }
        $code = Get-HttpStatus -Uri "$base/metrics" -Headers (New-SignedHeaders -Token $testToken -Method "GET" -Target "/metrics")
        if ($code -ne 200) { throw "Signed request with strict mode expected 200, got $code" }
    } finally {
        Remove-Item $strictFile -Force -ErrorAction SilentlyContinue
    }
    $code = Get-HttpStatus -Uri "$base/metrics" -Headers $auth
    if ($code -ne 200) { throw "Plain token without strict mode expected 200, got $code" }
    Write-Host "Assertion PASS: strict file turns the plain token off, removing it turns it back on"

    Write-Host "=== 5.x4 Repeated failures lock a client out ==="
    $lockPort = 18804
    $psiLock = New-Object System.Diagnostics.ProcessStartInfo
    $psiLock.FileName = $exePath
    $psiLock.WorkingDirectory = $buildDir
    $psiLock.UseShellExecute = $false
    $psiLock.CreateNoWindow = $true
    $psiLock.EnvironmentVariables["PCAGENT_PORT"] = "$lockPort"
    $psiLock.EnvironmentVariables["PCAGENT_TOKEN"] = $testToken
    $psiLock.EnvironmentVariables["PCAGENT_BIND_LOCAL"] = "1"
    $psiLock.EnvironmentVariables["PCAGENT_TEST_LOCK_LOOPBACK"] = "1"
    $procLock = [System.Diagnostics.Process]::Start($psiLock)
    try {
        Start-Sleep -Seconds 2
        for ($i = 1; $i -le 5; $i++) {
            $code = Get-HttpStatus -Uri "http://localhost:$lockPort/metrics" -Headers $badAuth
            if ($code -ne 403) { throw "Failure $i expected 403, got $code" }
        }
        $code = Get-HttpStatus -Uri "http://localhost:$lockPort/metrics" -Headers $badAuth
        if ($code -ne 429) { throw "Sixth request after 5 failures expected 429, got $code" }
        $code = Get-HttpStatus -Uri "http://localhost:$lockPort/metrics" -Headers $auth
        if ($code -ne 429) { throw "Even the right token is refused while locked, expected 429, got $code" }
        $code = Get-HttpStatus -Uri "http://localhost:$lockPort/healthz"
        if ($code -ne 200) { throw "/healthz must stay open while locked, got $code" }
        Write-Host "Assertion PASS: 5 failures lock the client (429) and /healthz stays open"
    } finally {
        if ($procLock -and -not $procLock.HasExited) { Stop-Process -Id $procLock.Id -Force -ErrorAction SilentlyContinue }
    }

    Write-Host "=== 5.x5 Self-update from a (local) release ==="
    $updRoot = Join-Path $buildDir "upd"
    if (Test-Path $updRoot) { Remove-Item $updRoot -Recurse -Force }
    New-Item -ItemType Directory -Path $updRoot | Out-Null
    $oldDir = Join-Path $updRoot "old"
    $newDir = Join-Path $updRoot "new"
    $site = Join-Path $updRoot "site"
    New-Item -ItemType Directory -Path $oldDir, $newDir, (Join-Path $site "zipstage\build") | Out-Null
    & $buildScript -Out (Join-Path $oldDir "PcAgent.exe") -Version 1.0.0 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Build of the 1.0.0 test agent failed" }
    & $buildScript -Out (Join-Path $newDir "PcAgent.exe") -Version 1.0.1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Build of the 1.0.1 test agent failed" }
    Copy-Item (Join-Path $newDir "PcAgent.exe") (Join-Path $site "zipstage\build\PcAgent.exe")
    $zipName = "pcdash-agent-windows-v1.0.1.zip"
    Compress-Archive -Path (Join-Path $site "zipstage\*") -DestinationPath (Join-Path $site $zipName)
    $zipHash = (Get-FileHash (Join-Path $site $zipName) -Algorithm SHA256).Hash.ToLowerInvariant()
    $updPort = 18810
    $releaseJson = '{"tag_name":"v1.0.1","assets":[{"browser_download_url":"http://127.0.0.1:' + $updPort + '/' + $zipName + '"},{"browser_download_url":"http://127.0.0.1:' + $updPort + '/SHA256SUMS.txt"}]}'
    Set-Content -Path (Join-Path $site "release") -Value $releaseJson -NoNewline
    $newHash = (Get-FileHash (Join-Path $newDir "PcAgent.exe") -Algorithm SHA256).Hash
    $oldHash = (Get-FileHash (Join-Path $oldDir "PcAgent.exe") -Algorithm SHA256).Hash

    # A listener in its own runspace; stopping it is closing the listener (a job would wait for GetContext).
    function Start-UpdateSite {
        param([string]$Site, [int]$Port)
        $l = New-Object System.Net.HttpListener
        $l.Prefixes.Add("http://127.0.0.1:$Port/")
        $l.Start()
        $ps = [PowerShell]::Create()
        [void]$ps.AddScript({
            param($l, $Site)
            try {
                while ($l.IsListening) {
                    $ctx = $l.GetContext()
                    $name = $ctx.Request.Url.AbsolutePath.TrimStart('/')
                    $file = Join-Path $Site $name
                    if ($name -and (Test-Path $file -PathType Leaf)) {
                        $bytes = [IO.File]::ReadAllBytes($file)
                        $ctx.Response.OutputStream.Write($bytes, 0, $bytes.Length)
                    } else {
                        $ctx.Response.StatusCode = 404
                    }
                    $ctx.Response.Close()
                }
            } catch { }
        }).AddArgument($l).AddArgument($Site)
        [void]$ps.BeginInvoke()
        return @{ Listener = $l; Ps = $ps }
    }

    function Stop-UpdateSite {
        param($SiteHandle)
        try { $SiteHandle.Listener.Close() } catch { }
        try { $SiteHandle.Ps.Dispose() } catch { }
    }

    function Test-AgentUpdate {
        param([string]$Label, [bool]$ExpectUpdate)
        $siteHandle = Start-UpdateSite -Site $site -Port $updPort
        Start-Sleep -Seconds 1
        $psiU = New-Object System.Diagnostics.ProcessStartInfo
        $psiU.FileName = (Join-Path $oldDir "PcAgent.exe")
        $psiU.WorkingDirectory = $oldDir
        $psiU.UseShellExecute = $false
        $psiU.CreateNoWindow = $true
        $psiU.EnvironmentVariables["PCAGENT_PORT"] = "18805"
        $psiU.EnvironmentVariables["PCAGENT_TOKEN"] = $testToken
        $psiU.EnvironmentVariables["PCAGENT_BIND_LOCAL"] = "1"
        $psiU.EnvironmentVariables.Remove("PCAGENT_NO_UPDATE")
        $psiU.EnvironmentVariables["PCAGENT_UPDATE_API"] = "http://127.0.0.1:$updPort/release"
        $psiU.EnvironmentVariables["PCAGENT_UPDATE_DELAY_SEC"] = "1"
        $null = [System.Diagnostics.Process]::Start($psiU)
        try {
            # An update that must happen gets a long wait; one that must not has been refused within seconds.
            $deadline = (Get-Date).AddSeconds($(if ($ExpectUpdate) { 40 } else { 6 }))
            $updated = $false
            while ((Get-Date) -lt $deadline) {
                Start-Sleep -Milliseconds 500
                $h = (Get-FileHash (Join-Path $oldDir "PcAgent.exe") -Algorithm SHA256).Hash
                if ($h -eq $newHash) {
                    $up = $false
                    try { $up = ((Get-HttpStatus -Uri "http://localhost:18805/healthz") -eq 200) } catch {}
                    if ($up) { $updated = $true; break }
                }
            }
            if ($ExpectUpdate -and -not $updated) { throw "$Label : the agent did not update itself to 1.0.1 and come back" }
            if (-not $ExpectUpdate) {
                $h = (Get-FileHash (Join-Path $oldDir "PcAgent.exe") -Algorithm SHA256).Hash
                if ($h -ne $oldHash) { throw "$Label : the agent replaced itself although the checksum did not match" }
            }
        } finally {
            Get-Process -Name "PcAgent" -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$oldDir\PcAgent.exe*" } | Stop-Process -Force -ErrorAction SilentlyContinue
            Stop-UpdateSite $siteHandle
        }
    }

    # A wrong checksum: nothing may change.
    Set-Content -Path (Join-Path $site "SHA256SUMS.txt") -Value ("0" * 64 + "  $zipName") -NoNewline
    Test-AgentUpdate -Label "Wrong checksum" -ExpectUpdate $false
    Write-Host "Assertion PASS: a zip that does not match SHA256SUMS.txt is refused"

    # The right one: the agent replaces itself and comes back as 1.0.1.
    Set-Content -Path (Join-Path $site "SHA256SUMS.txt") -Value "$zipHash  $zipName" -NoNewline
    Test-AgentUpdate -Label "Right checksum" -ExpectUpdate $true
    Write-Host "Assertion PASS: a newer release is downloaded, verified, swapped in and started"

    Write-Host "=== 5.y Agent without token is closed ==="
    $noTokPort = 18803
    $psiNo = New-Object System.Diagnostics.ProcessStartInfo
    $psiNo.FileName = $exePath
    $psiNo.WorkingDirectory = $buildDir
    $psiNo.UseShellExecute = $false
    $psiNo.CreateNoWindow = $true
    $psiNo.EnvironmentVariables["PCAGENT_PORT"] = "$noTokPort"
    $psiNo.EnvironmentVariables["PCAGENT_BIND_LOCAL"] = "1"
    $psiNo.EnvironmentVariables["PCAGENT_TOKEN_FILE"] = (Join-Path $buildDir "does-not-exist.token")
    $procNo = [System.Diagnostics.Process]::Start($psiNo)
    try {
        Start-Sleep -Seconds 2
        $code = Get-HttpStatus -Uri "http://localhost:$noTokPort/exec" -Method Post -Body '{"action":"ping"}' -Headers $auth
        if ($code -ne 503) { throw "/exec on agent without token expected 503, got $code" }
        $code = Get-HttpStatus -Uri "http://localhost:$noTokPort/metrics" -Headers $auth
        if ($code -ne 503) { throw "/metrics on agent without token expected 503, got $code" }
        $code = Get-HttpStatus -Uri "http://localhost:$noTokPort/healthz"
        if ($code -ne 200) { throw "/healthz on agent without token expected 200, got $code" }
        Write-Host "Assertion PASS: agent without a token answers 503 on protected routes and 200 on /healthz"
    } finally {
        if ($procNo -and -not $procNo.HasExited) { try { $procNo.Kill() } catch {} }
    }

    # 5.8 CPU temp source order & cputemp.txt test
    Write-Host "=== Testing cputemp.txt dynamic reading & expiration ==="
    [System.IO.File]::WriteAllText($testTempFile, "48.6`n")
    $deadlineTemp = (Get-Date).AddSeconds(10)
    $tempVerified = $false
    while ((Get-Date) -lt $deadlineTemp) {
        Start-Sleep -Milliseconds 500
        $tResp = Invoke-WebRequest -Headers $auth -Uri $url -UseBasicParsing -TimeoutSec 3
        $tData = $tResp.Content | ConvertFrom-Json
        if ($tData.cpu.tempC -and [Math]::Abs([double]$tData.cpu.tempC - 48.6) -lt 0.1) {
            $tempVerified = $true
            break
        }
    }
    if (-not $tempVerified) {
        throw "Expected cpu.tempC to reflect cputemp.txt (48.6), got $($tData.cpu.tempC)"
    }
    Write-Host "Assertion PASS: cpu.tempC correctly updated from fresh cputemp.txt -> $($tData.cpu.tempC)"

    # Age the file > 10s to test expiration
    (Get-Item $testTempFile).LastWriteTimeUtc = (Get-Date).AddSeconds(-15).ToUniversalTime()
    $deadlineExpired = (Get-Date).AddSeconds(10)
    $expiredVerified = $false
    while ((Get-Date) -lt $deadlineExpired) {
        Start-Sleep -Milliseconds 500
        $tResp = Invoke-WebRequest -Headers $auth -Uri $url -UseBasicParsing -TimeoutSec 3
        $tData = $tResp.Content | ConvertFrom-Json
        # If WMI is null, cpu.tempC should revert to null; if WMI is active, it won't equal 48.6
        if ($null -eq $tData.cpu.tempC -or [Math]::Abs([double]$tData.cpu.tempC - 48.6) -gt 0.1) {
            $expiredVerified = $true
            break
        }
    }
    if (-not $expiredVerified) {
        throw "Expected cpu.tempC to not use expired (>10s) cputemp.txt, still got $($tData.cpu.tempC)"
    }
    Write-Host "Assertion PASS: expired cputemp.txt (>10s) is correctly ignored"
    Remove-Item $testTempFile -Force -ErrorAction SilentlyContinue
    # 5.9 Quota tests (/quota)
    Write-Host "=== 5.9 Testing /quota aggregated results ==="
    $deadlineQuota = (Get-Date).AddSeconds(15)
    $quotaResp = $null
    $quotaData = $null
    while ((Get-Date) -lt $deadlineQuota) {
        Start-Sleep -Milliseconds 500
        $qResp = Invoke-WebRequest -Headers $auth -Uri $quotaUrl -UseBasicParsing -TimeoutSec 3
        if ($qResp.StatusCode -eq 200) {
            $qData = $qResp.Content | ConvertFrom-Json
            if ($qData -and -not $qData.loading -and $qData.providers -and $qData.providers.Count -gt 0) {
                $quotaResp = $qResp
                $quotaData = $qData
                break
            }
        }
    }
    if ($null -eq $quotaData) {
        throw "Timed out waiting for /quota to populate from fake-omp"
    }

    # Assert providers present
    if (-not ($quotaData.providers -is [System.Array]) -or $quotaData.providers.Count -eq 0) {
        throw "Expected non-empty providers array in /quota"
    }

    # Assert google-antigravity with group Gemini and windows 5h, weekly
    $ag = $quotaData.providers | Where-Object { $_.id -eq "google-antigravity" }
    if (-not $ag) { throw "Missing provider 'google-antigravity' in /quota" }
    if ($ag.name -ne "Antigravity") { throw "Expected provider name 'Antigravity', got '$($ag.name)'" }

    $geminiGroup = $ag.groups | Where-Object { $_.name -eq "Gemini" }
    if (-not $geminiGroup) { throw "Missing group 'Gemini' in google-antigravity" }

    $w5h = $geminiGroup.windows | Where-Object { $_.id -eq "5h" }
    if (-not $w5h) { throw "Missing window '5h' in group Gemini" }
    $wWeekly = $geminiGroup.windows | Where-Object { $_.id -eq "weekly" }
    if (-not $wWeekly) { throw "Missing window 'weekly' in group Gemini" }

    # Assert pooledRemainingPct equals average of accounts with quota
    # Fixture: 3 accounts for 5h (70, 80, 90 -> avg 80), weekly (90, 100, 95 -> avg 95)
    if ($w5h.accountsWithQuota -ne 3 -or $w5h.accountsTotal -ne 3) {
        throw "Expected 5h 3 accounts with quota / 3 total, got $($w5h.accountsWithQuota)/$($w5h.accountsTotal)"
    }
    if ($w5h.pooledRemainingPct -ne 80) {
        throw "Expected 5h pooledRemainingPct=80, got $($w5h.pooledRemainingPct)"
    }
    if ($wWeekly.pooledRemainingPct -ne 95) {
        throw "Expected weekly pooledRemainingPct=95, got $($wWeekly.pooledRemainingPct)"
    }
    Write-Host "Assertion PASS: pooledRemainingPct for 5h=80, weekly=95"

    # Assert account labels: xxx@* or ^Tài khoản \d+$
    $taiKhoanPattern = "^T" + [char]0x00E0 + "i kho" + [char]0x1EA3 + "n \d+$"
    $allAccountLabels = @()
    foreach ($p in $quotaData.providers) {
        foreach ($g in $p.groups) {
            foreach ($w in $g.windows) {
                foreach ($acc in $w.accounts) {
                    $allAccountLabels += $acc.label
                    if (-not ($acc.label -match '^[^\@]+@\*$' -or $acc.label -match $taiKhoanPattern -or $acc.label -match '^[^\@]+$')) {
                        throw "Invalid account label format: '$($acc.label)'"
                    }
                }
            }
        }
    }
    if (-not ($allAccountLabels | Where-Object { $_ -match '^[^\@]+@\*$' })) {
        throw "Expected at least one account label with '@*'"
    }
    if (-not ($allAccountLabels | Where-Object { $_ -match $taiKhoanPattern })) {
        throw "Expected at least one account label matching '$taiKhoanPattern'"
    }
    Write-Host "Assertion PASS: account labels match expected redacted format"

    # Assert every '@' in body is followed by '*'
    $rawQuota = $quotaResp.Content
    if ($rawQuota -match '@[^*]' -or $rawQuota -match '@$') {
        throw "Found '@' not followed by '*' in /quota JSON: $rawQuota"
    }
    Write-Host "Assertion PASS: every '@' in /quota body is followed by '*'"

    # Assert no accountId, orgName, or full email in body
    foreach ($forbidden in @("accountId", "orgName", "fake-acc-", "fake-org-", "fake-proj-", "daily-cloudcode-pa", "googleapis.com")) {
        if ($rawQuota.Contains($forbidden)) {
            throw "Found forbidden sensitive token '$forbidden' in /quota JSON!"
        }
    }
    Write-Host "Assertion PASS: no accountId/orgName/internal metadata leaked"

    # Assert provider anthropic is ignored
    $anthropicProv = $quotaData.providers | Where-Object { $_.id -eq "anthropic" -or $_.name -eq "Claude" }
    if ($anthropicProv) {
        throw "Expected provider 'anthropic' to be skipped, but found in /quota: $($anthropicProv.id)"
    }
    Write-Host "Assertion PASS: provider 'anthropic' is skipped"

    # Assert alice@example.com is sanitized to 'Tài khoản N' and 'example' is not in rawQuota
    if ($rawQuota.Contains("example")) {
        throw "Found sensitive unredacted email domain 'example' in /quota JSON!"
    }
    $codex = $quotaData.providers | Where-Object { $_.id -eq "openai-codex" }
    if (-not $codex) { throw "Missing provider 'openai-codex' in /quota" }
    $w30d = $codex.groups[0].windows | Where-Object { $_.id -eq "30d" }
    if (-not $w30d) { throw "Missing window '30d' in codex" }
    $aliceAcc = $w30d.accounts | Where-Object { $_.label -eq "alice@*" -or $_.label -like "*example*" }
    if ($aliceAcc) {
        throw "Account with raw email was not converted to 'Tài khoản N', got label: '$($aliceAcc.label)'"
    }
    $secondAcc = $w30d.accounts[1]
    if (-not ($secondAcc.label -match $taiKhoanPattern)) {
        throw "Expected second codex account label to match '$taiKhoanPattern', got '$($secondAcc.label)'"
    }
    Write-Host "Assertion PASS: unredacted 'alice@example.com' fell back to '$($secondAcc.label)' and 'example' removed"

    # Assert GET /quota?refresh=1 rapid calls trigger omp exactly once
    # The fake omp of the previous request may still hold the file for a moment.
    for ($try = 0; $try -lt 20 -and (Test-Path $fakeOmpCountFile); $try++) {
        try { Remove-Item $fakeOmpCountFile -Force -ErrorAction Stop } catch { Start-Sleep -Milliseconds 250 }
    }
    if (Test-Path $fakeOmpCountFile) { throw "Cannot remove ${fakeOmpCountFile}: still in use after 5 s" }
    $refResp1 = Invoke-WebRequest -Headers $auth -Uri "$($quotaUrl)?refresh=1" -UseBasicParsing -TimeoutSec 3
    $refResp2 = Invoke-WebRequest -Headers $auth -Uri "$($quotaUrl)?refresh=1" -UseBasicParsing -TimeoutSec 3
    Start-Sleep -Milliseconds 600
    $refreshRuns = 0
    if (Test-Path $fakeOmpCountFile) {
        $refreshRuns = @(Get-Content $fakeOmpCountFile | Where-Object { $_.Trim().Length -gt 0 }).Count
    }
    if ($refreshRuns -ne 1) {
        throw "Expected fake-omp to run exactly 1 time on rapid refresh calls, ran $refreshRuns times"
    }
    Write-Host "Assertion PASS: rapid ?refresh=1 calls throttled to 1 run ($refreshRuns run)"

    # Assert PCAGENT_OMP_PATH pointing to non-existent file returns error:omp_unavailable
    Write-Host "=== Testing PCAGENT_OMP_PATH pointing to non-existent file ==="
    $badTestPort = 18800
    $badPsi = New-Object System.Diagnostics.ProcessStartInfo
    $badPsi.FileName = $exePath
    $badPsi.WorkingDirectory = $buildDir
    $badPsi.UseShellExecute = $false
    $badPsi.CreateNoWindow = $true
    $badPsi.EnvironmentVariables["PCAGENT_PORT"] = "$badTestPort"
    $badPsi.EnvironmentVariables["PCAGENT_TOKEN"] = $testToken
    $badPsi.EnvironmentVariables["PCAGENT_BIND_LOCAL"] = "1"
    $badPsi.EnvironmentVariables["PCAGENT_OMP_PATH"] = "C:\non_existent_dir_12345\fake-omp.cmd"
    $badProc = [System.Diagnostics.Process]::Start($badPsi)
    try {
        Start-Sleep -Milliseconds 500
        $badResp = Invoke-WebRequest -Headers $auth -Uri "http://localhost:$badTestPort/quota" -UseBasicParsing -TimeoutSec 3
        $badData = $badResp.Content | ConvertFrom-Json
        if ($badData.error -ne "omp_unavailable") {
            throw "Expected error:'omp_unavailable', got: $($badResp.Content)"
        }
        Write-Host "Assertion PASS: non-existent PCAGENT_OMP_PATH returns error:omp_unavailable"
    } finally {
        if ($badProc -and -not $badProc.HasExited) {
            try { $badProc.Kill(); $badProc.WaitForExit(3000) } catch {}
        }
    }

    # 5.10 Direct reflection tests for QuotaAggregator limits & sanitization
    Write-Host "=== 5.10 Direct reflection tests for QuotaAggregator limits & sanitization ==="
    $asm = [System.Reflection.Assembly]::LoadFrom($exePath)
    $qa = $asm.GetType("PcAgent.QuotaAggregator")
    $aggMethod = $qa.GetMethod("Aggregate")
    $sanMethod = $qa.GetMethod("SanitizeField")

    # SanitizeField removes @ and control chars, truncates to 64
    $rawField = "test@provider" + [char]0x07 + "name" + ("x" * 100)
    $sanResult = $sanMethod.Invoke($null, @($rawField, 64))
    if ($sanResult.Length -ne 64 -or $sanResult.Contains("@") -or $sanResult.Contains([char]0x07)) {
        throw "SanitizeField failed to strip '@', control chars, or truncate: '$sanResult'"
    }
    Write-Host "Assertion PASS: SanitizeField strips '@', control chars, and caps at 64"

    # MiniJson depth > 32 throws FormatException
    $deepJson = ("[" * 35) + "1" + ("]" * 35)
    $depthExCaught = $false
    try {
        $aggMethod.Invoke($null, @($deepJson, [long]1000))
    } catch {
        if ($_.Exception.InnerException -is [System.FormatException] -and $_.Exception.InnerException.Message.Contains("32")) {
            $depthExCaught = $true
        }
    }
    if (-not $depthExCaught) {
        throw "Expected MiniJson depth > 32 to throw FormatException"
    }
    Write-Host "Assertion PASS: MiniJson depth > 32 throws FormatException"

    # Reports > 200 throws FormatException
    $manyReports = "{" + [char]34 + "reports" + [char]34 + ":[" + ((1..205 | ForEach-Object { "{}" }) -join ",") + "]}"
    $reportsExCaught = $false
    try {
        $aggMethod.Invoke($null, @($manyReports, [long]1000))
    } catch {
        if ($_.Exception.InnerException -is [System.FormatException] -and $_.Exception.InnerException.Message.Contains("200")) {
            $reportsExCaught = $true
        }
    }
    if (-not $reportsExCaught) {
        throw "Expected reports > 200 to throw FormatException"
    }
    Write-Host "Assertion PASS: reports > 200 throws FormatException"

    # Limits > 100 throws FormatException
    $manyLimits = "{" + [char]34 + "reports" + [char]34 + ":[{" + [char]34 + "provider" + [char]34 + ":" + [char]34 + "p1" + [char]34 + "," + [char]34 + "limits" + [char]34 + ":[" + ((1..105 | ForEach-Object { "{}" }) -join ",") + "]}]}"
    $limitsExCaught = $false
    try {
        $aggMethod.Invoke($null, @($manyLimits, [long]1000))
    } catch {
        if ($_.Exception.InnerException -is [System.FormatException] -and $_.Exception.InnerException.Message.Contains("100")) {
            $limitsExCaught = $true
        }
    }
    if (-not $limitsExCaught) {
        throw "Expected limits > 100 to throw FormatException"
    }
    Write-Host "Assertion PASS: limits > 100 throws FormatException"

    # Test stdout > 512 KB returns error:omp_unavailable
    Write-Host "=== Testing stdout > 512 KB returns error:omp_unavailable ==="
    $hugeOmpCmd = Join-Path $buildDir "fake-huge-omp.cmd"
    $hugeOutFile = Join-Path $buildDir "fake-huge-out.txt"
    [System.IO.File]::WriteAllText($hugeOutFile, ("A" * 600000))
    Set-Content -Path $hugeOmpCmd -Value "@echo off`r`ntype `"$hugeOutFile`""
    $hugeTestPort = 18801
    $hugePsi = New-Object System.Diagnostics.ProcessStartInfo
    $hugePsi.FileName = $exePath
    $hugePsi.WorkingDirectory = $buildDir
    $hugePsi.UseShellExecute = $false
    $hugePsi.CreateNoWindow = $true
    $hugePsi.EnvironmentVariables["PCAGENT_PORT"] = "$hugeTestPort"
    $hugePsi.EnvironmentVariables["PCAGENT_TOKEN"] = $testToken
    $hugePsi.EnvironmentVariables["PCAGENT_BIND_LOCAL"] = "1"
    $hugePsi.EnvironmentVariables["PCAGENT_OMP_PATH"] = $hugeOmpCmd
    $hugeProc = [System.Diagnostics.Process]::Start($hugePsi)
    try {
        $deadlineHuge = (Get-Date).AddSeconds(10)
        $hugeData = $null
        while ((Get-Date) -lt $deadlineHuge) {
            Start-Sleep -Milliseconds 400
            $hugeResp = Invoke-WebRequest -Headers $auth -Uri "http://localhost:$hugeTestPort/quota" -UseBasicParsing -TimeoutSec 3
            if ($hugeResp.StatusCode -eq 200) {
                $hugeData = $hugeResp.Content | ConvertFrom-Json
                if ($hugeData -and -not $hugeData.loading) {
                    break
                }
            }
        }
        if ($null -eq $hugeData -or $hugeData.error -ne "omp_unavailable") {
            throw "Expected error:'omp_unavailable' for >512KB stdout, got: $($hugeResp.Content)"
        }
        Write-Host "Assertion PASS: stdout > 512 KB kills process and returns error:omp_unavailable"
    } finally {
        if ($hugeProc -and -not $hugeProc.HasExited) {
            try { $hugeProc.Kill(); $hugeProc.WaitForExit(3000) } catch {}
        }
        Remove-Item $hugeOmpCmd -Force -ErrorAction SilentlyContinue
        Remove-Item $hugeOutFile -Force -ErrorAction SilentlyContinue
    }

    # === 5.11-5.16 TcpListener minimal HTTP server specifications ===
    Write-Host "=== 5.11 Testing HTTP server limits: Header > 8 KB -> 431 or closed ==="
    $rawClient = New-Object System.Net.Sockets.TcpClient
    $rawClient.Connect("127.0.0.1", $testPort)
    $stream = $rawClient.GetStream()
    $stream.ReadTimeout = 4000
    $stream.WriteTimeout = 4000
    $longHeader = "X-Large: " + ("A" * 9000)
    $reqBytes = [System.Text.Encoding]::ASCII.GetBytes("GET /healthz HTTP/1.1`r`nHost: localhost`r`n$longHeader`r`n`r`n")
    $stream.Write($reqBytes, 0, $reqBytes.Length)
    $respBuf = New-Object byte[] 4096
    $bytesRead = 0
    $respStr = ""
    try {
        $bytesRead = $stream.Read($respBuf, 0, $respBuf.Length)
        if ($bytesRead -gt 0) {
            $respStr = [System.Text.Encoding]::ASCII.GetString($respBuf, 0, $bytesRead)
        }
    } catch {
        # Connection closed / reset by server is acceptable
    }
    $rawClient.Close()
    if ($bytesRead -gt 0 -and -not ($respStr.StartsWith("HTTP/1.1 431") -or $respStr.StartsWith("HTTP/1.0 431"))) {
        throw "Expected 431 or closed connection for header > 8 KB, got: $respStr"
    }
    Write-Host "Assertion PASS: header > 8 KB returned 431 or closed connection"

    Write-Host "=== 5.12 Testing HTTP server limits: Body > 16 KB -> 413 ==="
    $rawClient = New-Object System.Net.Sockets.TcpClient
    $rawClient.Connect("127.0.0.1", $testPort)
    $stream = $rawClient.GetStream()
    $stream.ReadTimeout = 4000
    $stream.WriteTimeout = 4000
    $bodySize = 20480 # 20 KB
    $reqHeader = "POST /exec HTTP/1.1`r`nHost: localhost`r`nContent-Length: $bodySize`r`nConnection: close`r`n`r`n"
    $hdrBytes = [System.Text.Encoding]::ASCII.GetBytes($reqHeader)
    $stream.Write($hdrBytes, 0, $hdrBytes.Length)
    $bodyChunk = New-Object byte[] 1024
    $stream.Write($bodyChunk, 0, $bodyChunk.Length)
    $respBuf = New-Object byte[] 4096
    $bytesRead = 0
    $respStr = ""
    try {
        $bytesRead = $stream.Read($respBuf, 0, $respBuf.Length)
        if ($bytesRead -gt 0) {
            $respStr = [System.Text.Encoding]::ASCII.GetString($respBuf, 0, $bytesRead)
        }
    } catch {}
    $rawClient.Close()
    if (-not ($respStr.StartsWith("HTTP/1.1 413") -or $respStr.StartsWith("HTTP/1.0 413"))) {
        throw "Expected 413 for body > 16 KB, got: $respStr"
    }
    Write-Host "Assertion PASS: body > 16 KB returned 413 Payload Too Large"

    Write-Host "=== 5.13 Testing HTTP server: strange path -> 404 ==="
    $rawClient = New-Object System.Net.Sockets.TcpClient
    $rawClient.Connect("127.0.0.1", $testPort)
    $stream = $rawClient.GetStream()
    $stream.ReadTimeout = 4000
    $reqBytes = [System.Text.Encoding]::ASCII.GetBytes("GET /strange/path/xyz HTTP/1.1`r`nHost: localhost`r`nConnection: close`r`n`r`n")
    $stream.Write($reqBytes, 0, $reqBytes.Length)
    $respBuf = New-Object byte[] 4096
    $bytesRead = $stream.Read($respBuf, 0, $respBuf.Length)
    $respStr = [System.Text.Encoding]::ASCII.GetString($respBuf, 0, $bytesRead)
    $rawClient.Close()
    if (-not ($respStr.StartsWith("HTTP/1.1 404") -or $respStr.StartsWith("HTTP/1.0 404"))) {
        throw "Expected 404 for strange path, got: $respStr"
    }
    Write-Host "Assertion PASS: strange path returned 404 Not Found"

    Write-Host "=== 5.14 Testing HTTP server: strange method -> 405 ==="
    $rawClient = New-Object System.Net.Sockets.TcpClient
    $rawClient.Connect("127.0.0.1", $testPort)
    $stream = $rawClient.GetStream()
    $stream.ReadTimeout = 4000
    $reqBytes = [System.Text.Encoding]::ASCII.GetBytes("FOO /metrics HTTP/1.1`r`nHost: localhost`r`nX-PcAgent-Token: $testToken`r`nConnection: close`r`n`r`n")
    $stream.Write($reqBytes, 0, $reqBytes.Length)
    $respBuf = New-Object byte[] 4096
    $bytesRead = 0
    $respStr = ""
    try {
        $bytesRead = $stream.Read($respBuf, 0, $respBuf.Length)
        if ($bytesRead -gt 0) {
            $respStr = [System.Text.Encoding]::ASCII.GetString($respBuf, 0, $bytesRead)
        }
    } catch {}
    $rawClient.Close()
    if (-not ($respStr.StartsWith("HTTP/1.1 405") -or $respStr.StartsWith("HTTP/1.0 405"))) {
        throw "Expected 405 for strange method, got: $respStr"
    }
    Write-Host "Assertion PASS: strange method returned 405 Method Not Allowed"

    Write-Host "=== 5.15 Testing connection concurrency limit (16 max): 20 concurrent -> 17+ gets 503 or rejected ==="
    $heldClients = @()
    try {
        for ($i = 1; $i -le 16; $i++) {
            $c = New-Object System.Net.Sockets.TcpClient
            $c.Connect("127.0.0.1", $testPort)
            $heldClients += $c
        }
        # Now slot 17 to 20
        $rejectedOr503 = 0
        for ($i = 17; $i -le 20; $i++) {
            try {
                $c = New-Object System.Net.Sockets.TcpClient
                $c.ReceiveTimeout = 2000
                $c.SendTimeout = 2000
                $c.Connect("127.0.0.1", $testPort)
                $s = $c.GetStream()
                $reqBytes = [System.Text.Encoding]::ASCII.GetBytes("GET /healthz HTTP/1.1`r`nHost: localhost`r`nConnection: close`r`n`r`n")
                $s.Write($reqBytes, 0, $reqBytes.Length)
                $buf = New-Object byte[] 1024
                $read = 0
                try { $read = $s.Read($buf, 0, $buf.Length) } catch {}
                $resp = if ($read -gt 0) { [System.Text.Encoding]::ASCII.GetString($buf, 0, $read) } else { "" }
                $c.Close()
                if ($resp.StartsWith("HTTP/1.1 503") -or $resp.StartsWith("HTTP/1.0 503") -or $read -eq 0) {
                    $rejectedOr503++
                }
            } catch {
                $rejectedOr503++
            }
        }
        if ($rejectedOr503 -lt 4) {
            throw "Expected connections 17-20 to get 503 or be rejected, but only $rejectedOr503 were rejected/503"
        }
        Write-Host "Assertion PASS: connections 17-20 were rejected or received 503"
    } finally {
        foreach ($c in $heldClients) {
            try { $c.Close() } catch {}
        }
    }
    # Verify agent still serves /metrics afterwards
    Start-Sleep -Milliseconds 300
    $afterResp = Invoke-WebRequest -Headers $auth -Uri "http://localhost:$testPort/metrics" -UseBasicParsing -TimeoutSec 3
    if ($afterResp.StatusCode -ne 200) {
        throw "Expected /metrics to return 200 after connection flood, got $($afterResp.StatusCode)"
    }
    Write-Host "Assertion PASS: /metrics returns 200 after concurrency test"

    Write-Host "=== 5.16 Testing hanging connection read timeout (~5s) ==="
    $hangClient = New-Object System.Net.Sockets.TcpClient
    $hangClient.Connect("127.0.0.1", $testPort)
    $hangStream = $hangClient.GetStream()
    $hangStream.ReadTimeout = 10000
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $hangBuf = New-Object byte[] 128
    $hangRead = -1
    try {
        $hangRead = $hangStream.Read($hangBuf, 0, $hangBuf.Length)
    } catch {
        $hangRead = 0
    }
    $sw.Stop()
    $hangClient.Close()
    $elapsedSec = [math]::Round($sw.ElapsedMilliseconds / 1000.0, 2)
    Write-Host "Hanging connection closed after $elapsedSec seconds (hangRead=$hangRead)"
    if ($elapsedSec -lt 4.0 -or $elapsedSec -gt 8.0) {
        throw "Expected hanging connection to close after ~5 seconds, but took $elapsedSec seconds"
    }
    if ($hangRead -gt 0) {
        throw "Expected connection to be closed with 0 bytes or error, got $hangRead bytes"
    }
    Write-Host "Assertion PASS: hanging connection timed out and closed after ~5s ($elapsedSec s)"
    Write-Host "`n>>> ALL ASSERTIONS PASSED SUCCESSFULLY <<<"
}
finally {
    Write-Host "=== 6. Cleanup: Stopping test PID $targetPid (SAFE, strictly by PID) ==="
    if ($proc -and -not $proc.HasExited) {
        try {
            $proc.Kill()
            $proc.WaitForExit(3000)
            Write-Host "Test PID $targetPid stopped."
        } catch {
            Write-Warning "Failed to stop PID $($targetPid): $_"
        }
    } else {
        Write-Host "Process PID $targetPid already exited."
    }
    if (Test-Path $testTempFile) {
        Remove-Item $testTempFile -Force -ErrorAction SilentlyContinue
    }
    if (Test-Path $fakeOmpCountFile) {
        Remove-Item $fakeOmpCountFile -Force -ErrorAction SilentlyContinue
    }
}
