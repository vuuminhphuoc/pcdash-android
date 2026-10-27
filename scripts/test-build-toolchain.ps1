$ErrorActionPreference = "Stop"
. "$PSScriptRoot\BuildToolchain.ps1"

function Assert-Equal($Actual, $Expected, $Message) {
    if ($Actual -ne $Expected) { throw "$Message`: expected '$Expected', got '$Actual'" }
}
function Assert-Throws([scriptblock] $Action, [string] $Message) {
    try { & $Action } catch { return }
    throw $Message
}
function TestConvertToGoVersion {
    Assert-Equal (ConvertTo-GoVersion "go version go1.27.1 windows/amd64") ([version]'1.27.1') 'ConvertTo-GoVersion'
}
function TestRejectsOldGo {
    Assert-Throws { Assert-MinimumGoVersion ([version]'1.22.12') ([version]'1.27.1') } 'Old Go was accepted'
}
function TestResolvesCurrentSdk {
    Use-PcdashJdk17
    $tools = Get-PcdashToolchain (Split-Path $PSScriptRoot -Parent)
    Assert-Equal $tools.Sdk (Get-PcdashSdk) 'Android SDK'
    foreach ($name in @('aapt2', 'd8', 'zipalign', 'apksigner', 'android.jar', 'javac')) {
        if (-not $tools.Contains($name) -or -not (Test-Path $tools[$name])) { throw "Missing tool: $name" }
    }
}
TestConvertToGoVersion
TestRejectsOldGo
TestResolvesCurrentSdk
Write-Host 'PASS: build toolchain tests'
