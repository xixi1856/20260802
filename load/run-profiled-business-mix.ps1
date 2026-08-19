param(
    [Parameter(Mandatory = $true)]
    [string]$ResultName,
    [string]$Duration = "3m",
    [int]$NearbyRate = 10,
    [int]$RouteRate = 2,
    [int]$TrackRate = 5,
    [int]$ReportRate = 2,
    [int]$SampleIntervalSeconds = 5
)

$ErrorActionPreference = "Stop"
$resultDirectory = Join-Path $PSScriptRoot "results"
New-Item -ItemType Directory -Force -Path $resultDirectory | Out-Null

$summaryPath = Join-Path $resultDirectory "$ResultName.json"
$metricsPath = Join-Path $resultDirectory "$ResultName-metrics.csv"
$stdoutPath = Join-Path $resultDirectory "$ResultName.stdout.log"
$stderrPath = Join-Path $resultDirectory "$ResultName.stderr.log"

$env:DURATION = $Duration
$env:NEARBY_RATE = [string]$NearbyRate
$env:ROUTE_RATE = [string]$RouteRate
$env:TRACK_RATE = [string]$TrackRate
$env:REPORT_RATE = [string]$ReportRate

function Get-PrometheusValue {
    param(
        [string[]]$Lines,
        [string]$Metric
    )

    $line = $Lines | Where-Object { $_ -match "^$([regex]::Escape($Metric))(\{| )" } | Select-Object -First 1
    if (-not $line) {
        return [double]::NaN
    }
    return [double]::Parse(($line -split " ")[-1], [Globalization.CultureInfo]::InvariantCulture)
}

function Get-PrometheusMatchingValue {
    param(
        [string[]]$Lines,
        [string]$Pattern
    )

    $line = $Lines | Where-Object { $_ -match $Pattern } | Select-Object -First 1
    if (-not $line) {
        return [double]::NaN
    }
    return [double]::Parse(($line -split " ")[-1], [Globalization.CultureInfo]::InvariantCulture)
}

function Get-ContainerStats {
    param([string]$ContainerName)

    $raw = docker stats --no-stream --format "{{json .}}" $ContainerName | ConvertFrom-Json
    return [pscustomobject]@{
        CpuPercent = [double](($raw.CPUPerc -replace "%", ""))
        Memory = $raw.MemUsage
    }
}

$rows = [System.Collections.Generic.List[object]]::new()
$arguments = @(
    "run",
    "--summary-export", $summaryPath,
    (Join-Path $PSScriptRoot "k6-business-mix.js")
)

$process = Start-Process -FilePath "k6" -ArgumentList $arguments -NoNewWindow -PassThru `
    -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
$initialNearbyCount = $null

try {
    while (-not $process.HasExited) {
        $sampledAt = [DateTimeOffset]::UtcNow.ToString("O")
        $prometheus = (Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:8080/actuator/prometheus" `
            -TimeoutSec 5).Content -split "`n"
        $backend = Get-ContainerStats "blindway-backend-1"
        $postgres = Get-ContainerStats "blindway-postgres-1"
        $nearbyCount = Get-PrometheusMatchingValue $prometheus `
            '^http_server_requests_seconds_count\{.*method="GET".*uri="/api/v1/accessibility-issues".*\}'
        if ($null -eq $initialNearbyCount) {
            $initialNearbyCount = $nearbyCount
        }

        $rows.Add([pscustomobject]@{
            sampled_at = $sampledAt
            phase = if ($nearbyCount -gt $initialNearbyCount) { "business" } else { "setup" }
            nearby_request_count = $nearbyCount
            hikari_active = Get-PrometheusValue $prometheus "hikaricp_connections_active"
            hikari_idle = Get-PrometheusValue $prometheus "hikaricp_connections_idle"
            hikari_pending = Get-PrometheusValue $prometheus "hikaricp_connections_pending"
            hikari_acquire_count = Get-PrometheusValue $prometheus "hikaricp_connections_acquire_seconds_count"
            hikari_acquire_seconds = Get-PrometheusValue $prometheus "hikaricp_connections_acquire_seconds_sum"
            hikari_usage_count = Get-PrometheusValue $prometheus "hikaricp_connections_usage_seconds_count"
            hikari_usage_seconds = Get-PrometheusValue $prometheus "hikaricp_connections_usage_seconds_sum"
            hikari_timeouts = Get-PrometheusValue $prometheus "hikaricp_connections_timeout_total"
            backend_cpu_percent = $backend.CpuPercent
            postgres_cpu_percent = $postgres.CpuPercent
            backend_memory = $backend.Memory
            postgres_memory = $postgres.Memory
        })
        Start-Sleep -Seconds $SampleIntervalSeconds
        $process.Refresh()
    }
}
finally {
    $rows | Export-Csv -NoTypeInformation -Encoding utf8 $metricsPath
}

if ($process.ExitCode -eq 99) {
    Write-Warning "k6 completed, but one or more performance thresholds were crossed"
}
elseif ($process.ExitCode -ne 0) {
    Get-Content -LiteralPath $stderrPath -Tail 80
    throw "k6 exited with code $($process.ExitCode)"
}

Write-Host "Summary: $summaryPath"
Write-Host "Metrics: $metricsPath"
