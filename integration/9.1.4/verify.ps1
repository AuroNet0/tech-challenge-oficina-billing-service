param(
    [string]$Maven = 'C:/Users/arneto/.m2/wrapper/dists/apache-maven-3.10.0-bin/39q0qgmngdu329psje7bjgne79/apache-maven-3.10.0/bin/mvn.cmd',
    [string]$JavaHome = 'C:/Users/arneto/.jdks/ms-21.0.12.1',
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'artifacts/verify')
)
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = $JavaHome
$env:PATH = "$JavaHome/bin;C:/Program Files/Docker/Docker/resources/bin;$env:PATH"
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$output = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force $output | Out-Null
$results = @()
foreach ($service in @('auth', 'os-service', 'execution-service', 'billing-service')) {
    $repo = Join-Path $root "tech-challenge-oficina-$service"
    $log = Join-Path $output "$service.log"
    if (Test-Path $log) { throw "Verification output already exists: $log" }
    Push-Location $repo
    try {
        $previous = Join-Path $output "$service-previous-reports"
        New-Item -ItemType Directory -Force $previous | Out-Null
        foreach ($directory in @('surefire-reports','failsafe-reports','site/jacoco')) {
            if (Test-Path "target/$directory") { Copy-Item -Recurse -LiteralPath "target/$directory" -Destination $previous }
        }
        & $Maven -B clean verify *> $log
        $buildExit = $LASTEXITCODE
        $reports = Join-Path $output "$service-reports"
        New-Item -ItemType Directory -Force $reports | Out-Null
        foreach ($directory in @('surefire-reports','failsafe-reports','site/jacoco')) {
            if (Test-Path "target/$directory") { Copy-Item -Recurse -LiteralPath "target/$directory" -Destination $reports }
        }
        $totals = @{ tests = 0; failures = 0; errors = 0; skipped = 0 }
        foreach ($report in Get-ChildItem target/surefire-reports/TEST-*.xml,target/failsafe-reports/TEST-*.xml -ErrorAction SilentlyContinue) {
            [xml]$document = Get-Content $report.FullName
            foreach ($key in @('tests','failures','errors','skipped')) { $totals[$key] += [int]$document.testsuite.$key }
        }
        $coverage = $null
        if (Test-Path target/site/jacoco/jacoco.xml) {
            [xml]$jacoco = Get-Content target/site/jacoco/jacoco.xml
            $counter = $jacoco.report.counter | Where-Object type -eq 'LINE'
            $coverage = [math]::Round(100 * [int]$counter.covered / ([int]$counter.covered + [int]$counter.missed), 2)
        }
        $passed = $buildExit -eq 0 -and $totals.tests -gt 0 -and $totals.skipped -eq 0 -and $totals.failures -eq 0 -and $totals.errors -eq 0 -and $coverage -ge 80
        $results += [ordered]@{ service = $service; exitCode = $buildExit; counts = $totals; lineCoverage = $coverage; passed = $passed }
        $results | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 (Join-Path $output 'result.json')
        Write-Output "$service passed=$passed tests=$($totals.tests) coverage=$coverage"
    } finally { Pop-Location }
}
if ($results.passed -contains $false) { exit 1 }
