param(
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 10000000)]
    [int]$ExpectedPublished,

    [datetime]$Since = (Get-Date).AddMinutes(-30),

    [ValidateRange(1, 3600)]
    [int]$TimeoutSeconds = 120,

    [ValidateRange(1, 30)]
    [int]$PollSeconds = 2
)

$sinceUtc = $Since.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
$databaseUser = if ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { 'blindway' }
$databaseName = if ($env:POSTGRES_DB) { $env:POSTGRES_DB } else { 'blindway' }
$sql = @"
WITH test_events AS (
    SELECT id
    FROM integration_event_outbox
    WHERE event_type = 'PERCEPTION_RECORDED'
      AND created_at >= '$sinceUtc'::timestamptz
), counts AS (
    SELECT
        (SELECT count(*) FROM test_events) AS outbox_total,
        (SELECT count(*) FROM mqtt_inbox i JOIN test_events t ON t.id = i.event_id
         WHERE i.process_status = 'PROCESSED') AS inbox_processed,
        (SELECT count(*) FROM integration_event_outbox o JOIN test_events t ON t.id = o.id
         WHERE o.status = 'PUBLISHED') AS outbox_published,
        (SELECT count(*) FROM kafka_consumed_event c JOIN test_events t ON t.id = c.event_id
         WHERE c.consumer_name = 'community-candidate-v1') AS community_consumed,
        (SELECT count(*) FROM kafka_consumed_event c JOIN test_events t ON t.id = c.event_id
         WHERE c.consumer_name = 'trip-risk-v1') AS trip_consumed,
        (SELECT count(*) FROM kafka_consumed_event c JOIN test_events t ON t.id = c.event_id
         WHERE c.consumer_name = 'device-analytics-v1') AS analytics_consumed
)
SELECT outbox_total, inbox_processed, outbox_published, community_consumed, trip_consumed, analytics_consumed
FROM counts;
"@

function Read-ConservationCounters {
    $result = docker compose exec -T postgres psql `
        -U $databaseUser `
        -d $databaseName `
        -At -F ',' -c $sql

    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to query Kafka fan-out conservation counters.'
    }
    return $result.Trim().Split(',') | ForEach-Object { [int64]$_ }
}

$names = @('outbox_total', 'inbox_processed', 'outbox_published', 'community_consumed', 'trip_consumed', 'analytics_consumed')
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
do {
    $values = @(Read-ConservationCounters)
    $complete = $values.Count -eq $names.Count
    for ($index = 0; $complete -and $index -lt $names.Count; $index++) {
        $complete = $values[$index] -eq $ExpectedPublished
    }
    if (-not $complete) {
        Start-Sleep -Seconds $PollSeconds
    }
} while (-not $complete -and (Get-Date) -lt $deadline)

for ($index = 0; $index -lt $names.Count; $index++) {
    Write-Host "$($names[$index])=$($values[$index])"
    if ($values[$index] -ne $ExpectedPublished) {
        throw "$($names[$index]) expected $ExpectedPublished but was $($values[$index])."
    }
}

$runningKafka = docker compose ps --status running --services | Where-Object { $_ -match '^kafka-[123]$' } | Select-Object -First 1
if (-not $runningKafka) {
    throw 'No running Kafka broker is available for ISR verification.'
}
$topicDescription = docker compose exec -T $runningKafka `
    /opt/kafka/bin/kafka-topics.sh `
    --bootstrap-server "$runningKafka`:19092" `
    --describe `
    --topic blindway.perception.recorded.v1
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to describe the perception Kafka topic.'
}

$isrMatches = [regex]::Matches(($topicDescription -join "`n"), 'Isr:\s+([0-9,]+)')
if ($isrMatches.Count -ne 6) {
    throw "Expected ISR data for 6 partitions but found $($isrMatches.Count)."
}
foreach ($match in $isrMatches) {
    $isrCount = $match.Groups[1].Value.Split(',').Count
    if ($isrCount -lt 2) {
        throw "Partition ISR dropped below min ISR 2: $($match.Groups[1].Value)."
    }
}

Write-Host 'Kafka ISR verified: all 6 partitions have at least 2 in-sync replicas.'
Write-Host "Kafka fan-out conservation verified for $ExpectedPublished unique events."
