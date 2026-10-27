# Fails when Java or Kotlin code shows Vietnamese text that is not wrapped in L10n.t("English", "Vietnamese"),
# so the English UI does not regress. Allowed without L10n: log calls, comments, and lines marked
# "// l10n-ok: <reason>" (for example text that is matched in both languages on purpose).
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path $PSScriptRoot -Parent
$roots = @("app/src", "remote/src", "shared/src") | ForEach-Object { Join-Path $repoRoot $_ }
# Case-sensitive (-cmatch): with -match, culture case folding makes this range match ASCII i and s.
$vietnamese = '[\u00C0-\u1EF9\u0110\u0111]'
$literal = '"(?:[^"\\]|\\.)*"'

$violations = @()
$files = Get-ChildItem -Path $roots -Recurse -Include *.java, *.kt
foreach ($file in $files) {
    $lines = [IO.File]::ReadAllLines($file.FullName, [Text.Encoding]::UTF8)
    $previous = ""
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        $trimmed = $line.Trim()
        $isComment = $trimmed.StartsWith("//") -or $trimmed.StartsWith("*") -or $trimmed.StartsWith("/*")
        $hasText = $false
        foreach ($m in [regex]::Matches($line, $literal)) {
            if ($m.Value -cmatch $vietnamese) { $hasText = $true; break }
        }
        $allowed = $isComment -or
            $line.Contains("L10n.t(") -or
            $line.Contains("l10n-ok") -or
            ($previous -match 'L10n\.t\(.*,\s*$') -or
            ($line -match '\blog[IWED]?\(|\bLog\.[iwed]\(')
        if ($hasText -and -not $allowed) {
            $violations += "$($file.FullName):$($i + 1): $trimmed"
        }
        if ($trimmed.Length -gt 0) { $previous = $trimmed }
    }
}

if ($violations.Count -gt 0) {
    Write-Host "Vietnamese text outside L10n.t(...) ($($violations.Count)):" -ForegroundColor Red
    foreach ($v in $violations) { Write-Host "  $v" -ForegroundColor Red }
    exit 1
}
Write-Host "PASS: $($files.Count) Java/Kotlin files keep UI text in L10n.t(...)" -ForegroundColor Green
