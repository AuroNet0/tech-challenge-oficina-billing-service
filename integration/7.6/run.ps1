param([Parameter(Mandatory = $true)][string]$RunRoot)
. "$PSScriptRoot/common.ps1" -RunRoot $RunRoot
$scenarios = [Collections.Generic.List[object]]::new()
$mockHeaders = @{Authorization = 'Bearer ' + $providerCredentials.accessToken}

function Mock($Method, $Path, $Body = $null) {
    $arguments = @{Uri = "http://127.0.0.1:$($manifest.ports.mock)$Path"; Method = $Method; Headers = $mockHeaders; TimeoutSec = 20}
    if ($null -ne $Body) { $arguments.ContentType = 'application/json'; $arguments.Body = ConvertTo-Json -InputObject $Body -Depth 25 }
    Invoke-RestMethod @arguments
}

function Session-Billing {
    $script:billingSession = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    Required-Api billing Get '/api/v1/pagamentos?osId=00000000-0000-0000-0000-000000000000' $null $billingSession | Out-Null
    $script:csrfToken = $script:lastCsrf
    Check (-not [string]::IsNullOrWhiteSpace($csrfToken)) 'Sessao Billing fornece CSRF real'
}

function Payment($PaymentId) { Required-Api billing Get "/api/v1/pagamentos/$PaymentId" }

function Checkout($PaymentId) {
    Api billing Post "/api/v1/pagamentos/$PaymentId/checkout" @{} $billingSession $csrfToken
}

function Webhook($ResourceId, $RequestId = ([Guid]::NewGuid().ToString()), $Invalid = '') {
    $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()
    if ($Invalid -eq 'expired') { $timestamp = [DateTimeOffset]::UtcNow.AddHours(-1).ToUnixTimeSeconds().ToString() }
    $hmac = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($providerCredentials.webhookSecret))
    try { $digest = $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes("id:$ResourceId;request-id:$RequestId;ts:$timestamp;")) }
    finally { $hmac.Dispose() }
    $signature = 'ts=' + $timestamp + ',v1=' + ([BitConverter]::ToString($digest).Replace('-', '').ToLowerInvariant())
    if ($Invalid -eq 'signature') { $signature = 'ts=' + $timestamp + ',v1=' + ('0' * 64) }
    $notificationHeaders = @{'x-signature' = $signature}
    if ($Invalid -ne 'missing-request-id') { $notificationHeaders['x-request-id'] = $RequestId }
    $bodyId = if ($Invalid -eq 'mismatch') { '999999' } else { $ResourceId }
    $eventType = if ($Invalid -eq 'type') { 'merchant_order' } else { 'payment' }
    $body = ConvertTo-Json -Compress @{type = $eventType; data = @{id = $bodyId}}
    if ($Invalid -eq 'json') { $body = '{' }
    return @{resourceId = $ResourceId; requestId = $RequestId; headers = $notificationHeaders; body = $body}
}

function Send-Webhook($Notification) {
    $status = 0
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$($manifest.ports.billing)/api/v1/webhooks/mercado-pago?data.id=$($Notification.resourceId)" -Method Post -ContentType 'application/json' -Headers $Notification.headers -Body $Notification.body
        $status = [int]$response.StatusCode
    } catch { if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode } else { throw } }
    $httpTrace.Add(@{at = [DateTime]::UtcNow.ToString('o'); service = 'billing'; method = 'POST'; path = '/api/v1/webhooks/mercado-pago'; status = $status})
    return $status
}

function Parallel-Post($Path, $Bodies, $RequestHeaders, $Cookies = $null) {
    $handler = [Net.Http.HttpClientHandler]::new()
    if ($Cookies) { $handler.CookieContainer = $Cookies }
    $clientHttp = [Net.Http.HttpClient]::new($handler)
    try {
        $tasks = @()
        foreach ($body in $Bodies) {
            $message = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Post, "http://127.0.0.1:$($manifest.ports.billing)$Path")
            foreach ($entry in $RequestHeaders.GetEnumerator()) { $message.Headers.TryAddWithoutValidation($entry.Key, $entry.Value) | Out-Null }
            $message.Content = [Net.Http.StringContent]::new($body, [Text.Encoding]::UTF8, 'application/json')
            $tasks += $clientHttp.SendAsync($message)
        }
        [Threading.Tasks.Task]::WaitAll([Threading.Tasks.Task[]]$tasks)
        @($tasks | ForEach-Object { @{status = [int]$_.Result.StatusCode; body = $_.Result.Content.ReadAsStringAsync().Result} })
    } finally { $clientHttp.Dispose() }
}

function Wait-Receipt($ResourceId) {
    Until "notificacao processada $ResourceId" {
        (Sql billing "SELECT count(*) FROM provider_notifications WHERE resource_id='$ResourceId' AND status='PROCESSED';") -ne '0'
    } | Out-Null
}

function Counts($PaymentId) {
    Sql billing "SELECT json_build_object('confirmations',(SELECT count(*) FROM outbox_events WHERE aggregate_id='$PaymentId' AND event_type='payment.confirmed.v1'),'attempts',(SELECT count(*) FROM pagamentos_tentativas WHERE pagamento_id='$PaymentId'),'checkouts',(SELECT count(*) FROM provider_checkouts WHERE payment_id='$PaymentId'));"
}

function New-Pending($Name, $Number) {
    Step "$Name cadastro e abertura"
    $client = Required-Api os Post '/api/v1/clientes' @{nome = "Cliente sintetico $Name"; cpfCnpj = @('52998224725','11144477735','12345678909','98765432100','39053344705')[$Number]}
    $branch = Required-Api os Post '/api/v1/filiais' @{nome = "Filial $Name"; codigo = "E76$Number"; cidade = 'Sao Paulo'; uf = 'SP'}
    $vehicle = Required-Api os Post '/api/v1/veiculos' @{clienteId = $client.id; placa = "TST7A4$Number"; marca = 'Teste'; modelo = 'Saga'; ano = 2026}
    $script:order = Required-Api os Post '/api/v1/ordens-servico' @{clienteId = $client.id; veiculoId = $vehicle.id; filialId = $branch.id; observacoes = "E2E7.6 $Name sem pagamento real"}
    $saga = Required-Api os Get "/api/v1/ordens-servico/$($order.id)/saga"
    Check ($saga.status -eq 'WAITING_DIAGNOSTIC') "$Name Saga WAITING_DIAGNOSTIC"
    $diagnostic = Wait-Api execution "/api/v1/diagnosticos?osId=$($order.id)" {param($value) $value.status -eq 'IN_PROGRESS'}
    Wait-States 'EM_DIAGNOSTICO' 'WAITING_DIAGNOSTIC' | Out-Null
    $origin = Parse-Json (Os-Command 'diagnostic.start.v1')
    $items = @(
        @{itemId = 'SVC-E76'; descricao = 'Servico E2E'; tipo = 'SERVICO'; quantidade = '1.250000'; unidadePreco = 'HORA'; precoPrevisto = '123.456000'},
        @{itemId = 'PECA-E76'; descricao = 'Peca E2E'; tipo = 'PECA'; quantidade = '2.500000'; unidadePreco = 'UNIDADE'; precoPrevisto = '12.340000'},
        @{itemId = 'MAT-E76'; descricao = 'Material E2E'; tipo = 'MATERIAL'; quantidade = '0.750000'; unidadePreco = 'KIT'; precoPrevisto = '19.960000'}
    )
    foreach ($item in $items) { $diagnostic = Required-Api execution Post "/api/v1/diagnosticos/$($diagnostic.id)/itens" $item }
    Save "$Name-diagnostic" $diagnostic
    Required-Api execution Post "/api/v1/diagnosticos/$($diagnostic.id)/conclusao" | Out-Null
    $quote = Wait-Api billing "/api/v1/orcamentos?osId=$($order.id)" {param($value) $value.status -eq 'PENDING_APPROVAL'}
    Wait-States 'AGUARDANDO_APROVACAO' 'WAITING_APPROVAL' | Out-Null
    Check ($quote.itens.Count -eq 3 -and $quote.sagaId -eq $saga.sagaId) "$Name orcamento tres itens e Saga correta"
    foreach ($item in $items) {
        $saved = @($quote.itens | Where-Object itemId -eq $item.itemId)
        Check ($saved.Count -eq 1 -and $saved[0].quantidade -ceq $item.quantidade -and $saved[0].precoUnitario -ceq $item.precoPrevisto) "$Name quantidade/preco exatos $($item.itemId)"
        $expected = [decimal]::Parse($item.quantidade, [Globalization.CultureInfo]::InvariantCulture) * [decimal]::Parse($item.precoPrevisto, [Globalization.CultureInfo]::InvariantCulture)
        Check ([decimal]::Parse($saved[0].valorTotal, [Globalization.CultureInfo]::InvariantCulture) -eq $expected) "$Name total item $($item.itemId)"
    }
    Check ($quote.valorTotal -ceq '200.14') "$Name total exato BRL200.14"
    Session-Billing
    Check ((Api billing Post "/api/v1/orcamentos/$($quote.id)/aprovar" @{} $billingSession).status -eq 403) "$Name aprovacao sem CSRF negada"
    $decision = Required-Api billing Post "/api/v1/orcamentos/$($quote.id)/aprovar" @{responsavelId = 'operador-e2e-provisorio'} $billingSession $csrfToken
    Check ($decision.correlationId -eq $origin.correlationId) "$Name decisao preserva correlacao"
    $repair = Wait-Api execution "/api/v1/reparos?osId=$($order.id)" {param($value) $value.status -eq 'IN_PROGRESS'}
    Wait-States 'EM_EXECUCAO' 'WAITING_REPAIR' | Out-Null
    Required-Api execution Post "/api/v1/reparos/$($repair.id)/conclusao" | Out-Null
    Wait-States 'AGUARDANDO_PAGAMENTO' 'WAITING_PAYMENT' | Out-Null
    $paymentList = @(Wait-Api billing "/api/v1/pagamentos?osId=$($order.id)" {param($value) @($value).Count -eq 1})
    $payment = $paymentList[0]
    $payment | Add-Member -NotePropertyName externalReference -NotePropertyValue (Sql billing "SELECT external_reference FROM pagamentos WHERE id='$($payment.id)';")
    $payment | Add-Member -NotePropertyName idempotencyKey -NotePropertyValue (Sql billing "SELECT idempotency_key FROM pagamentos WHERE id='$($payment.id)';")
    Check ($payment.status -eq 'PENDING' -and $payment.valor -ceq '200.14' -and $payment.moeda -eq 'BRL') "$Name pagamento inicial PENDING BRL200.14"
    Check ($payment.osId -eq $order.id -and $payment.sagaId -eq $saga.sagaId -and $payment.orcamentoId -eq $quote.id) "$Name vinculos financeiros"
    Check ($payment.tentativas.Count -eq 1 -and $payment.tentativas[0].status -eq 'PENDING') "$Name uma tentativa inicial"
    Check ((Sql billing "SELECT count(*) FROM aprovacoes WHERE orcamento_id='$($quote.id)';") -eq '1') "$Name uma decisao"
    $case = [ordered]@{name = $Name; osId = $order.id; sagaId = $saga.sagaId; correlationId = $origin.correlationId; diagnosticId = $diagnostic.id; quoteId = $quote.id; repairId = $repair.id; paymentId = $payment.id; quote = $quote; decision = $decision; paymentBefore = $payment}
    $scenarios.Add($case)
    Save "$Name-before" $case
    return $case
}

function Create-Checkout($Case, $Concurrent = $false) {
    Step "$($Case.name) checkout"
    if ($Concurrent) {
        Check ((Api billing Post "/api/v1/pagamentos/$($Case.paymentId)/checkout" @{} $billingSession).status -eq 403) 'Checkout sem CSRF negado'
        $denied = 0
        try { Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$($manifest.ports.billing)/api/v1/pagamentos/$($Case.paymentId)" | Out-Null } catch { $denied = [int]$_.Exception.Response.StatusCode }
        Check ($denied -eq 401) 'Consulta financeira sem Basic negada'
        $responses = @(Parallel-Post "/api/v1/pagamentos/$($Case.paymentId)/checkout" @('{}','{}') @{Authorization = $authorization; 'X-CSRF-TOKEN' = $csrfToken} $billingSession.Cookies)
        Save 'checkout-concurrency' $responses
        Check ($responses.Count -eq 2 -and @($responses | Where-Object {$_.status -notin @(200,409)}).Count -eq 0) 'Checkout concorrente retorna sucesso ou conflito controlado/reuso'
    }
    $response = Until "checkout disponivel $($Case.paymentId)" {
        $current = Checkout $Case.paymentId
        if($current.status -eq 200){return $current}
        if($current.status -ne 409){throw "HTTP inesperado no checkout: $($current.status)"}
    }
    Check ($response.status -eq 200 -and $response.body.checkoutStatus -eq 'READY') "$($Case.name) checkout READY autorizado BILLING_OPERATOR"
    $Case.checkout = $response.body
    $state = Mock Get '/__admin/state'
    $preference = $state.preferences.($Case.checkout.preferenceId)
    Check ($preference.external_reference -eq $Case.paymentBefore.externalReference) "$($Case.name) externalReference preservada"
    Check ($preference.metadata.billing_payment_id -eq $Case.paymentId -and $preference.metadata.billing_os_id -eq $Case.osId -and $preference.metadata.billing_saga_id -eq $Case.sagaId) "$($Case.name) metadata identidade correta"
    Check ($preference.metadata.billing_idempotency_key -eq $Case.paymentBefore.idempotencyKey) "$($Case.name) chave idempotente preservada"
    Check ([decimal]::Parse($preference.items[0].unit_price, [Globalization.CultureInfo]::InvariantCulture) -eq [decimal]::Parse('200.14', [Globalization.CultureInfo]::InvariantCulture) -and $preference.items[0].currency_id -eq 'BRL') "$($Case.name) preferencia BRL200.14 exata"
    Check ($Case.checkout.checkoutUrl -like 'https://sandbox.mercadopago.com.br/*') "$($Case.name) link sandbox"
    Check (@($state.preferences.PSObject.Properties | Where-Object {$_.Value.external_reference -eq $Case.paymentBefore.externalReference}).Count -eq 1) "$($Case.name) uma preferencia externa"
}

function Finish-Scenario($Case, $OrderState, $SagaState) {
    $Case.paymentAfter = Payment $Case.paymentId
    $Case.finalStates = Wait-States $OrderState $SagaState
    $Case.counts = Counts $Case.paymentId
    $Case.provider = Mock Get '/__admin/state'
    Save "$($Case.name)-after" $Case
}

try {
    Step 'preflight'
    foreach ($service in @('os','execution','billing')) { Check ((Required-Api $service Get '/actuator/health/readiness').status -eq 'UP') "Readiness $service UP" }
    Check ((Mock Get '/health').status -eq 'UP') 'Mock HTTP UP'
    Check ((Sql os 'SELECT count(*) FROM ordens_servico;') -eq '0') 'Banco OS novo'
    Check ((Sql billing 'SELECT count(*) FROM pagamentos;') -eq '0') 'Banco Billing novo'
    Check ((Mongo 'db.diagnostics.countDocuments({})') -eq '0') 'Mongo novo'
    Check ((Mongo 'db.hello().isWritablePrimary') -eq 'true') 'Mongo replica set PRIMARY'
    foreach ($database in @('os','billing')) {
        $migrations = Parse-Json (Sql $database 'SELECT json_agg(row_to_json(migrations)) FROM (SELECT version,description,success FROM flyway_schema_history ORDER BY installed_rank) migrations;')
        Check (@($migrations | Where-Object {-not $_.success}).Count -eq 0) "Flyway $database sem falhas"
        Save "$database-migrations" $migrations
    }
    $queues = @(Broker Get 'queues/%2F')
    Check (@($queues | Where-Object {$_.messages -gt 0}).Count -eq 0) 'Broker novo sem mensagens anteriores'
    Save 'preflight-queues' @($queues | Select-Object name,consumers,messages)
    Save 'preflight-bindings' @(Broker Get 'bindings/%2F')
    foreach($queue in @('billing.quote.create','billing.payment.request','execution.diagnostic.start','execution.repair.start','os.saga.events')) {
        Until "consumidor $queue" { (Broker Get "queues/%2F/$queue").consumers -gt 0 } | Out-Null
        Check $true "Consumidor ativo $queue"
    }
    Broker Put 'queues/%2F/e2e76.audit' @{durable = $true; auto_delete = $false; arguments = @{}} | Out-Null
    foreach($exchange in @('oficina.commands','oficina.events')) { Broker Post "bindings/%2F/e/$exchange/q/e2e76.audit" @{routing_key = '#'; arguments = @{}} | Out-Null }

    $main = New-Pending 'Approved' 0
    Create-Checkout $main $true
    $external = Mock Post '/__admin/payments' @{preferenceId = $main.checkout.preferenceId; status = 'pending'}
    $main.providerPaymentId = "$($external.id)"
    Step 'pending and invalid webhooks'
    $beforeInvalid = Sql billing 'SELECT count(*) FROM provider_notifications;'
    foreach($invalid in @('signature','missing-request-id','expired','mismatch','json','type')) {
        $status = Send-Webhook (Webhook $main.providerPaymentId ([Guid]::NewGuid().ToString()) $invalid)
        Check ($status -eq $(if($invalid -in @('signature','missing-request-id','expired')){401}else{400})) "Webhook invalido $invalid rejeitado"
    }
    Check ((Sql billing 'SELECT count(*) FROM provider_notifications;') -eq $beforeInvalid) 'Webhooks invalidos sem recibos ou efeitos'
    Check ((Send-Webhook (Webhook $main.providerPaymentId)) -eq 200) 'Webhook pending autentico aceito'
    Wait-Receipt $main.providerPaymentId
    Check ((Payment $main.paymentId).status -eq 'PENDING') 'Pending externo nao confirma pagamento'
    Wait-States 'AGUARDANDO_PAGAMENTO' 'WAITING_PAYMENT' | Out-Null
    Check ((Sql billing "SELECT count(*) FROM outbox_events WHERE event_type='payment.confirmed.v1';") -eq '0') 'Pending sem evento de sucesso'
    Mock Post '/__admin/payments' @{preferenceId = $main.checkout.preferenceId; status = 'in_process'} | Out-Null
    Check ((Send-Webhook (Webhook $main.providerPaymentId)) -eq 200) 'Webhook in_process aceito'
    Wait-Api billing "/api/v1/pagamentos/$($main.paymentId)" {param($value) $value.status -eq 'PROCESSING'} | Out-Null
    Check ((Sql billing "SELECT count(*) FROM outbox_events WHERE event_type='payment.confirmed.v1';") -eq '0') 'In_process sem confirmacao'
    Mock Post '/__admin/payments' @{preferenceId = $main.checkout.preferenceId; status = 'approved'} | Out-Null
    Step 'approved and concurrent signed notifications'
    $notification = Webhook $main.providerPaymentId
    $responses = @(Parallel-Post "/api/v1/webhooks/mercado-pago?data.id=$($main.providerPaymentId)" @($notification.body,$notification.body) $notification.headers)
    Check (@($responses | Where-Object status -ne 200).Count -eq 0) 'Dois webhooks identicos concorrentes aceitos'
    Wait-Api billing "/api/v1/pagamentos/$($main.paymentId)" {param($value) $value.status -eq 'CONFIRMED'} | Out-Null
    Finish-Scenario $main 'FINALIZADA' 'COMPLETED'
    Check ((Sql billing "SELECT provider_payment_id FROM pagamentos WHERE id='$($main.paymentId)';") -eq $main.providerPaymentId) 'ProviderPaymentId confirmado e real do mock'
    $beforeDuplicate = Counts $main.paymentId
    $version = Sql os "SELECT version::text FROM saga_instances WHERE id='$($main.sagaId)';"
    Check ((Send-Webhook $notification) -eq 200) 'Reentrega do mesmo webhook aceita'
    Check ((Send-Webhook (Webhook $main.providerPaymentId)) -eq 200) 'Nova notificacao da mesma aprovacao aceita'
    Until 'todos recibos processados' { (Sql billing "SELECT count(*) FROM provider_notifications WHERE resource_id='$($main.providerPaymentId)' AND status NOT IN ('PROCESSED','REVIEW');") -eq '0' } | Out-Null
    Check ((Counts $main.paymentId) -ceq $beforeDuplicate) 'Reentregas nao duplicam evento tentativa ou checkout'
    Check ((Sql os "SELECT version::text FROM saga_instances WHERE id='$($main.sagaId)';") -ceq $version) 'Reentregas nao avancam Saga novamente'
    Check ((Checkout $main.paymentId).status -eq 409) 'Pagamento confirmado nao abre novo checkout'

    $rejected = New-Pending 'Rejected' 1
    Create-Checkout $rejected
    $external = Mock Post '/__admin/payments' @{preferenceId = $rejected.checkout.preferenceId; status = 'rejected'}
    $rejected.providerPaymentId = "$($external.id)"
    Check ((Send-Webhook (Webhook $rejected.providerPaymentId)) -eq 200) 'Webhook rejected aceito'
    Wait-Api billing "/api/v1/pagamentos/$($rejected.paymentId)" {param($value) $value.status -eq 'FAILED'} | Out-Null
    Until 'payment.failed publicado' { (Sql billing "SELECT count(*) FROM outbox_events WHERE aggregate_id='$($rejected.paymentId)' AND event_type='payment.failed.v1' AND status='PUBLISHED';") -eq '1' } | Out-Null
    Until 'OS consumiu falha' { (Sql os "SELECT count(*) FROM inbox_messages WHERE event_type='payment.failed.v1' AND saga_id='$($rejected.sagaId)' AND status='PROCESSED';") -eq '1' } | Out-Null
    Finish-Scenario $rejected 'AGUARDANDO_PAGAMENTO' 'WAITING_PAYMENT'
    Check ($rejected.paymentAfter.tentativas[0].status -eq 'FAILED') 'Falha preservada na tentativa'

    $outage = New-Pending 'BrokerOutage' 2
    Create-Checkout $outage
    $external = Mock Post '/__admin/payments' @{preferenceId = $outage.checkout.preferenceId; status = 'pending'}
    $outage.providerPaymentId = "$($external.id)"
    Wait-Published
    Step 'isolated broker interruption'
    Compose @('stop','rabbitmq')
    Mock Post '/__admin/payments' @{preferenceId = $outage.checkout.preferenceId; status = 'approved'} | Out-Null
    Check ((Send-Webhook (Webhook $outage.providerPaymentId)) -eq 200) 'Webhook duravel durante falha broker'
    Wait-Api billing "/api/v1/pagamentos/$($outage.paymentId)" {param($value) $value.status -eq 'CONFIRMED'} | Out-Null
    Until 'publisher tentou durante interrupcao' { [int](Sql billing "SELECT attempts FROM outbox_events WHERE aggregate_id='$($outage.paymentId)' AND event_type='payment.confirmed.v1';") -ge 1 } | Out-Null
    $outboxBefore = Sql billing "SELECT payload FROM outbox_events WHERE aggregate_id='$($outage.paymentId)' AND event_type='payment.confirmed.v1';"
    Save 'broker-outage' @{payment = (Payment $outage.paymentId); outbox = (Sql billing "SELECT row_to_json(record) FROM (SELECT event_id,status,attempts,next_attempt_at FROM outbox_events WHERE aggregate_id='$($outage.paymentId)' AND event_type='payment.confirmed.v1') record;")}
    Wait-States 'AGUARDANDO_PAGAMENTO' 'WAITING_PAYMENT' | Out-Null
    Check ((Sql billing "SELECT status FROM outbox_events WHERE aggregate_id='$($outage.paymentId)' AND event_type='payment.confirmed.v1';") -ne 'PUBLISHED') 'Evento financeiro preservado sem broker'
    Compose @('start','rabbitmq')
    Until 'broker recuperado' { try {(Broker Get 'overview').rabbitmq_version} catch {$null} } | Out-Null
    Finish-Scenario $outage 'FINALIZADA' 'COMPLETED'
    Check ((Sql billing "SELECT payload FROM outbox_events WHERE aggregate_id='$($outage.paymentId)' AND event_type='payment.confirmed.v1';") -ceq $outboxBefore) 'Retry real preserva eventId e envelope financeiro'

    $unknown = New-Pending 'UnknownRecovered' 3
    Mock Post '/__admin/config' @{delay_next_create = $true} | Out-Null
    $response = Checkout $unknown.paymentId
    Check ($response.status -eq 202 -and $response.body.checkoutStatus -eq 'UNKNOWN') 'Timeout apos criacao deixa UNKNOWN'
    Save 'unknown-response' $response
    Until 'preferencia recuperada por referencia' { $current = Checkout $unknown.paymentId; if($current.status -eq 200){$current.body} } | Out-Null
    Create-Checkout $unknown
    $external = Mock Post '/__admin/payments' @{preferenceId = $unknown.checkout.preferenceId; status = 'approved'}
    $unknown.providerPaymentId = "$($external.id)"
    Check ((Send-Webhook (Webhook $unknown.providerPaymentId)) -eq 200) 'Webhook apos recuperacao UNKNOWN'
    Wait-Api billing "/api/v1/pagamentos/$($unknown.paymentId)" {param($value) $value.status -eq 'CONFIRMED'} | Out-Null
    Finish-Scenario $unknown 'FINALIZADA' 'COMPLETED'

    $hidden = New-Pending 'UnknownUnproven' 4
    Mock Post '/__admin/config' @{delay_next_create = $true; hide_preferences = $true} | Out-Null
    $response = Checkout $hidden.paymentId
    Check ($response.status -eq 202 -and $response.body.checkoutStatus -eq 'UNKNOWN') 'Resultado nao demonstravel permanece UNKNOWN'
    $secondResponse = Until 'reserva liberada apos timeout' { $current = Checkout $hidden.paymentId; if($current.status -eq 202){$current} }
    Check ($secondResponse.body.checkoutStatus -eq 'UNKNOWN') 'Pesquisa vazia nao autoriza novo POST'
    $state = Mock Get '/__admin/state'
    Check (@($state.preferences.PSObject.Properties | Where-Object {$_.Value.external_reference -eq $hidden.paymentBefore.externalReference}).Count -eq 1) 'Timeout nao cria preferencia duplicada mesmo sem visibilidade'
    Finish-Scenario $hidden 'AGUARDANDO_PAGAMENTO' 'WAITING_PAYMENT'
    Check ($hidden.paymentAfter.status -eq 'PENDING') 'UNKNOWN nao confirma nem falha financeiramente'

    Step 'wire and persistence evidence'
    Wait-Published
    $deliveries = @(Broker Post 'queues/%2F/e2e76.audit/get' @{count = 200; ackmode = 'ack_requeue_true'; encoding = 'auto'; truncate = 100000})
    $timeline = @($deliveries | ForEach-Object {
        $envelope = Parse-Json $_.payload
        Check ($envelope.occurredAt -is [string] -and $envelope.occurredAt -match '^\d{4}-\d{2}-\d{2}T.*Z$') "occurredAt ISO $($envelope.eventType)"
        Check ($envelope.eventType -eq $_.routing_key) "Routing key $($envelope.eventType)"
        $expectedSource = if($_.exchange -eq 'oficina.commands'){'os-service'}elseif($envelope.eventType -like 'quote.*' -or $envelope.eventType -like 'payment.*'){'billing-service'}else{'execution-service'}
        Check ($envelope.source -eq $expectedSource) "Source $($envelope.eventType)"
        $envelope
    })
    Save 'event-timeline' $timeline
    foreach($case in $scenarios) {
        $events = @($timeline | Where-Object osId -eq $case.osId)
        $requiredTypes = @('diagnostic.start.v1','diagnostic.started.v1','diagnostic.completed.v1','quote.create.v1','quote.created.v1','quote.approved.v1','repair.start.v1','repair.started.v1','repair.completed.v1','payment.request.v1')
        if($case.name -in @('Approved','BrokerOutage','UnknownRecovered')){$requiredTypes += 'payment.confirmed.v1'}
        if($case.name -eq 'Rejected'){$requiredTypes += 'payment.failed.v1'}
        foreach($eventType in $requiredTypes){Check (@($events | Where-Object eventType -eq $eventType).Count -gt 0) "$($case.name) evento real $eventType"}
        Check (@($events | Where-Object {$_.sagaId -ne $case.sagaId -or $_.correlationId -ne $case.correlationId}).Count -eq 0) "$($case.name) correlacao integral"
        $case.events = $events
        $confirmationCount = Sql billing "SELECT count(*) FROM outbox_events WHERE aggregate_id='$($case.paymentId)' AND event_type='payment.confirmed.v1';"
        Check ($confirmationCount -eq $(if($case.name -in @('Approved','BrokerOutage','UnknownRecovered')){'1'}else{'0'})) "$($case.name) contagem exata confirmacoes"
        Check ((Sql billing "SELECT count(*) FROM pagamentos WHERE orcamento_id='$($case.quoteId)';") -eq '1') "$($case.name) pagamento logico unico"
        Check ((Sql billing "SELECT count(*) FROM pagamentos_tentativas WHERE pagamento_id='$($case.paymentId)' AND idempotency_key='$($case.paymentBefore.idempotencyKey)';") -eq '1') "$($case.name) tentativa e chave originais unicas"
        $financialEvents = @($events | Where-Object {$_.eventType -in @('payment.confirmed.v1','payment.failed.v1')})
        foreach($event in $financialEvents) {
            Check ((Sql os "SELECT count(*) FROM inbox_messages WHERE event_id='$($event.eventId)' AND status='PROCESSED';") -eq '1') "$($case.name) evento financeiro consumido na Inbox OS"
            if($event.eventType -eq 'payment.confirmed.v1'){Check ($event.payload.paymentId -eq $case.paymentId) "$($case.name) evento usa pagamento logico correto"}
        }
        if($case.providerPaymentId){
            $external = (Mock Get '/__admin/state').payments.($case.providerPaymentId)
            Check ($external.live_mode -eq $false -and $external.currency_id -eq 'BRL' -and $external.external_reference -eq $case.paymentBefore.externalReference) "$($case.name) identidade externa de teste"
            Check ([decimal]::Parse($external.transaction_amount, [Globalization.CultureInfo]::InvariantCulture) -eq [decimal]::Parse($case.paymentBefore.valor, [Globalization.CultureInfo]::InvariantCulture)) "$($case.name) valor externo exato aprovado"
        }
        $case.persistence = Sql billing "SELECT json_build_object('pagamento',(SELECT row_to_json(record) FROM (SELECT * FROM pagamentos WHERE id='$($case.paymentId)') record),'tentativas',(SELECT json_agg(record) FROM (SELECT * FROM pagamentos_tentativas WHERE pagamento_id='$($case.paymentId)') record),'checkouts',(SELECT json_agg(record) FROM (SELECT * FROM provider_checkouts WHERE payment_id='$($case.paymentId)') record),'outbox',(SELECT json_agg(record) FROM (SELECT event_id,event_type,status,attempts,payload FROM outbox_events WHERE os_id='$($case.osId)') record));"
    }
    Save 'scenarios' $scenarios.ToArray()
    Save 'mock-state' (Mock Get '/__admin/state')
    Save 'final-snapshot' (Snapshot 'snapshot')
    $queues = @(Broker Get 'queues/%2F')
    Save 'queues' @($queues | Select-Object name,consumers,messages_ready,messages_unacknowledged)
    Check (@($queues | Where-Object {$_.name -like '*.dlq' -and $_.messages -gt 0}).Count -eq 0) 'DLQs vazias'
    $result.status = 'PASSED'
} catch {
    $result.status = 'BLOCKED'
    $result.failure = @{checkpoint = $script:step; message = $_.Exception.Message}
    Write-Host "BLOQUEIO: $($result.failure.message)"
} finally {
    $result.completedAt = [DateTime]::UtcNow.ToString('o')
    $result.checks = $checks.ToArray()
    $result.scenarios = $scenarios.ToArray()
    Save 'result' $result
    Save 'http-trace' $httpTrace.ToArray()
    $ErrorActionPreference = 'Continue'
    & $Docker @composeArgs logs --no-color --timestamps os execution billing *> (Join-Path $RunRoot 'services.log')
    & $Docker @composeArgs ps --format json | Set-Content -Encoding UTF8 (Join-Path $RunRoot 'containers.jsonl')
}
Write-Output "E2E7.6 $($result.status): $($checks.Count) verificacoes. $RunRoot"
if($result.status -ne 'PASSED'){exit 1}
