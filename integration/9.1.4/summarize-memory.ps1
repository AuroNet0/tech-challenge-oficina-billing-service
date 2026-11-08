param([Parameter(Mandatory = $true)][string]$RunRoot)
$ErrorActionPreference = 'Stop'
$manifest = Get-Content -Raw "$RunRoot/manifest.json" | ConvertFrom-Json
$samples = @(Get-Content "$RunRoot/evidence/memory.jsonl" | Where-Object { ![string]::IsNullOrWhiteSpace($_) } | ForEach-Object { $_ | ConvertFrom-Json })
if ($samples.Count -eq 0) { throw 'No measured memory samples' }
function UsageMiB($Usage) {
    if ($Usage -notmatch '^([\d.]+)([KMGT]?i?B)\s*/') { throw "Unknown memory unit: $Usage" }
    $number = [double]::Parse($matches[1], [Globalization.CultureInfo]::InvariantCulture)
    $factor = switch ($matches[2]) { 'B' { 1 / 1048576 }; 'KiB' { 1 / 1024 }; 'MiB' { 1 }; 'GiB' { 1024 }; 'TiB' { 1048576 }; default { throw 'Unsupported memory unit' } }
    return $number * $factor
}
$peaks = @{}
foreach ($sample in $samples) {
    foreach ($stat in $sample.stats) {
        $value = UsageMiB $stat.MemUsage
        if (!$peaks.ContainsKey($stat.Name) -or $value -gt $peaks[$stat.Name]) { $peaks[$stat.Name] = $value }
    }
}
$final = $samples[-1]
$containers = @($peaks.Keys | Sort-Object | ForEach-Object {
    $name = $_
    $lastStat = @($final.stats | Where-Object Name -eq $name)
    @{ name = $name; peakMiB = [math]::Round($peaks[$name], 2); finalMiB = $(if ($lastStat.Count) { [math]::Round((UsageMiB $lastStat[0].MemUsage), 2) } else { $null }) }
})
$stages = @($samples | Where-Object label -Like 'ready-*' | Group-Object label | ForEach-Object {
    $sample = $_.Group[0]
    @{ label = $sample.label; timestamp = $sample.timestamp; windowsFreeMiB = [math]::Round($sample.windowsFreeKiB / 1024, 2); vmAvailableMiB = [math]::Round($sample.vm.MemAvailable / 1024, 2); swapFreeMiB = [math]::Round($sample.vm.SwapFree / 1024, 2) }
})
$summary = [ordered]@{
    runId = $manifest.runId; samples = $samples.Count; firstTimestamp = $samples[0].timestamp; lastTimestamp = $final.timestamp
    metric = 'docker stats --no-stream memory usage; periodic samples, not instantaneous maxima'
    windowsMinimumFreeMiB = [math]::Round(($samples.windowsFreeKiB | Measure-Object -Minimum).Minimum / 1024, 2)
    vmMinimumAvailableMiB = [math]::Round(($samples.vm.MemAvailable | Measure-Object -Minimum).Minimum / 1024, 2)
    swapMinimumFreeMiB = [math]::Round(($samples.vm.SwapFree | Measure-Object -Minimum).Minimum / 1024, 2)
    initialOomKills = $samples[0].vm.oomKill; finalOomKills = $final.vm.oomKill
    maximumFullPressureAvg10 = ($samples.vm.fullPressureAvg10 | Measure-Object -Maximum).Maximum
    finalWindowsFreeMiB = [math]::Round($final.windowsFreeKiB / 1024, 2)
    finalVmAvailableMiB = [math]::Round($final.vm.MemAvailable / 1024, 2)
    finalTotalContainerMiB = [math]::Round(($containers.finalMiB | Measure-Object -Sum).Sum, 2)
    containers = $containers; readinessStages = $stages
}
$summary | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 "$RunRoot/evidence/memory-summary.json"
Write-Output "Memory summary: samples=$($summary.samples) finalContainerMiB=$($summary.finalTotalContainerMiB) newOomKills=$($summary.finalOomKills - $summary.initialOomKills)"
