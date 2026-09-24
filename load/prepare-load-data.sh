#!/usr/bin/env bash
set -euo pipefail

user_count="${USER_COUNT:-20}"
issue_count="${ISSUE_COUNT:-300}"
base_url="${BASE_URL:-http://127.0.0.1:8080/api/v1}"
docker_bin="${DOCKER_BIN:-/Applications/Docker.app/Contents/Resources/bin/docker}"
postgres_container="${POSTGRES_CONTAINER:-blindway-postgres-1}"
run_id="$(date +%Y%m%d%H%M%S)"
password="load-test-password-42"
script_dir="$(cd "$(dirname "$0")" && pwd)"
data_dir="$script_dir/data"
users_path="$data_dir/users.local.json"
devices_path="$data_dir/devices.local.tsv"

mkdir -p "$data_dir"

curl --fail --silent --show-error "${base_url%/api/v1}/actuator/health" |
  jq -e '.status == "UP"' >/dev/null

if [[ "${KEEP_EXISTING_LOAD_ISSUES:-false}" != "true" ]]; then
  "$docker_bin" exec "$postgres_container" psql -U blindway -d blindway -v ON_ERROR_STOP=1 \
    -c "DELETE FROM accessibility_issue i USING app_user u WHERE i.reporter_user_id = u.id AND (i.description LIKE 'LOAD_SEED_%' OR u.email LIKE 'load-user-%@example.org');" >/dev/null
fi

post_json() {
  local uri="$1"
  local body="$2"
  local token="${3:-}"
  if [[ -n "$token" ]]; then
    curl --fail --silent --show-error --connect-timeout 5 --max-time 30 \
      -H 'Content-Type: application/json' -H "Authorization: Bearer $token" \
      -d "$body" "$uri"
  else
    curl --fail --silent --show-error --connect-timeout 5 --max-time 30 \
      -H 'Content-Type: application/json' -d "$body" "$uri"
  fi
}

admin_email="load-admin-$run_id@example.org"
post_json "$base_url/auth/register" \
  "$(jq -cn --arg email "$admin_email" --arg password "$password" \
    --arg name "Load Admin $run_id" '{email:$email,password:$password,displayName:$name}')" >/dev/null
"$docker_bin" exec "$postgres_container" psql -U blindway -d blindway -v ON_ERROR_STOP=1 \
  -c "UPDATE app_user SET role = 'ADMIN' WHERE email = '$admin_email';" >/dev/null
admin_token="$(post_json "$base_url/auth/login" \
  "$(jq -cn --arg email "$admin_email" --arg password "$password" \
    '{email:$email,password:$password}')" | jq -r '.accessToken')"

printf '[]' >"$users_path"
printf 'deviceId\tdeviceSecret\ttripId\n' >"$devices_path"

for ((index = 1; index <= user_count; index++)); do
  suffix="$(printf '%04d' "$index")"
  email="load-user-$run_id-$suffix@example.org"
  registration="$(post_json "$base_url/auth/register" \
    "$(jq -cn --arg email "$email" --arg password "$password" --arg name "Load User $suffix" \
      '{email:$email,password:$password,displayName:$name}')")"
  user_token="$(jq -r '.accessToken' <<<"$registration")"
  device="$(post_json "$base_url/devices" \
    "$(jq -cn --arg label "LOAD-$run_id-$suffix" '{label:$label}')" "$admin_token")"
  device_id="$(jq -r '.id' <<<"$device")"
  device_secret="$(jq -r '.deviceSecret' <<<"$device")"
  post_json "$base_url/devices/$device_id/binding" \
    "$(jq -cn --arg secret "$device_secret" '{deviceSecret:$secret}')" "$user_token" >/dev/null
  trip="$(post_json "$base_url/trips" "$(jq -cn --arg id "$device_id" '{deviceId:$id}')" "$user_token")"
  trip_id="$(jq -r '.id' <<<"$trip")"

  jq --arg email "$email" --arg password "$password" --arg deviceId "$device_id" \
    --arg deviceSecret "$device_secret" --arg tripId "$trip_id" \
    '. + [{email:$email,password:$password,deviceId:$deviceId,deviceSecret:$deviceSecret,tripId:$tripId}]' \
    "$users_path" >"$users_path.tmp"
  mv "$users_path.tmp" "$users_path"
  printf '%s\t%s\t%s\n' "$device_id" "$device_secret" "$trip_id" >>"$devices_path"
done

"$docker_bin" exec -i "$postgres_container" psql -U blindway -d blindway -v ON_ERROR_STOP=1 \
  -v "run_id=$run_id" -v "seed_email=$(jq -r '.[0].email' "$users_path")" \
  -v "issue_count=$issue_count" <"$script_dir/seed/accessibility-issues.sql"

printf 'Prepared run %s: %s users, %s issues\n' "$run_id" "$user_count" "$issue_count"
