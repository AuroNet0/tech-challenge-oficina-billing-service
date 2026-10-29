param([Parameter(Mandatory = $true)][string]$RunRoot, [string]$Docker = 'C:/Program Files/Docker/Docker/resources/bin/docker.exe',
    [switch]$ContinueAfterProvisioning, [switch]$ContinueAfterApproval)
$ErrorActionPreference = 'Stop'
if ($ContinueAfterProvisioning -and $ContinueAfterApproval) { throw 'Select only one validated checkpoint' }
$env:PATH = (Split-Path $Docker) + ';C:/Windows/System32;' + $env:PATH
$manifest = Get-Content -Raw (Join-Path $RunRoot 'manifest.json') | ConvertFrom-Json
if ($manifest.project -notmatch '^oficina-e2e914-[a-f0-9]{12}$') { throw 'Invalid isolated E2E project' }
$startupLogRoot = Join-Path $RunRoot ('startup-' + [Guid]::NewGuid().ToString('N').Substring(0, 12))
New-Item -ItemType Directory $startupLogRoot | Out-Null
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$artifactHashes = [ordered]@{}
foreach ($service in @('os','execution','billing')) {
    $jar = Get-ChildItem "$workspace/tech-challenge-oficina-$service-service/target/*.jar" | Where-Object Name -NotLike '*original*' | Select-Object -First 1
    if (!$jar) { throw "Missing verified jar: $service" }
    $destination = "$RunRoot/jars/$service.jar"
    if (Test-Path $destination) {
        if ((Get-FileHash -Algorithm SHA256 $jar.FullName).Hash -ne (Get-FileHash -Algorithm SHA256 $destination).Hash) {
            throw "Immutable run jar differs from verified jar: $service; prepare a new execution"
        }
    } else {
        Copy-Item -LiteralPath $jar.FullName -Destination $destination
    }
    $artifactHashes[$service] = (Get-FileHash -Algorithm SHA256 $destination).Hash
}
$artifactHashes['auth'] = (Get-FileHash -Algorithm SHA256 "$workspace/tech-challenge-oficina-auth/target/tech-challenge-oficina-auth.jar").Hash
$hashFile = "$RunRoot/evidence/artifact-sha256-before-start.json"
if (Test-Path $hashFile) {
    $previousHashes = Get-Content -Raw $hashFile | ConvertFrom-Json
    foreach ($service in $artifactHashes.Keys) { if ($artifactHashes[$service] -ne $previousHashes.$service) { throw "Previously recorded hash differs: $service" } }
} else {
    $artifactHashes | ConvertTo-Json | Set-Content -Encoding UTF8 $hashFile
}
$arguments = @('compose','--env-file',"$RunRoot/private.env",'-p',$manifest.project,'-f',"$PSScriptRoot/compose.yaml")
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot/memory.ps1"
Measure-E2EMemory -RunRoot $RunRoot -Label 'before-start' -Docker $Docker
& $Docker @arguments --profile tools build runner *> "$startupLogRoot/runner-build.log"
if ($LASTEXITCODE -ne 0) { throw 'Runner build failed' }
foreach ($service in @('auth-db','os-db','billing-db','mongodb','rabbitmq','auth','os-service','execution-service','billing-service','mercado-pago-mock')) {
    Measure-E2EMemory -RunRoot $RunRoot -Label "before-$service" -Docker $Docker
    & $Docker @arguments up -d --no-deps $service *> "$startupLogRoot/$service.log"
    if ($LASTEXITCODE -ne 0) { throw "Compose startup failed: $service" }
    $deadline = [DateTime]::UtcNow.AddSeconds(360)
    do {
        Start-Sleep -Seconds 3
        Measure-E2EMemory -RunRoot $RunRoot -Label "starting-$service" -Docker $Docker
        $health = & $Docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$($manifest.project)-$service-1"
        if ($LASTEXITCODE -ne 0) { throw "Cannot inspect readiness: $service" }
        if ($health -eq 'healthy') { break }
        if ([DateTime]::UtcNow -ge $deadline) { throw "Readiness timeout: $service" }
    } while ($true)
    Measure-E2EMemory -RunRoot $RunRoot -Label "ready-$service" -Docker $Docker
    if ($service -in @('auth','os-service','execution-service','billing-service') -and !(Test-Path "$RunRoot/evidence/jvm-$service.txt")) {
        & $Docker @arguments exec -T $service jcmd 1 VM.flags -all 2>&1 | Select-String 'InitialHeapSize|MaxHeapSize|MaxRAMPercentage|UseContainerSupport|ActiveProcessorCount' | Set-Content -Encoding UTF8 "$RunRoot/evidence/jvm-$service.txt"
        if ($LASTEXITCODE -ne 0) { throw "JVM inspection failed: $service" }
    }
}
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
$flowPhase = if ($ContinueAfterApproval) { 'resume-after-approval' } else { 'bdd' }
& $Docker @arguments --profile tools run --rm runner python /runner.py $flowPhase *> "$RunRoot/flow-$flowPhase.log"
$flowExit = $LASTEXITCODE
@{ phase = $flowPhase; exitCode = $flowExit } | ConvertTo-Json -Compress | Add-Content -Encoding UTF8 "$RunRoot/evidence/flow-process-exits.jsonl"
if ($flowExit -ne 0 -and !$ContinueAfterApproval) {
    $checkpoint = Get-Content -Raw "$RunRoot/evidence/result.json" | ConvertFrom-Json
    if ($checkpoint.status -eq 'FAILED' -and $checkpoint.error -like '*/aprovar: expected success, received 0*') {
        Copy-Item -LiteralPath "$RunRoot/evidence/result.json" -Destination "$RunRoot/evidence/approval-checkpoint-result.json"
        Copy-Item -LiteralPath "$RunRoot/evidence/bdd.json" -Destination "$RunRoot/evidence/approval-checkpoint-bdd.json"
        $flowPhase = 'resume-after-approval'
        & $Docker @arguments --profile tools run --rm runner python /runner.py $flowPhase *> "$RunRoot/flow-$flowPhase.log"
        $flowExit = $LASTEXITCODE
        @{ phase = $flowPhase; exitCode = $flowExit } | ConvertTo-Json -Compress | Add-Content -Encoding UTF8 "$RunRoot/evidence/flow-process-exits.jsonl"
    }
}
& $Docker @arguments ps --format json *> "$RunRoot/evidence/containers.json"
if ($flowExit -ne 0) { throw "Authenticated E2E failed; inspect evidence/result.json and flow-$flowPhase.log" }
Measure-E2EMemory -RunRoot $RunRoot -Label 'before-compensations' -Docker $Docker
& $Docker @arguments --profile tools run --rm runner python /runner.py prepare-compensations *> "$RunRoot/compensation-preparation.log"
if ($LASTEXITCODE -ne 0) { throw 'Compensation preparation failed' }
$fixture = Get-Content -Raw "$RunRoot/runtime/failure-fixture.json" | ConvertFrom-Json
Measure-E2EMemory -RunRoot $RunRoot -Label 'before-failure-fixture' -Docker $Docker
& $Docker @arguments exec -T execution-service sh -ec 'mkdir -p /tmp/e2e914/classes; cd /tmp/e2e914; jar xf /app.jar; javac -cp "BOOT-INF/classes:BOOT-INF/lib/*" -d classes /fixture/RepairFailureFixture.java; exec java -Xmx256m -XX:ActiveProcessorCount=2 -Djavax.net.ssl.trustStore=/tls/identity.p12 -Djavax.net.ssl.trustStorePassword="$TLS_PASSWORD" -cp "classes:BOOT-INF/classes:BOOT-INF/lib/*" integration.RepairFailureFixture "$1"' fixture $fixture.repairId *> "$RunRoot/repair-failure-fixture.log"
if ($LASTEXITCODE -ne 0) { throw 'Domain repair failure fixture failed' }
& $Docker @arguments --profile tools run --rm runner python /runner.py verify-compensations *> "$RunRoot/compensations.log"
if ($LASTEXITCODE -ne 0) { throw 'Compensation checks failed' }
Measure-E2EMemory -RunRoot $RunRoot -Label 'after-compensations' -Docker $Docker
& "$PSScriptRoot/recover.ps1" -RunRoot $RunRoot -Docker $Docker
& "$PSScriptRoot/finalize.ps1" -RunRoot $RunRoot -Docker $Docker
