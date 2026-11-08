param(
    [Parameter(Mandatory = $true)][string]$RunRoot,
    [string]$Destination
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Destination)) {
    $Destination = Join-Path (Resolve-Path "$PSScriptRoot/../..").Path 'docs/evidence/7.4.1'
}
function Read-Artifact($Name) {
    $parsed = Get-Content -Raw (Join-Path $RunRoot "$Name.json") | ConvertFrom-Json
    foreach ($entry in $parsed) { $entry }
}
$manifest = Read-Artifact 'manifest'
$delivery = @(Read-Artifact 'execution-dlq') | Select-Object -First 1
$body = $delivery.payload
$envelope = $body | ConvertFrom-Json
$hash = [Security.Cryptography.SHA256]::Create()
try {
    $bodyHash = [BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($body))).Replace('-', '').ToLowerInvariant()
} finally {
    $hash.Dispose()
}
$containers = @(Get-Content (Join-Path $RunRoot 'containers.jsonl') | ForEach-Object { $_ | ConvertFrom-Json })
$summary = [ordered]@{
    runId = $manifest.runId
    project = $manifest.project
    revisions = @{
        os = $manifest.revisions.os.commit
        execution = $manifest.revisions.execution.commit
        billing = $manifest.revisions.billing.commit
    }
    result = (Read-Artifact 'result')
    initialDatabase = (Read-Artifact 'database-initial')
    readiness = @{
        os = (Read-Artifact 'readiness-18080')
        execution = (Read-Artifact 'readiness-18081')
        billing = (Read-Artifact 'readiness-18082')
    }
    containers = @($containers | Select-Object Name, Service, State, Health, ID, Image)
    registration = (Read-Artifact 'registration')
    finalOrder = (Read-Artifact 'order-final')
    finalSaga = (Read-Artifact 'saga-final')
    originalDelivery = @{
        eventId = $envelope.eventId
        correlationId = $envelope.correlationId
        osId = $envelope.osId
        sagaId = $envelope.sagaId
        exchange = $delivery.exchange
        routingKey = $delivery.routing_key
        payloadBytes = $delivery.payload_bytes
        payloadSha256 = $bodyHash
        originalEnvelope = $body
        occurredAtIsJsonString = ($envelope.occurredAt -is [string])
        deliveryMode = $delivery.properties.delivery_mode
        executionAttempt = $delivery.properties.headers.'execution-attempt'
        executionResult = $delivery.properties.headers.'execution-result'
    }
    osOutbox = @((Read-Artifact 'os-outbox') | Select-Object id, event_type, status, attempts, created_at, published_at, last_error)
    queues = (Read-Artifact 'queues')
    bindings = @((Read-Artifact 'bindings') | Where-Object { $_.source -ne '' })
    brokerConnections = (Read-Artifact 'broker-connections')
    executionCounts = (Read-Artifact 'execution-counts')
    billingCounts = (Read-Artifact 'billing-counts')
    migrations = @{os = (Read-Artifact 'os-migrations'); billing = (Read-Artifact 'billing-migrations')}
    originalEnvironments = (Read-Artifact 'original-environments-preserved')
    logExcerpts = @(Select-String -Path (Join-Path $RunRoot 'services.log') -Pattern 'result=rejected|result=dlq|publication confirmed' | Select-Object -ExpandProperty Line)
}
New-Item -ItemType Directory -Path $Destination -Force | Out-Null
$summary | ConvertTo-Json -Depth 40 | Set-Content -Encoding UTF8 (Join-Path $Destination 'baseline-blocked.json')
Write-Output "Evidencia selecionada: $Destination/baseline-blocked.json"
