function Measure-E2EMemory {
    param([string]$RunRoot, [string]$Label, [string]$Docker = 'C:/Program Files/Docker/Docker/resources/bin/docker.exe')
    if (Test-Path "$RunRoot/evidence/memory-alert.json") { throw 'Memory monitoring reported an alert; no further load allowed' }
    $manifest = Get-Content -Raw "$RunRoot/manifest.json" | ConvertFrom-Json
    if ($manifest.project -notmatch '^oficina-e2e914-[a-f0-9]{12}$') { throw 'Invalid isolated E2E project' }
    $windows = Get-CimInstance Win32_OperatingSystem
    $vmLines = @(& C:/Windows/System32/wsl.exe -d docker-desktop -- cat /proc/meminfo /proc/pressure/memory /proc/vmstat)
    if ($LASTEXITCODE -ne 0) { throw 'VM memory measurement failed' }
    $vm = @{}
    foreach ($line in $vmLines) {
        if ($line -match '^(MemTotal|MemAvailable|SwapTotal|SwapFree):\s+(\d+)') { $vm[$matches[1]] = [long]$matches[2] }
        if ($line -match '^oom_kill\s+(\d+)') { $vm.oomKill = [long]$matches[1] }
        if ($line -match '^full avg10=([\d.]+)') { $vm.fullPressureAvg10 = [double]::Parse($matches[1], [Globalization.CultureInfo]::InvariantCulture) }
    }
    if (!$vm.ContainsKey('MemAvailable') -or !$vm.ContainsKey('oomKill')) { throw 'Incomplete memory measurement' }
    $containerIds = @(& $Docker ps -aq --filter "label=com.docker.compose.project=$($manifest.project)" --filter 'label=com.docker.compose.oneoff=False')
    if ($LASTEXITCODE -ne 0) { throw 'Docker Engine unavailable' }
    $containers = @()
    foreach ($containerId in $containerIds) {
        $state = & $Docker inspect --format '{{.Name}}|{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{end}}|{{.State.OOMKilled}}|{{.RestartCount}}|{{.HostConfig.Memory}}|{{.State.ExitCode}}' $containerId
        if ($LASTEXITCODE -ne 0) { throw 'Container measurement failed' }
        $parts = $state -split '\|'
        $containers += @{ name = $parts[0]; state = $parts[1]; health = $parts[2]; oomKilled = ($parts[3] -eq 'true'); restarts = [int]$parts[4]; memoryLimitBytes = [long]$parts[5]; exitCode = [int]$parts[6] }
    }
    $stats = @()
    if (@($containers | Where-Object state -eq 'running').Count -gt 0) {
        $stats = @(& $Docker stats --no-stream --format '{{json .}}' @($containerIds) | ForEach-Object { $_ | ConvertFrom-Json })
        if ($LASTEXITCODE -ne 0) { throw 'Docker stats failed' }
    }
    $sample = [ordered]@{
        timestamp = (Get-Date -Format o); label = $Label
        windowsFreeKiB = [long]$windows.FreePhysicalMemory; windowsTotalKiB = [long]$windows.TotalVisibleMemorySize
        windowsFreeVirtualKiB = [long]$windows.FreeVirtualMemory
        pageFile = @(Get-CimInstance Win32_PageFileUsage | Select-Object AllocatedBaseSize,CurrentUsage,PeakUsage)
        vm = $vm; containers = $containers; stats = $stats
        processes = @(Get-Process | Where-Object ProcessName -Match 'docker|vmmem|wsl|java' | Select-Object ProcessName,Id,WorkingSet64,PrivateMemorySize64)
    }
    $baselineFile = "$RunRoot/memory-baseline.json"
    $mutex = [Threading.Mutex]::new($false, "Local\OficinaE2EMemory-$($manifest.runId)")
    $acquired = $false
    try {
        $acquired = $mutex.WaitOne([TimeSpan]::FromSeconds(30))
        if (!$acquired) { throw 'Memory evidence writer lock timed out' }
        $line = ($sample | ConvertTo-Json -Depth 8 -Compress) + [Environment]::NewLine
        $bytes = [Text.UTF8Encoding]::new($false).GetBytes($line)
        $writeDeadline = [DateTime]::UtcNow.AddSeconds(10)
        do {
            $stream = $null
            try {
                $stream = [IO.File]::Open("$RunRoot/evidence/memory.jsonl", [IO.FileMode]::Append, [IO.FileAccess]::Write, [IO.FileShare]::ReadWrite)
                $stream.Write($bytes, 0, $bytes.Length)
                $stream.Flush()
                break
            } catch [IO.IOException] {
                if ([DateTime]::UtcNow -ge $writeDeadline) { throw }
                Start-Sleep -Milliseconds 100
            } finally {
                if ($null -ne $stream) { $stream.Dispose() }
            }
        } while ($true)
        if (!(Test-Path $baselineFile)) { $sample | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 $baselineFile }
    } finally {
        if ($acquired) { $mutex.ReleaseMutex() }
        $mutex.Dispose()
    }
    $baseline = Get-Content -Raw $baselineFile | ConvertFrom-Json
    Write-Output "MEMORY $Label WindowsFreeMiB=$([math]::Round($windows.FreePhysicalMemory / 1024)) VMAvailableMiB=$([math]::Round($vm.MemAvailable / 1024)) SwapFreeMiB=$([math]::Round($vm.SwapFree / 1024)) oomKill=$($vm.oomKill)"
    if ($vm.oomKill -gt $baseline.vm.oomKill) { throw 'New VM OOM detected; no further load allowed' }
    $expectedNames = @()
    if (Test-Path "$RunRoot/runtime/expected-outages.json") {
        $outages = Get-Content -Raw "$RunRoot/runtime/expected-outages.json" | ConvertFrom-Json
        foreach ($service in $outages.services) {
            if ($service -notin @('os-service','rabbitmq')) { throw 'Invalid controlled outage component' }
            $expectedNames += "/$($manifest.project)-$service-1"
        }
    }
    if (@($containers | Where-Object { $_.oomKilled -or $_.restarts -gt 1 -or (($_.health -eq 'unhealthy' -or ($_.state -eq 'exited' -and $_.exitCode -ne 0)) -and $_.name -notin $expectedNames) }).Count -gt 0) { throw 'Container OOM, repeated restart or failed readiness detected' }
    if ($vm.MemAvailable -lt 131072 -or ($vm.SwapFree -lt 131072 -and $vm.fullPressureAvg10 -gt 20)) { throw 'Measured critical VM memory pressure; no further load allowed' }
}
