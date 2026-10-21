param([Parameter(Mandatory = $true)][string]$RunRoot)
$ErrorActionPreference = 'Stop'
$docker = 'C:/Program Files/Docker/Docker/resources/bin/docker.exe'
$original = @(Get-Content (Join-Path $RunRoot 'original-containers.jsonl') | ForEach-Object { $_ | ConvertFrom-Json })
$current = @(& $docker ps --no-trunc --format '{{json .}}' | ForEach-Object { $_ | ConvertFrom-Json })
if($LASTEXITCODE -ne 0){throw 'Docker indisponivel'}
$normal = @($original | ForEach-Object {
    $before = $_
    $after = @($current | Where-Object {$_.ID -eq $before.ID -and $_.Names -eq $before.Names})
    @{name = $before.Names; sameContainerRunning = $after.Count -eq 1; status = ($after | Select-Object -ExpandProperty Status)}
})
$isolated = @(& $docker ps -a --filter name=billing-e2e76- --format '{{json .}}' | ForEach-Object {
    $entry = $_ | ConvertFrom-Json
    @{name = $entry.Names; state = $entry.State; status = $entry.Status}
})
$safe = @{checkedAt = [DateTime]::UtcNow.ToString('o'); normal = $normal; isolated = $isolated}
$safe | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 (Join-Path $PSScriptRoot 'evidence/cleanup.json')
if(@($normal | Where-Object {-not $_.sameContainerRunning}).Count -gt 0){throw 'Um container original nao permaneceu em execucao'}
if(@($isolated | Where-Object state -eq 'running').Count -gt 0){throw 'Ambiente E2E ainda em execucao'}
$credentials = Get-Content (Join-Path $RunRoot 'provider-secrets.json') -Raw | ConvertFrom-Json
foreach($file in (Get-ChildItem $PSScriptRoot -Recurse -File | Where-Object {$_.FullName -notmatch '[\\/]artifacts[\\/]'})) {
    $content = Get-Content $file.FullName -Raw
    if($content.Contains($credentials.accessToken) -or $content.Contains($credentials.webhookSecret)) {throw "Secret ficticio vazou em $($file.Name)"}
}
Write-Output "Containers normais preservados: $($normal.Count); isolados sem execucao: $($isolated.Count); secrets ausentes da exportacao."
