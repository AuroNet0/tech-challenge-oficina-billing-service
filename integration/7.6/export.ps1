param([Parameter(Mandatory = $true)][string]$RunRoot, [switch]$MetricsOnly)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot/../..").Path
$destination = Join-Path $PSScriptRoot 'evidence'
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$result = Get-Content -Raw (Join-Path $RunRoot 'result.json') | ConvertFrom-Json
$safe = [ordered]@{status = $result.status; runId = $result.runId; project = $result.project; revisions = $result.revisions; ports = $result.ports; completedAt = $result.completedAt; checks = $result.checks; failure = $result.failure; scenarios = @()}
foreach($case in $result.scenarios) {
    $safe.scenarios += [ordered]@{name = $case.name; osId = $case.osId; sagaId = $case.sagaId; correlationId = $case.correlationId; diagnosticId = $case.diagnosticId; quoteId = $case.quoteId; repairId = $case.repairId; paymentId = $case.paymentId; providerPaymentId = $case.providerPaymentId; quote = $case.quote; decision = $case.decision; checkout = $case.checkout; paymentBefore = $case.paymentBefore; paymentAfter = $case.paymentAfter; finalStates = $case.finalStates; counts = $case.counts; persistence = $case.persistence}
}
$safe | ConvertTo-Json -Depth 50 | Set-Content -Encoding UTF8 (Join-Path $destination 'result.json')
foreach($name in @('manifest.json','event-timeline.json','preflight-queues.json','preflight-bindings.json','os-migrations.json','billing-migrations.json','queues.json','containers.jsonl','checkout-concurrency.json','broker-outage.json','unknown-response.json','mock-state.json','http-trace.json')) {
    $path = Join-Path $RunRoot $name
    if(Test-Path -LiteralPath $path){Copy-Item -LiteralPath $path -Destination (Join-Path $destination $name)}
}
$metrics = @()
foreach($service in @('os','execution','billing')) {
    $repo = (Resolve-Path "$root/../tech-challenge-oficina-$service-service").Path
    $logName = "bootstrap-7.6-$service-verify.log"
    if($service -eq 'billing' -and (Test-Path (Join-Path $root 'bootstrap-7.6-billing-retry-verify.log'))){$logName = 'bootstrap-7.6-billing-retry-verify.log'}
    $log = Get-Content -Raw (Join-Path $root $logName)
    $summary = [regex]::Matches($log, 'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)') | Select-Object -Last 1
    $line = $null
    $jacoco = Join-Path $repo 'target/site/jacoco/jacoco.xml'
    if(Test-Path -LiteralPath $jacoco){[xml]$coverage = Get-Content $jacoco; $line = $coverage.report.counter | Where-Object type -eq LINE}
    $metrics += @{service = $service; log = $logName; buildSuccess = $log.Contains('BUILD SUCCESS'); coverageGateReached = $log.Contains('All coverage checks have been met.'); tests = [int]$summary.Groups[1].Value; failures = [int]$summary.Groups[2].Value; errors = [int]$summary.Groups[3].Value; skipped = [int]$summary.Groups[4].Value; coveredLines = $line.covered; missedLines = $line.missed}
}
ConvertTo-Json -InputObject $metrics -Depth 10 | Set-Content -Encoding UTF8 (Join-Path $destination 'maven.json')
[xml]$providerTests = Get-Content (Join-Path $root 'target/surefire-reports/TEST-com.oficina.billing.payment.ProviderIntegrationTests.xml')
$applicationCases = @($providerTests.testsuite.testcase | Where-Object {$_.name -match 'lateApprovalOfTheFirstAttempt|concurrentWebhookAndReconciliation|twoExternalApprovals'} | ForEach-Object {@{name = $_.name; time = $_.time; passed = $null -eq $_.failure -and $null -eq $_.error; scope = 'application integration, not distributed HTTP E2E'}})
ConvertTo-Json -InputObject $applicationCases -Depth 10 | Set-Content -Encoding UTF8 (Join-Path $destination 'application-integration.json')
if($MetricsOnly){Write-Output 'Metricas atualizadas; nenhuma consulta ao ambiente parado'; exit 0}
$container = "$($result.project)-billing-db-1"
$docker = 'C:/Program Files/Docker/Docker/resources/bin/docker.exe'
$queries = @{
    'provider-ledger.json' = 'SELECT json_agg(record) FROM (SELECT payment_id,attempt_id,external_id,status,provider_updated_at,observed_at,previous_attempt FROM provider_observations ORDER BY observed_at,id) record;'
    'notifications.json' = 'SELECT json_agg(record) FROM (SELECT id,resource_id,request_id,status,attempts,received_at,processed_at,last_error FROM provider_notifications ORDER BY received_at,id) record;'
    'provider-sync.json' = 'SELECT json_agg(record) FROM (SELECT payment_id,next_sync_at,last_sync_at,last_attempt_at,attempts,last_error,review_reason FROM provider_sync ORDER BY payment_id) record;'
    'billing-messaging-counts.json' = 'SELECT json_build_object(''inbox'',(SELECT json_agg(record) FROM (SELECT status,count(*) FROM inbox_messages GROUP BY status) record),''outbox'',(SELECT json_agg(record) FROM (SELECT event_type,status,count(*) FROM outbox_events GROUP BY event_type,status ORDER BY event_type) record));'
}
foreach($entry in $queries.GetEnumerator()) {
    $data = & $docker exec $container psql -v ON_ERROR_STOP=1 -U integration -d oficina_billing -Atc $entry.Value
    if($LASTEXITCODE -ne 0){throw 'Falha ao exportar evidencia PostgreSQL'}
    $data | Set-Content -Encoding UTF8 (Join-Path $destination $entry.Key)
}
Write-Output "Exportado sem headers/cookies/secrets: $destination"
