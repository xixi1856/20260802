#!/usr/bin/env bash
set -euo pipefail

environment_name="${1:-}"
if [[ "${environment_name}" != "staging" && "${environment_name}" != "production" ]]; then
  echo "Usage: $0 <staging|production>" >&2
  exit 2
fi

deployment_dir="/opt/blindway/${environment_name}"
backup_dir="/opt/blindway/backups/${environment_name}"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"

test -d "${deployment_dir}"
mkdir -p "${backup_dir}"
cd "${deployment_dir}"

COMPOSE_PROJECT_NAME="blindway-${environment_name}" docker compose exec -T postgres \
  sh -c 'pg_dump --format=custom --no-owner --username="$POSTGRES_USER" "$POSTGRES_DB"' \
  > "${backup_dir}/blindway-${timestamp}.dump"

find "${backup_dir}" -type f -name 'blindway-*.dump' -mtime +35 -delete
echo "Backup written to ${backup_dir}/blindway-${timestamp}.dump"
