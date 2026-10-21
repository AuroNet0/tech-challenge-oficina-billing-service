param(
    [Parameter(Mandatory = $true)][string]$RunRoot,
    [ValidateSet('Main', 'Rejection')][string]$Scenario = 'Main',
    [string]$Docker = 'C:/Program Files/Docker/Docker/resources/bin/docker.exe'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$manifest = Get-Content -Raw (Join-Path $RunRoot 'manifest.json') | ConvertFrom-Json
$env:E2E_SOURCE_ROOT = $manifest.sourceRoot
$env:E2E_OS_PORT = "$($manifest.ports.os)"
$env:E2E_EXECUTION_PORT = "$($manifest.ports.execution)"
$env:E2E_BILLING_PORT = "$($manifest.ports.billing)"
$env:E2E_RABBIT_MANAGEMENT_PORT = "$($manifest.ports.rabbitManagement)"
$composeArgs = @('compose', '-p', $manifest.project, '-f', $manifest.compose)
$authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('integration:integration_local_only'))
$headers = @{Authorization = $authorization}
$brokerUrl = "http://127.0.0.1:$($manifest.ports.rabbitManagement)/api"
$httpTrace = [Collections.Generic.List[object]]::new()
$checks = [Collections.Generic.List[string]]::new()
$checkpoints = [Collections.Generic.List[object]]::new()
$script:step = 'initialization'
$script:lastCsrf = $null
$billingSession = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
$result = [ordered]@{status = 'RUNNING'; scenario = $Scenario; runId = $manifest.runId; project = $manifest.project; revisions = $manifest.revisions; ports = $manifest.ports}

function Save($Name, $Value) {
    ConvertTo-Json -InputObject $Value -Depth 50 | Set-Content -Encoding UTF8 (Join-Path $RunRoot "$Name.json")
}

function Parse-Json($Text) {
    if ($Text -is [byte[]]) { $Text = [Text.Encoding]::UTF8.GetString($Text) }
    $parsed = ConvertFrom-Json -InputObject $Text
    foreach ($entry in $parsed) { $entry }
}

function Check($Condition, $Message) {
    if (-not $Condition) { throw "ASSERT: $Message" }
    $checks.Add($Message)
}

function Step($Name) {
    $script:step = $Name
    $checkpoints.Add(@{name = $Name; at = [DateTime]::UtcNow.ToString('o')})
    Save 'checkpoints' $checkpoints.ToArray()
    Write-Host "$([DateTime]::UtcNow.ToString('o')) $Name"
}

function Api($Service, $Method, $Path, $Body = $null, $Session = $null, $Csrf = $null) {
    $port = $manifest.ports.$Service
    $requestHeaders = @{Authorization = $authorization}
    if ($Csrf) { $requestHeaders['X-CSRF-TOKEN'] = $Csrf }
    $arguments = @{Uri = "http://127.0.0.1:$port$Path"; Method = $Method; Headers = $requestHeaders; UseBasicParsing = $true; TimeoutSec = 20}
    if ($null -ne $Body) { $arguments.ContentType = 'application/json'; $arguments.Body = ConvertTo-Json -InputObject $Body -Depth 20 }
    if ($null -ne $Session) { $arguments.WebSession = $Session }
    try {
        $response = Invoke-WebRequest @arguments
        $status = [int]$response.StatusCode
        $content = $response.Content
        if ($response.Headers['X-CSRF-TOKEN']) { $script:lastCsrf = $response.Headers['X-CSRF-TOKEN'] }
    } catch {
        if ($null -eq $_.Exception.Response) {
            $status = 0
            $content = $null
        } else {
            $status = [int]$_.Exception.Response.StatusCode
            $reader = [IO.StreamReader]::new($_.Exception.Response.GetResponseStream())
            try { $content = $reader.ReadToEnd() } finally { $reader.Dispose() }
        }
    }
    $httpTrace.Add(@{at = [DateTime]::UtcNow.ToString('o'); service = $Service; method = $Method; path = $Path; status = $status})
    Save 'http-trace' $httpTrace.ToArray()
    $bodyValue = if ([string]::IsNullOrWhiteSpace($content)) { $null } else { Parse-Json $content }
    return [pscustomobject]@{status = $status; body = $bodyValue}
}

function Required-Api($Service, $Method, $Path, $Body = $null, $Session = $null, $Csrf = $null) {
    $response = Api $Service $Method $Path $Body $Session $Csrf
    if ($response.status -notin @(200, 201)) { throw "HTTP $($response.status): $Service $Method $Path" }
    return $response.body
}

function Until($Name, [scriptblock]$Poll, $Timeout = 240) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Timeout)
    while ([DateTime]::UtcNow -lt $deadline) {
        $value = & $Poll
        if ($value) { return $value }
        Start-Sleep -Seconds 1
    }
    throw "TIMEOUT: $Name"
}

function Wait-Api($Service, $Path, [scriptblock]$Predicate) {
    Until "$Service $Path" {
        $response = Api $Service Get $Path
        if ($response.status -eq 200) {
            if (& $Predicate $response.body) { return $response.body }
        } elseif ($response.status -notin @(0, 404, 503)) { throw "HTTP $($response.status): $Service $Path" }
    }
}

function Sql($Database, $Query) {
    $output = & $Docker @composeArgs exec -T "$Database-db" psql -v ON_ERROR_STOP=1 -U integration -d "oficina_$Database" -Atc $Query
    if ($LASTEXITCODE -ne 0) { throw "Consulta PostgreSQL falhou: $Database" }
    return ($output -join "`n").Trim()
}

function Mongo($Query) {
    $output = & $Docker @composeArgs exec -T mongodb mongosh --quiet --username integration --password integration_local_only --authenticationDatabase admin oficina_execution --eval $Query
    if ($LASTEXITCODE -ne 0) { throw 'Consulta MongoDB falhou' }
    return ($output -join "`n").Trim()
}

function Compose($Arguments) {
    $preference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Docker @composeArgs @Arguments *> (Join-Path $RunRoot 'docker-last-operation.log')
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $preference }
    if ($exitCode -ne 0) { throw "Docker Compose falhou: $Arguments" }
}

function Broker($Method, $Path, $Body = $null) {
    $arguments = @{Uri = "$brokerUrl/$Path"; Headers = $headers; Method = $Method; TimeoutSec = 20}
    if ($null -ne $Body) { $arguments.ContentType = 'application/json'; $arguments.Body = ConvertTo-Json -InputObject $Body -Depth 20 }
    $response = Invoke-RestMethod @arguments
    foreach ($entry in $response) { $entry }
}

function Publish-Original($Exchange, $RawBody) {
    $envelope = Parse-Json $RawBody
    $reply = Broker Post "exchanges/%2F/$Exchange/publish" @{
        routing_key = $envelope.eventType; payload = $RawBody; payload_encoding = 'string'
        properties = @{delivery_mode = 2; content_type = 'application/json'; message_id = $envelope.eventId; correlation_id = $envelope.correlationId}
    }
    Check ($reply.routed -eq $true) "Replay roteado: $($envelope.eventType) $($envelope.eventId)"
}

function Os-Command($EventType) {
    $raw = Sql os "SELECT payload::text FROM outbox_events WHERE aggregate_id='$($script:order.id)' AND event_type='$EventType';"
    if (-not $raw) { throw "Comando original ausente: $EventType" }
    return $raw
}

function Billing-Event($EventType) {
    $raw = Sql billing "SELECT payload FROM outbox_events WHERE os_id='$($script:order.id)' AND event_type='$EventType';"
    if (-not $raw) { throw "Evento original ausente: $EventType" }
    return $raw
}

function Wait-States($OrderState, $SagaState) {
    $currentOrder = Wait-Api os "/api/v1/ordens-servico/$($script:order.id)" {param($value) $value.status -eq $OrderState}
    $currentSaga = Wait-Api os "/api/v1/ordens-servico/$($script:order.id)/saga" {param($value) $value.status -eq $SagaState}
    Save "state-$OrderState" @{order = $currentOrder; saga = $currentSaga}
    return @{order = $currentOrder; saga = $currentSaga}
}

function Wait-Published {
    Until 'all outboxes published' {
        $osPending = [int](Sql os "SELECT count(*) FROM outbox_events WHERE status <> 'PUBLISHED';")
        $billingPending = [int](Sql billing "SELECT count(*) FROM outbox_events WHERE status <> 'PUBLISHED';")
        $executionPending = [int](Mongo "db.outbox_events.countDocuments({status:{`$ne:'PUBLISHED'}})")
        $osPending -eq 0 -and $billingPending -eq 0 -and $executionPending -eq 0
    } | Out-Null
}

function Snapshot($Name) {
    $osSnapshot = Sql os "SELECT json_build_object('ordens',(SELECT json_agg(row_to_json(records)) FROM (SELECT * FROM ordens_servico ORDER BY id) records),'sagas',(SELECT json_agg(row_to_json(records)) FROM (SELECT * FROM saga_instances ORDER BY id) records),'historico',(SELECT json_agg(row_to_json(records)) FROM (SELECT * FROM historico_status ORDER BY id) records),'inbox',(SELECT json_agg(row_to_json(records)) FROM (SELECT * FROM inbox_messages ORDER BY event_id) records),'outbox',(SELECT json_agg(row_to_json(records)) FROM (SELECT * FROM outbox_events ORDER BY id) records));"
    $billingSnapshot = Sql billing "SELECT json_build_object('orcamentos',(SELECT json_agg(row_to_json(records)) FROM (SELECT * FROM orcamentos ORDER BY id) records),'itens',(SELECT json_agg(row_to_json(records)) FROM (SELECT * FROM orcamento_itens ORDER BY id) records),'aprovacoes',(SELECT json_agg(row_to_json(records)) FROM (SELECT * FROM aprovacoes ORDER BY id) records),'pagamentos',(SELECT json_agg(row_to_json(records)) FROM (SELECT * FROM pagamentos ORDER BY id) records),'tentativas',(SELECT json_agg(row_to_json(records)) FROM (SELECT * FROM pagamentos_tentativas ORDER BY id) records),'inbox',(SELECT json_agg(row_to_json(records)) FROM (SELECT * FROM inbox_messages ORDER BY event_id) records),'outbox',(SELECT json_agg(row_to_json(records)) FROM (SELECT * FROM outbox_events ORDER BY event_id) records));"
    $mongoSnapshot = Mongo 'JSON.stringify({diagnostics:db.diagnostics.find().sort({_id:1}).toArray(),repairs:db.work_orders.find().sort({_id:1}).toArray(),history:db.execution_history.find().sort({_id:1}).toArray(),inbox:db.inbox_messages.find().sort({_id:1}).toArray(),outbox:db.outbox_events.find().sort({_id:1}).toArray()})'
    $snapshot = @{os = $osSnapshot; billing = $billingSnapshot; execution = $mongoSnapshot}
    Save $Name $snapshot
    return $snapshot
}

try {
    Step 'fresh environment and authenticated registration'
    foreach ($service in @('os', 'execution', 'billing')) {
        $health = Required-Api $service Get '/actuator/health/readiness'
        Check ($health.status -eq 'UP') "Readiness $service UP"
    }
    Check ((Sql os 'SELECT count(*) FROM ordens_servico;') -eq '0') 'Banco OS inicialmente vazio'
    Check ((Sql billing 'SELECT count(*) FROM orcamentos;') -eq '0') 'Banco Billing inicialmente vazio'
    Check ((Mongo 'db.diagnostics.countDocuments({})') -eq '0') 'Mongo inicialmente sem diagnosticos'
    Broker Put 'queues/%2F/e2e.audit' @{durable = $true; auto_delete = $false; arguments = @{}} | Out-Null
    foreach ($exchange in @('oficina.commands', 'oficina.events')) {
        Broker Post "bindings/%2F/e/$exchange/q/e2e.audit" @{routing_key = '#'; arguments = @{}} | Out-Null
    }
    $client = Required-Api os Post '/api/v1/clientes' @{nome = 'Cliente sintetico E2E'; cpfCnpj = '52998224725'}
    $vehicle = Required-Api os Post '/api/v1/veiculos' @{clienteId = $client.id; placa = 'TST7A41'; marca = 'Teste'; modelo = 'Integracao'; ano = 2026}
    $branch = Required-Api os Post '/api/v1/filiais' @{nome = 'Filial E2E'; codigo = 'E2E741'; cidade = 'Sao Paulo'; uf = 'SP'}
    $script:order = Required-Api os Post '/api/v1/ordens-servico' @{clienteId = $client.id; veiculoId = $vehicle.id; filialId = $branch.id; observacoes = "E2E 7.4.1 $Scenario sem provedor financeiro"}
    $saga = Required-Api os Get "/api/v1/ordens-servico/$($order.id)/saga"
    Check ($saga.status -eq 'WAITING_DIAGNOSTIC') 'Saga inicialmente WAITING_DIAGNOSTIC'
    $result.osId = $order.id; $result.sagaId = $saga.sagaId
    Save 'registration' @{clienteId = $client.id; veiculoId = $vehicle.id; filialId = $branch.id; order = $order; saga = $saga}

    Step 'real diagnostic.start and diagnostic.started'
    $diagnostic = Wait-Api execution "/api/v1/diagnosticos?osId=$($order.id)" {param($value) $value.status -eq 'IN_PROGRESS'}
    $result.diagnosticId = $diagnostic.id
    Wait-States 'EM_DIAGNOSTICO' 'WAITING_DIAGNOSTIC' | Out-Null
    $diagnosticCommand = Os-Command 'diagnostic.start.v1'
    $origin = Parse-Json $diagnosticCommand
    Check ($origin.occurredAt -is [string]) 'diagnostic.start occurredAt e string ISO'
    $result.correlationId = $origin.correlationId
    $items = @(
        @{itemId = 'SVC-E2E'; descricao = 'Servico E2E'; tipo = 'SERVICO'; quantidade = '1.250000'; unidadePreco = 'HORA'; precoPrevisto = '123.456000'},
        @{itemId = 'PECA-E2E'; descricao = 'Peca E2E'; tipo = 'PECA'; quantidade = '2.500000'; unidadePreco = 'UNIDADE'; precoPrevisto = '12.340000'},
        @{itemId = 'MAT-E2E'; descricao = 'Material E2E'; tipo = 'MATERIAL'; quantidade = '0.750000'; unidadePreco = 'KIT'; precoPrevisto = '19.960000'}
    )
    foreach ($item in $items) { $diagnostic = Required-Api execution Post "/api/v1/diagnosticos/$($diagnostic.id)/itens" $item }
    Save 'diagnostic-items' $diagnostic

    Step 'diagnostic.completed and original quote.create'
    if ($Scenario -eq 'Main') { Compose @('stop', 'billing') }
    $diagnostic = Required-Api execution Post "/api/v1/diagnosticos/$($diagnostic.id)/conclusao"
    Check ($diagnostic.status -eq 'COMPLETED') 'Diagnostico concluido pela API'
    Save 'diagnostic-completed' $diagnostic
    Until 'quote.create persisted and published' {
        (Sql os "SELECT count(*) FROM outbox_events WHERE aggregate_id='$($order.id)' AND event_type='quote.create.v1' AND status='PUBLISHED';") -eq '1'
    } | Out-Null
    $quoteCommand = Os-Command 'quote.create.v1'
    Save 'original-quote-command' @{body = $quoteCommand}
    if ($Scenario -eq 'Main') {
        Step 'two original quote deliveries queued for concurrent consumers'
        $peek = @(Broker Post 'queues/%2F/billing.quote.create/get' @{count = 1; ackmode = 'ack_requeue_true'; encoding = 'auto'; truncate = 100000})
        Check ($peek.Count -eq 1 -and $peek[0].payload -ceq $quoteCommand) 'Copia de quote.create identica aos bytes originais publicados'
        Publish-Original 'oficina.commands' $quoteCommand
        $competingDeliveries = @(Broker Post 'queues/%2F/billing.quote.create/get' @{count = 2; ackmode = 'ack_requeue_true'; encoding = 'auto'; truncate = 100000})
        Check ($competingDeliveries.Count -eq 2 -and @($competingDeliveries | Where-Object { $_.payload -cne $quoteCommand }).Count -eq 0) 'Duas entregas originais identicas aguardam consumidores'
        Save 'quote-concurrency-before' @{deliveries = $competingDeliveries; inboxCount = (Sql billing 'SELECT count(*) FROM inbox_messages;')}
        Compose @('start', 'billing')
    }
    $quote = Wait-Api billing "/api/v1/orcamentos?osId=$($order.id)" {param($value) $value.status -eq 'PENDING_APPROVAL'}
    $result.quoteId = $quote.id
    Wait-States 'AGUARDANDO_APROVACAO' 'WAITING_APPROVAL' | Out-Null
    Check ($quote.sagaId -eq $saga.sagaId) 'Orcamento pertence a Saga original'
    Check ($quote.itens.Count -eq 3) 'Orcamento possui exatamente tres itens'
    $total = [decimal]0
    foreach ($item in $items) {
        $savedItem = @($quote.itens | Where-Object itemId -eq $item.itemId)
        Check ($savedItem.Count -eq 1) "Item unico $($item.itemId)"
        Check ($savedItem[0].quantidade -ceq $item.quantidade) "Quantidade e escala preservadas $($item.itemId)"
        Check ($savedItem[0].precoUnitario -ceq $item.precoPrevisto) "Preco preservado $($item.itemId)"
        Check ($savedItem[0].descricao -ceq $item.descricao -and $savedItem[0].tipo -eq $item.tipo -and $savedItem[0].unidadePreco -eq $item.unidadePreco) "Contrato de item preservado $($item.itemId)"
        $itemTotal = [decimal]::Parse($item.quantidade, [Globalization.CultureInfo]::InvariantCulture) * [decimal]::Parse($item.precoPrevisto, [Globalization.CultureInfo]::InvariantCulture)
        Check ([decimal]::Parse($savedItem[0].valorTotal, [Globalization.CultureInfo]::InvariantCulture) -eq $itemTotal) "Total exato $($item.itemId)"
        $total += $itemTotal
    }
    Check ($quote.valorTotal -ceq '200.14' -and $total -eq [decimal]::Parse('200.14', [Globalization.CultureInfo]::InvariantCulture)) 'Total BRL 200.14 sem arredondamento'
    Check ((Sql billing 'SELECT count(*) FROM orcamentos;') -eq '1') 'Um unico orcamento'
    Check ((Sql billing 'SELECT count(*) FROM orcamento_itens;') -eq '3') 'Um unico conjunto de itens'
    Check ((Sql billing "SELECT count(*) FROM outbox_events WHERE event_type='quote.created.v1';") -eq '1') 'Um unico evento quote.created'
    Check ((Sql billing "SELECT count(*) FROM inbox_messages WHERE status='PROCESSED';") -eq '1') 'Inbox original quote unica e processada'
    Save 'quote' $quote
    Wait-Published
    if ($Scenario -eq 'Main') {
        $consumerEvidence = Broker Get 'queues/%2F/billing.quote.create'
        Check ($consumerEvidence.consumers -eq 2) 'Dois consumidores ativos na fila de criacao concorrente'
        Save 'quote-concurrency-after' @{queue = ($consumerEvidence | Select-Object name, consumers, messages_ready, messages_unacknowledged, message_stats); originalInbox = (Sql billing 'SELECT count(*) FROM inbox_messages;')}
    }

    $quote = Required-Api billing Get "/api/v1/orcamentos/$($quote.id)" $null $billingSession
    $csrfToken = $script:lastCsrf
    Check (-not [string]::IsNullOrWhiteSpace($csrfToken)) 'CSRF real obtido na sessao Billing'
    $withoutCsrf = Api billing Post "/api/v1/orcamentos/$($quote.id)/aprovar" @{responsavelId = 'e2e-provisorio'} $billingSession
    Check ($withoutCsrf.status -eq 403) 'POST Billing sem CSRF negado com 403'

    if ($Scenario -eq 'Rejection') {
        Step 'authenticated rejection with real CSRF'
        $decision = Required-Api billing Post "/api/v1/orcamentos/$($quote.id)/rejeitar" @{responsavelId = 'e2e-provisorio'; motivo = 'Recusa sintetica do cenario E2E'} $billingSession $csrfToken
        Check ($decision.status -eq 'REJECTED') 'Recusa persistida'
        Save 'decision' $decision
        $finalStates = Wait-States 'CANCELADA' 'COMPENSATED'
        Wait-Published
        Check ((Sql os "SELECT count(*) FROM outbox_events WHERE event_type='repair.start.v1';") -eq '0') 'Recusa nao gera repair.start'
        Check ((Sql billing 'SELECT count(*) FROM pagamentos;') -eq '0') 'Recusa nao cria pagamento'
        Check ((Mongo 'db.work_orders.countDocuments({})') -eq '0') 'Recusa nao cria reparo'
        Check ((Sql billing "SELECT count(*) FROM aprovacoes WHERE status='REJECTED';") -eq '1') 'Uma unica decisao de recusa'
        Check ((Sql billing "SELECT count(*) FROM outbox_events WHERE event_type='quote.rejected.v1';") -eq '1') 'Um unico evento de recusa'
    } else {
        Step 'broker outage and concurrent approval with CSRF'
        Compose @('stop', 'rabbitmq')
        $handler = [Net.Http.HttpClientHandler]::new()
        $handler.CookieContainer = $billingSession.Cookies
        $clientHttp = [Net.Http.HttpClient]::new($handler)
        $clientHttp.DefaultRequestHeaders.TryAddWithoutValidation('Authorization', $authorization) | Out-Null
        $clientHttp.DefaultRequestHeaders.TryAddWithoutValidation('X-CSRF-TOKEN', $csrfToken) | Out-Null
        $approvalUrl = "http://127.0.0.1:$($manifest.ports.billing)/api/v1/orcamentos/$($quote.id)/aprovar"
        $approvalBody = '{"responsavelId":"e2e-provisorio"}'
        try {
            $first = $clientHttp.PostAsync($approvalUrl, [Net.Http.StringContent]::new($approvalBody, [Text.Encoding]::UTF8, 'application/json'))
            $second = $clientHttp.PostAsync($approvalUrl, [Net.Http.StringContent]::new($approvalBody, [Text.Encoding]::UTF8, 'application/json'))
            [Threading.Tasks.Task]::WaitAll([Threading.Tasks.Task[]]@($first, $second))
            $responses = @($first.Result, $second.Result)
            $statuses = @($responses | ForEach-Object { [int]$_.StatusCode } | Sort-Object)
            Save 'approval-concurrency' @{statuses = $statuses; bodies = @($responses | ForEach-Object { Parse-Json $_.Content.ReadAsStringAsync().Result })}
            Check ($statuses.Count -eq 2 -and $statuses[0] -eq 200 -and $statuses[1] -eq 409) 'Aprovacoes concorrentes retornam 200 e 409'
        } finally { $clientHttp.Dispose() }
        $decision = Required-Api billing Get "/api/v1/orcamentos/$($quote.id)/aprovacao"
        Save 'decision' $decision
        Check ($decision.status -eq 'APPROVED' -and $decision.correlationId -eq $origin.correlationId) 'Aprovacao preserva correlacao original sem header'
        Check ((Sql billing 'SELECT count(*) FROM aprovacoes;') -eq '1') 'Uma unica aprovacao concorrente persistida'
        Check ((Sql billing "SELECT count(*) FROM outbox_events WHERE event_type='quote.approved.v1';") -eq '1') 'Uma unica Outbox de aprovacao concorrente'
        $approvedEventBefore = Billing-Event 'quote.approved.v1'
        Start-Sleep -Seconds 7
        $outage = Parse-Json (Sql billing "SELECT row_to_json(record) FROM (SELECT event_id, status, attempts, payload, last_error, next_attempt_at FROM outbox_events WHERE event_type='quote.approved.v1') record;")
        Save 'broker-outage' @{approval = $decision; outbox = $outage; readiness = (Required-Api billing Get '/actuator/health/readiness')}
        Check ($outage.status -in @('PENDING', 'PROCESSING') -and $outage.attempts -ge 1) 'Outbox preservada e tentativa observada durante indisponibilidade'
        Compose @('start', 'rabbitmq')
        Until 'RabbitMQ recovered' { try { (Broker Get 'overview').rabbitmq_version } catch { $null } } | Out-Null
        Step 'broker recovery and real repair.start/repair.started'
        $repair = Wait-Api execution "/api/v1/reparos?osId=$($order.id)" {param($value) $value.status -eq 'IN_PROGRESS'}
        $result.repairId = $repair.id
        Save 'repair-started' $repair
        Wait-States 'EM_EXECUCAO' 'WAITING_REPAIR' | Out-Null
        Check ((Billing-Event 'quote.approved.v1') -ceq $approvedEventBefore) 'Recuperacao preserva eventId e bytes da Outbox'
        $repeated = Api billing Post "/api/v1/orcamentos/$($quote.id)/aprovar" @{responsavelId = 'e2e-provisorio'} $billingSession $csrfToken
        Check ($repeated.status -eq 409) 'Repeticao REST retorna 409 sem nova decisao'

        Step 'repair.completed and pending payment'
        $repair = Required-Api execution Post "/api/v1/reparos/$($repair.id)/conclusao"
        Check ($repair.status -eq 'COMPLETED') 'Reparo concluido somente pela API real'
        Save 'repair-completed' $repair
        $finalStates = Wait-States 'AGUARDANDO_PAGAMENTO' 'WAITING_PAYMENT'
        $payments = @(Wait-Api billing "/api/v1/pagamentos?osId=$($order.id)" {param($value) @($value).Count -eq 1})
        $payment = $payments[0]
        $result.paymentId = $payment.id
        Save 'payment' $payment
        Check ($payment.status -eq 'PENDING' -and $payment.valor -ceq '200.14' -and $payment.moeda -eq 'BRL') 'Pagamento pendente corresponde ao total BRL aprovado'
        Check ($payment.orcamentoId -eq $quote.id -and $payment.sagaId -eq $saga.sagaId -and $payment.osId -eq $order.id) 'Pagamento vinculado a OS Saga e orcamento corretos'
        Check ($payment.tentativas.Count -eq 1 -and $payment.tentativas[0].status -eq 'PENDING') 'Uma tentativa inicial PENDING'
        Check ((Sql billing 'SELECT count(*) FROM pagamentos;') -eq '1') 'Um unico pagamento logico'
        Check ((Sql billing 'SELECT count(*) FROM pagamentos_tentativas;') -eq '1') 'Uma unica tentativa'
        Check ((Sql billing 'SELECT count(*) FROM pagamentos WHERE provider_payment_id IS NOT NULL OR confirmed_at IS NOT NULL;') -eq '0') 'Sem identificador ou confirmacao de cobranca do provedor'
        Check ((Sql billing "SELECT count(*) FROM pagamentos current_payment JOIN pagamentos_tentativas attempt ON attempt.pagamento_id=current_payment.id WHERE current_payment.idempotency_key=attempt.idempotency_key AND current_payment.idempotency_key IS NOT NULL;") -eq '1') 'Chave idempotente inicial consistente'
        Wait-Published

        Step 'original envelope replay and no duplicated effects'
        $before = Snapshot 'before-replay'
        $paymentCommand = Os-Command 'payment.request.v1'
        $quoteCreated = Billing-Event 'quote.created.v1'
        $quoteApproved = Billing-Event 'quote.approved.v1'
        $repairCompleted = Mongo "db.outbox_events.findOne({eventType:'repair.completed.v1'}).payload"
        $originals = @(
            @{exchange = 'oficina.commands'; body = $quoteCommand},
            @{exchange = 'oficina.commands'; body = $paymentCommand},
            @{exchange = 'oficina.events'; body = $quoteCreated},
            @{exchange = 'oficina.events'; body = $quoteApproved},
            @{exchange = 'oficina.events'; body = $repairCompleted}
        )
        Save 'original-replays' $originals
        foreach ($original in $originals) { Publish-Original $original.exchange $original.body }
        Start-Sleep -Seconds 8
        Until 'replayed deliveries acknowledged' {
            $queues = @(Broker Get 'queues/%2F')
            @($queues | Where-Object { $_.name -ne 'e2e.audit' -and ($_.messages_ready -gt 0 -or $_.messages_unacknowledged -gt 0) }).Count -eq 0
        } | Out-Null
        Wait-Published
        $after = Snapshot 'after-replay'
        foreach ($service in @('os', 'billing', 'execution')) { Check ($before.$service -ceq $after.$service) "Replay nao altera dados persistidos $service" }
        $finalStates = Wait-States 'AGUARDANDO_PAGAMENTO' 'WAITING_PAYMENT'
    }

    $finalQuote = Required-Api billing Get "/api/v1/orcamentos/$($quote.id)"
    Save 'quote-final' $finalQuote
    if ($Scenario -eq 'Rejection') { Snapshot 'final-snapshot' | Out-Null }
    $decisionTimestamp = if ($Scenario -eq 'Main') { $finalQuote.approvedAt } else { $finalQuote.rejectedAt }
    Check ($decisionTimestamp -ceq $decision.decisionAt) 'Timestamp unico do orcamento e decisao'
    Step 'wire contract and final evidence'
    $deliveries = @(Broker Post 'queues/%2F/e2e.audit/get' @{count = 100; ackmode = 'ack_requeue_true'; encoding = 'auto'; truncate = 100000})
    Save 'audit-deliveries' $deliveries
    $timeline = @($deliveries | ForEach-Object {
        $envelope = Parse-Json $_.payload
        Check ($envelope.occurredAt -is [string] -and $envelope.occurredAt -match '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$') "Wire occurredAt ISO string $($envelope.eventType)"
        Check ($envelope.osId -eq $order.id -and $envelope.sagaId -eq $saga.sagaId -and $envelope.correlationId -eq $origin.correlationId) "Wire correlacao $($envelope.eventType)"
        Check ($envelope.eventType -eq $_.routing_key) "Wire routing key $($envelope.eventType)"
        $expectedSource = if ($_.exchange -eq 'oficina.commands') { 'os-service' } elseif ($envelope.eventType -like 'quote.*') { 'billing-service' } else { 'execution-service' }
        Check ($envelope.source -eq $expectedSource) "Wire source $($envelope.eventType)"
        @{eventId = $envelope.eventId; eventType = $envelope.eventType; occurredAt = $envelope.occurredAt; osId = $envelope.osId; sagaId = $envelope.sagaId; correlationId = $envelope.correlationId; source = $envelope.source; payload = $envelope.payload; exchange = $_.exchange}
    })
    Save 'event-timeline' $timeline
    $requiredTypes = @('diagnostic.start.v1','diagnostic.started.v1','diagnostic.completed.v1','quote.create.v1','quote.created.v1')
    if ($Scenario -eq 'Main') { $requiredTypes += @('quote.approved.v1','repair.start.v1','repair.started.v1','repair.completed.v1','payment.request.v1') } else { $requiredTypes += 'quote.rejected.v1' }
    foreach ($eventType in $requiredTypes) { Check (@($timeline | Where-Object eventType -eq $eventType).Count -gt 0) "Evento real observado $eventType" }
    Check (@($timeline | Where-Object { $_.eventType -eq 'quote.created.v1' -and $_.payload.quoteId -ne $quote.id }).Count -eq 0) 'quote.created usa ID real persistido'
    if ($Scenario -eq 'Main') {
        Check (@($timeline | Where-Object { $_.eventType -eq 'payment.request.v1' -and $_.payload.repairId -ne $result.repairId }).Count -eq 0) 'payment.request referencia reparo real'
    } else {
        Check (@($timeline | Where-Object { $_.eventType -eq 'quote.rejected.v1' -and [string]::IsNullOrWhiteSpace($_.payload.reason) }).Count -eq 0) 'Recusa publica reason nao vazio'
    }
    Check (@($timeline | Where-Object { $_.eventType -in @('payment.confirmed.v1', 'payment.failed.v1') }).Count -eq 0) 'Nenhum resultado financeiro artificial'
    if ($Scenario -eq 'Rejection') { Check (@($timeline | Where-Object eventType -eq 'repair.start.v1').Count -eq 0) 'Wire sem repair.start na recusa' }
    $queues = @(Broker Get 'queues/%2F')
    Save 'queues' @($queues | Select-Object name, consumers, messages_ready, messages_unacknowledged)
    Check (@($queues | Where-Object { $_.name -like '*.dlq' -and $_.messages -gt 0 }).Count -eq 0) 'DLQs vazias no ambiente corrigido'
    $result.finalStates = $finalStates
    $result.status = 'PASSED'
} catch {
    $result.status = 'BLOCKED'
    $result.failure = @{checkpoint = $script:step; message = $_.Exception.Message}
    Write-Host "BLOQUEIO: $($result.failure.message)"
} finally {
    $result.completedAt = [DateTime]::UtcNow.ToString('o')
    $result.checks = $checks.ToArray()
    Save 'result' $result
    $ErrorActionPreference = 'Continue'
    & $Docker @composeArgs logs --no-color --timestamps os execution billing *> (Join-Path $RunRoot 'services.log')
    & $Docker @composeArgs ps --format json | Set-Content -Encoding UTF8 (Join-Path $RunRoot 'containers.jsonl')
    foreach ($database in @('os', 'billing')) {
        Sql $database 'SELECT json_agg(row_to_json(migrations)) FROM (SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank) migrations;' | Set-Content -Encoding UTF8 (Join-Path $RunRoot "$database-migrations.json")
    }
    $originalContainers = @(Get-Content (Join-Path $RunRoot 'original-containers.jsonl') | ForEach-Object { Parse-Json $_ })
    $currentContainers = @(& $Docker ps --no-trunc --format '{{json .}}' | ForEach-Object { Parse-Json $_ })
    Save 'original-environments-preserved' @($originalContainers | ForEach-Object {
        $original = $_
        @{name = $original.Names; sameContainerStillRunning = (@($currentContainers | Where-Object { $_.ID -eq $original.ID -and $_.Names -eq $original.Names }).Count -eq 1)}
    })
}
Write-Output "Resultado $Scenario : $($result.status), $($checks.Count) verificacoes. Evidencias: $RunRoot"
if ($result.status -ne 'PASSED') { exit 1 }
