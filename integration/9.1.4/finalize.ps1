param([Parameter(Mandatory = $true)][string]$RunRoot, [string]$Docker = 'C:/Program Files/Docker/Docker/resources/bin/docker.exe')
$ErrorActionPreference = 'Stop'
$manifest = Get-Content -Raw "$RunRoot/manifest.json" | ConvertFrom-Json
if ($manifest.project -notmatch '^oficina-e2e914-[a-f0-9]{12}$') { throw 'Invalid isolated E2E project' }
. "$PSScriptRoot/memory.ps1"
Measure-E2EMemory -RunRoot $RunRoot -Label 'final-validation' -Docker $Docker
foreach ($name in @('bdd','result','compensations','preflight','compensation-preparation','dependency-unavailable')) {
    $gate = Get-Content -Raw "$RunRoot/evidence/$name.json" | ConvertFrom-Json
    if ($gate.status -ne 'PASSED') { throw "Required scenario did not pass: $name" }
}
$containers = @(& $Docker ps -aq --filter "label=com.docker.compose.project=$($manifest.project)" --filter 'label=com.docker.compose.oneoff=False')
if ($LASTEXITCODE -ne 0 -or $containers.Count -ne 10) { throw 'Expected ten isolated containers' }
foreach ($container in $containers) {
    $state = & $Docker inspect --format '{{.State.Status}}|{{.State.Health.Status}}|{{.State.OOMKilled}}' $container
    if ($LASTEXITCODE -ne 0 -or $state -ne 'running|healthy|false') { throw 'Final container state failed' }
}
$totalChecks = 0
foreach ($name in @('preflight','result','compensation-preparation','compensations','dependency-unavailable','broker-outage','broker-recovery')) {
    $evidence = Get-Content -Raw "$RunRoot/evidence/$name.json" | ConvertFrom-Json
    if (@($evidence.checks).Count -eq 0) { throw "Missing actual checks: $name" }
    $totalChecks += @($evidence.checks).Count
}
@{ status = 'PASSED'; runId = $manifest.runId; totalChecks = $totalChecks; phases = @('preflight','provision','bdd','resume-after-approval','compensations','dependency-unavailable','broker-outage','broker-recovery') } | ConvertTo-Json | Set-Content -Encoding UTF8 "$RunRoot/evidence/validation-global.json"
Write-Output "Authenticated E2E completed: checks=$totalChecks"
