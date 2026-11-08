param(
    [Parameter(Mandatory = $true)][string]$RunRoot,
    [string]$Docker = 'C:/Program Files/Docker/Docker/resources/bin/docker.exe'
)
$ErrorActionPreference = 'Stop'
$manifest = Get-Content -Raw (Join-Path $RunRoot 'manifest.json') | ConvertFrom-Json
$env:E2E_SOURCE_ROOT = $manifest.sourceRoot
$composeArgs = @('compose', '-p', $manifest.project, '-f', $manifest.compose)
$authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('integration:integration_local_only'))
$headers = @{Authorization = $authorization}

function Save-Evidence($Name, $Value) {
    $Value | ConvertTo-Json -Depth 40 | Set-Content -Encoding UTF8 (Join-Path $RunRoot "$Name.json")
}

function Get-Api($Port, $Path) {
    Invoke-RestMethod -Uri "http://127.0.0.1:$Port$Path" -Headers $headers -TimeoutSec 15
}

function Post-Os($Path, $Body) {
    Invoke-RestMethod -Uri "http://127.0.0.1:18080/api/v1/$Path" -Method Post -Headers $headers -ContentType 'application/json' -Body ($Body | ConvertTo-Json) -TimeoutSec 15
}

foreach ($port in @(18080, 18081, 18082)) {
    $health = Get-Api $port '/actuator/health/readiness'
    if ($health.status -ne 'UP') { throw "Readiness indisponivel: $port" }
    Save-Evidence "readiness-$port" $health
}
$existing = & $Docker @composeArgs exec -T os-db psql -U integration -d oficina_os -Atc 'SELECT count(*) FROM ordens_servico;'
if ($LASTEXITCODE -ne 0 -or "$existing".Trim() -ne '0') { throw 'Probe exige banco novo, sem OS anteriores. Execute prepare.ps1 novamente.' }
Save-Evidence 'database-initial' @{ordensServico = [int]"$existing".Trim(); verifiedAt = [DateTime]::UtcNow.ToString('o')}
$client = Post-Os 'clientes' @{nome = 'Cliente sintetico E2E'; cpfCnpj = '52998224725'}
$vehicle = Post-Os 'veiculos' @{clienteId = $client.id; placa = 'TST7A41'; marca = 'Teste'; modelo = 'Integracao'; ano = 2026}
$branch = Post-Os 'filiais' @{nome = 'Filial E2E'; codigo = 'E2E741'; cidade = 'Sao Paulo'; uf = 'SP'}
Save-Evidence 'registration' @{clienteId = $client.id; veiculoId = $vehicle.id; filialId = $branch.id}
$order = Post-Os 'ordens-servico' @{clienteId = $client.id; veiculoId = $vehicle.id; filialId = $branch.id; observacoes = 'Integracao isolada 7.4.1, sem pagamento externo'}
Save-Evidence 'order-created' $order
$saga = Get-Api 18080 "/api/v1/ordens-servico/$($order.id)/saga"
Save-Evidence 'saga-initial' $saga
if ($saga.status -ne 'WAITING_DIAGNOSTIC') { throw 'Saga inicial inesperada' }
$diagnostic = $null
$deadline = [DateTime]::UtcNow.AddSeconds(75)
while ([DateTime]::UtcNow -lt $deadline) {
    try { $diagnostic = Get-Api 18081 "/api/v1/diagnosticos?osId=$($order.id)"; break }
    catch {
        if ([int]$_.Exception.Response.StatusCode -ne 404) { throw }
        Start-Sleep -Seconds 2
    }
}
if ($null -ne $diagnostic) { Save-Evidence 'diagnostic-started' $diagnostic }
Save-Evidence 'order-final' (Get-Api 18080 "/api/v1/ordens-servico/$($order.id)")
Save-Evidence 'saga-final' (Get-Api 18080 "/api/v1/ordens-servico/$($order.id)/saga")
$outboxSql = "SELECT coalesce(json_agg(row_to_json(events)), '[]'::json) FROM (SELECT id, event_type, payload, status, attempts, created_at, published_at, last_error FROM outbox_events ORDER BY created_at) events;"
& $Docker @composeArgs exec -T os-db psql -U integration -d oficina_os -Atc $outboxSql | Set-Content -Encoding UTF8 (Join-Path $RunRoot 'os-outbox.json')
$queueUrl = 'http://127.0.0.1:15675/api/queues/%2F'
$queues = Invoke-RestMethod -Uri $queueUrl -Headers $headers -TimeoutSec 15
Save-Evidence 'queues' @($queues | Select-Object name, consumers, messages, messages_ready, messages_unacknowledged)
$bindings = Invoke-RestMethod -Uri 'http://127.0.0.1:15675/api/bindings/%2F' -Headers $headers -TimeoutSec 15
Save-Evidence 'bindings' @($bindings | Select-Object source, destination, routing_key)
$connections = Invoke-RestMethod -Uri 'http://127.0.0.1:15675/api/connections' -Headers $headers -TimeoutSec 15
Save-Evidence 'broker-connections' @($connections | Select-Object name, user, vhost, peer_host, state)
$dlq = Invoke-RestMethod -Uri "$queueUrl/execution.commands.dlq/get" -Headers $headers -Method Post -ContentType 'application/json' -Body '{"count":20,"ackmode":"ack_requeue_true","encoding":"auto","truncate":100000}' -TimeoutSec 15
Save-Evidence 'execution-dlq' @($dlq)
& $Docker @composeArgs logs --no-color --timestamps os execution billing *> (Join-Path $RunRoot 'services.log')
& $Docker @composeArgs ps --format json | Set-Content -Encoding UTF8 (Join-Path $RunRoot 'containers.jsonl')
& $Docker @composeArgs exec -T billing-db psql -U integration -d oficina_billing -Atc "SELECT json_build_object('orcamentos',(SELECT count(*) FROM orcamentos),'aprovacoes',(SELECT count(*) FROM aprovacoes),'pagamentos',(SELECT count(*) FROM pagamentos),'tentativas',(SELECT count(*) FROM pagamentos_tentativas),'inbox',(SELECT count(*) FROM inbox_messages),'outbox',(SELECT count(*) FROM outbox_events));" | Set-Content -Encoding UTF8 (Join-Path $RunRoot 'billing-counts.json')
$mongoQuery = 'JSON.stringify({diagnostics:db.diagnostics.countDocuments({}),repairs:db.work_orders.countDocuments({}),inbox:db.inbox_messages.countDocuments({}),outbox:db.outbox_events.countDocuments({}),replicaSet:db.hello().setName,primary:db.hello().isWritablePrimary})'
& $Docker @composeArgs exec -T mongodb mongosh --quiet --username integration --password integration_local_only --authenticationDatabase admin oficina_execution --eval $mongoQuery | Set-Content -Encoding UTF8 (Join-Path $RunRoot 'execution-counts.json')
foreach ($database in @('os', 'billing')) {
    & $Docker @composeArgs exec -T "$database-db" psql -U integration -d "oficina_$database" -Atc 'SELECT json_agg(row_to_json(migrations)) FROM (SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank) migrations;' | Set-Content -Encoding UTF8 (Join-Path $RunRoot "$database-migrations.json")
}
$originalContainers = @(Get-Content (Join-Path $RunRoot 'original-containers.jsonl') | ForEach-Object { $_ | ConvertFrom-Json })
$currentContainers = @(& $Docker ps --no-trunc --format '{{json .}}' | ForEach-Object { $_ | ConvertFrom-Json })
$preserved = @($originalContainers | ForEach-Object {
    $original = $_
    $current = @($currentContainers | Where-Object { $_.ID -eq $original.ID -and $_.Names -eq $original.Names })
    @{name = $original.Names; sameContainerStillRunning = ($current.Count -eq 1)}
})
Save-Evidence 'original-environments-preserved' $preserved
if ($null -eq $diagnostic) {
    Save-Evidence 'result' @{status = 'BLOCKED'; checkpoint = 'diagnostic.start.v1 -> Execution'; osId = $order.id; sagaId = $saga.sagaId; capturedAt = [DateTime]::UtcNow.ToString('o'); nextScenariosExecuted = $false}
    throw "BLOQUEIO: diagnostico nao criado. Evidencias em $RunRoot. Nenhum evento foi fabricado ou contrato alterado."
}
Save-Evidence 'result' @{status = 'CHECKPOINT_PASSED'; checkpoint = 'diagnostic.start.v1 -> Execution'; osId = $order.id; sagaId = $saga.sagaId; nextScenariosExecuted = $false}
Write-Output 'Checkpoint inicial passou. Este probe nao executa as etapas financeiras posteriores.'
