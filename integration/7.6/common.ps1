param(
    [Parameter(Mandatory = $true)][string]$RunRoot,
    [ValidateSet('All')][string]$Scenario = 'All',
    [string]$Docker = 'C:/Program Files/Docker/Docker/resources/bin/docker.exe'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$manifest = Get-Content -Raw (Join-Path $RunRoot 'manifest.json') | ConvertFrom-Json
$providerCredentials = Get-Content -Raw (Join-Path $RunRoot 'provider-secrets.json') | ConvertFrom-Json
$env:E2E_MP_ACCESS_TOKEN = $providerCredentials.accessToken
$env:E2E_MP_WEBHOOK_SECRET = $providerCredentials.webhookSecret
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
    if ($null -ne $Session) {
        $Session.Headers.Remove('X-CSRF-TOKEN') | Out-Null
        $arguments.WebSession = $Session
    }
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

function Os-Command($EventType) {
    $raw = Sql os "SELECT payload::text FROM outbox_events WHERE aggregate_id='$($script:order.id)' AND event_type='$EventType';"
    if (-not $raw) { throw "Comando original ausente: $EventType" }
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
