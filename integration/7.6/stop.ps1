param([Parameter(Mandatory = $true)][string]$RunRoot)
$ErrorActionPreference = 'Stop'
$manifest = Get-Content -Raw (Join-Path $RunRoot 'manifest.json') | ConvertFrom-Json
if ($manifest.project -notmatch '^billing-e2e76-\d{8}-\d{6}-[0-9a-f]{6}$') { throw 'Projeto fora do escopo E2E7.6' }
$credentials = Get-Content -Raw (Join-Path $RunRoot 'provider-secrets.json') | ConvertFrom-Json
$env:E2E_MP_ACCESS_TOKEN = $credentials.accessToken
$env:E2E_MP_WEBHOOK_SECRET = $credentials.webhookSecret
$env:E2E_SOURCE_ROOT = $manifest.sourceRoot
$env:E2E_OS_PORT = "$($manifest.ports.os)"
$env:E2E_EXECUTION_PORT = "$($manifest.ports.execution)"
$env:E2E_BILLING_PORT = "$($manifest.ports.billing)"
$env:E2E_RABBIT_MANAGEMENT_PORT = "$($manifest.ports.rabbitManagement)"
& 'C:/Program Files/Docker/Docker/resources/bin/docker.exe' compose -p $manifest.project -f $manifest.compose stop
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
