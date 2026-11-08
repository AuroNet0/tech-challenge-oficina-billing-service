param(
    [string]$Git = 'C:/Program Files/Git/cmd/git.exe',
    [string]$Docker = 'C:/Program Files/Docker/Docker/resources/bin/docker.exe'
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot/../..").Path
$runId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 6)
$runRoot = Join-Path $root "integration/7.6/artifacts/$runId"
$sourceRoot = Join-Path $runRoot 'sources'
New-Item -ItemType Directory -Path $sourceRoot -Force | Out-Null
$providerCredentials = @{accessToken = 'TEST-' + [Guid]::NewGuid().ToString('N'); webhookSecret = [Guid]::NewGuid().ToString('N')}
$providerCredentials | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $runRoot 'provider-secrets.json')
$env:E2E_MP_ACCESS_TOKEN = $providerCredentials.accessToken
$env:E2E_MP_WEBHOOK_SECRET = $providerCredentials.webhookSecret
$manifest = [ordered]@{
    runId = $runId; project = "billing-e2e76-$runId"; createdAt = [DateTime]::UtcNow.ToString('o')
    sourceRoot = $sourceRoot.Replace('\', '/'); compose = (Join-Path $PSScriptRoot 'compose.yaml')
    revisions = [ordered]@{}; ports = @{os = 21080; execution = 21081; billing = 21082; rabbitManagement = 15680; mock = 21099}
}
foreach ($service in @('os', 'execution', 'billing')) {
    $repository = (Resolve-Path "$root/../tech-challenge-oficina-$service-service").Path.Replace('\', '/')
    $branch = & $Git -c "safe.directory=$repository" -C $repository branch --show-current
    $revision = & $Git -c "safe.directory=$repository" -C $repository rev-parse HEAD
    $remote = & $Git ls-remote "https://github.com/AuroNet0/tech-challenge-oficina-$service-service.git" refs/heads/homolog
    if ($LASTEXITCODE -ne 0 -or $branch -ne 'homolog' -or ($remote -split '\s+')[0] -ne $revision) {
        throw "HEAD local de $service nao corresponde a homolog remoto"
    }
    $dirty = @(& $Git -c "safe.directory=$repository" -C $repository status --porcelain --untracked-files=no -- src pom.xml Dockerfile compose.yaml)
    if ($dirty.Count -gt 0) { throw "Fontes rastreadas alteradas em $service; snapshot recusado" }
    $manifest.revisions[$service] = @{commit = $revision; branch = $branch}
    $archive = Join-Path $runRoot "$service.zip"
    & $Git -c "safe.directory=$repository" -c core.autocrlf=false -c core.eol=lf -C $repository archive --format=zip "--output=$archive" HEAD
    if ($LASTEXITCODE -ne 0) { throw "Falha ao obter snapshot $service" }
    Expand-Archive -LiteralPath $archive -DestinationPath (Join-Path $sourceRoot $service)
}
& $Docker ps --no-trunc --format '{{json .}}' | Set-Content -Encoding UTF8 (Join-Path $runRoot 'original-containers.jsonl')
if ($LASTEXITCODE -ne 0) { throw 'Docker indisponivel' }
$manifest | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 (Join-Path $runRoot 'manifest.json')
$env:E2E_SOURCE_ROOT = $manifest.sourceRoot
$env:E2E_OS_PORT = "$($manifest.ports.os)"
$env:E2E_EXECUTION_PORT = "$($manifest.ports.execution)"
$env:E2E_BILLING_PORT = "$($manifest.ports.billing)"
$env:E2E_RABBIT_MANAGEMENT_PORT = "$($manifest.ports.rabbitManagement)"
$env:PATH = (Split-Path $Docker) + ';' + $env:PATH
$ErrorActionPreference = 'Continue'
& $Docker compose -p $manifest.project -f $manifest.compose up --build -d --wait --wait-timeout 360 *> (Join-Path $runRoot 'compose-up.log')
$composeExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($composeExit -ne 0) { throw "Falha ao iniciar ambiente. Inspecione $runRoot/compose-up.log" }
Write-Output "Ambiente novo: $runRoot"
