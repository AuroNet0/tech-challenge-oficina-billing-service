param([Parameter(Mandatory = $true)][string]$RunRoot, [string]$Docker = 'C:/Program Files/Docker/Docker/resources/bin/docker.exe')
$ErrorActionPreference = 'Stop'
$manifest = Get-Content -Raw "$RunRoot/manifest.json" | ConvertFrom-Json
if ($manifest.project -notmatch '^oficina-e2e914-[a-f0-9]{12}$') { throw 'Invalid isolated E2E project' }
. "$PSScriptRoot/memory.ps1"
$arguments = @('compose','--env-file',"$RunRoot/private.env",'-p',$manifest.project,'-f',"$PSScriptRoot/compose.yaml")
$logRoot = Join-Path $RunRoot ('recovery-' + [Guid]::NewGuid().ToString('N').Substring(0, 12))
New-Item -ItemType Directory $logRoot | Out-Null
foreach ($name in @('dependency-unavailable','broker-outage','broker-recovery')) {
    if (Test-Path "$RunRoot/evidence/$name.json") { Copy-Item -LiteralPath "$RunRoot/evidence/$name.json" -Destination "$logRoot/$name-before.json" }
}
foreach ($scenario in @(@{ service = 'os-service'; phase = 'unavailable'; timeout = 360 }, @{ service = 'rabbitmq'; phase = 'outage'; timeout = 180 })) {
    Measure-E2EMemory -RunRoot $RunRoot -Label "before-$($scenario.phase)" -Docker $Docker
    @{ services = @($scenario.service); phase = $scenario.phase } | ConvertTo-Json | Set-Content -Encoding UTF8 "$RunRoot/runtime/expected-outages.json"
    try {
        $ErrorActionPreference = 'Continue'
        & $Docker @arguments stop $scenario.service *> "$logRoot/stop-$($scenario.service).log"
        if ($LASTEXITCODE -ne 0) { throw 'Controlled stop failed' }
        & $Docker @arguments --profile tools run --rm runner python /runner.py $scenario.phase *> "$logRoot/$($scenario.phase).log"
        if ($LASTEXITCODE -ne 0) { throw "Recovery scenario failed: $($scenario.phase)" }
    } finally {
        & $Docker @arguments up -d --wait --wait-timeout $scenario.timeout $scenario.service *> "$logRoot/restore-$($scenario.service).log"
        if ($LASTEXITCODE -ne 0) { throw "Failed to restore isolated $($scenario.service)" }
        @{ services = @(); phase = 'restored' } | ConvertTo-Json | Set-Content -Encoding UTF8 "$RunRoot/runtime/expected-outages.json"
    }
    Measure-E2EMemory -RunRoot $RunRoot -Label "after-$($scenario.phase)" -Docker $Docker
}
& $Docker @arguments --profile tools run --rm runner python /runner.py recover *> "$logRoot/recover.log"
if ($LASTEXITCODE -ne 0) { throw 'Broker recovery failed' }
Measure-E2EMemory -RunRoot $RunRoot -Label 'after-integrated-tests' -Docker $Docker
