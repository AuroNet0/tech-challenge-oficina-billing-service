param([Parameter(Mandatory = $true)][string]$RunRoot)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/memory.ps1"
try {
    while (!(Test-Path "$RunRoot/monitor-stop")) {
        Measure-E2EMemory -RunRoot $RunRoot -Label 'periodic'
        Start-Sleep -Seconds 10
    }
} catch {
    @{ timestamp = (Get-Date -Format o); reason = $_.Exception.Message } | ConvertTo-Json | Set-Content -Encoding UTF8 "$RunRoot/evidence/memory-alert.json"
    throw
}
