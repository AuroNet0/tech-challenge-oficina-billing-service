param([Parameter(Mandatory = $true)][string]$RunRoot, [string]$Docker = 'C:/Program Files/Docker/Docker/resources/bin/docker.exe',
    [switch]$ContinueAfterProvisioning, [switch]$ContinueAfterApproval)
$ErrorActionPreference = 'Stop'
if ($ContinueAfterProvisioning -and $ContinueAfterApproval) { throw 'Select only one validated checkpoint' }
$env:PATH = (Split-Path $Docker) + ';C:/Windows/System32;' + $env:PATH
$manifest = Get-Content -Raw (Join-Path $RunRoot 'manifest.json') | ConvertFrom-Json
if ($manifest.project -notmatch '^oficina-e2e835-[a-f0-9]{12}$') { throw 'Invalid isolated E2E project' }
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
foreach ($service in @('os','execution','billing')) {
    $jar = Get-ChildItem "$workspace/tech-challenge-oficina-$service-service/target/*.jar" | Where-Object Name -NotLike '*original*' | Select-Object -First 1
    if (!$jar) { throw "Missing verified jar: $service" }
    Copy-Item -LiteralPath $jar.FullName -Destination "$RunRoot/jars/$service.jar"
}
$arguments = @('compose','--env-file',"$RunRoot/private.env",'-p',$manifest.project,'-f',"$PSScriptRoot/compose.yaml")
$ErrorActionPreference = 'Continue'
& $Docker @arguments up -d --wait --wait-timeout 360 *> "$RunRoot/compose-up.log"
if ($LASTEXITCODE -ne 0) { throw 'Compose startup failed; inspect private compose-up.log' }
if ($ContinueAfterProvisioning -or $ContinueAfterApproval) {
    $preflight = Get-Content -Raw "$RunRoot/evidence/preflight.json" | ConvertFrom-Json
    if ($preflight.status -ne 'PASSED' -or !(Test-Path "$RunRoot/runtime/state.json") -or !(Test-Path "$RunRoot/runtime/users.json")) {
        throw 'Validated provisioning checkpoint required'
    }
} else {
    if (!(Test-Path "$RunRoot/runtime/users.json")) {
        & $Docker @arguments exec -T auth java -cp /auth.jar:/classes integration.AuthFixture create *> "$RunRoot/auth-fixture.log"
        if ($LASTEXITCODE -ne 0) { throw 'Auth provisioning failed' }
    }
    & $Docker @arguments --profile tools build runner *> "$RunRoot/runner-build.log"
    if ($LASTEXITCODE -ne 0) { throw 'Runner build failed' }
    & $Docker @arguments --profile tools run --rm runner python /runner.py provision *> "$RunRoot/provision.log"
    if ($LASTEXITCODE -ne 0) { throw 'Operational provisioning failed' }
}
$linkDeadline = [DateTime]::UtcNow.AddSeconds(60)
do {
    & $Docker @arguments exec -T auth java -cp /auth.jar:/classes integration.AuthFixture link *> "$RunRoot/auth-link.log"
    if ($LASTEXITCODE -eq 0) { break }
    $linkError = Get-Content -Raw "$RunRoot/auth-link.log"
    if ($linkError -notmatch 'AUTH_FIXTURE_FAILED (PERSISTENCIA|CONCORRENCIA|[A-Za-z]*IOException|JsonParseException|JsonEOFException)') {
        throw 'Auth links failed; inspect sanitized reason'
    }
    Start-Sleep -Milliseconds 500
} while ([DateTime]::UtcNow -lt $linkDeadline)
if ($LASTEXITCODE -ne 0) { throw 'Auth link provisioning timed out' }
$flowPhase = if ($ContinueAfterApproval) { 'resume-after-approval' } else { 'flow' }
& $Docker @arguments --profile tools run --rm runner python /runner.py $flowPhase *> "$RunRoot/flow-$flowPhase.log"
$flowExit = $LASTEXITCODE
& $Docker @arguments ps --format json *> "$RunRoot/evidence/containers.json"
if ($flowExit -ne 0) { throw "Authenticated E2E failed; inspect evidence/result.json and flow-$flowPhase.log" }
try {
    & $Docker @arguments stop os-service *> "$RunRoot/stop-os.log"
    if ($LASTEXITCODE -ne 0) { throw 'Failed to stop isolated OS' }
    & $Docker @arguments --profile tools run --rm runner python /runner.py unavailable *> "$RunRoot/unavailable.log"
    if ($LASTEXITCODE -ne 0) { throw 'Dependency failure checks failed' }
} finally {
    & $Docker @arguments up -d --wait --wait-timeout 360 os-service *> "$RunRoot/restart-os.log"
    if ($LASTEXITCODE -ne 0) { throw 'Failed to restore isolated OS' }
}
try {
    & $Docker @arguments stop rabbitmq *> "$RunRoot/stop-broker.log"
    if ($LASTEXITCODE -ne 0) { throw 'Failed to stop isolated broker' }
    & $Docker @arguments --profile tools run --rm runner python /runner.py outage *> "$RunRoot/outage.log"
    if ($LASTEXITCODE -ne 0) { throw 'Broker outage checks failed' }
} finally {
    & $Docker @arguments up -d --wait --wait-timeout 180 rabbitmq *> "$RunRoot/restart-broker.log"
    if ($LASTEXITCODE -ne 0) { throw 'Failed to restore isolated broker' }
}
& $Docker @arguments --profile tools run --rm runner python /runner.py recover *> "$RunRoot/recovery.log"
if ($LASTEXITCODE -ne 0) { throw 'Broker recovery failed' }
$totalChecks = 0
foreach ($name in @('preflight','result','dependency-unavailable','broker-outage','broker-recovery')) {
    $evidence = Get-Content -Raw "$RunRoot/evidence/$name.json" | ConvertFrom-Json
    $totalChecks += @($evidence.checks).Count
}
@{ status = 'PASSED'; runId = $manifest.runId; totalChecks = $totalChecks; phases = @('preflight','provision',$flowPhase,'dependency-unavailable','broker-outage','broker-recovery') } | ConvertTo-Json | Set-Content -Encoding UTF8 "$RunRoot/evidence/validation-global.json"
Write-Output "Authenticated E2E completed: $RunRoot/evidence/result.json"
