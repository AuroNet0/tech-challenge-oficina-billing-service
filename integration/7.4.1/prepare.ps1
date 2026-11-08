param(
    [string]$Git = 'C:/Program Files/Git/cmd/git.exe',
    [string]$Docker = 'C:/Program Files/Docker/Docker/resources/bin/docker.exe',
    [int]$OsPort = 19080,
    [int]$ExecutionPort = 19081,
    [int]$BillingPort = 19082,
    [int]$RabbitManagementPort = 15676
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot/../..").Path
$runId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 6)
$runRoot = Join-Path $root "target/integration-7.4.1/$runId"
$sourceRoot = Join-Path $runRoot 'sources'
New-Item -ItemType Directory -Path $sourceRoot -Force | Out-Null
$manifest = [ordered]@{
    runId = $runId
    project = "billing-e2e-$runId"
    createdAt = [DateTime]::UtcNow.ToString('o')
    sourceRoot = $sourceRoot.Replace('\', '/')
    compose = (Join-Path $PSScriptRoot 'compose.yaml')
    revisions = [ordered]@{}
    ports = @{os = $OsPort; execution = $ExecutionPort; billing = $BillingPort; rabbitManagement = $RabbitManagementPort}
}
foreach ($service in @('os', 'execution', 'billing')) {
    $repository = (Resolve-Path "$root/../tech-challenge-oficina-$service-service").Path.Replace('\', '/')
    $branch = & $Git -c "safe.directory=$repository" -C $repository branch --show-current
    $revision = & $Git -c "safe.directory=$repository" -C $repository rev-parse HEAD
    $remote = & $Git ls-remote "https://github.com/AuroNet0/tech-challenge-oficina-$service-service.git" refs/heads/homolog
    if ($LASTEXITCODE -ne 0 -or $branch -ne 'homolog' -or ($remote -split '\s+')[0] -ne $revision) {
        throw "HEAD local de $service nao corresponde a homolog remoto; nenhuma fonte foi modificada."
    }
    $dirty = @(& $Git -c "safe.directory=$repository" -C $repository status --porcelain --untracked-files=no)
    $manifest.revisions[$service] = @{commit = $revision; trackedLocalChangesExcluded = $dirty}
    $archive = Join-Path $runRoot "$service.zip"
    & $Git -c "safe.directory=$repository" -c core.autocrlf=false -c core.eol=lf -C $repository archive --format=zip "--output=$archive" HEAD
    if ($LASTEXITCODE -ne 0) { throw "Falha ao obter snapshot $service" }
    Expand-Archive -LiteralPath $archive -DestinationPath (Join-Path $sourceRoot $service)
}
& $Docker ps --no-trunc --format '{{json .}}' | Set-Content -Encoding UTF8 (Join-Path $runRoot 'original-containers.jsonl')
if ($LASTEXITCODE -ne 0) { throw 'Docker indisponivel' }
$manifest | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 (Join-Path $runRoot 'manifest.json')
$env:E2E_SOURCE_ROOT = $manifest.sourceRoot
$env:E2E_OS_PORT = "$OsPort"
$env:E2E_EXECUTION_PORT = "$ExecutionPort"
$env:E2E_BILLING_PORT = "$BillingPort"
$env:E2E_RABBIT_MANAGEMENT_PORT = "$RabbitManagementPort"
$ErrorActionPreference = 'Continue'
& $Docker compose -p $manifest.project -f $manifest.compose up --build -d --wait --wait-timeout 360 *> (Join-Path $runRoot 'compose-up.log')
$composeExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($composeExit -ne 0) { throw "Falha ao iniciar ambiente. Inspecione $runRoot/compose-up.log" }
Write-Output "Ambiente novo: $runRoot"
Write-Output "Execute: & '$PSScriptRoot/run.ps1' -RunRoot '$runRoot' -Scenario Main"
