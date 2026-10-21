param(
    [Parameter(Mandatory = $true)][string]$RunRoot,
    [string]$Destination
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Destination)) {
    $Destination = Join-Path (Resolve-Path "$PSScriptRoot/../..").Path 'docs/evidence/7.4.1'
}
function Read-Artifact($Name) {
    $path = Join-Path $RunRoot "$Name.json"
    if (-not (Test-Path $path)) { return }
    $parsed = Get-Content -Raw $path | ConvertFrom-Json
    foreach ($entry in $parsed) { $entry }
}
function Digest($Value) {
    $hash = [Security.Cryptography.SHA256]::Create()
    try { [BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value))).Replace('-', '').ToLowerInvariant() }
    finally { $hash.Dispose() }
}
$result = Read-Artifact 'result'
if ($result.status -ne 'PASSED') { throw 'Exportacao de sucesso exige result PASSED real' }
$quote = Read-Artifact 'quote-final'
$decision = Read-Artifact 'decision'
$decisionTimestamp = if ($result.scenario -eq 'Main') { $quote.approvedAt } else { $quote.rejectedAt }
if ($decisionTimestamp -cne $decision.decisionAt) { throw 'Timestamp da decisao divergente' }
$timeline = @(Read-Artifact 'event-timeline')
if (@($timeline | Where-Object { $_.eventType -eq 'quote.created.v1' -and $_.payload.quoteId -ne $result.quoteId }).Count) { throw 'quoteId real divergente' }
if ($result.scenario -eq 'Main' -and @($timeline | Where-Object { $_.eventType -eq 'payment.request.v1' -and $_.payload.repairId -ne $result.repairId }).Count) { throw 'repairId real divergente' }
$summary = [ordered]@{
    result = $result
    checkpoints = @(Read-Artifact 'checkpoints')
    quote = $quote
    decision = $decision
    payment = (Read-Artifact 'payment')
    diagnostic = (Read-Artifact 'diagnostic-completed')
    repair = (Read-Artifact 'repair-completed')
    quoteConcurrency = (Read-Artifact 'quote-concurrency-after')
    approvalConcurrency = (Read-Artifact 'approval-concurrency')
    brokerOutage = (Read-Artifact 'broker-outage')
    timeline = $timeline
    queues = @(Read-Artifact 'queues')
    securityObservations = @(Read-Artifact 'security-observations')
    bindings = @(Read-Artifact 'bindings')
    brokerConnections = @(Read-Artifact 'broker-connections')
    containersAtCompletion = @(Get-Content (Join-Path $RunRoot 'containers.jsonl') | ForEach-Object { $_ | ConvertFrom-Json } | Select-Object Name, Service, State, Health, ID, Image)
    migrations = @{os = @(Read-Artifact 'os-migrations'); billing = @(Read-Artifact 'billing-migrations')}
    originalEnvironments = @(Read-Artifact 'original-environments-preserved')
    supplementalChecks = @{decisionTimestampIdentical = $true; quoteIdMatchesPersistedRecord = $true}
}
if ($result.scenario -eq 'Main') {
    $summary.supplementalChecks.repairIdMatchesPersistedRecord = $true
    $queued = Read-Artifact 'quote-concurrency-before'
    if ($queued.deliveries.Count -ne 2 -or $queued.deliveries[0].payload -cne $queued.deliveries[1].payload) { throw 'Entregas concorrentes divergentes' }
    $summary.quoteConcurrencyBefore = @{deliveries = $queued.deliveries.Count; inboxCount = [int]$queued.inboxCount; identicalBodies = $true; sha256 = (Digest $queued.deliveries[0].payload)}
    $before = Read-Artifact 'before-replay'
    $after = Read-Artifact 'after-replay'
    $hashes = [ordered]@{}
    foreach ($service in @('os', 'billing', 'execution')) {
        if ($before.$service -cne $after.$service) { throw "Snapshot divergiu: $service" }
        $hashes[$service] = @{beforeSha256 = (Digest $before.$service); afterSha256 = (Digest $after.$service); identical = $true}
    }
    $summary.replaySnapshots = $hashes
    $summary.originalReplays = @((Read-Artifact 'original-replays') | ForEach-Object {
        $envelope = $_.body | ConvertFrom-Json
        @{exchange = $_.exchange; eventId = $envelope.eventId; eventType = $envelope.eventType; rawEnvelope = $_.body; sha256 = (Digest $_.body)}
    })
    $financialSnapshot = $after.billing | ConvertFrom-Json
    $summary.persistedCounts = @{quotes = @($financialSnapshot.orcamentos).Count; items = @($financialSnapshot.itens).Count; decisions = @($financialSnapshot.aprovacoes).Count; payments = @($financialSnapshot.pagamentos).Count; attempts = @($financialSnapshot.tentativas).Count; inbox = @($financialSnapshot.inbox).Count; outbox = @($financialSnapshot.outbox).Count}
    $summary.finalOutbox = @($financialSnapshot.outbox | Select-Object event_id, event_type, status, attempts, published_at)
} else {
    $snapshot = Read-Artifact 'final-snapshot'
    $financialSnapshot = $snapshot.billing | ConvertFrom-Json
    $summary.persistedCounts = @{quotes = @($financialSnapshot.orcamentos | Where-Object { $null -ne $_ }).Count; items = @($financialSnapshot.itens | Where-Object { $null -ne $_ }).Count; decisions = @($financialSnapshot.aprovacoes | Where-Object { $null -ne $_ }).Count; payments = @($financialSnapshot.pagamentos | Where-Object { $null -ne $_ }).Count; attempts = @($financialSnapshot.tentativas | Where-Object { $null -ne $_ }).Count; inbox = @($financialSnapshot.inbox | Where-Object { $null -ne $_ }).Count; outbox = @($financialSnapshot.outbox | Where-Object { $null -ne $_ }).Count}
    $summary.finalOutbox = @($financialSnapshot.outbox | Select-Object event_id, event_type, status, attempts, published_at)
}
New-Item -ItemType Directory -Path $Destination -Force | Out-Null
$name = 'corrected-' + $result.scenario.ToLowerInvariant() + '.json'
ConvertTo-Json -InputObject $summary -Depth 50 | Set-Content -Encoding UTF8 (Join-Path $Destination $name)
Write-Output "Evidencia selecionada: $Destination/$name"
