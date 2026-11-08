param([Parameter(Mandatory = $true)][string]$RunRoot,
    [string]$Git = 'C:/Program Files/Git/cmd/git.exe',
    [string]$Docker = 'C:/Program Files/Docker/Docker/resources/bin/docker.exe',
    [switch]$VerificationOnly,
    [string]$VerificationDirectory = (Join-Path $PSScriptRoot 'artifacts/verify'))
$ErrorActionPreference = 'Stop'
$manifest = Get-Content -Raw "$RunRoot/manifest.json" | ConvertFrom-Json
if ($manifest.project -notmatch '^oficina-e2e914-[a-f0-9]{12}$') { throw 'Invalid isolated E2E project' }
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$destination = Join-Path $PSScriptRoot "evidence/$($manifest.runId)"
New-Item -ItemType Directory -Force $destination | Out-Null
$verification = @()
$revisions = [ordered]@{}
foreach ($service in @('auth','os-service','execution-service','billing-service')) {
    $repo = Join-Path $workspace "tech-challenge-oficina-$service"
    $revisions[$service] = @{ branch = (& $Git -C $repo branch --show-current); commit = (& $Git -C $repo rev-parse HEAD); changes = @(& $Git -C $repo status --short) }
    $totals = @{ tests = 0; failures = 0; errors = 0; skipped = 0 }
    $classes = @()
    foreach ($report in Get-ChildItem "$repo/target/surefire-reports/TEST-*.xml","$repo/target/failsafe-reports/TEST-*.xml" -ErrorAction SilentlyContinue) {
        [xml]$document = Get-Content $report.FullName
        foreach ($key in @('tests','failures','errors','skipped')) { $totals[$key] += [int]$document.testsuite.$key }
        $classes += @{ name = $document.testsuite.name; tests = [int]$document.testsuite.tests; failures = [int]$document.testsuite.failures; errors = [int]$document.testsuite.errors; skipped = [int]$document.testsuite.skipped }
    }
    [xml]$jacoco = Get-Content "$repo/target/site/jacoco/jacoco.xml"
    $counter = $jacoco.report.counter | Where-Object type -eq 'LINE'
    $coverage = [math]::Round(100 * [int]$counter.covered / ([int]$counter.covered + [int]$counter.missed), 2)
    $logName = "$service.log"
    if ($service -eq 'os-service' -and (Test-Path "$VerificationDirectory/os-final.log")) {
        if ((Get-Item "$VerificationDirectory/os-final.log").LastWriteTime -gt (Get-Item "$VerificationDirectory/os-service.log").LastWriteTime) {
            $logName = 'os-final.log'
        }
    }
    $buildSuccess = [bool](Select-String -LiteralPath "$VerificationDirectory/$logName" -Pattern '^\[INFO\] BUILD SUCCESS$' -Quiet)
    $passed = $buildSuccess -and $totals.tests -gt 0 -and $totals.failures -eq 0 -and $totals.errors -eq 0 -and $totals.skipped -eq 0 -and $coverage -ge 80
    $verification += @{ service = $service; counts = $totals; lineCoverage = $coverage; coveredLines = [int]$counter.covered; missedLines = [int]$counter.missed; passed = $passed; suites = $classes }
}
$verification | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 "$destination/verification.json"
$verification | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 "$VerificationDirectory/collection-result.json"
$revisions | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 "$destination/revisions.json"
if ($verification.passed -contains $false) { throw 'At least one clean verify or coverage requirement failed' }
if ($VerificationOnly) {
    @{ status = 'REGRESSION_ONLY'; runId = $manifest.runId; integratedEvidenceCollected = $false } |
        ConvertTo-Json | Set-Content -Encoding UTF8 "$destination/collection-status.json"
    Write-Output 'Regression evidence collected; integrated E2E/BDD is not validated'
    exit 0
}
foreach ($name in @('bdd','compensations')) {
    $gate = Get-Content -Raw "$RunRoot/evidence/$name.json" | ConvertFrom-Json
    if ($gate.status -ne 'PASSED') { throw "Required scenario failed: $name" }
}
$allowed = @('validation-global','result','preflight','provisioning','http-trace','bindings','auth-migrations','os-migrations','billing-migrations',
    'saga-initial','diagnostic','approval','repair','payment','order-final','saga-final','billing-outbox','os-outbox','execution-outbox',
    'billing-inbox','os-inbox','execution-inbox','financial-state','assignment-audit','dependency-unavailable','broker-outage','broker-recovery','queues-final','queues-dlq','bdd','notifications','compensations','late-failure')
foreach ($name in $allowed) {
    if (!(Test-Path "$RunRoot/evidence/$name.json")) { throw "Missing mandatory evidence: $name" }
    Copy-Item -LiteralPath "$RunRoot/evidence/$name.json" -Destination "$destination/$name.json"
}
$arguments = @('compose','--env-file',"$RunRoot/private.env",'-p',$manifest.project,'-f',"$PSScriptRoot/compose.yaml")
$containers = @()
foreach ($containerId in (& $Docker @arguments ps -q)) {
    $value = & $Docker inspect --format '{{.Id}}|{{.Name}}|{{.Config.Image}}|{{.Image}}|{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{end}}' $containerId
    $parts = $value -split '\|'
    $containers += @{ id = $parts[0]; name = $parts[1]; image = $parts[2]; imageId = $parts[3]; state = $parts[4]; health = $parts[5] }
}
$containers | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 "$destination/containers.json"
$versions = [ordered]@{
    dockerEngine = (& $Docker version --format '{{.Server.Version}}')
    java = ((& $Docker exec "$($manifest.project)-execution-service-1" java --version) -join ' ')
    postgres = (& $Docker exec "$($manifest.project)-os-db-1" psql -U e2e -d e2e_os -Atc 'SHOW server_version')
    mongo = (& $Docker exec "$($manifest.project)-mongodb-1" mongosh --quiet --eval 'db.version()')
    rabbitmq = (& $Docker exec "$($manifest.project)-rabbitmq-1" rabbitmq-diagnostics -q server_version)
}
$versions | ConvertTo-Json | Set-Content -Encoding UTF8 "$destination/runtime-versions.json"
$providerEvidence = Get-Content -Raw "$PSScriptRoot/export-provider.py" | & $Docker exec -i "$($manifest.project)-mercado-pago-mock-1" python -
if ($LASTEXITCODE -ne 0) { throw 'Simulated provider evidence failed' }
$providerEvidence | Set-Content -Encoding UTF8 "$destination/provider-evidence.json"
$artifacts = [ordered]@{}
foreach ($service in @('os','execution','billing')) {
    $testedJar = Get-ChildItem "$workspace/tech-challenge-oficina-$service-service/target/*.jar" | Where-Object Name -NotLike '*original*' | Select-Object -First 1
    $testedHash = (Get-FileHash -Algorithm SHA256 $testedJar.FullName).Hash
    $executedHash = (Get-FileHash -Algorithm SHA256 "$RunRoot/jars/$service.jar").Hash
    if ($testedHash -ne $executedHash) { throw "E2E jar differs from verified jar: $service" }
    $runtimeHash = ((& $Docker exec "$($manifest.project)-$service-service-1" sha256sum /tmp/oficina-app.jar) -split ' ')[0]
    if ($LASTEXITCODE -ne 0 -or $runtimeHash -ne $executedHash) { throw "Runtime jar differs from verified jar: $service" }
    $artifacts[$service] = $executedHash
}
$artifacts['auth'] = (Get-FileHash -Algorithm SHA256 "$workspace/tech-challenge-oficina-auth/target/tech-challenge-oficina-auth.jar").Hash
$artifacts | ConvertTo-Json | Set-Content -Encoding UTF8 "$destination/artifact-sha256.json"
$preservation = @()
foreach ($line in Get-Content "$RunRoot/evidence/original-containers.jsonl") {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $original = $line | ConvertFrom-Json
    if ($original.Labels -match 'org.testcontainers') { continue }
    $current = & $Docker inspect --format '{{.State.Status}}' $original.ID
    if ($LASTEXITCODE -ne 0) { throw 'A preexisting user container is missing' }
    $kind = if ($original.Names -like 'oficina-e2e914-*') { 'previous isolated E2E' } else { 'preexisting user container' }
    if ($kind -eq 'preexisting user container' -and $original.State -ne $current) { throw 'Preexisting user container state changed' }
    $preservation += @{ id = $original.ID; name = $original.Names; kind = $kind; before = $original.State; after = $current }
}
ConvertTo-Json -InputObject $preservation -Depth 5 | Set-Content -Encoding UTF8 "$destination/preservation.json"
$global = Get-Content -Raw "$destination/validation-global.json" | ConvertFrom-Json
if ($global.status -ne 'PASSED' -or $containers.Count -ne 10 -or @($containers | Where-Object health -ne 'healthy').Count -gt 0) {
    throw 'Global result or final container health failed'
}
@{ status = 'COMPLETE'; runId = $manifest.runId; integratedEvidenceCollected = $true } |
    ConvertTo-Json | Set-Content -Encoding UTF8 "$destination/collection-status.json"
Write-Output 'Sanitized evidence collected; all mandatory checks passed'
