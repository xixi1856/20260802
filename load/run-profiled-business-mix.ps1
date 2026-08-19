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

function Get-PrometheusSum {
    param(
        [string[]]$Lines,
        [string]$Pattern
    )

    $matching = @($Lines | Where-Object { $_ -match $Pattern })
    if ($matching.Count -eq 0) {
        return 0.0
    }
    return [double](($matching | ForEach-Object {
        [double]::Parse(($_ -split " ")[-1], [Globalization.CultureInfo]::InvariantCulture)
    } | Measure-Object -Sum).Sum)
}

function Get-ContainerStats {
    param([string]$ContainerName)

    $raw = docker stats --no-stream --format "{{json .}}" $ContainerName | ConvertFrom-Json
    return [pscustomobject]@{
        CpuPercent = [double](($raw.CPUPerc -replace "%", ""))
        Memory = $raw.MemUsage
    }
}

function Get-PostgresStats {
    $sql = @"
WITH table_stats AS (
    SELECT COALESCE(sum(autovacuum_count), 0) AS autovacuum_count,
           COALESCE(sum(autoanalyze_count), 0) AS autoanalyze_count,
           COALESCE(sum(n_dead_tup), 0) AS dead_tuples
    FROM pg_stat_user_tables
), activity AS (
    SELECT count(*) FILTER (WHERE pid <> pg_backend_pid() AND state = 'active') AS active_sessions,
           count(*) FILTER (WHERE pid <> pg_backend_pid() AND state = 'active'
               AND wait_event_type IS NOT NULL) AS waiting_sessions,
           COALESCE(max(EXTRACT(EPOCH FROM (clock_timestamp() - query_start)) * 1000)
               FILTER (WHERE pid <> pg_backend_pid() AND state = 'active'), 0) AS longest_query_ms
    FROM pg_stat_activity
    WHERE datname = current_database()
)
SELECT checkpointer.num_timed, checkpointer.num_requested, checkpointer.num_done,
       checkpointer.write_time, checkpointer.sync_time, checkpointer.buffers_written,
       database.xact_commit, database.xact_rollback, database.blks_read, database.blks_hit,
       database.temp_files, database.temp_bytes, database.deadlocks,
       table_stats.autovacuum_count, table_stats.autoanalyze_count, table_stats.dead_tuples,
       activity.active_sessions, activity.waiting_sessions, activity.longest_query_ms
FROM pg_stat_checkpointer checkpointer
CROSS JOIN pg_stat_database database
CROSS JOIN table_stats
CROSS JOIN activity
WHERE database.datname = current_database()
"@
    $values = (docker exec blindway-postgres-1 psql -U blindway -d blindway -At -F "|" -c $sql) -split "\|"
    return [pscustomobject]@{
        CheckpointsTimed = [double]$values[0]
        CheckpointsRequested = [double]$values[1]
        CheckpointsDone = [double]$values[2]
        CheckpointWriteMs = [double]$values[3]
        CheckpointSyncMs = [double]$values[4]
        CheckpointBuffers = [double]$values[5]
        Commits = [double]$values[6]
        Rollbacks = [double]$values[7]
        BlocksRead = [double]$values[8]
        BlocksHit = [double]$values[9]
        TempFiles = [double]$values[10]
        TempBytes = [double]$values[11]
        Deadlocks = [double]$values[12]
        AutovacuumCount = [double]$values[13]
        AutoanalyzeCount = [double]$values[14]
        DeadTuples = [double]$values[15]
        ActiveSessions = [double]$values[16]
        WaitingSessions = [double]$values[17]
        LongestQueryMs = [double]$values[18]
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
        $postgresStats = Get-PostgresStats
        $nearbyCount = Get-PrometheusMatchingValue $prometheus `
            '^http_server_requests_seconds_count\{.*method="GET".*uri="/api/v1/accessibility-issues".*\}'
        if ($null -eq $initialNearbyCount) {
            $initialNearbyCount = if ([double]::IsNaN($nearbyCount)) { 0 } else { $nearbyCount }
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
            spatial_active = Get-PrometheusValue $prometheus "blindway_spatial_active"
            spatial_waiting = Get-PrometheusValue $prometheus "blindway_spatial_waiting"
            spatial_rejected = Get-PrometheusSum $prometheus '^blindway_spatial_rejected_total'
            spatial_timeouts = Get-PrometheusSum $prometheus '^blindway_spatial_timeouts_total'
            backend_cpu_percent = $backend.CpuPercent
            postgres_cpu_percent = $postgres.CpuPercent
            backend_memory = $backend.Memory
            postgres_memory = $postgres.Memory
            postgres_active_sessions = $postgresStats.ActiveSessions
            postgres_waiting_sessions = $postgresStats.WaitingSessions
            postgres_longest_query_ms = $postgresStats.LongestQueryMs
            postgres_checkpoints_timed = $postgresStats.CheckpointsTimed
            postgres_checkpoints_requested = $postgresStats.CheckpointsRequested
            postgres_checkpoints_done = $postgresStats.CheckpointsDone
            postgres_checkpoint_write_ms = $postgresStats.CheckpointWriteMs
            postgres_checkpoint_sync_ms = $postgresStats.CheckpointSyncMs
            postgres_checkpoint_buffers = $postgresStats.CheckpointBuffers
            postgres_commits = $postgresStats.Commits
            postgres_rollbacks = $postgresStats.Rollbacks
            postgres_blocks_read = $postgresStats.BlocksRead
            postgres_blocks_hit = $postgresStats.BlocksHit
            postgres_temp_files = $postgresStats.TempFiles
            postgres_temp_bytes = $postgresStats.TempBytes
            postgres_deadlocks = $postgresStats.Deadlocks
            postgres_autovacuum_count = $postgresStats.AutovacuumCount
            postgres_autoanalyze_count = $postgresStats.AutoanalyzeCount
            postgres_dead_tuples = $postgresStats.DeadTuples
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
