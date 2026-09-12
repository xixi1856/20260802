param(
    [int]$UserCount = 50,
    [int]$IssueCount = 10000,
    [string]$BaseUrl = "http://127.0.0.1:8080/api/v1",
    [switch]$KeepExistingLoadIssues
)

$ErrorActionPreference = "Stop"
$runId = Get-Date -Format "yyyyMMddHHmmss"
$password = "load-test-password-42"
$dataDirectory = Join-Path $PSScriptRoot "data"
$usersPath = Join-Path $dataDirectory "users.local.json"
$devicesPath = Join-Path $dataDirectory "devices.local.tsv"
$seedPath = Join-Path $PSScriptRoot "seed\accessibility-issues.sql"

New-Item -ItemType Directory -Force -Path $dataDirectory | Out-Null

function Invoke-JsonPost {
    param(
        [string]$Uri,
        [hashtable]$Body,
        [string]$AccessToken
    )

    $headers = @{}
    if ($AccessToken) {
        $headers.Authorization = "Bearer $AccessToken"
    }

    Invoke-RestMethod `
        -Method Post `
        -Uri $Uri `
        -Headers $headers `
        -ContentType "application/json; charset=utf-8" `
        -Body ($Body | ConvertTo-Json -Depth 8 -Compress) `
        -TimeoutSec 30
}

$health = Invoke-RestMethod -Uri "http://127.0.0.1:8080/actuator/health" -TimeoutSec 10
if ($health.status -ne "UP") {
    throw "Backend health is not UP"
}

if (-not $KeepExistingLoadIssues) {
    docker exec blindway-postgres-1 psql -U blindway -d blindway -v ON_ERROR_STOP=1 `
        -c "DELETE FROM accessibility_issue i USING app_user u WHERE i.reporter_user_id = u.id AND (i.description LIKE 'LOAD_SEED_%' OR u.email LIKE 'load-user-%@example.org');" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to reset previous load-test issues"
    }
}

$adminEmail = "load-admin-$runId@example.org"
Invoke-JsonPost `
    -Uri "$BaseUrl/auth/register" `
    -Body @{ email = $adminEmail; password = $password; displayName = "Load Admin $runId" } | Out-Null

docker exec blindway-postgres-1 psql -U blindway -d blindway -v ON_ERROR_STOP=1 `
    -c "UPDATE app_user SET role = 'ADMIN' WHERE email = '$adminEmail';" | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Unable to promote the load-test administrator"
}

$adminLogin = Invoke-JsonPost `
    -Uri "$BaseUrl/auth/login" `
    -Body @{ email = $adminEmail; password = $password }
$adminToken = $adminLogin.accessToken

$users = [System.Collections.Generic.List[object]]::new()
$deviceRows = [System.Collections.Generic.List[string]]::new()
$deviceRows.Add("deviceId`tdeviceSecret`ttripId")

for ($index = 1; $index -le $UserCount; $index++) {
    $suffix = $index.ToString("D4")
    $email = "load-user-$runId-$suffix@example.org"
    $registration = Invoke-JsonPost `
        -Uri "$BaseUrl/auth/register" `
        -Body @{ email = $email; password = $password; displayName = "Load User $suffix" }

    $device = Invoke-JsonPost `
        -Uri "$BaseUrl/devices" `
        -AccessToken $adminToken `
        -Body @{ label = "LOAD-$runId-$suffix" }

    Invoke-JsonPost `
        -Uri "$BaseUrl/devices/$($device.id)/binding" `
        -AccessToken $registration.accessToken `
        -Body @{ deviceSecret = $device.deviceSecret } | Out-Null

    $trip = Invoke-JsonPost `
        -Uri "$BaseUrl/trips" `
        -AccessToken $registration.accessToken `
        -Body @{ deviceId = $device.id }

    $users.Add([ordered]@{
        email = $email
        password = $password
        deviceId = [string]$device.id
        deviceSecret = $device.deviceSecret
        tripId = [string]$trip.id
    })
    $deviceRows.Add("$($device.id)`t$($device.deviceSecret)`t$($trip.id)")

    if ($index % 10 -eq 0 -or $index -eq $UserCount) {
        Write-Host "Prepared $index/$UserCount users, devices, and trips"
    }
}

$usersJson = ConvertTo-Json -InputObject $users -Depth 5
[System.IO.File]::WriteAllText($usersPath, $usersJson, [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllLines($devicesPath, $deviceRows, [System.Text.UTF8Encoding]::new($false))

Get-Content -LiteralPath $seedPath -Raw |
    docker exec -i blindway-postgres-1 psql -U blindway -d blindway -v ON_ERROR_STOP=1 `
        -v "run_id=$runId" -v "seed_email=$($users[0].email)" -v "issue_count=$IssueCount"
if ($LASTEXITCODE -ne 0) {
    throw "Unable to seed accessibility issues"
}

Write-Host "Load data preparation completed"
Write-Host "Run ID: $runId"
Write-Host "Users: $UserCount"
Write-Host "Issues: $IssueCount"
Write-Host "k6 data: $usersPath"
Write-Host "MQTT data: $devicesPath"
