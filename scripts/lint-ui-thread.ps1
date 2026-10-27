# Lint UI Thread Safety
# Quet cac file UI (*Activity.java, *TabView.java, MoreSheet.java, BottomNavBar.java, WatchdogReceiver.java, BootReceiver.java, LoginPanel.java, PairingScreen.java)
# Tim cac token cam chay truc tiep tren luong giao dien.

$scriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Definition }
$repoRoot = if ($scriptDir) { Split-Path $scriptDir -Parent } else { (Get-Location).Path }

$targetDirs = @(
    (Join-Path $repoRoot "remote\src"),
    (Join-Path $repoRoot "app\src"),
    (Join-Path $repoRoot "shared\src")
)
$targetFiles = Get-ChildItem -Path $targetDirs -Recurse -Filter *.java | Where-Object {
    $_.Name -match 'Activity\.java$|TabView\.java$|^MoreSheet\.java$|^BottomNavBar\.java$|^WatchdogReceiver\.java$|^BootReceiver\.java$|^LoginPanel\.java$|^PairingScreen\.java$'
}

function Lint-File {
    param([string]$filePath)
    $lines = Get-Content $filePath
    $violations = @()

    $contextStack = [System.Collections.Generic.Stack[string]]::new()
    $contextStack.Push('UI_THREAD')
    $pendingContexts = [System.Collections.Generic.Queue[string]]::new()

    for ($lineIdx = 0; $lineIdx -lt $lines.Count; $lineIdx++) {
        $line = $lines[$lineIdx]
        $trimmed = $line.Trim()

        # Bo qua dong chu thich hoan toan
        if ($trimmed.StartsWith('//') -or $trimmed.StartsWith('*') -or $trimmed.StartsWith('/*')) {
            continue
        }

        # Chap nhan danh sach ngoai le co chu thich // ui-thread-ok: <ly do>
        $hasException = $line -match '//\s*ui-thread-ok:'

        # Phat hien bat dau khoi chay tren luong nen
        if ($line -match '(executor|lifecycleExecutor)\.submit\s*\(' -or $line -match 'new\s+Thread\s*\(') {
            $pendingContexts.Enqueue('BACKGROUND')
        }
        if ($line -match 'class\s+\w*(Worker|Task)\s+implements\s+Runnable') {
            $pendingContexts.Enqueue('BACKGROUND')
        }
        # Quay lai luong giao dien khi co ui.post(new Runnable...)
        if ($line -match '\b(ui|handler|uiHandler|view)\.post(Delayed)?\s*\(\s*new\s+Runnable' -or $line -match '\brunOnUiThread\s*\(\s*new\s+Runnable') {
            $pendingContexts.Enqueue('UI_THREAD')
        }

        $currentContext = $contextStack.Peek()

        # Kiem tra token cam tren luong giao dien (UI_THREAD)
        if ($currentContext -eq 'UI_THREAD' -and -not $hasException) {
            if ($line -match '\bHttpURLConnection\b') {
                $violations += "$($filePath):$($lineIdx+1): HttpURLConnection khong duoc phep goi truc tiep tren luong giao dien"
            }
            if ($line -match '\bThread\.sleep\s*\(') {
                $violations += "$($filePath):$($lineIdx+1): Thread.sleep() khong duoc phep goi truc tiep tren luong giao dien"
            }
            if ($line -match '\b\w*(Future|Task)\w*\.get\s*\(') {
                $violations += "$($filePath):$($lineIdx+1): Future.get() khong duoc phep goi truc tiep tren luong giao dien"
            }
            if ($line -match '\b\w+\.join\s*\(') {
                $violations += "$($filePath):$($lineIdx+1): .join() khong duoc phep goi truc tiep tren luong giao dien"
            }
            if ($line -match '\bnew\s+URL\s*\(') {
                $violations += "$($filePath):$($lineIdx+1): new URL() khong duoc phep goi truc tiep tren luong giao dien"
            }
            if ($line -match '\b(controller|ctrl|act\.controller)\.(getStatus|getStatusQuick|getPcsQuick|postAction|getMetrics|getLog|getHistory|getQuota|getUpdateInfo|downloadUpdate|open|close|pair|forgetHost)\s*\(') {
                $violations += "$($filePath):$($lineIdx+1): controller.$($Matches[2])() khong duoc phep goi truc tiep tren luong giao dien"
            }
        }

        # Theo doi do sau cua khoi bang ngoac nhon
        foreach ($ch in $line.ToCharArray()) {
            if ($ch -eq '{') {
                if ($pendingContexts.Count -gt 0) {
                    $nextCtx = $pendingContexts.Dequeue()
                    $contextStack.Push($nextCtx)
                } else {
                    $contextStack.Push($currentContext)
                }
                $currentContext = $contextStack.Peek()
            } elseif ($ch -eq '}') {
                if ($contextStack.Count -gt 1) {
                    [void]$contextStack.Pop()
                    $currentContext = $contextStack.Peek()
                }
            }
        }
    }
    return $violations
}

Write-Host "Kiem tra an toan luong giao dien (UI Thread Safety Lint)..."
$allViolations = @()
foreach ($file in $targetFiles) {
    $v = Lint-File $file.FullName
    if ($v.Count -gt 0) {
        $allViolations += $v
    }
}

if ($allViolations.Count -gt 0) {
    Write-Host "PHAT HIEN $($allViolations.Count) LOI VI PHAM AN TOAN LUONG GIAO DIEN:" -ForegroundColor Red
    foreach ($v in $allViolations) {
        Write-Host "  [FAIL] $v" -ForegroundColor Red
    }
    exit 1
} else {
    Write-Host "PASS: $($targetFiles.Count) tap tin UI dat yeu cau an toan luong giao dien!" -ForegroundColor Green
    exit 0
}
